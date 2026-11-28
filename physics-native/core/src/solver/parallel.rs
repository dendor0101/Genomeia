//! Параллельный обход блоков — `solver/ParallelBlocks.kt` (ParallelPhysics: solveBlocks, solveSeams,
//! globalLimitsByBlocks, solveBlockParallel и пределы воркера) на Rust.
//!
//! Как в Kotlin: блоки одной чётности частиц не делят, поэтому идут по воркерам без замков. Блок, у которого предел
//! длины дал бы событие топологии (разрыв, раздавливание — `limit_violation_is_event`), останавливается на этой
//! связи, ничего не записав, а после барьера дорабатывается в вызывающем потоке по возрастанию номера блока —
//! в исходном порядке событий. Счётчики воркера (срабатывания пределов и потолка кости, удары костей о рамку) —
//! в его черновике и сливаются после барьера. Итог побитово тот же, что у обхода в одном потоке, при любом числе
//! воркеров и любой раздаче кусков; это проверяют тесты сверки при 1 и N воркерах.
//!
//! Доступ к состоянию из воркеров — через [SharedMut]: Rust не может доказать, что блоки пишут в разные частицы,
//! это обеспечивает раскладка блоков (тот же довод, что в Kotlin).

use std::sync::Arc;

use crate::params::{BLOCK_STAGE_LIMITS_EARLY, BLOCK_STAGE_LIMITS_LATE, LIMIT_NEAR_HI2, LIMIT_NEAR_LO2, SLOW_CONSTRAINT_PERIOD};
use crate::solver::bones::{project_bone_into, BoneWallHits};
use crate::solver::kernels::link_max_length;
use crate::solver::pool::{SharedMut, Workers};
use crate::solver::tick_momentum::{GroupSums, TickMomentum};
use crate::state::BodyState;
use crate::jmath::sqrtf;

/// Черновик воркера (`WorkerScratch`): то, во что пишет только он. Выровнен на 128 байт — соседние черновики не делят
/// кэш-линий (false sharing).
#[repr(align(128))]
pub struct WorkerScratch {
    pub bone_dx: Vec<f32>,
    pub bone_dy: Vec<f32>,
    pub wall_hits: BoneWallHits,
    pub link_cap_hits: i32,
    pub bone_cap_hits: i32,
    pub speed_cap_hits: i32,
    pub peak_speed2: f32,
    /// Треугольники, которым пора умереть от долгой инверсии (`invKills`), — по номерам из исходной топологии.
    pub inv_kills: Vec<i32>,
}

/// Рабочие массивы параллельных стадий (`ParallelPhysics`): на время стадии вынимаются из состояния, чтобы куски не
/// видели их через `&mut BodyState`.
#[derive(Default)]
pub struct ParallelState {
    pub scratch: Vec<WorkerScratch>,
    blocks: Vec<i32>,
    aborts: Vec<i64>,
    limit_stop: Vec<i32>,
    /// Слияние групп импульса по касаниям (`noteKeys`, `noteRare`): по куску касаний (4 на воркер).
    note_keys: Vec<Vec<i64>>,
    note_rare: Vec<Vec<i32>>,
    workers: usize,
    bones: usize,
    bone_len: usize,
}

impl ParallelState {
    fn ensure(&mut self, workers: usize, bones: usize, bone_len: usize, buckets: usize) {
        if self.workers != workers || self.bones != bones || self.bone_len != bone_len {
            self.scratch = (0..workers)
                .map(|_| WorkerScratch {
                    bone_dx: vec![0.0; bone_len],
                    bone_dy: vec![0.0; bone_len],
                    wall_hits: BoneWallHits::new(bones),
                    link_cap_hits: 0,
                    bone_cap_hits: 0,
                    speed_cap_hits: 0,
                    peak_speed2: 0.0,
                    inv_kills: Vec::with_capacity(16),
                })
                .collect();
            self.note_keys = (0..4 * workers).map(|_| Vec::with_capacity(256)).collect();
            self.note_rare = (0..4 * workers).map(|_| Vec::with_capacity(16)).collect();
            self.workers = workers;
            self.bones = bones;
            self.bone_len = bone_len;
        }
        if self.blocks.len() < buckets {
            self.blocks = vec![0; buckets];
            self.aborts = vec![-1; buckets];
            self.limit_stop = vec![-1; buckets];
        }
    }
}

impl BodyState {
    /// `activeWorkers`: воркеры этого тика — на мире меньше PARALLEL_MIN частиц никакие (барьеры дороже работы).
    #[inline]
    pub fn active_workers(&self) -> Option<Arc<Workers>> {
        // После островов — по частицам мира (`scopeParticles`): маленький мир без раздачи по воркерам.
        let particles = if self.isl.scope_particles >= 0 { self.isl.scope_particles } else { self.n as i64 };
        if particles >= self.cfg.parallel_min as i64 { self.workers.clone() } else { None }
    }

