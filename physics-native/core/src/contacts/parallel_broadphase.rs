//! Широкая фаза по воркерам — `ParallelMoved`, `ParallelReinsert`, `ParallelCandidates`, `ParallelPairs`
//! (contacts/ParallelContacts.kt) на Rust. Каждый проход даёт ровно то, что дал бы проход подряд в одном потоке.
//!
//! Уехавшие вершины и уцелевшие пары — фильтры с сохранением порядка: часть сперва считает, сколько оставит, потом
//! пишет со своего смещения (префиксная сумма). Перекладка — след вершин по частям moved, затем каждая часть сетки
//! (`shards`) перекладывает свои ячейки. Поиск пар только читает сетки, привязки, радиусы и связи; пишет пометку «уже
//! видел» (у воркера своя, номер прохода — свой) и ключи пар (у куска свой буфер). Куски — отрезки moved подряд, и
//! склейка их буферов по порядку кусков даёт ту же последовательность новых ключей, что один проход (внутри вершины
//! ключи сортируются, между вершинами идут по moved); нижние ключи куска сортируются в нём же — отсортированными
//! прогонами их берёт слияние по ключам-разделителям (ключи различны, граница частей однозначна). Сверка —
//! RustWorkersParityTest.
//!
//! Память: пометка — по i32 на частицу на воркер (27 КБ на 6.8 тысячи частиц, 400 КБ на 100 тысяч); читается по
//! номерам соседей, то есть так же вразброс, как и в одном потоке.

use super::helpers::PairKeyBuffer;
use super::{BoundaryContacts, DDA_MAX};
use crate::solver::pool::{SharedMut, Workers};

/// Перекладываемых вершин, с которых поиск идёт по воркерам (`PARALLEL_QUERY_MIN`).
pub(crate) const PARALLEL_QUERY_MIN: usize = 64;
/// Шаг номера прохода воркера в [ParallelCandidates::stamps]: 32 × 4 байта = 128, у каждого воркера своя пара линий.
const STAMP_STRIDE: usize = 32;

/// Буфер ключей куска в своих 128 байтах: счётчики соседних кусков не делят кэш-линий.
#[repr(align(128))]
#[derive(Clone, Debug, Default)]
struct ChunkKeys(PairKeyBuffer);

/// Рабочие массивы параллельного поиска (живут в касаниях между подшагами).
#[derive(Clone, Debug, Default)]
pub struct ParallelCandidates {
    workers: usize,
    n: usize,
    marks: Vec<Vec<i32>>,
    stamps: Vec<i32>,
    dda_x: Vec<Vec<i32>>,
    dda_y: Vec<Vec<i32>>,
    /// Кусков вшестнадцатеро больше воркеров: цена вершины зависит от числа соседей.
    buffers: Vec<ChunkKeys>,
    from: Vec<usize>,
    to: Vec<usize>,
    /// Фильтры с сохранением порядка (`OrderedChunks`: уехавшие вершины, уцелевшие пары): 4 части на воркер —
    /// начало части, её счёт и смещение её записи.
    part_from: Vec<usize>,
    part_count: Vec<usize>,
    part_at: Vec<usize>,
    /// Перекладка (`ParallelReinsert`): ключи ячеек следа вершин части подряд и конец следа каждой её вершины.
    cells: Vec<Vec<i64>>,
    cell_end: Vec<Vec<i32>>,
    /// Концы отсортированных прогонов нижних ключей после склейки кусков поиска (`lowRunEnd`).
    low_run_end: Vec<usize>,
    /// Слияние (`ParallelPairs.merge`): прогоны нижних ключей, ключи-разделители частей, нижние ключи части.
    run_end: Vec<usize>,
    runs: usize,
    splitters: Vec<i64>,
    low_buf: Vec<Vec<i64>>,
}

