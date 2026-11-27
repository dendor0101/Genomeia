//! Сон организмов — структура `BodySleep` из `solver/BodySleep.kt`. Функции BodyState
//! (будить, усыплять, пролёты бодрствующих) — в `solver/sleep_fns.rs`.

use crate::snapshot::{In, Out};

/// Свои части вида сна острова: диапазоны и организмы острова (живут в комплекте воркера между тиками).
#[derive(Default)]
pub struct SleepOwn {
    span_start: Vec<i32>,
    span_end: Vec<i32>,
    island_orgs: Vec<i32>,
}

#[derive(Clone, Debug)]
pub struct BodySleep {
    pub on: bool,
    pub asleep: Vec<bool>,
    pub calm_ticks: Vec<i32>,
    pub first: Vec<i32>,
    pub last: Vec<i32>,
    pub asleep_count: i32,
    busy: Vec<bool>,
    wake_queue: Vec<i32>,
    wake_queue_n: usize,
    pub span_start: Vec<i32>,
    pub span_end: Vec<i32>,
    pub span_count: usize,
    spans_dirty: bool,
    pub version: i32,
    /// Все бодрствующие диапазоны без отбора по области (`rebuildAllSpansIfNeeded`): из них режутся куски мира
    /// (PassChunks). Пересобираются, только когда кто-то уснул или проснулся (version) или сменилось число частиц.
    pub all_span_start: Vec<i32>,
    pub all_span_end: Vec<i32>,
    pub all_span_count: usize,
    all_spans_version: i32,
    all_spans_n: i64,
    /// ОБЛАСТЬ ТИКА (острова, `TickScope.kt`): есть ли области организмов в этом тике (массив — у хозяина,
    /// `BodyState.island_org_scope`) и какую область обходят поштучные проходы. Диапазоны — бодрствующие организмы
    /// текущей области; смена области их пересобирает. [scope_version] растёт на каждой смене — по нему
    /// пересобираются куски области.
    pub org_scope_on: bool,
    pub scope: i32,
    pub scope_version: i32,
    /// Вид острова (`islandView`): диапазоны острова собраны разбиением ([set_island_spans]), организмы острова — свой
    /// список ([island_orgs]); у мира — false.
    pub island_view: bool,
    pub island_orgs: Vec<i32>,
}

impl BodySleep {
    pub fn new(on: bool) -> BodySleep {
        BodySleep {
            on,
            asleep: Vec::new(),
            calm_ticks: Vec::new(),
            first: Vec::new(),
            last: Vec::new(),
            asleep_count: 0,
            busy: Vec::new(),
            wake_queue: vec![0; 16],
            wake_queue_n: 0,
            span_start: vec![0; 1],
            span_end: vec![0; 1],
            span_count: 0,
            spans_dirty: true,
            version: 0,
            all_span_start: vec![0; 1],
            all_span_end: vec![0; 1],
            all_span_count: 0,
            all_spans_version: -1,
            all_spans_n: -1,
            org_scope_on: false,
            scope: crate::solver::blocks::SCOPE_WORLD,
            scope_version: 0,
            island_view: false,
            island_orgs: Vec::new(),
        }
    }

    /// `setScope`: область [sc] для поштучных проходов; диапазоны пересоберутся.
    pub fn set_scope(&mut self, sc: i32) {
        if self.scope == sc {
            return;
        }
        self.scope = sc;
        self.spans_dirty = true;
        self.scope_version += 1;
    }

    /// `forceScope`: область [sc] с пересборкой диапазонов, даже если номер тот же (состав области сменился).
    pub fn force_scope(&mut self, sc: i32) {
        self.scope = sc;
        self.spans_dirty = true;
        self.scope_version += 1;
    }