    /// `parallelBlocksAllowed`: несобранного контура нет (его собрал бы refreshSurface посреди блока).
    #[inline]
    pub fn parallel_blocks_allowed(&self, w: &Workers) -> bool {
        w.count > 1 && self.baked.dirty_count == 0
    }

    fn take_parallel(&mut self, w: &Workers) -> ParallelState {
        let buckets = self.constraint_blocks.as_ref().map_or(0, |cb| cb.buckets);
        let mut p = std::mem::take(&mut self.parallel);
        p.ensure(w.count, self.rigid_bones.len(), self.bone_dx.len(), buckets);
        p
    }

    /// `mergeCounters`: счётчики воркеров — в состояние, черновики — к нулю.
    fn merge_counters(&mut self, p: &mut ParallelState) {
        for s in p.scratch.iter_mut() {
            self.link_cap_hits += s.link_cap_hits;
            s.link_cap_hits = 0;
            self.bone_cap_hits += s.bone_cap_hits;
            s.bone_cap_hits = 0;
        }
    }

    /// `finishBlock`: блок, который воркер остановил перед событием, — с той же стадии и записи.
    fn finish_block(&mut self, p: &mut ParallelState, h: f32, block: usize) {
        let a = p.aborts[block];
        if a < 0 {
            return;
        }
        p.aborts[block] = -1;
        self.solve_constraint_block_from(h, block, (a >> 32) as i32, a as i32 as usize);
    }

    /// Блоки из `p.blocks[0..count]` — по воркерам (`solveBlockParallel`), остановки — в `p.aborts`.
    fn run_blocks(&mut self, w: &Workers, p: &mut ParallelState, count: usize, h: f32, integrate: bool) {
        let st = SharedMut(self as *mut BodyState);
        let blocks = SharedMut(p.blocks.as_mut_ptr());
        let aborts = SharedMut(p.aborts.as_mut_ptr());
        let scratch = SharedMut(p.scratch.as_mut_ptr());
        w.run(count, &|k, worker| {
            // SAFETY: кусок k — свой блок (свои частицы и ограничения), свой элемент aborts, черновик своего воркера.
            unsafe {
                let s = st.get();
                let block = *blocks.at(k) as usize;
                let r = s.solve_block_parallel(h, block, &mut *scratch.at(worker), integrate);
                *aborts.at(block) = r;
            }
        });
    }

    /// `solveBlocks`: обе чётности — блоки по воркерам, затем по возрастанию номера доработка остановленных и блоки
    /// тел, которым нельзя параллельный обход (`serialBlock`).
    pub fn solve_blocks_parallel(&mut self, w: &Workers, h: f32, integrate: bool) {
        let mut p = self.take_parallel(w);
        for parity in 0..2usize {
            let mut count = 0usize;
            {
                let layout = self.constraint_blocks.as_ref().unwrap();
                for &block in &layout.dispatch_order[parity] {
                    let b = block as usize;
                    if layout.active(b) && !layout.serial_block[b] {
                        p.blocks[count] = block;
                        count += 1;
                    }
                }
            }
            if count > 0 {
                self.run_blocks(w, &mut p, count, h, integrate);
                self.merge_counters(&mut p);
            }
            let block_count = self.constraint_blocks.as_ref().unwrap().block_count;
            let mut block = parity;
            while block < block_count {
                let (serial, active) = {
                    let layout = self.constraint_blocks.as_ref().unwrap();
                    (layout.serial_block[block], layout.active(block))
                };
                if !serial {
                    self.finish_block(&mut p, h, block);
                } else if active {
                    self.solve_block_serial(h, block, integrate);
                }
                block += 2;
            }
        }
        if integrate && self.bounds_on {
            for s in p.scratch.iter_mut() {
                if s.wall_hits.size != 0 {
                    let hits = &mut self.wall_hits;
                    s.wall_hits.drain(|b| hits.add(b));
                }
            }
        }
        self.parallel = p;
    }

    /// `solveSeams`: наборы швов разных тел — по воркерам, затем доработка остановленных по возрастанию.
    pub fn solve_seams_parallel(&mut self, w: &Workers, h: f32) {
        let mut p = self.take_parallel(w);
        let (seam, buckets) = {
            let cb = self.constraint_blocks.as_ref().unwrap();
            (cb.seam, cb.buckets)
        };
        let mut count = 0usize;
        {
            let layout = self.constraint_blocks.as_ref().unwrap();
            for bucket in seam..buckets {
                if layout.active(bucket) {
                    p.blocks[count] = bucket as i32;
                    count += 1;
                }
            }
        }
        self.run_blocks(w, &mut p, count, h, false);
        self.merge_counters(&mut p);
        for bucket in seam..buckets {
            self.finish_block(&mut p, h, bucket);
        }
        self.parallel = p;
    }