impl ParallelCandidates {
    pub fn new(workers: usize, n: usize) -> ParallelCandidates {
        ParallelCandidates {
            workers,
            n,
            marks: (0..workers).map(|_| vec![0; n]).collect(),
            stamps: vec![0; workers * STAMP_STRIDE],
            dda_x: (0..workers).map(|_| vec![0; DDA_MAX]).collect(),
            dda_y: (0..workers).map(|_| vec![0; DDA_MAX]).collect(),
            buffers: (0..16 * workers).map(|_| ChunkKeys::default()).collect(),
            from: vec![0; 16 * workers],
            to: vec![0; 16 * workers],
            part_from: vec![0; 4 * workers + 1],
            part_count: vec![0; 4 * workers],
            part_at: vec![0; 4 * workers + 1],
            cells: (0..4 * workers).map(|_| Vec::with_capacity(256)).collect(),
            cell_end: (0..4 * workers).map(|_| Vec::with_capacity(64)).collect(),
            low_run_end: vec![0; 16 * workers],
            run_end: vec![0; 16 * workers],
            runs: 0,
            splitters: vec![0; 4 * workers + 1],
            low_buf: (0..4 * workers).map(|_| Vec::with_capacity(256)).collect(),
        }
    }

    /// `split`: части [0, n) поровну на 4·воркеров; возвращает их число.
    fn split(&mut self, n: usize) -> usize {
        let parts = 4 * self.workers;
        for k in 0..=parts {
            self.part_from[k] = (n as i64 * k as i64 / parts as i64) as usize;
        }
        parts
    }

    /// `prefix`: смещения записи частей; возвращает итог.
    fn prefix(&mut self) -> usize {
        let parts = 4 * self.workers;
        self.part_at[0] = 0;
        for k in 0..parts {
            self.part_at[k + 1] = self.part_at[k] + self.part_count[k];
        }
        self.part_at[parts]
    }
}

impl BoundaryContacts {
    fn take_parallel_candidates(&mut self, workers: usize) -> Box<ParallelCandidates> {
        match self.parallel_candidates.take() {
            Some(p) if p.workers == workers && p.n == self.n => p,
            _ => Box::new(ParallelCandidates::new(workers, self.n)),
        }
    }

    /// `ParallelReinsert.run`: сперва по частям moved — новая привязка (у вершины своя) и след: ключи ячеек пути
    /// подшага в буфер части; затем по частям сетки — воркер части s проходит все вершины по порядку, вынимает из своей
    /// части их старые записи и кладёт ключи, которые достались его части. Части — независимые таблицы со своими
    /// следами вершин. Набор записей тот же, что у перекладки подряд.
    pub(crate) fn reinsert_parallel(&mut self, w: &Workers, px: &[f32], py: &[f32], qx: &[f32], qy: &[f32]) {
        let mut p = self.take_parallel_candidates(w.count);
        let parts = p.split(self.moved_count);
        let ct = SharedMut(self as *mut BoundaryContacts);
        let pp = SharedMut(&mut *p as *mut ParallelCandidates);
        w.run(parts, &|k, worker| {
            // SAFETY: часть k пишет привязки своих вершин (вершины в moved различны) и свои буферы; буферы DDA — воркера.
            unsafe {
                let ct = ct.get();
                let p = pp.get();
                let (from, to) = (p.part_from[k], p.part_from[k + 1]);
                let buf = &mut *p.cells.as_mut_ptr().add(k);
                let ends = &mut *p.cell_end.as_mut_ptr().add(k);
                buf.clear();
                ends.clear();
                let dx = &mut *p.dda_x.as_mut_ptr().add(worker);
                let dy = &mut *p.dda_y.as_mut_ptr().add(worker);
                for a in from..to {
                    let v = ct.moved[a] as usize;
                    ct.anchor_vertex(v, px, py, qx, qy);
                    let c = BoundaryContacts::dda_into(ct.cell_size, qx[v], qy[v], px[v], py[v], dx, dy);
                    for i in 0..c {
                        buf.push(BoundaryContacts::key(dx[i], dy[i]));
                    }
                    ends.push(buf.len() as i32);
                }
            }
        });
        let shards = self.shards.len();
        w.run(shards, &|s, _| {
            // SAFETY: задача s меняет только свою часть сетки; буферы частей только читаются.
            unsafe {
                let ct = ct.get();
                let p = pp.get();
                let g = &mut *ct.shards.as_mut_ptr().add(s);
                for k in 0..parts {
                    let (buf, ends) = (&p.cells[k], &p.cell_end[k]);
                    let mut start = 0usize;
                    for (x, a) in (p.part_from[k]..p.part_from[k + 1]).enumerate() {
                        let v = ct.moved[a];
                        g.remove(v);
                        let end = ends[x] as usize;
                        for &cell in &buf[start..end] {
                            if ct.shard_of(cell) == s {
                                g.insert(v, cell);
                            }
                        }
                        start = end;
                    }
                }
            }
        });
        self.parallel_candidates = Some(p);
    }

