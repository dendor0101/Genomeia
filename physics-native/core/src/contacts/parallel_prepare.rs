//! Подготовка касаний по воркерам — `ParallelPrepare`, `ParallelCcd`, `ParallelPierce`, `ParallelBuild`
//! (contacts/ParallelContacts.kt) и `prepareFused` (BoundaryContacts.kt) на Rust.
//!
//! Проход по кускам пар подряд: кусок читает свои пары и их частицы один раз и копит у себя то, что велено, — пары для
//! тела CCD (отсев `ccd_skips`), пробитые рёбра и перекрытые пары, всё в порядке пар. Склейка кусков по порядку даёт те
//! же списки, что проход подряд в одном потоке, при любом числе кусков; тело CCD (`ccd_pair`) идёт по найденным парам
//! подряд в исходном порядке, касания пишутся со смещения своего куска (префиксная сумма) — те же номера, что подряд.
//!
//! Слитая подготовка (все три сразу) — главный путь игры: пока CCD не нашёл удара, позиции не меняются, и пробой с
//! касаниями, посчитанные до тела CCD, — ровно те, что после него. Удар (в Kotlin — 3 подшага из 3200, замер 10.11):
//! CCD доделывается, пробой и касания считаются заново. Отдельные проходы — для этого случая и для подготовки без
//! пробоя. Кусков, как у Kotlin `ParallelPrepare`: по 64 пары, не больше 16 на воркер; на биты нарезка не влияет.
//! Сверка — RustWorkersParityTest.

use super::helpers::PierceBuffer;
use super::{BoundaryContacts, ContactCtx};
use crate::solver::pool::{SharedMut, Workers};

/// Пар, с которых подготовка идёт по воркерам (`PARALLEL_PAIRS_MIN`).
pub(crate) const PARALLEL_PAIRS_MIN: usize = 1024;
/// Мельче — запись касаний в одном потоке дешевле барьера (`WRITE_PARALLEL_MIN`; 3.4 тысячи касаний в одном потоке
/// стоили 7% тика, замер Kotlin 10.11).
const WRITE_PARALLEL_MIN: usize = 256;
/// Подвижных вершин, с которых границы отсева пробоя считаются по воркерам и тогда, когда пар меньше
/// [PARALLEL_PAIRS_MIN] (в Kotlin в этом случае — в одном потоке; на биты не влияет: вершина пишет только свои
/// ячейки). Профиль A21s 27.11 (simpleperf, 7 воркеров, 7 тел игроков, пар меньше 1024): границы в одном потоке —
/// 3.7% шага Rust, ~0.15 мкс на вершину; барьер — десятки мкс, поэтому от пятисот вершин.
pub(crate) const BOUNDS_PARALLEL_MIN: usize = 512;

/// Что копит кусок пар.
pub(crate) const SCAN_CCD: u8 = 1;
pub(crate) const SCAN_PIERCE: u8 = 2;
pub(crate) const SCAN_TOUCH: u8 = 4;

/// Списки кусков пар (живут в касаниях между подшагами).
#[derive(Clone, Debug, Default)]
pub struct ParallelPrepare {
    workers: usize,
    from: Vec<usize>,
    chunks: usize,
    ccd_lists: Vec<Vec<i32>>,
    ccd_n: Vec<usize>,
    pierce_bufs: Vec<PierceBuffer>,
    touch_lists: Vec<Vec<i32>>,
    touch_n: Vec<usize>,
    at: Vec<usize>,
}

impl ParallelPrepare {
    pub fn new(workers: usize) -> ParallelPrepare {
        let max = 16 * workers;
        ParallelPrepare {
            workers,
            from: vec![0; max + 1],
            chunks: 0,
            ccd_lists: (0..max).map(|_| vec![0; 64]).collect(),
            ccd_n: vec![0; max],
            pierce_bufs: (0..max).map(|_| PierceBuffer::default()).collect(),
            touch_lists: (0..max).map(|_| vec![0; 64]).collect(),
            touch_n: vec![0; max],
            at: vec![0; max + 1],
        }
    }

    /// Кусков последнего прохода.
    pub(crate) fn chunks(&self) -> usize {
        self.chunks
    }

    /// Пробитые рёбра куска [k] последнего прохода.
    pub(crate) fn pierces(&self, k: usize) -> &PierceBuffer {
        &self.pierce_bufs[k]
    }
}