    /// `solveBlockParallel`: блок воркером. -1 — блок решён; иначе стадия (старшие 32 бита) и запись, на которой
    /// предел дал бы событие топологии: с неё блок дорабатывает вызывающий поток.
    pub fn solve_block_parallel(&mut self, h: f32, block: usize, s: &mut WorkerScratch, integrate: bool) -> i64 {
        if integrate {
            let (from, to) = {
                let cb = self.constraint_blocks.as_ref().unwrap();
                (cb.first_particle[block] as usize, cb.first_particle[block + 1] as usize)
            };
            self.integrate_range_into(from, to, h, &mut s.wall_hits);
        }
        self.solve_block_links(h, block);
        self.solve_block_flaps(h, block);
        if self.cfg.maxlen_early {
            let stop = self.solve_block_limits_parallel(block, s);
            if stop >= 0 {
                return ((BLOCK_STAGE_LIMITS_EARLY as i64) << 32) | stop;
            }
        }
        if self.slow_constraints_now {
            if self.areas_on {
                self.solve_block_areas(h * SLOW_CONSTRAINT_PERIOD as f32, block);
            }
            self.solve_block_bend(h * SLOW_CONSTRAINT_PERIOD as f32, block);
        }
        if self.bones_rigid {
            let mut b = self.constraint_blocks.as_ref().unwrap().bones.head[block];
            while b >= 0 {
                let bu = b as usize;
                let first = self.rigid_bones[bu][0] as usize;
                if !self.sleep.is_asleep(self.organism_of[first]) && project_bone_into(self, bu, &mut s.bone_dx, &mut s.bone_dy) {
                    s.bone_cap_hits += 1;
                }
                b = self.constraint_blocks.as_ref().unwrap().bones.next[bu];
            }
        }
        let stop = if self.cfg.maxlen_early {
            self.solve_block_near_limits_parallel(block, s)
        } else {
            self.solve_block_limits_parallel(block, s)
        };
        if stop >= 0 {
            return ((BLOCK_STAGE_LIMITS_LATE as i64) << 32) | stop;
        }
        -1
    }

    /// `solveBlockLimitsParallel`: ранний предел блока воркером — по плотному списку; собирает связи у порога.
    fn solve_block_limits_parallel(&mut self, block: usize, s: &mut WorkerScratch) -> i64 {
        let (items, size) = {
            let layout = self.constraint_blocks.as_mut().unwrap();
            layout.near_count[block] = 0;
            let size = layout.limits.dense(block).len();
            (layout.limits.take_dense(block), size)
        };
        let mut stop = -1i64;
        for cursor in 0..size {
            let c = items[cursor];
            if c < 0 || self.con_dead[c as usize] {
                continue;
            }
            let cu = c as usize;
            let i = self.con_a[cu] as usize;
            let j = self.con_b[cu] as usize;
            let dx = self.px[i] - self.px[j];
            let dy = self.py[i] - self.py[j];
            let len2 = dx * dx + dy * dy;
            let (crushed, over, scale, hi2, lo2) = self.limit_state(cu, len2);
            let is_near = len2 > hi2 * LIMIT_NEAR_HI2 || (self.tearing_on && len2 < lo2 * LIMIT_NEAR_LO2);
            if !is_near {
                continue;
            }
            if (crushed || over) && self.limit_violation_parallel(cu, len2, crushed, scale, s) {
                stop = cursor as i64;
                break;
            }
            self.constraint_blocks.as_mut().unwrap().add_near(block, c);
        }
        self.constraint_blocks.as_mut().unwrap().limits.put_dense(block, items);
        stop
    }

    /// `solveBlockNearLimitsParallel`: поздний предел блока воркером — по связям у порога.
    fn solve_block_near_limits_parallel(&mut self, block: usize, s: &mut WorkerScratch) -> i64 {
        let n = self.constraint_blocks.as_ref().unwrap().near_count[block] as usize;
        for x in 0..n {
            let c = self.constraint_blocks.as_ref().unwrap().near_items[block][x];
            if self.limit_item_parallel(c, s) {
                return x as i64;
            }
        }
        -1
    }

    /// `limitItemParallel`: одна связь предела воркером; true — событие, связь не тронута.
    pub fn limit_item_parallel(&mut self, c: i32, s: &mut WorkerScratch) -> bool {
        if c < 0 || self.con_dead[c as usize] {
            return false;
        }
        let cu = c as usize;
        let i = self.con_a[cu] as usize;
        let j = self.con_b[cu] as usize;
        let dx = self.px[i] - self.px[j];
        let dy = self.py[i] - self.py[j];
        let len2 = dx * dx + dy * dy;
        let (crushed, over, scale, _, _) = self.limit_state(cu, len2);
        if !crushed && !over {
            return false;
        }
        self.limit_violation_parallel(cu, len2, crushed, scale, s)
    }

