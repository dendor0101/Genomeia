//! Пул воркеров физики — `solver/PhysicsWorkers.kt` (SpinPhysicsWorkers) на Rust.
//!
//! Вызывающий поток работает сам (воркер 0), остальные `count - 1` потоков ждут эпоху: сначала крутятся
//! (`spin_limit` проверок), потом спят с удвоением паузы до 2 мс — на телефоне долгий спин греет и сбрасывает частоту
//! (CLAUDE.md, PlatformTuning). Работа делится на куски, кусок берёт тот, кто первым увеличил курсор: КТО какой кусок
//! взял, на биты не влияет — каждая стадия пишет только данные своего куска, слияние идёт после барьера в
//! фиксированном порядке (как в Kotlin).
//!
//! Курсор, счётчик незавершённых, эпоха и признаки сна лежат каждый в своей паре кэш-линий (128 байт): в них пишут
//! разные потоки, и соседство в одной линии стоило бы перекидывания линии на каждую запись (false sharing).
//! В горячем пути нет блокировок и выделений: работа передаётся указателем на замыкание, которое живёт на стеке
//! вызывающего до конца `run` (он ждёт всех).

use std::cell::UnsafeCell;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread::{self, JoinHandle};
use std::time::Duration;

/// Значение в своей паре кэш-линий: префетчер тянет соседнюю линию парой, поэтому 128, а не 64.
#[repr(align(128))]
struct Padded<T>(T);

type JobRef = *const (dyn Fn(usize, usize) + Sync);

struct Shared {
    epoch: Padded<AtomicU64>,
    cursor: Padded<AtomicUsize>,
    pending: Padded<AtomicUsize>,
    ready: Padded<AtomicUsize>,
    running: AtomicBool,
    failed: AtomicBool,
    spin_limit: u32,
    /// Работа и число кусков текущей эпохи: пишет вызывающий ДО увеличения эпохи (Release), читают воркеры ПОСЛЕ того,
    /// как увидели новую эпоху (Acquire).
    job: UnsafeCell<(Option<JobRef>, usize)>,
    parked: Vec<Padded<AtomicBool>>,
}

// SAFETY: `job` пишет только вызывающий между эпохами, пока воркеры её не читают (порядок — через `epoch`
// Release/Acquire и `pending`); остальное — атомики.
unsafe impl Sync for Shared {}
unsafe impl Send for Shared {}

/// Пул воркеров. Общий на число воркеров ([shared]), как `PhysicsWorkers.shared` в Kotlin.
pub struct Workers {
    pub count: usize,
    shared: Arc<Shared>,
    threads: Vec<JoinHandle<()>>,
    busy: AtomicBool,
}

impl std::fmt::Debug for Workers {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Workers({})", self.count)
    }
}

impl Workers {
    pub fn new(count: usize, spin_limit: u32) -> Workers {
        assert!(count >= 1, "воркеров не меньше одного");
        let shared = Arc::new(Shared {
            epoch: Padded(AtomicU64::new(0)),
            cursor: Padded(AtomicUsize::new(0)),
            pending: Padded(AtomicUsize::new(0)),
            ready: Padded(AtomicUsize::new(0)),
            running: AtomicBool::new(true),
            failed: AtomicBool::new(false),
            spin_limit,
            job: UnsafeCell::new((None, 0)),
            parked: (0..count).map(|_| Padded(AtomicBool::new(false))).collect(),
        });
        let threads = (1..count)
            .map(|w| {
                let s = shared.clone();
                thread::Builder::new()
                    .name(format!("Physics-Worker-{w}"))
                    .spawn(move || worker_loop(&s, w))
                    .expect("поток воркера физики")
            })
            .collect();
        while shared.ready.0.load(Ordering::Acquire) < count - 1 {
            std::hint::spin_loop();
        }
        Workers { count, shared, threads, busy: AtomicBool::new(false) }
    }

    /// Общий пул на [count] воркеров (один на процесс; миры с одинаковым числом воркеров делят его).
    pub fn shared(count: usize, spin_limit: u32) -> Arc<Workers> {
        static POOLS: OnceLock<Mutex<HashMap<(usize, u32), Arc<Workers>>>> = OnceLock::new();
        let pools = POOLS.get_or_init(|| Mutex::new(HashMap::new()));
        let mut map = pools.lock().expect("пулы воркеров");
        map.entry((count, spin_limit)).or_insert_with(|| Arc::new(Workers::new(count, spin_limit))).clone()
    }

    /// Выполнить [job] (кусок, воркер) для кусков 0..[chunks]. Возвращается, когда все куски сделаны. Вложенный вызов,
    /// один кусок или пул из одного — всё в вызывающем потоке, по порядку.
    pub fn run(&self, chunks: usize, job: &(dyn Fn(usize, usize) + Sync)) {
        if chunks == 0 {
            return;
        }
        if self.count == 1 || chunks == 1 || self.busy.swap(true, Ordering::Acquire) {
            for k in 0..chunks {
                job(k, 0);
            }
            return;
        }
        let s = &*self.shared;
        // SAFETY: воркеры сейчас не читают `job` (прошлая эпоха закончена: pending = 0), читать начнут только после
        // увеличения эпохи ниже. Время жизни замыкания стирается: run ждёт всех воркеров до выхода.
        unsafe {
            let erased: JobRef = std::mem::transmute::<&(dyn Fn(usize, usize) + Sync), JobRef>(job);
            *s.job.get() = (Some(erased), chunks);
        }
        s.cursor.0.store(0, Ordering::Relaxed);
        s.pending.0.store(self.count, Ordering::Relaxed);
        s.epoch.0.fetch_add(1, Ordering::Release);
        for (w, t) in self.threads.iter().enumerate() {
            if s.parked[w + 1].0.load(Ordering::SeqCst) {
                t.thread().unpark();
            }
        }
        drain(s, job, chunks, 0);
        s.pending.0.fetch_sub(1, Ordering::Release);
        let mut spins = 0u32;
        while s.pending.0.load(Ordering::Acquire) != 0 {
            std::hint::spin_loop();
            spins += 1;
            if spins >= s.spin_limit {
                thread::yield_now();
                spins = 0;
            }
        }
        self.busy.store(false, Ordering::Release);
        if s.failed.swap(false, Ordering::AcqRel) {
            panic!("воркер физики упал");
        }
    }
}