impl BoundaryContacts {
    /// Воркеры подготовки этого подшага: больше одного и пар не меньше [PARALLEL_PAIRS_MIN].
    pub(crate) fn prepare_workers(&self, pairs: usize) -> Option<std::sync::Arc<Workers>> {
        match &self.workers {
            Some(w) if w.count > 1 && pairs >= PARALLEL_PAIRS_MIN => Some(w.clone()),
            _ => None,
        }
    }

    pub(crate) fn take_parallel_prepare(&mut self, workers: usize) -> Box<ParallelPrepare> {
        match self.parallel_prepare.take() {
            Some(p) if p.workers == workers => p,
            _ => Box::new(ParallelPrepare::new(workers)),
        }
    }

    /// `fusedPrepareAllowed`: условия всех трёх параллельных проходов сразу, и список пар у них один (ближние = пары).
    pub(crate) fn fused_prepare_allowed(&self, continuing: bool) -> bool {
        !continuing
            && self.opt_pierce_tear
            && self.near_n == self.pair_n
            && self.bnd_start.len() == self.n + 1
            && self.prepare_workers(self.pair_n).is_some()
    }

    /// `prepareFused`: CCD, пробой и сборка касаний одной задачей по кускам пар — побитово то же, что ccd_clamp,
    /// find_pierce и build_contacts подряд.
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn prepare_fused(
        &mut self,
        w: &Workers,
        px: &mut [f32],
        py: &mut [f32],
        qx: &[f32],
        qy: &[f32],
        vx: &[f32],
        vy: &[f32],
        inv_mass: &[f32],
        ctx: &ContactCtx,
    ) {
        self.last_toi_clamps = 0;
        self.start_deep.clear();
        if self.pierce_reach.len() < self.n {
            self.pierce_reach = vec![0.0; self.n];
            self.pierce_move = vec![0.0; self.n];
        }
        let mut p = self.take_parallel_prepare(w.count);
        let pairs = self.pair_n;
        self.scan_pairs_parallel(w, &mut p, SCAN_CCD | SCAN_PIERCE | SCAN_TOUCH, pairs, px, py, qx, qy, inv_mass, ctx);
        let hit = self.ccd_found_pairs(&p, false, px, py, qx, qy, inv_mass, ctx);
        if hit {
            self.parallel_prepare = Some(p);
            self.finish_ccd(px, py, qx, qy, inv_mass, ctx);
            self.find_pierce(px, py, qx, qy, ctx);
            self.build_contacts(px, py, vx, vy, false, ctx);
            return;
        }
        for k in 0..p.chunks {
            self.add_pierces(&p.pierce_bufs[k]);
        }
        let total = self.write_contacts_parallel(w, &mut p, px, py, vx, vy, ctx);
        self.c_n = total;
        self.last_contacts = total;
        self.parallel_prepare = Some(p);
    }

    /// Тело CCD по парам, которые прошли отсев, — подряд в порядке кусков (то есть в порядке пар).
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn ccd_found_pairs(
        &mut self,
        p: &ParallelPrepare,
        hit_before: bool,
        px: &[f32],
        py: &[f32],
        qx: &[f32],
        qy: &[f32],
        inv_mass: &[f32],
        ctx: &ContactCtx,
    ) -> bool {
        let mut hit = hit_before;
        for k in 0..p.chunks {
            let list = &p.ccd_lists[k];
            for &q in &list[..p.ccd_n[k]] {
                hit = self.ccd_pair(q as usize, hit, px, py, qx, qy, inv_mass, ctx);
            }
        }
        hit
    }

    /// Найденные рёбра — в общий список пробоя, в порядке кусков (`addPierces`).
    pub(crate) fn add_pierces(&mut self, found: &PierceBuffer) {
        let mut k = 0;
        while k < found.n {
            self.pierce(found.edges[k], found.edges[k + 1]);
            k += 2;
        }
    }

    /// Проход по кускам пар [0, pairs): что велит [what] (SCAN_*). Перед поиском пробоя — границы отсева подвижных
    /// вершин (раз в два подшага, как у Kotlin), 4 части на воркер.
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn scan_pairs_parallel(
        &mut self,
        w: &Workers,
        p: &mut ParallelPrepare,
        what: u8,
        pairs: usize,
        px: &[f32],
        py: &[f32],
        qx: &[f32],
        qy: &[f32],
        inv_mass: &[f32],
        ctx: &ContactCtx,
    ) {
        if what & SCAN_PIERCE != 0 && !self.skip_candidates_now {
            self.pierce_bounds_parallel(w, px, py, qx, qy);
        }
        let k = (16 * p.workers).min((pairs / 64).max(1));
        for c in 0..=k {
            p.from[c] = (pairs as i64 * c as i64 / k as i64) as usize;
        }
        p.chunks = k;
        let ct = SharedMut(self as *mut BoundaryContacts);
        let pp = SharedMut(p as *mut ParallelPrepare);
        w.run(k, &|c, _| {
            // SAFETY: кусок c пишет только свои списки и кэш слота покоя своих пар (near_rest_slot[q]); позиции, контур
            // и границы отсева только читает.
            unsafe { scan_chunk(ct.get(), pp.get(), c, what, px, py, qx, qy, inv_mass, ctx) }
        });
    }