    /// `limitViolationParallel`: нарушение предела без событий — поправка до предела в черновик воркера; true —
    /// нарушение дало бы событие топологии (тогда ничего не записано).
    fn limit_violation_parallel(&mut self, c: usize, len2: f32, crushed: bool, scale: f32, s: &mut WorkerScratch) -> bool {
        let i = self.con_a[c] as usize;
        let j = self.con_b[c] as usize;
        if self.inv_mass[i] + self.inv_mass[j] == 0.0 {
            return false;
        }
        if self.is_growing_link(c) {
            return false;
        }
        if self.limit_violation_is_event(c, len2, crushed, scale) {
            return true;
        }
        let len = sqrtf(len2);
        if len < 1e-12f32 {
            return false;
        }
        let max = link_max_length(self.limit_rest(c, self.con_rest[c], scale), scale);
        self.apply_link_limit_correction(i, j, len, max);
        s.link_cap_hits += 1;
        false
    }

    /// `limitViolationIsEvent`: даст ли нарушение предела событие топологии — без единой записи. Условия и порядок —
    /// как в `solve_link_limit_violation`.
    pub fn limit_violation_is_event(&self, c: usize, len2: f32, crushed: bool, scale: f32) -> bool {
        if crushed {
            return true;
        }
        let i = self.con_a[c] as usize;
        let j = self.con_b[c] as usize;
        let len = sqrtf(len2);
        if self.tearing_on && self.membrane_pass_tear && self.con_boundary[c] {
            let (ri, rj, min_r) = match self.contacts.as_ref() {
                Some(ct) => (ct.contact_radius[i], ct.contact_radius[j], ct.min_contact_radius()),
                None => (0.0, 0.0, 0.0),
            };
            if len > ri + rj + 2.0f32 * min_r {
                return true;
            }
        }
        if len < 1e-12f32 {
            return false;
        }
        let rest_l = self.limit_rest(c, self.con_rest[c], scale);
        let max = link_max_length(rest_l, scale);
        let tear = if self.con_tendon[c] { self.link_tear_strain * self.tendon_tear } else { self.link_tear_strain };
        if len - crate::params::LINK_MAX_STRETCH * rest_l > tear * rest_l {
            return true;
        }
        self.tearing_on && self.membrane_tear_strain >= 0.0 && self.con_boundary[c] && !self.con_dead[c] && len - max > self.membrane_tear_strain * rest_l
    }

    /// `globalLimitsByBlocks`: общий проход пределов — наборы блоков по воркерам, затем наборы швов; остановки —
    /// в одном потоке по возрастанию набора; хвост прохода — края, открытые разрывами.
    pub fn solve_global_limits_parallel(&mut self, w: &Workers) {
        let mut p = self.take_parallel(w);
        let (seam, buckets) = {
            let cb = self.constraint_blocks.as_ref().unwrap();
            (cb.seam, cb.buckets)
        };
        p.limit_stop[..buckets].fill(-1);
        self.constraint_blocks.as_mut().unwrap().begin_limit_pass();
        for (from, to) in [(0usize, seam), (seam, buckets)] {
            let st = SharedMut(self as *mut BodyState);
            let stops = SharedMut(p.limit_stop.as_mut_ptr());
            let scratch = SharedMut(p.scratch.as_mut_ptr());
            w.run(to - from, &|k, worker| {
                // SAFETY: набор — связи с обоими концами в своём блоке (или шов своего тела); элемент stops — свой.
                unsafe {
                    let s = st.get();
                    let bucket = from + k;
                    if let Some(c) = s.block_limits_parallel(bucket, &mut *scratch.at(worker)) {
                        *stops.at(bucket) = c;
                    }
                }
            });
            self.merge_counters(&mut p);
            for bucket in from..to {
                let stop = p.limit_stop[bucket];
                if stop >= 0 {
                    self.solve_limit_bucket_serial(bucket, stop);
                }
            }
        }
        self.solve_limit_pass_tail();
        self.constraint_blocks.as_mut().unwrap().end_limit_pass();
        self.parallel = p;
    }

    /// Набор общего прохода пределов воркером (`blockLimitsJob`): связь, на которой случилось бы событие, или None.
    fn block_limits_parallel(&mut self, bucket: usize, s: &mut WorkerScratch) -> Option<i32> {
        let (items, n, skip) = {
            let layout = self.constraint_blocks.as_mut().unwrap();
            if !layout.active(bucket) {
                return None;
            }
            let n = layout.limits.dense(bucket).len();
            let skip = layout.pass_added_n > 0;
            (layout.limits.take_dense(bucket), n, skip)
        };
        let mut stop = None;
        for &c in &items[..n] {
            if skip && self.constraint_blocks.as_ref().unwrap().added_in_pass(c) {
                continue;
            }
            if self.limit_item_parallel(c, s) {
                stop = Some(c);
                break;
            }
        }
        self.constraint_blocks.as_mut().unwrap().limits.put_dense(bucket, items);
        stop
    }