    /// Вид острова поверх побитовой копии сна мира (`islandView`): свои — диапазоны и организмы острова; сон, покой и
    /// очередь пробуждений — общие (остров их не трогает: в нём нет спящих, а пробуждение — событие).
    ///
    /// # Safety
    /// `self` — побитовая копия сна мира: прежние значения своих полей не освобождаются; копию не освобождать —
    /// только вернуть свои части [remove_island_own].
    pub unsafe fn install_island_own(&mut self, own: &mut SleepOwn) {
        unsafe {
            std::ptr::write(&mut self.span_start, std::mem::take(&mut own.span_start));
            std::ptr::write(&mut self.span_end, std::mem::take(&mut own.span_end));
            std::ptr::write(&mut self.island_orgs, std::mem::take(&mut own.island_orgs));
        }
        if self.span_start.is_empty() {
            self.span_start = vec![0; 16];
            self.span_end = vec![0; 16];
        }
        self.span_count = 0;
        self.spans_dirty = false;
        self.island_view = true;
    }

    /// # Safety
    /// Только после [install_island_own] на той же копии.
    pub unsafe fn remove_island_own(&mut self, own: &mut SleepOwn) {
        unsafe {
            own.span_start = std::ptr::read(&self.span_start);
            own.span_end = std::ptr::read(&self.span_end);
            own.island_orgs = std::ptr::read(&self.island_orgs);
        }
    }

    /// `setIslandOrgs`: организмы острова у копии.
    pub fn set_island_orgs(&mut self, orgs: &[i32]) {
        self.island_orgs.clear();
        self.island_orgs.extend_from_slice(orgs);
    }

    /// `setIslandSpans`: диапазоны острова [k] — from[a..b], to[a..b] по возрастанию, без стыков: ровно то, что собрал
    /// бы [rebuild_spans_if_needed] по его области, без прохода по всем частицам мира.
    pub fn set_island_spans(&mut self, k: i32, from: &[i32], to: &[i32]) {
        self.scope = k;
        self.scope_version += 1;
        self.spans_dirty = false;
        self.span_count = 0;
        for x in 0..from.len() {
            self.add_span(from[x], to[x]);
        }
    }

    #[inline]
    pub fn is_asleep(&self, organism: i32) -> bool {
        self.asleep_count > 0 && self.asleep[organism as usize]
    }

    pub fn request_wake(&mut self, cell: i32) {
        if !self.on {
            return;
        }
        if self.wake_queue_n == self.wake_queue.len() {
            let cap = self.wake_queue.len() * 2;
            self.wake_queue.resize(cap, 0);
        }
        self.wake_queue[self.wake_queue_n] = cell;
        self.wake_queue_n += 1;
    }

    pub fn reset(&mut self, n: usize, organism_of: &[i32], organism_count: usize) {
        self.version += 1;
        if self.asleep.len() != n {
            self.asleep = vec![false; n];
            self.calm_ticks = vec![0; n];
            self.busy = vec![false; n];
            self.first = vec![0; n];
            self.last = vec![0; n];
        } else {
            self.asleep.fill(false);
            self.calm_ticks.fill(0);
            self.busy.fill(false);
        }
        self.asleep_count = 0;
        self.wake_queue_n = 0;
        self.spans_dirty = true;
        self.first[..organism_count].fill(i32::MAX);
        self.last[..organism_count].fill(-1);
        for i in 0..n {
            let o = organism_of[i] as usize;
            if (i as i32) < self.first[o] {
                self.first[o] = i as i32;
            }
            self.last[o] = i as i32;
        }
    }

    pub fn compact(&mut self, org_new: &[i32], org_count: usize, particle_new: &[i32], n: usize) {
        self.version += 1;
        let mut a = vec![false; n];
        let mut c = vec![0; n];
        let mut b = vec![false; n];
        let mut f = vec![0; n];
        let mut l = vec![0; n];
        let mut count = 0;
        if self.asleep.len() >= org_count {
            for o in 0..org_count {
                let k = org_new[o];
                if k < 0 {
                    continue;
                }
                let k = k as usize;
                a[k] = self.asleep[o];
                c[k] = self.calm_ticks[o];
                b[k] = self.busy[o];
                f[k] = particle_new[self.first[o] as usize];
                l[k] = particle_new[self.last[o] as usize];
                if a[k] {
                    count += 1;
                }
            }
        }
        self.asleep = a;
        self.calm_ticks = c;
        self.busy = b;
        self.first = f;
        self.last = l;
        self.asleep_count = count;
        let mut m = 0;
        for k in 0..self.wake_queue_n {
            let v = particle_new[self.wake_queue[k] as usize];
            if v >= 0 {
                self.wake_queue[m] = v;
                m += 1;
            }
        }
        self.wake_queue_n = m;
        for x in self.wake_queue[m..].iter_mut() {
            *x = 0;
        }
        self.spans_dirty = true;
    }