    /// `ParallelMoved.find`: уехавшие подвижные вершины по частям — счёт, смещения, запись в moved со своего места
    /// (тот же список, что подряд). Возвращает их число.
    pub(crate) fn moved_parallel(&mut self, w: &Workers, px: &[f32], py: &[f32]) -> usize {
        let mut p = self.take_parallel_candidates(w.count);
        let parts = p.split(self.mover_count);
        {
            let ct = SharedMut(self as *mut BoundaryContacts);
            let pp = SharedMut(&mut *p as *mut ParallelCandidates);
            w.run(parts, &|k, _| {
                // SAFETY: часть k пишет только свой счёт; касания только читаются.
                unsafe {
                    let p = pp.get();
                    let ct: &BoundaryContacts = ct.get();
                    let c = ct.count_moved(p.part_from[k], p.part_from[k + 1], px, py);
                    *p.part_count.as_mut_ptr().add(k) = c;
                }
            });
        }
        let total = p.prefix();
        {
            let ct = SharedMut(self as *mut BoundaryContacts);
            let pp = SharedMut(&mut *p as *mut ParallelCandidates);
            w.run(parts, &|k, _| {
                // SAFETY: часть k пишет moved[at[k], at[k] + count[k]).
                unsafe {
                    let p = pp.get();
                    ct.get().write_moved(p.part_from[k], p.part_from[k + 1], p.part_at[k], px, py);
                }
            });
        }
        self.parallel_candidates = Some(p);
        total
    }

    /// `ParallelPairs.keep`: уцелевшие пары (ни одна вершина не перекладывается) по частям — счёт, смещения, запись
    /// ключей и слотов покоя со своего места. Возвращает их число.
    pub(crate) fn keep_parallel(&mut self, w: &Workers) -> usize {
        let mut p = self.take_parallel_candidates(w.count);
        let parts = p.split(self.pair_n);
        {
            let ct = SharedMut(self as *mut BoundaryContacts);
            let pp = SharedMut(&mut *p as *mut ParallelCandidates);
            w.run(parts, &|k, _| {
                // SAFETY: часть k пишет только свой счёт; касания только читаются.
                unsafe {
                    let p = pp.get();
                    let ct: &BoundaryContacts = ct.get();
                    let c = ct.count_kept(p.part_from[k], p.part_from[k + 1]);
                    *p.part_count.as_mut_ptr().add(k) = c;
                }
            });
        }
        let total = p.prefix();
        {
            let ct = SharedMut(self as *mut BoundaryContacts);
            let pp = SharedMut(&mut *p as *mut ParallelCandidates);
            w.run(parts, &|k, _| {
                // SAFETY: часть k пишет kept_keys и kept_slot на местах [at[k], at[k] + count[k]).
                unsafe {
                    let p = pp.get();
                    ct.get().write_kept(p.part_from[k], p.part_from[k + 1], p.part_at[k]);
                }
            });
        }
        self.parallel_candidates = Some(p);
        total
    }
}