    /// `computeSerialBlocks`: тела, у которых частица дотягивается связями (через соседа) дальше соседнего блока, —
    /// в один поток целиком: их ограничения могли бы задеть частицы блока той же чётности.
    pub fn compute_serial_blocks(&mut self) {
        let layout = self.constraint_blocks.as_mut().unwrap();
        let mut unsafe_run = vec![false; layout.body_runs];
        let start = &self.baked.vert_start;
        let other = &self.baked.vert_other;
        let block = &layout.particle_block;
        for i in 0..self.n {
            let run = layout.body_run_of[block[i] as usize] as usize;
            if unsafe_run[run] {
                continue;
            }
            let bi = block[i];
            let mut far = false;
            'scan: for s in start[i]..start[i + 1] {
                let j = other[s as usize] as usize;
                if (block[j] - bi).abs() > 1 {
                    far = true;
                    break;
                }
                for t in start[j]..start[j + 1] {
                    if (block[other[t as usize] as usize] - bi).abs() > 1 {
                        far = true;
                        break 'scan;
                    }
                }
            }
            if far {
                unsafe_run[run] = true;
            }
        }
        let mut serial = 0;
        for b in 0..layout.block_count {
            layout.serial_block[b] = unsafe_run[layout.body_run_of[b] as usize];
            if layout.serial_block[b] {
                serial += 1;
            }
        }
        layout.serial_blocks = serial;
    }
}

/// Поштучный проход по частям кусков без своих параметров (`flowPayment`, `removeJitter`, `restoreJitter`).
#[derive(Clone, Copy)]
pub enum SpanPass {
    FlowPayment,
    RemoveJitter,
    RestoreJitter,
}

// --- поштучные проходы: по частям кусков (PassChunks, `spanChunks` в Kotlin) ---
impl BodyState {
    /// `spanChunks`: части кусков поштучных проходов — единицы работы воркеров (бодрствующие частицы целиком).
    fn span_parts(&mut self) -> usize {
        self.ensure_pass_chunks();
        self.active_chunks().part_count
    }

    #[inline(always)]
    fn part_range(&self, k: usize) -> (usize, usize) {
        (self.active_chunks().part_from[k] as usize, self.active_chunks().part_to[k] as usize)
    }

    /// Проход [pass] по частям кусков воркерами: каждая часть — свои частицы.
    pub fn span_parts_run(&mut self, w: &Workers, pass: SpanPass) {
        let parts = self.span_parts();
        let st = SharedMut(self as *mut BodyState);
        w.run(parts, &|k, _| {
            // SAFETY: часть k — свои частицы.
            let s = unsafe { st.get() };
            let (from, to) = s.part_range(k);
            match pass {
                SpanPass::FlowPayment => s.flow_payment_range(from, to),
                SpanPass::RemoveJitter => s.remove_jitter_range(from, to),
                SpanPass::RestoreJitter => s.restore_jitter_range(from, to),
            }
        });
    }

    /// Учёт импульса на время прохода воркеров: вынут из состояния, отдан кускам указателем (частицы у них свои).
    fn take_momentum_ptr(&mut self) -> (Option<TickMomentum>, SharedMut<TickMomentum>) {
        let mut tm = if self.cancel_spin_on { Some(self.take_tick_momentum()) } else { None };
        let p = SharedMut(tm.as_mut().map_or(std::ptr::null_mut(), |t| t as *mut TickMomentum));
        (tm, p)
    }

    /// Пики скорости и срабатывания потолка воркеров — в состояние (`velocityFast/After`, `finishTouched`).
    fn merge_speed(&mut self, p: &mut ParallelState) {
        for s in p.scratch.iter_mut() {
            if s.peak_speed2 > self.peak_speed2 {
                self.peak_speed2 = s.peak_speed2;
            }
            s.peak_speed2 = 0.0;
            self.speed_cap_hits += s.speed_cap_hits;
            s.speed_cap_hits = 0;
        }
    }

    /// `ParallelPhysics.velocityBefore`.
    pub fn velocity_before_parallel(&mut self, w: &Workers, h: f32) {
        let parts = self.span_parts();
        let (tm, tmp) = self.take_momentum_ptr();
        let st = SharedMut(self as *mut BodyState);
        w.run(parts, &|k, _| {
            // SAFETY: часть k — свои частицы (и свои записи учёта импульса).
            unsafe {
                let s = st.get();
                let (from, to) = s.part_range(k);
                s.before_range_with(from, to, h, tmp.opt());
            }
        });
        if let Some(t) = tm {
            self.put_tick_momentum(t);
        }
    }