    pub fn write(&self, out: &mut Out, org_count: usize) {
        out.boolean(self.on);
        out.booleans(&self.asleep[..org_count]);
        out.ints(&self.calm_ticks[..org_count]);
        out.booleans(&self.busy[..org_count]);
        out.ints(&self.first[..org_count]);
        out.ints(&self.last[..org_count]);
        out.ints(&self.wake_queue[..self.wake_queue_n]);
    }

    pub fn read(&mut self, input: &mut In, n: usize) {
        self.version += 1;
        self.on = input.boolean();
        self.asleep = input.booleans(n);
        self.calm_ticks = input.ints(n);
        self.busy = input.booleans(n);
        self.first = input.ints(n);
        self.last = input.ints(n);
        let q = input.ints(0);
        self.wake_queue_n = q.len();
        let mut wq = q;
        let cap = wq.len().max(16);
        wq.resize(cap, 0);
        self.wake_queue = wq;
        self.asleep_count = 0;
        for o in 0..self.asleep.len() {
            if self.asleep[o] {
                self.asleep_count += 1;
            }
        }
        self.spans_dirty = true;
    }

    pub fn append(
        &mut self,
        n: usize,
        organism_of: &[i32],
        from_particle: usize,
        from_organism: usize,
        organism_count: usize,
    ) {
        self.version += 1;
        if self.asleep.len() < n {
            self.asleep.resize(n, false);
            self.calm_ticks.resize(n, 0);
            self.busy.resize(n, false);
            self.first.resize(n, 0);
            self.last.resize(n, 0);
        }
        self.asleep[from_organism..organism_count].fill(false);
        self.calm_ticks[from_organism..organism_count].fill(0);
        self.busy[from_organism..organism_count].fill(false);
        self.first[from_organism..organism_count].fill(i32::MAX);
        self.last[from_organism..organism_count].fill(-1);
        for i in from_particle..n {
            let o = organism_of[i] as usize;
            if (i as i32) < self.first[o] {
                self.first[o] = i as i32;
            }
            self.last[o] = i as i32;
        }
        self.spans_dirty = true;
    }

    pub fn inherit_split(&mut self, fresh: usize, old: usize, cells: &[i32]) {
        if self.asleep.is_empty() {
            return;
        }
        self.asleep[fresh] = self.asleep[old];
        if self.asleep[fresh] {
            self.asleep_count += 1;
        }
        self.calm_ticks[fresh] = self.calm_ticks[old];
        self.busy[fresh] = self.busy[old];
        let mut lo = i32::MAX;
        let mut hi = -1;
        for &c in cells {
            if c < lo {
                lo = c;
            }
            if c > hi {
                hi = c;
            }
        }
        self.first[fresh] = lo;
        self.last[fresh] = hi;
    }

    #[inline]
    pub fn has_wake_requests(&self) -> bool {
        self.wake_queue_n > 0
    }
    #[inline]
    pub fn wake_request_count(&self) -> usize {
        self.wake_queue_n
    }
    #[inline]
    pub fn wake_request(&self, k: usize) -> i32 {
        self.wake_queue[k]
    }
    #[inline]
    pub fn clear_wake_requests(&mut self) {
        self.wake_queue_n = 0;
    }
    #[inline]
    pub fn mark_busy(&mut self, organism: usize) {
        self.busy[organism] = true;
    }
    pub fn take_busy(&mut self, organism: usize) -> bool {
        let b = self.busy[organism];
        self.busy[organism] = false;
        b
    }