fn drain(s: &Shared, job: &(dyn Fn(usize, usize) + Sync), chunks: usize, worker: usize) {
    loop {
        let k = s.cursor.0.fetch_add(1, Ordering::Relaxed);
        if k >= chunks {
            return;
        }
        job(k, worker);
    }
}

fn worker_loop(s: &Shared, worker: usize) {
    let mut seen = 0u64;
    s.ready.0.fetch_add(1, Ordering::Release);
    loop {
        let mut spins = 0u32;
        let mut park = Duration::from_micros(50);
        loop {
            let e = s.epoch.0.load(Ordering::Acquire);
            if e != seen {
                seen = e;
                break;
            }
            if !s.running.load(Ordering::Acquire) {
                return;
            }
            if spins < s.spin_limit {
                std::hint::spin_loop();
                spins += 1;
                continue;
            }
            s.parked[worker].0.store(true, Ordering::SeqCst);
            if s.epoch.0.load(Ordering::SeqCst) == seen && s.running.load(Ordering::SeqCst) {
                thread::park_timeout(park);
            }
            s.parked[worker].0.store(false, Ordering::Relaxed);
            park = (park * 2).min(Duration::from_millis(2));
        }
        if !s.running.load(Ordering::Acquire) {
            return;
        }
        // SAFETY: вызывающий записал работу до увеличения эпохи (Release), мы её увидели (Acquire); он ждёт нас.
        let (job, chunks) = unsafe { *s.job.get() };
        if let Some(job) = job {
            let job: &(dyn Fn(usize, usize) + Sync) = unsafe { &*job };
            let ok = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| drain(s, job, chunks, worker)));
            if ok.is_err() {
                s.failed.store(true, Ordering::Release);
            }
        }
        s.pending.0.fetch_sub(1, Ordering::Release);
    }
}

impl Drop for Workers {
    fn drop(&mut self) {
        self.shared.running.store(false, Ordering::Release);
        self.shared.epoch.0.fetch_add(1, Ordering::Release);
        for t in &self.threads {
            t.thread().unpark();
        }
        for t in self.threads.drain(..) {
            let _ = t.join();
        }
    }
}

/// Указатель на данные, которые куски одной стадии пишут в непересекающихся местах (свой блок, свой кусок частиц).
/// Rust этого доказать не может: каждая стадия обязана писать только своё — так же, как в Kotlin (CLAUDE.md,
/// «параллельные стадии пишут только в свои данные»). Проверка — сверка битов при 1 и N воркерах.
#[derive(Clone, Copy)]
pub struct SharedMut<T>(pub *mut T);

// SAFETY: см. описание типа — разные куски трогают разные элементы.
unsafe impl<T> Send for SharedMut<T> {}
unsafe impl<T> Sync for SharedMut<T> {}

impl<T> SharedMut<T> {
    /// # Safety
    /// Кусок трогает только свои данные; объект живёт дольше стадии.
    #[inline(always)]
    #[allow(clippy::mut_from_ref)]
    pub unsafe fn get(&self) -> &mut T {
        unsafe { &mut *self.0 }
    }

    /// Необязательный объект: нулевой указатель — None.
    ///
    /// # Safety
    /// Как у [get].
    #[inline(always)]
    #[allow(clippy::mut_from_ref)]
    pub unsafe fn opt(&self) -> Option<&mut T> {
        if self.0.is_null() { None } else { Some(unsafe { &mut *self.0 }) }
    }

    /// Адрес элемента [k] (замыкание захватывает весь указатель, а не его поле).
    #[inline(always)]
    pub fn at(&self, k: usize) -> *mut T {
        self.0.wrapping_add(k)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicI64;

    #[test]
    fn every_chunk_once() {
        let w = Workers::new(4, 1000);
        for round in 0..200 {
            let hits: Vec<AtomicUsize> = (0..97).map(|_| AtomicUsize::new(0)).collect();
            w.run(97, &|k, _| {
                hits[k].fetch_add(1, Ordering::Relaxed);
            });
            for (k, h) in hits.iter().enumerate() {
                assert_eq!(h.load(Ordering::Relaxed), 1, "кусок {k} в раунде {round}");
            }
        }
    }

    #[test]
    fn sums_independent_of_workers() {
        let data: Vec<i64> = (0..10_000).collect();
        let mut out = Vec::new();
        for count in [1usize, 2, 3, 8] {
            let w = Workers::new(count, 1000);
            let parts: Vec<AtomicI64> = (0..16).map(|_| AtomicI64::new(0)).collect();
            w.run(16, &|k, _| {
                let s: i64 = data[k * 625..(k + 1) * 625].iter().sum();
                parts[k].store(s, Ordering::Relaxed);
            });
            out.push(parts.iter().map(|p| p.load(Ordering::Relaxed)).sum::<i64>());
        }
        assert!(out.windows(2).all(|p| p[0] == p[1]));
    }
}