    /// `ParallelPhysics.velocityAfter`.
    pub fn velocity_after_parallel(&mut self, w: &Workers, last_step: bool, h_tick: f32) {
        let mut p = self.take_parallel(w);
        let parts = self.span_parts();
        let (tm, tmp) = self.take_momentum_ptr();
        let st = SharedMut(self as *mut BodyState);
        let scratch = SharedMut(p.scratch.as_mut_ptr());
        w.run(parts, &|k, worker| {
            // SAFETY: часть k — свои частицы; счётчики — черновик своего воркера.
            unsafe {
                let s = st.get();
                let sc = &mut *scratch.at(worker);
                let (from, to) = s.part_range(k);
                s.after_range_with(from, to, last_step, h_tick, tmp.opt(), &mut sc.peak_speed2, &mut sc.speed_cap_hits);
            }
        });
        if let Some(t) = tm {
            self.put_tick_momentum(t);
        }
        self.merge_speed(&mut p);
        self.parallel = p;
    }

    /// `ParallelPhysics.velocityFast`.
    pub fn velocity_fast_parallel(&mut self, w: &Workers, h: f32) {
        let mut p = self.take_parallel(w);
        let parts = self.span_parts();
        let (tm, tmp) = self.take_momentum_ptr();
        let st = SharedMut(self as *mut BodyState);
        let scratch = SharedMut(p.scratch.as_mut_ptr());
        w.run(parts, &|k, worker| {
            // SAFETY: как у velocity_after_parallel.
            unsafe {
                let s = st.get();
                let sc = &mut *scratch.at(worker);
                let (from, to) = s.part_range(k);
                s.fast_range_with(from, to, h, tmp.opt(), &mut sc.peak_speed2, &mut sc.speed_cap_hits);
            }
        });
        if let Some(t) = tm {
            self.put_tick_momentum(t);
        }
        self.merge_speed(&mut p);
        self.parallel = p;
    }

    /// `ParallelPhysics.finishTouched`: список частиц контактов — на 4·воркеров частей подряд.
    pub fn finish_touched_parallel(&mut self, w: &Workers) {
        let mut p = self.take_parallel(w);
        let parts = 4 * p.scratch.len();
        let n = self.vel_touch_n as i64;
        let (tm, tmp) = self.take_momentum_ptr();
        let st = SharedMut(self as *mut BodyState);
        let scratch = SharedMut(p.scratch.as_mut_ptr());
        w.run(parts, &|k, worker| {
            // SAFETY: записи списка частиц контактов — разные частицы (список без повторов).
            unsafe {
                let s = st.get();
                let sc = &mut *scratch.at(worker);
                let from = (n * k as i64 / parts as i64) as usize;
                let to = (n * (k as i64 + 1) / parts as i64) as usize;
                s.finish_touched_with(from, to, tmp.opt(), &mut sc.peak_speed2, &mut sc.speed_cap_hits);
            }
        });
        if let Some(t) = tm {
            self.put_tick_momentum(t);
        }
        self.merge_speed(&mut p);
        self.parallel = p;
    }

    /// `ParallelPhysics.momentumBegin`: группы — в одном потоке, импульс частиц — по частям кусков.
    pub fn momentum_begin_parallel(&mut self, w: &Workers) {
        let mut tm = self.take_tick_momentum();
        tm.begin_groups(&self.organism_of, Some(&self.sleep), Some(&self.cell_dead));
        let parts = self.span_parts();
        let tmp = SharedMut(&mut tm as *mut TickMomentum);
        let st = SharedMut(self as *mut BodyState);
        w.run(parts, &|k, _| {
            // SAFETY: часть k — свои частицы.
            unsafe {
                let s = st.get();
                let t = tmp.get();
                let (from, to) = s.part_range(k);
                t.begin_targets(from, to, &s.px, &s.py, &s.vx, &s.vy, &s.inv_mass);
            }
        });
        self.put_tick_momentum(tm);
    }

    /// `ParallelPhysics.momentumCancel`: те же шаги, что у `TickMomentum.cancel`, — поштучные по частям, суммы по
    /// кускам в свои таблицы и слияние в порядке кусков.
    pub fn momentum_cancel_parallel(&mut self, w: &Workers) {
        let parts = self.span_parts();
        let chunks = self.active_chunks().count;
        let mut tm = self.take_tick_momentum();
        let mut sums = std::mem::take(&mut self.parallel_sums);
        if sums.len() < chunks {
            sums.resize_with(chunks, GroupSums::default);
        }
        let tmp = SharedMut(&mut tm as *mut TickMomentum);
        let st = SharedMut(self as *mut BodyState);
        let sp = SharedMut(sums.as_mut_ptr());
        // SAFETY во всех кусках ниже: часть k (кусок c) — свои частицы и свои строки учёта импульса; таблица куска — своя.
        w.run(parts, &|k, _| unsafe {
            let (from, to) = st.get().part_range(k);
            tmp.get().clear_range(from, to);
        });
        tm.merge_org_groups();
        w.run(parts, &|k, _| unsafe {
            let (from, to) = st.get().part_range(k);
            tmp.get().flatten_range(from, to);
        });
        w.run(chunks, &|c, _| unsafe {
            let s = st.get();
            let t = tmp.get();
            let table = &mut *sp.at(c);
            table.clear();
            let pc = s.active_chunks();
            for q in pc.part_start[c]..pc.part_start[c + 1] {
                let q = q as usize;
                t.momentum_range(pc.part_from[q] as usize, pc.part_to[q] as usize, &s.px, &s.py, &s.vx, &s.vy, &s.inv_mass, table);
            }
        });
        for table in &sums[..chunks] {
            tm.merge_momentum(table);
        }
        w.run(parts, &|k, _| unsafe {
            let (from, to) = st.get().part_range(k);
            tmp.get().centre_range(from, to);
        });
        w.run(chunks, &|c, _| unsafe {
            let s = st.get();
            let t = tmp.get();
            let table = &mut *sp.at(c);
            table.clear();
            let pc = s.active_chunks();
            for q in pc.part_start[c]..pc.part_start[c + 1] {
                let q = q as usize;
                t.inertia_range(pc.part_from[q] as usize, pc.part_to[q] as usize, &s.px, &s.py, &s.inv_mass, table);
            }
        });
        for table in &sums[..chunks] {
            tm.merge_inertia(table);
        }
        w.run(parts, &|k, _| unsafe {
            let (from, to) = st.get().part_range(k);
            tmp.get().spin_range(from, to);
        });
        w.run(parts, &|k, _| unsafe {
            let s = st.get();
            let (from, to) = s.part_range(k);
            tmp.get().apply_range(from, to, &s.px, &s.py, &mut s.vx, &mut s.vy, &s.inv_mass);
        });
        self.parallel_sums = sums;
        self.put_tick_momentum(tm);
    }