impl BoundaryContacts {
    /// `ParallelCandidates.run` и склейка: пары перекладываемых вершин moved[0, moved_count) — в `keys` по порядку
    /// кусков. Возвращает число кусков — отсортированных прогонов нижних ключей (для [merge_parallel]).
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn query_moved_parallel(&mut self, w: &Workers, px: &[f32], py: &[f32], qx: &[f32], qy: &[f32], group: &[i32]) -> usize {
        let mut p = self.take_parallel_candidates(w.count);
        let count = self.moved_count;
        // По 16 вершин на кусок (находка 41 в Kotlin, 24.11): при 64 вершинах на кусок поиск меньше чем по 128
        // вершинам шёл одним куском, то есть в одном потоке. Список пар от нарезки не зависит.
        let k = p.buffers.len().min((count / 16).max(1));
        for c in 0..k {
            p.from[c] = (count as i64 * c as i64 / k as i64) as usize;
            p.to[c] = (count as i64 * (c as i64 + 1) / k as i64) as usize;
        }
        {
            let ct = SharedMut(self as *mut BoundaryContacts);
            let pp = SharedMut(&mut *p as *mut ParallelCandidates);
            w.run(k, &|c, worker| {
                // SAFETY: касания только читаются; кусок c пишет свой буфер, воркер — свою пометку, свой номер прохода и
                // свои буферы DDA.
                unsafe {
                    let ct: &BoundaryContacts = ct.get();
                    let p = pp.get();
                    let out = &mut (*p.buffers.as_mut_ptr().add(c)).0;
                    out.new_n = 0;
                    out.low_n = 0;
                    let mark = &mut *p.marks.as_mut_ptr().add(worker);
                    let dx = &mut *p.dda_x.as_mut_ptr().add(worker);
                    let dy = &mut *p.dda_y.as_mut_ptr().add(worker);
                    let stamp_at = p.stamps.as_mut_ptr().add(worker * STAMP_STRIDE);
                    let mut stamp = *stamp_at;
                    for a in p.from[c]..p.to[c] {
                        stamp = stamp.wrapping_add(1);
                        let v = ct.moved[a] as usize;
                        ct.query_vertex_into(v, px, py, qx, qy, mark, stamp, dx, dy, out, group);
                    }
                    *stamp_at = stamp;
                    // Нижние куска — отсортированным прогоном (как в Kotlin; слиянию по прогонам).
                    out.low_keys[..out.low_n].sort_unstable();
                }
            });
        }
        for c in 0..k {
            p.buffers[c].0.append_to(&mut self.keys);
            p.low_run_end[c] = self.keys.low_n;
        }
        self.parallel_candidates = Some(p);
        k
    }

    /// `ParallelPairs.merge`: три упорядоченных источника (уцелевшие, новые, нижние) — один канонический список по
    /// частям. Выход делится ключами-разделителями из большего источника: часть p берёт из КАЖДОГО источника ключи в
    /// [S_p, S_{p+1}) (двоичный поиск) и сливает их сама; ключи различны, поэтому граница частей однозначна, а смещение
    /// части — число ключей меньше S_p во всех источниках. Результат тот же, что у слияния подряд. [low_runs] — сколько
    /// отсортированных прогонов нижних ключей оставил поиск по воркерам (0 — нижние не отсортированы). false — источники
    /// слишком малы для разделителей: слияние подряд.
    pub(crate) fn merge_parallel(&mut self, w: &Workers, kept: usize, fresh: usize, low: usize, low_runs: usize) -> bool {
        let parts = 4 * w.count;
        let from_kept = if kept >= parts * 4 {
            true
        } else if fresh >= parts * 4 {
            false
        } else {
            return false;
        };
        let mut p = self.take_parallel_candidates(w.count);
        if low_runs == 0 {
            self.keys.low_keys[..low].sort_unstable();
            p.run_end[0] = low;
            p.runs = 1;
        } else {
            let (src, dst) = (&p.low_run_end, &mut p.run_end);
            dst[..low_runs].copy_from_slice(&src[..low_runs]);
            p.runs = low_runs;
        }
        {
            let (src, src_n) = if from_kept { (&self.kept_keys, kept) } else { (&self.keys.new_keys, fresh) };
            p.splitters[0] = i64::MIN;
            for q in 1..parts {
                p.splitters[q] = src[(src_n as i64 * q as i64 / parts as i64) as usize];
            }
            p.splitters[parts] = i64::MAX;
        }
        let total = kept + fresh + low;
        if self.pair_a.len() < total {
            let mut cap = self.pair_a.len() * 2;
            while cap < total {
                cap *= 2;
            }
            self.pair_a.resize(cap, 0);
            self.pair_b.resize(cap, 0);
        }
        {
            let ct = SharedMut(self as *mut BoundaryContacts);
            let pp = SharedMut(&mut *p as *mut ParallelCandidates);
            w.run(parts, &|q, _| {
                // SAFETY: часть q пишет пары и слоты покоя только на своих местах выхода и свой буфер нижних ключей;
                // источники только читаются.
                unsafe { merge_part(ct.get(), pp.get(), q, kept, fresh) }
            });
        }
        self.pair_n = total;
        self.ensure_near_capacity();
        self.parallel_candidates = Some(p);
        true
    }
}