    /// Границы отсева пробоя всех подвижных вершин по воркерам, 4 части на воркер (`boundsJob`).
    pub(crate) fn pierce_bounds_parallel(&mut self, w: &Workers, px: &[f32], py: &[f32], qx: &[f32], qy: &[f32]) {
        let parts = 4 * w.count;
        let movers = self.mover_count;
        let ct = SharedMut(self as *mut BoundaryContacts);
        w.run(parts, &|k, _| {
            let from = (movers as i64 * k as i64 / parts as i64) as usize;
            let to = (movers as i64 * (k as i64 + 1) / parts as i64) as usize;
            // SAFETY: часть k пишет границы только своих подвижных вершин (вершины в movers различны).
            unsafe { ct.get().pierce_bounds_range(from, to, px, py, qx, qy) }
        });
    }

    /// Касания из перекрытых пар кусков — со смещения своего куска (`ParallelPrepare.writeContacts`). Возвращает число.
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn write_contacts_parallel(
        &mut self,
        w: &Workers,
        p: &mut ParallelPrepare,
        px: &[f32],
        py: &[f32],
        vx: &[f32],
        vy: &[f32],
        ctx: &ContactCtx,
    ) -> usize {
        let chunks = p.chunks;
        p.at[0] = 0;
        for k in 0..chunks {
            p.at[k + 1] = p.at[k] + p.touch_n[k];
        }
        let total = p.at[chunks];
        self.ensure_contact_capacity(total);
        if total < WRITE_PARALLEL_MIN {
            for k in 0..chunks {
                let base = p.at[k];
                for x in 0..p.touch_n[k] {
                    let q = p.touch_lists[k][x] as usize;
                    self.write_contact(q, base + x, px, py, vx, vy, ctx);
                }
            }
        } else {
            let ct = SharedMut(self as *mut BoundaryContacts);
            let pp = SharedMut(p as *mut ParallelPrepare);
            w.run(chunks, &|k, _| {
                // SAFETY: кусок k пишет касания [at[k], at[k + 1]) и кэш слота покоя своих пар.
                unsafe {
                    let ct = ct.get();
                    let p = pp.get();
                    let base = p.at[k];
                    let list = &p.touch_lists[k];
                    for x in 0..p.touch_n[k] {
                        ct.write_contact(list[x] as usize, base + x, px, py, vx, vy, ctx);
                    }
                }
            });
        }
        total
    }
}

/// `ParallelPrepare.scanChunk`: пары куска [c] — пары для CCD, пробитые рёбра, перекрытые пары (что велит [what]).
#[allow(clippy::too_many_arguments)]
unsafe fn scan_chunk(
    ct: &mut BoundaryContacts,
    p: &mut ParallelPrepare,
    c: usize,
    what: u8,
    px: &[f32],
    py: &[f32],
    qx: &[f32],
    qy: &[f32],
    inv_mass: &[f32],
    ctx: &ContactCtx,
) {
    let (a, b) = (p.from[c], p.from[c + 1]);
    if what & SCAN_CCD != 0 {
        let list = &mut p.ccd_lists[c];
        let mut n = 0usize;
        for q in a..b {
            if ct.ccd_skips(q, px, py, qx, qy, inv_mass) {
                continue;
            }
            if n == list.len() {
                list.resize(n * 2, 0);
            }
            list[n] = q as i32;
            n += 1;
        }
        p.ccd_n[c] = n;
    }
    if what & SCAN_PIERCE != 0 {
        let out = &mut p.pierce_bufs[c];
        out.n = 0;
        ct.find_pierce_range(a, b, px, py, qx, qy, ctx, out);
    }
    if what & SCAN_TOUCH != 0 {
        let list = &mut p.touch_lists[c];
        let mut n = 0usize;
        for q in a..b {
            if !ct.contact_touches(q, px, py, ctx) {
                continue;
            }
            if n == list.len() {
                list.resize(n * 2, 0);
            }
            list[n] = q as i32;
            n += 1;
        }
        p.touch_n[c] = n;
    }
}