    /// `ParallelPhysics.comSums`: импульс организмов кусками в свои таблицы, слияние в порядке кусков.
    pub fn com_sums_parallel(&mut self, w: &Workers) {
        self.ensure_pass_chunks();
        let chunks = self.active_chunks().count;
        let mut sums = std::mem::take(&mut self.parallel_sums);
        if sums.len() < chunks {
            sums.resize_with(chunks, GroupSums::default);
        }
        let st = SharedMut(self as *mut BodyState);
        let sp = SharedMut(sums.as_mut_ptr());
        w.run(chunks, &|c, _| {
            // SAFETY: кусок c читает свои частицы и пишет только свою таблицу.
            unsafe {
                let s = st.get();
                let table = &mut *sp.at(c);
                table.clear();
                let pc = s.active_chunks();
                for q in pc.part_start[c]..pc.part_start[c + 1] {
                    let q = q as usize;
                    for i in pc.part_from[q] as usize..pc.part_to[q] as usize {
                        if s.inv_mass[i] <= 0.0 {
                            continue;
                        }
                        let m = 1.0f32 / s.inv_mass[i];
                        let at = table.at(s.organism_of[i]);
                        // Kotlin: v[at] += m * vx[i] — Float·Float во float, затем в double.
                        table.values[at] += (m * s.vx[i]) as f64;
                        table.values[at + 1] += (m * s.vy[i]) as f64;
                    }
                }
            }
        });
        for table in &sums[..chunks] {
            for x in 0..table.count() {
                let o = table.key_at(x) as usize;
                let at = table.offset_at(x);
                self.com_acc_x[o] += table.values[at] as f32;
                self.com_acc_y[o] += table.values[at + 1] as f32;
            }
        }
        self.parallel_sums = sums;
    }

    /// `runEdges`: рёбра контура наборов блоков по воркерам, затем наборов швов ([mode] 0 ориентация, 1 среда).
    pub fn edges_parallel(&mut self, w: &Workers, mode: i32, h: f32) {
        let (seam, buckets) = {
            let cb = self.constraint_blocks.as_ref().unwrap();
            (cb.seam, cb.buckets)
        };
        let st = SharedMut(self as *mut BodyState);
        for (from, to) in [(0usize, seam), (seam, buckets)] {
            w.run(to - from, &|k, _| {
                // SAFETY: рёбра набора трогают только частицы своего блока (шов — своего тела).
                unsafe { st.get().edges_bucket(from + k, mode, h) }
            });
        }
    }

    /// `ParallelPhysics.viscosity` с блоками: наборы блоков по воркерам, затем наборы швов.
    pub fn viscosity_parallel(&mut self, w: &Workers, k: f32) {
        let (seam, buckets) = {
            let cb = self.constraint_blocks.as_ref().unwrap();
            (cb.seam, cb.buckets)
        };
        let st = SharedMut(self as *mut BodyState);
        for (from, to) in [(0usize, seam), (seam, buckets)] {
            w.run(to - from, &|b, _| {
                // SAFETY: связи набора трогают только частицы своего блока (шов — своего тела).
                unsafe { st.get().viscosity_bucket(from + b, k) }
            });
        }
    }