    pub fn set_asleep(&mut self, organism: usize, value: bool) {
        if self.asleep[organism] == value {
            return;
        }
        self.version += 1;
        self.asleep[organism] = value;
        if value {
            self.asleep_count += 1;
        } else {
            self.asleep_count -= 1;
        }
        self.calm_ticks[organism] = 0;
        self.spans_dirty = true;
    }

    /// Диапазоны бодрствующих частиц текущей области; [org_scope] — области организмов (при [org_scope_on]).
    pub fn rebuild_spans_if_needed(&mut self, n: usize, organism_of: &[i32], org_scope: &[i32]) {
        if !self.spans_dirty {
            return;
        }
        self.spans_dirty = false;
        self.span_count = 0;
        if self.org_scope_on && self.scope != crate::solver::blocks::SCOPE_ALL {
            let sc = self.scope;
            let mut start: i32 = -1;
            for i in 0..n {
                let o = organism_of[i] as usize;
                // Организм, родившийся расколом посреди тика, — мира: в острове событие откатывает остров целиком.
                let os = if o < org_scope.len() { org_scope[o] } else { crate::solver::blocks::SCOPE_WORLD };
                let in_scope = !self.asleep[o] && os == sc;
                if in_scope && start < 0 {
                    start = i as i32;
                } else if !in_scope && start >= 0 {
                    self.add_span(start, i as i32);
                    start = -1;
                }
            }
            if start >= 0 {
                self.add_span(start, n as i32);
            }
            return;
        }
        if self.asleep_count == 0 {
            self.add_span(0, n as i32);
            return;
        }
        let mut start: i32 = -1;
        for i in 0..n {
            let awake = !self.asleep[organism_of[i] as usize];
            if awake && start < 0 {
                start = i as i32;
            } else if !awake && start >= 0 {
                self.add_span(start, i as i32);
                start = -1;
            }
        }
        if start >= 0 {
            self.add_span(start, n as i32);
        }
    }

    /// `rebuildAllSpansIfNeeded`: все бодрствующие диапазоны мира (без островов — то же, что пролёты сна).
    pub fn rebuild_all_spans_if_needed(&mut self, n: usize, organism_of: &[i32]) {
        if self.all_spans_version == self.version && self.all_spans_n == n as i64 {
            return;
        }
        self.all_spans_version = self.version;
        self.all_spans_n = n as i64;
        self.all_span_count = 0;
        if self.asleep_count == 0 {
            self.add_all_span(0, n as i32);
            return;
        }
        let mut start: i32 = -1;
        for i in 0..n {
            let awake = !self.asleep[organism_of[i] as usize];
            if awake && start < 0 {
                start = i as i32;
            } else if !awake && start >= 0 {
                self.add_all_span(start, i as i32);
                start = -1;
            }
        }
        if start >= 0 {
            self.add_all_span(start, n as i32);
        }
    }

    fn add_all_span(&mut self, start: i32, end: i32) {
        if self.all_span_count == self.all_span_start.len() {
            let cap = self.all_span_count * 2;
            self.all_span_start.resize(cap, 0);
            self.all_span_end.resize(cap, 0);
        }
        self.all_span_start[self.all_span_count] = start;
        self.all_span_end[self.all_span_count] = end;
        self.all_span_count += 1;
    }

    fn add_span(&mut self, start: i32, end: i32) {
        if self.span_count == self.span_start.len() {
            let cap = self.span_count * 2;
            self.span_start.resize(cap, 0);
            self.span_end.resize(cap, 0);
        }
        self.span_start[self.span_count] = start;
        self.span_end[self.span_count] = end;
        self.span_count += 1;
    }

    pub fn count_calm(&mut self, organism: usize) -> i32 {
        self.calm_ticks[organism] += 1;
        self.calm_ticks[organism]
    }
    pub fn reset_calm(&mut self, organism: usize) {
        self.calm_ticks[organism] = 0;
    }

    /// Отпечаток для сверки: то же, что `write`, плюс пролёты бодрствующих.
    pub fn digest(&self, out: &mut Out, org_count: usize) {
        self.write(out, org_count);
        out.int(self.asleep_count);
    }
}