/// `mergePart`: часть [q] слияния. Её смещение на выходе — число ключей меньше S_q во всех источниках; соседние части её
/// не ждут: границы каждая находит сама.
unsafe fn merge_part(ct: &mut BoundaryContacts, p: &mut ParallelCandidates, q: usize, kept: usize, fresh: usize) {
    let (lo, hi) = (p.splitters[q], p.splitters[q + 1]);
    let kk = &ct.kept_keys;
    let nk = &ct.keys.new_keys;
    let lk = &ct.keys.low_keys;
    let mut i = lower_bound(kk, 0, kept, lo);
    let i_end = lower_bound(kk, 0, kept, hi);
    let mut j = lower_bound(nk, 0, fresh, lo);
    let j_end = lower_bound(nk, 0, fresh, hi);
    let mut out = i + j;
    let buf = unsafe { &mut *p.low_buf.as_mut_ptr().add(q) };
    buf.clear();
    let mut run_start = 0usize;
    for r in 0..p.runs {
        let a = lower_bound(lk, run_start, p.run_end[r], lo);
        let b = lower_bound(lk, a, p.run_end[r], hi);
        out += a - run_start;
        buf.extend_from_slice(&lk[a..b]);
        run_start = p.run_end[r];
    }
    if p.runs > 1 {
        buf.sort_unstable();
    }
    let low_n = buf.len();
    let end = out + (i_end - i) + (j_end - j) + low_n;
    let mut l = 0usize;
    let pa = ct.pair_a.as_mut_ptr();
    let pb = ct.pair_b.as_mut_ptr();
    let slot = ct.near_rest_slot.as_mut_ptr();
    while out < end {
        // Наименьшая из трёх голов; ключи различны.
        let mut best = i64::MAX;
        let mut from = 0;
        if i < i_end {
            best = kk[i];
            from = 1;
        }
        if j < j_end && nk[j] < best {
            best = nk[j];
            from = 2;
        }
        if l < low_n && buf[l] < best {
            best = buf[l];
            from = 3;
        }
        let s = if from == 1 { ct.kept_slot[i] } else { -2 };
        match from {
            1 => i += 1,
            2 => j += 1,
            _ => l += 1,
        }
        let pair = (best as u64) >> 1;
        unsafe {
            *slot.add(out) = s;
            *pa.add(out) = (pair >> 32) as i32;
            *pb.add(out) = (pair & 0xFFFF_FFFF) as i32;
        }
        out += 1;
    }
}

/// `lowerBound`: первое место в a[from, to) с ключом не меньше [key].
fn lower_bound(a: &[i64], from: usize, to: usize, key: i64) -> usize {
    let (mut lo, mut hi) = (from, to);
    while lo < hi {
        let m = (lo + hi) >> 1;
        if a[m] < key {
            lo = m + 1;
        } else {
            hi = m;
        }
    }
    lo
}