    /// `ParallelPhysics.noteContacts`: ключи пар организмов касаний — по кускам (4 на воркер) без повторов, само слияние
    /// групп — здесь, в порядке кусков. Итог от порядка не зависит: корень группы — наименьший номер частицы.
    pub fn note_contacts_parallel(&mut self, w: &Workers, ct: &crate::contacts::BoundaryContacts, tm: &mut TickMomentum) {
        let mut p = self.take_parallel(w);
        let parts = p.note_keys.len();
        let n = ct.contact_count();
        {
            let keys = SharedMut(p.note_keys.as_mut_ptr());
            let rare = SharedMut(p.note_rare.as_mut_ptr());
            let blocked = SharedMut(tm.blocked.as_mut_ptr());
            let tmr: &TickMomentum = tm;
            let (inv, org) = (&self.inv_mass[..], &self.organism_of[..]);
            w.run(parts, &|k, _| {
                // SAFETY: кусок k пишет свои списки; пометку неподвижных — атомарно (contact_key_shared).
                unsafe {
                    let keys = &mut *keys.at(k);
                    let rare = &mut *rare.at(k);
                    keys.clear();
                    rare.clear();
                    let from = (n as i64 * k as i64 / parts as i64) as usize;
                    let to = (n as i64 * (k as i64 + 1) / parts as i64) as usize;
                    for c in from..to {
                        let key = tmr.contact_key_shared(ct.contact_i(c) as usize, ct.contact_j(c) as usize, inv, org, blocked.at(0));
                        if key == -1 {
                            continue;
                        }
                        if key == -2 {
                            rare.push(c as i32);
                            continue;
                        }
                        keys.push(key);
                    }
                    keys.sort_unstable();
                    keys.dedup();
                }
            });
        }
        for k in 0..parts {
            for &key in &p.note_keys[k] {
                tm.union_key(key);
            }
            for &c in &p.note_rare[k] {
                let c = c as usize;
                tm.union_contact(ct.contact_i(c) as usize, ct.contact_j(c) as usize, &self.organism_of);
            }
        }
        self.parallel = p;
    }

    /// `ParallelPhysics.plasticity`: связи и треугольники текут независимо — без сна куски по номерам (4 на воркер),
    /// со сном наборы блоков и швов с бодрствующими частицами; набор тот же, что в одном потоке. Положения только
    /// читаются, поэтому наборы блоков и швов идут одной задачей.
    pub fn plasticity_parallel(&mut self, w: &Workers, k: f32) {
        let st = SharedMut(self as *mut BodyState);
        if self.sleep.asleep_count == 0 || self.constraint_blocks.is_none() {
            let parts = 4 * w.count;
            let (con_n, tri_n) = (self.con_count, self.tri_count);
            w.run(parts, &|q, _| {
                let c0 = (con_n as i64 * q as i64 / parts as i64) as usize;
                let c1 = (con_n as i64 * (q as i64 + 1) / parts as i64) as usize;
                let t0 = (tri_n as i64 * q as i64 / parts as i64) as usize;
                let t1 = (tri_n as i64 * (q as i64 + 1) / parts as i64) as usize;
                // SAFETY: часть q пишет длины покоя и выдержку только своих связей и треугольников.
                unsafe { st.get().plastic_ranges(c0, c1, t0, t1, k) }
            });
            return;
        }
        let buckets = self.constraint_blocks.as_ref().unwrap().buckets;
        w.run(buckets, &|b, _| {
            // SAFETY: связь и треугольник лежат ровно в одном наборе — набор b пишет только свои.
            unsafe { st.get().plastic_block(b, k) }
        });
    }

    /// `ParallelPhysics.scanInversions`: счётчик инверсии у каждого треугольника свой — наборы блоков и швов по
    /// воркерам; гибель — событие топологии, после барьера в одном потоке по возрастанию номеров (как в Kotlin с
    /// воркерами).
    pub fn scan_inversions_parallel(&mut self, w: &Workers) {
        let mut p = self.take_parallel(w);
        for s in p.scratch.iter_mut() {
            s.inv_kills.clear();
        }
        let buckets = self.constraint_blocks.as_ref().unwrap().buckets;
        {
            let st = SharedMut(self as *mut BodyState);
            let scratch = SharedMut(p.scratch.as_mut_ptr());
            w.run(buckets, &|b, worker| {
                // SAFETY: треугольник лежит ровно в одном наборе — набор b пишет счётчики только своих; список гибели —
                // воркера.
                unsafe {
                    let s = st.get();
                    let kills = &mut (*scratch.at(worker)).inv_kills;
                    s.inversions_of_bucket(b, kills);
                }
            });
        }
        let total: usize = p.scratch.iter().map(|s| s.inv_kills.len()).sum();
        if total > 0 {
            // Редкое событие: общий список — только когда есть кого убить (как IntArray(total) в Kotlin).
            let mut all: Vec<i32> = Vec::with_capacity(total);
            for s in p.scratch.iter() {
                all.extend_from_slice(&s.inv_kills);
            }
            all.sort_unstable();
            self.parallel = p;
            for id in all {
                self.kill_triangle_now(id as usize);
            }
            return;
        }
        self.parallel = p;
    }
}
