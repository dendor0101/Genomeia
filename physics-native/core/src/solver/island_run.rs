//! Острова на воркерах — `IslandRun.kt` (IslandKit, runIslands, mergeIslandKits, moveIslandToWorld, keepWorldLerp) на
//! Rust.
//!
//! ТЕНЬ. Воркер считает остров на тени состояния мира: побитовой копии `BodyState`, в которой свои у воркера только
//! части, которые остров ведёт сам, — вид раскладки (область, свои блоки и швы, очередь пределов), вид сна (диапазоны
//! и организмы острова), вид учёта импульса (список слияний), копия касаний, куски области, черновики костей, рамки,
//! касаний скоростей и сумм среды, множители мышц-кластеров, списки плавности мышц и роста (только записи острова).
//! Остальные массивы общие с миром: остров пишет только свои частицы, связи, треугольники, кости и организмы. Код
//! стадий тот же, что у мира в одном потоке: тень без воркеров. Тень на воркер одна на тик, острова на ней идут по
//! очереди.
//!
//! ПАМЯТЬ. Общие поля тени принадлежат миру: тень никогда не освобождается (`MaybeUninit`), а свои части в конце тика
//! возвращаются в комплект ([IslandKit::end_tick]). Тень не пересоздаёт и не растит общие массивы: всё, что подшаг
//! дозаказывает лениво, мир готовит до островов (`prepare_shared_for_islands`), а растущие черновики у тени свои.
//!
//! СОБЫТИЯ. Тень не исполняет событий топологии (разрыв и раздавливание связи, гибель клетки, пробой, пробуждение
//! соседа): в точке события она ставит признак [Islands::abort] и пропускает его, подшаги острова заканчиваются на
//! ближайшей проверке, остров восстанавливается из снимка начала тика и идёт подшагами мира вместе с ним — так же, как
//! шёл бы без островов. Снимок — всё, что подшаги острова меняют до места события: частицы, давление касаний, учёт
//! импульса, запас среды.

use std::mem::{take, ManuallyDrop, MaybeUninit};
use std::ptr;

use crate::contacts::island_fork::ContactsOwn;
use crate::contacts::BoundaryContacts;
use crate::solver::blocks::{LayoutOwn, SCOPE_WORLD};
use crate::solver::bones::BoneWallHits;
use crate::solver::muscle_rings::MuscleRings;
use crate::solver::parallel::ParallelState;
use crate::solver::pass_chunks::PassChunks;
use crate::solver::pool::SharedMut;
use crate::solver::sleep::SleepOwn;
use crate::solver::tick_momentum::{GroupSums, MomentumOwn};
use crate::state::BodyState;

/// Комплект воркера островов: тень на тик, её свои части, снимок острова для отката. Живёт между тиками.
pub struct IslandKit {
    tick: i64,
    shadow: Box<MaybeUninit<BodyState>>,
    live: bool,
    fork: Option<Box<MaybeUninit<BoundaryContacts>>>,
    layout: LayoutOwn,
    sleep: SleepOwn,
    momentum: MomentumOwn,
    contacts: ContactsOwn,
    scope_chunks: PassChunks,
    com_sums: GroupSums,
    wall_hits: Option<BoneWallHits>,
    bone_dx: Vec<f32>,
    bone_dy: Vec<f32>,
    vel_touch_list: Vec<i32>,
    muscle_scale: Vec<f32>,
    parallel: ParallelState,
    parallel_sums: Vec<GroupSums>,
    muscle_rings: MuscleRings,
    lerp_edge_ids: Vec<i32>,
    lerp_edge_from: Vec<f32>,
    lerp_edge_to: Vec<f32>,
    lerp_tri_ids: Vec<i32>,
    lerp_tri_from: Vec<f32>,
    lerp_tri_to: Vec<f32>,
    grow_edge: Vec<i32>,
    grow_from: Vec<f32>,
    grow_to: Vec<f32>,
    grow_tri: Vec<i32>,
    grow_tri_from: Vec<f32>,
    grow_tri_to: Vec<f32>,
    /// Счётчики тени в начале тика: мир прибавляет разность (`mergeIslandKits`).
    speed_cap_hits0: i32,
    link_cap_hits0: i32,
    bone_cap_hits0: i32,
    // Снимок острова: частицы (f32 ×13, f64 ×3, i32 ×2, bool ×2) и организмы (f32 ×5, i32 ×1).
    buf_f: [Vec<f32>; 13],
    buf_d: [Vec<f64>; 3],
    buf_i: [Vec<i32>; 2],
    buf_z: [Vec<bool>; 2],
    org_f: [Vec<f32>; 5],
    org_i: Vec<i32>,
}

impl IslandKit {
    fn new() -> IslandKit {
        IslandKit {
            tick: -1,
            shadow: Box::new(MaybeUninit::uninit()),
            live: false,
            fork: None,
            layout: LayoutOwn::default(),
            sleep: SleepOwn::default(),
            momentum: MomentumOwn::default(),
            contacts: ContactsOwn::default(),
            scope_chunks: PassChunks::default(),
            com_sums: GroupSums::default(),
            wall_hits: None,
            bone_dx: Vec::new(),
            bone_dy: Vec::new(),
            vel_touch_list: Vec::new(),
            muscle_scale: Vec::new(),
            parallel: ParallelState::default(),
            parallel_sums: Vec::new(),
            muscle_rings: MuscleRings::default(),
            lerp_edge_ids: Vec::new(),
            lerp_edge_from: Vec::new(),
            lerp_edge_to: Vec::new(),
            lerp_tri_ids: Vec::new(),
            lerp_tri_from: Vec::new(),
            lerp_tri_to: Vec::new(),
            grow_edge: Vec::new(),
            grow_from: Vec::new(),
            grow_to: Vec::new(),
            grow_tri: Vec::new(),
            grow_tri_from: Vec::new(),
            grow_tri_to: Vec::new(),
            speed_cap_hits0: 0,
            link_cap_hits0: 0,
            bone_cap_hits0: 0,
            buf_f: Default::default(),
            buf_d: Default::default(),
            buf_i: Default::default(),
            buf_z: Default::default(),
            org_f: Default::default(),
            org_i: Vec::new(),
        }
    }

    /// Тень этого тика (после [begin_tick]).
    fn shadow(&mut self) -> &mut BodyState {
        debug_assert!(self.live);
        // SAFETY: begin_tick записал побитовую копию мира со своими частями; до end_tick она цела.
        unsafe { self.shadow.assume_init_mut() }
    }

    /// `beginTick`: тень на тик [tick] — копия мира со своими частями. Воркер зовёт на первом своём острове тика.
    ///
    /// # Safety
    /// [main] не меняется, пока живы тени этого тика (воркеры островов идут внутри одного `run` мира, мир ждёт их), и
    /// живёт дольше них; тень не освобождается, свои части забирает [end_tick].
    unsafe fn begin_tick(&mut self, main: &BodyState, tick: i64) {
        debug_assert!(!self.live, "тень прошлого тика не закрыта");
        self.tick = tick;
        let s: *mut BodyState = self.shadow.as_mut_ptr();
        unsafe {
            ptr::copy_nonoverlapping(main as *const BodyState, s, 1);
            let s = &mut *s;
            // Без воркеров: остров целиком в одном потоке; машинерия воркеров у тени своя и пустая.
            ptr::write(&mut s.workers, None);
            ptr::write(&mut s.parallel, take(&mut self.parallel));
            ptr::write(&mut s.parallel_sums, take(&mut self.parallel_sums));
            ptr::write(&mut s.muscle_rings, take(&mut self.muscle_rings));
            // Виды раскладки, сна и учёта импульса.
            if let Some(cb) = s.constraint_blocks.as_mut() {
                cb.install_island_own(&mut self.layout);
            }
            s.sleep.install_island_own(&mut self.sleep);
            if let Some(tm) = s.tick_momentum_state.as_mut() {
                tm.install_island_own(&mut self.momentum);
            }
            // Копия касаний: в своей памяти, которая живёт в комплекте между тиками.
            match main.contacts.as_ref() {
                Some(ct) => {
                    let fork = ManuallyDrop::into_inner(ct.island_fork(&mut self.contacts));
                    let mut mem = self.fork.take().unwrap_or_else(|| Box::new(MaybeUninit::uninit()));
                    mem.write(fork);
                    let raw = Box::into_raw(mem) as *mut BoundaryContacts;
                    ptr::write(&mut s.contacts, Some(Box::from_raw(raw)));
                }
                None => ptr::write(&mut s.contacts, None),
            }
            // Свои черновики.
            ptr::write(&mut s.isl.scope_chunks, take(&mut self.scope_chunks));
            ptr::write(&mut s.com_sums_scratch, take(&mut self.com_sums));
            let bones = main.rigid_bones.len();
            let wh = match self.wall_hits.take() {
                Some(wh) if wh.capacity == bones => wh,
                _ => BoneWallHits::new(bones),
            };
            ptr::write(&mut s.wall_hits, wh);
            if self.bone_dx.len() < main.bone_dx.len() {
                self.bone_dx = vec![0.0; main.bone_dx.len()];
                self.bone_dy = vec![0.0; main.bone_dx.len()];
            }
            ptr::write(&mut s.bone_dx, take(&mut self.bone_dx));
            ptr::write(&mut s.bone_dy, take(&mut self.bone_dy));
            let cells = main.isl.plan.max_cells;
            if self.vel_touch_list.len() < cells {
                self.vel_touch_list = vec![0; cells.max(self.vel_touch_list.len() * 2)];
            }
            ptr::write(&mut s.vel_touch_list, take(&mut self.vel_touch_list));
            s.vel_touch_n = 0;
            if self.muscle_scale.len() != main.muscle_scale_of.len() {
                self.muscle_scale = vec![0.0; main.muscle_scale_of.len()];
            }
            ptr::write(&mut s.muscle_scale_of, take(&mut self.muscle_scale));
            ptr::write(&mut s.lerp_edge_ids, take(&mut self.lerp_edge_ids));
            ptr::write(&mut s.lerp_edge_from, take(&mut self.lerp_edge_from));
            ptr::write(&mut s.lerp_edge_to, take(&mut self.lerp_edge_to));
            ptr::write(&mut s.lerp_tri_ids, take(&mut self.lerp_tri_ids));
            ptr::write(&mut s.lerp_tri_from, take(&mut self.lerp_tri_from));
            ptr::write(&mut s.lerp_tri_to, take(&mut self.lerp_tri_to));
            ptr::write(&mut s.grow_lerp_edge, take(&mut self.grow_edge));
            ptr::write(&mut s.grow_lerp_from, take(&mut self.grow_from));
            ptr::write(&mut s.grow_lerp_to, take(&mut self.grow_to));
            ptr::write(&mut s.grow_lerp_tri, take(&mut self.grow_tri));
            ptr::write(&mut s.grow_lerp_tri_from, take(&mut self.grow_tri_from));
            ptr::write(&mut s.grow_lerp_tri_to, take(&mut self.grow_tri_to));
            s.isl.shadow = true;
            s.isl.abort = false;
            self.speed_cap_hits0 = s.speed_cap_hits;
            self.link_cap_hits0 = s.link_cap_hits;
            self.bone_cap_hits0 = s.bone_cap_hits;
        }
        self.live = true;
    }

    /// Свои части тени — обратно в комплект; тень этого тика больше не трогать (её общие поля — мира).
    fn end_tick(&mut self) {
        if !self.live {
            return;
        }
        self.live = false;
        // SAFETY: тень записана begin_tick этого тика; читаем (перемещаем) только свои части, остальное забываем.
        unsafe {
            let s = self.shadow.assume_init_mut();
            self.parallel = ptr::read(&s.parallel);
            self.parallel_sums = ptr::read(&s.parallel_sums);
            self.muscle_rings = ptr::read(&s.muscle_rings);
            if let Some(cb) = s.constraint_blocks.as_mut() {
                cb.remove_island_own(&mut self.layout);
            }
            s.sleep.remove_island_own(&mut self.sleep);
            if let Some(tm) = s.tick_momentum_state.as_mut() {
                tm.remove_island_own(&mut self.momentum);
            }
            if let Some(fork) = ptr::read(&s.contacts) {
                let raw = Box::into_raw(fork);
                let mut f = ManuallyDrop::new(ptr::read(raw));
                BoundaryContacts::island_unfork(&mut f, &mut self.contacts);
                self.fork = Some(Box::from_raw(raw as *mut MaybeUninit<BoundaryContacts>));
            }
            self.scope_chunks = ptr::read(&s.isl.scope_chunks);
            self.com_sums = ptr::read(&s.com_sums_scratch);
            self.wall_hits = Some(ptr::read(&s.wall_hits));
            self.bone_dx = ptr::read(&s.bone_dx);
            self.bone_dy = ptr::read(&s.bone_dy);
            self.vel_touch_list = ptr::read(&s.vel_touch_list);
            self.muscle_scale = ptr::read(&s.muscle_scale_of);
            self.lerp_edge_ids = ptr::read(&s.lerp_edge_ids);
            self.lerp_edge_from = ptr::read(&s.lerp_edge_from);
            self.lerp_edge_to = ptr::read(&s.lerp_edge_to);
            self.lerp_tri_ids = ptr::read(&s.lerp_tri_ids);
            self.lerp_tri_from = ptr::read(&s.lerp_tri_from);
            self.lerp_tri_to = ptr::read(&s.lerp_tri_to);
            self.grow_edge = ptr::read(&s.grow_lerp_edge);
            self.grow_from = ptr::read(&s.grow_lerp_from);
            self.grow_to = ptr::read(&s.grow_lerp_to);
            self.grow_tri = ptr::read(&s.grow_lerp_tri);
            self.grow_tri_from = ptr::read(&s.grow_lerp_tri_from);
            self.grow_tri_to = ptr::read(&s.grow_lerp_tri_to);
        }
    }

    /// `runIsland`: остров [k] целиком на тени — настроить её на остров, снять снимок, подшаги. false — событие
    /// топологии: остров восстановлен из снимка и пойдёт с миром.
    fn run_island(&mut self, main: &BodyState, k: usize, h: f32, sweep0: bool, ct0: bool) -> bool {
        let plan = &main.isl.plan;
        {
            let s = self.shadow();
            let ki = k as i32;
            if let Some(cb) = s.constraint_blocks.as_mut() {
                cb.set_island(ki, plan.blocks.of(k), plan.seams.of(k));
            }
            let (a, b) = (plan.spans.from(k), plan.spans.to(k));
            s.sleep.set_island_spans(ki, &plan.span_from[a..b], &plan.span_to[a..b]);
            s.sleep.set_island_orgs(plan.orgs.of(k));
            // Куски области — сразу: куски мира собраны до островов, и куски тени их только читают.
            {
                let sl = &s.sleep;
                s.isl.scope_chunks.restrict(&main.pass_chunk_cache, sl.span_count, &sl.span_start, &sl.span_end);
                s.isl.scope_chunks.mark_built_scope(s.structure_version, sl.version, s.n, sl.scope_version);
            }
            if let Some(tm) = s.tick_momentum_state.as_mut() {
                tm.reset_island_links();
            }
            if let Some(ct) = s.contacts.as_mut() {
                ct.set_island(ki, plan.verts.of(k), plan.bones.of(k), ct0);
            }
        }
        self.gather_lerp(main, k);
        let s = self.shadow();
        s.wall_hits.drain(|_| {});
        s.vel_touch_n = 0;
        s.sweep_backwards = sweep0;
        let (speed, link, bone, peak) = (s.speed_cap_hits, s.link_cap_hits, s.bone_cap_hits, s.peak_speed2);
        s.isl.abort = false;
        self.snapshot(main, k, true);
        let s = self.shadow();
        s.solve_substeps(h);
        if main.cfg.islands_rollback_all {
            s.isl.abort = true;
        }
        if !s.isl.abort {
            return true;
        }
        self.snapshot(main, k, false);
        let s = self.shadow();
        s.speed_cap_hits = speed;
        s.link_cap_hits = link;
        s.bone_cap_hits = bone;
        s.peak_speed2 = peak;
        s.isl.abort = false;
        false
    }

    /// `gatherLerp`: записи плавности мышц и роста острова [k] — в свои списки тени, в порядке списков мира.
    fn gather_lerp(&mut self, main: &BodyState, k: usize) {
        let plan = &main.isl.plan;
        let s = self.shadow();
        let take3 = |list: &[i32], ids_src: &[i32], from_src: &[f32], to_src: &[f32], ids: &mut Vec<i32>, from: &mut Vec<f32>, to: &mut Vec<f32>| {
            let n = list.len();
            if ids.len() < n {
                let cap = n * 2;
                ids.resize(cap, 0);
                from.resize(cap, 0.0);
                to.resize(cap, 0.0);
            }
            for (x, &q) in list.iter().enumerate() {
                let q = q as usize;
                ids[x] = ids_src[q];
                from[x] = from_src[q];
                to[x] = to_src[q];
            }
            n
        };
        s.lerp_edge_n = take3(plan.lerp_edges.of(k), &main.lerp_edge_ids, &main.lerp_edge_from, &main.lerp_edge_to, &mut s.lerp_edge_ids, &mut s.lerp_edge_from, &mut s.lerp_edge_to);
        s.lerp_tri_n = take3(plan.lerp_tris.of(k), &main.lerp_tri_ids, &main.lerp_tri_from, &main.lerp_tri_to, &mut s.lerp_tri_ids, &mut s.lerp_tri_from, &mut s.lerp_tri_to);
        s.grow_lerp_n = take3(plan.grow_edges.of(k), &main.grow_lerp_edge, &main.grow_lerp_from, &main.grow_lerp_to, &mut s.grow_lerp_edge, &mut s.grow_lerp_from, &mut s.grow_lerp_to);
        s.grow_lerp_tri_n = take3(plan.grow_tris.of(k), &main.grow_lerp_tri, &main.grow_lerp_tri_from, &main.grow_lerp_tri_to, &mut s.grow_lerp_tri, &mut s.grow_lerp_tri_from, &mut s.grow_lerp_tri_to);
    }

    /// Снимок острова [k] ([save]) или возврат из него: всё, что подшаги острова меняют в общих массивах до места
    /// события. Множители мышц и длины покоя роста не нужны: подшаг мира пишет их заново из списков плавности раньше
    /// любого чтения; рабочие массивы касаний на вершину каждый проход начинает с нуля по меткам.
    fn snapshot(&mut self, main: &BodyState, k: usize, save: bool) {
        let plan = &main.isl.plan;
        let cells = plan.cells[k] as usize;
        if save && self.buf_f[0].len() < cells {
            let cap = cells.max(plan.max_cells);
            for b in self.buf_f.iter_mut() {
                *b = vec![0.0; cap];
            }
            for b in self.buf_d.iter_mut() {
                *b = vec![0.0; cap];
            }
            for b in self.buf_i.iter_mut() {
                *b = vec![0; cap];
            }
            for b in self.buf_z.iter_mut() {
                *b = vec![false; cap];
            }
        }
        let (a, b) = (plan.spans.from(k), plan.spans.to(k));
        let (sf, st) = (&plan.span_from[a..b], &plan.span_to[a..b]);
        let n = main.n;
        // SAFETY: тень — копия мира этого тика; её общие массивы — массивы мира, частицы острова пишет только он.
        let s: &mut BodyState = unsafe { self.shadow.assume_init_mut() };
        let (bf, bd, bi, bz) = (&mut self.buf_f, &mut self.buf_d, &mut self.buf_i, &mut self.buf_z);
        spans(&mut s.px, &mut bf[0], sf, st, save);
        spans(&mut s.py, &mut bf[1], sf, st, save);
        spans(&mut s.prev_x, &mut bf[2], sf, st, save);
        spans(&mut s.prev_y, &mut bf[3], sf, st, save);
        spans(&mut s.vx, &mut bf[4], sf, st, save);
        spans(&mut s.vy, &mut bf[5], sf, st, save);
        spans(&mut s.in_contact, &mut bz[0], sf, st, save);
        if s.tick_jitter_x.len() >= n {
            spans(&mut s.tick_jitter_x, &mut bf[6], sf, st, save);
            spans(&mut s.tick_jitter_y, &mut bf[7], sf, st, save);
        }
        if s.sleep_anchor_x.len() >= n {
            spans(&mut s.sleep_anchor_x, &mut bf[8], sf, st, save);
            spans(&mut s.sleep_anchor_y, &mut bf[9], sf, st, save);
        }
        if s.contact_pressure.len() >= n {
            spans(&mut s.contact_pressure, &mut bf[10], sf, st, save);
        }
        if s.contact_pressure_n.len() >= n {
            spans(&mut s.contact_pressure_n, &mut bi[0], sf, st, save);
        }
        let cancel = s.cancel_spin_on;
        if cancel {
            if let Some(tm) = s.tick_momentum_state.as_mut() {
                if tm.n == n {
                    spans(&mut tm.target_x, &mut bd[0], sf, st, save);
                    spans(&mut tm.target_y, &mut bd[1], sf, st, save);
                    spans(&mut tm.target_l, &mut bd[2], sf, st, save);
                    spans(&mut tm.before_x, &mut bf[11], sf, st, save);
                    spans(&mut tm.before_y, &mut bf[12], sf, st, save);
                    spans(&mut tm.blocked, &mut bz[1], sf, st, save);
                    spans(tm.parent_array(), &mut bi[1], sf, st, save);
                }
            }
        }
        // Организмы острова: запас среды (сумма импульса и скорость запаса), удаление от места покоя, дерево групп.
        let orgs = plan.orgs.of(k);
        if save && self.org_i.len() < orgs.len() {
            let cap = orgs.len().max(plan.max_orgs);
            for b in self.org_f.iter_mut() {
                *b = vec![0.0; cap];
            }
            self.org_i = vec![0; cap];
        }
        let org_arrays: [&mut Vec<f32>; 5] = [&mut s.com_acc_x, &mut s.com_acc_y, &mut s.flow_vx, &mut s.flow_vy, &mut s.org_sleep_drift2];
        for (q, arr) in org_arrays.into_iter().enumerate() {
            orgs_copy(arr, &mut self.org_f[q], orgs, save);
        }
        if cancel {
            if let Some(tm) = s.tick_momentum_state.as_mut() {
                if tm.n == n {
                    let op = tm.org_parent_array();
                    for (x, &o) in orgs.iter().enumerate() {
                        let o = o as usize;
                        if save {
                            self.org_i[x] = op[o];
                        } else {
                            op[o] = self.org_i[x];
                        }
                    }
                }
            }
        }
    }
}

/// Частицы диапазонов острова: в буфер ([save]) или обратно.
fn spans<T: Copy>(arr: &mut [T], buf: &mut [T], from: &[i32], to: &[i32], save: bool) {
    let mut off = 0usize;
    for x in 0..from.len() {
        let (a, b) = (from[x] as usize, to[x] as usize);
        let len = b - a;
        if save {
            buf[off..off + len].copy_from_slice(&arr[a..b]);
        } else {
            arr[a..b].copy_from_slice(&buf[off..off + len]);
        }
        off += len;
    }
}

/// Значения организмов [orgs]: в буфер ([save]) или обратно; организмы за концом массива пропускаются.
fn orgs_copy(arr: &mut [f32], buf: &mut [f32], orgs: &[i32], save: bool) {
    for (x, &o) in orgs.iter().enumerate() {
        let o = o as usize;
        if o >= arr.len() {
            continue;
        }
        if save {
            buf[x] = arr[o];
        } else {
            arr[o] = buf[x];
        }
    }
}

impl BodyState {
    /// `runIslands`: подшаги всех островов тика — по воркерам, от больших к меньшим. Возвращает, сколько островов
    /// откатилось (событие топологии); их области уже переведены в мир, и подшаги мира их посчитают.
    pub fn run_islands(&mut self, h: f32, sweep0: bool, tick: i64) -> usize {
        let w = self.active_workers();
        let kit_count = match &w {
            Some(w) if w.count > 1 => w.count,
            _ => 1,
        };
        if self.isl.kits.len() < kit_count {
            self.isl.kits.resize_with(kit_count, || None);
        }
        for kit in self.isl.kits.iter_mut().take(kit_count) {
            if kit.is_none() {
                *kit = Some(Box::new(IslandKit::new()));
            }
        }
        let ct0 = self.contacts.as_ref().is_some_and(|ct| ct.ct_backwards);
        let count = self.isl.plan.count;
        let mut kits = take(&mut self.isl.kits);
        {
            let main: &BodyState = self;
            let kits_ptr = SharedMut(kits.as_mut_ptr());
            let aborted = SharedMut(main.isl.plan.aborted.as_ptr() as *mut bool);
            let job = |x: usize, worker: usize| {
                let k = main.isl.plan.order[x] as usize;
                // SAFETY: комплект — воркера; признак отката — острова k; мир не меняется, пока идут острова.
                unsafe {
                    let kit = (*kits_ptr.at(worker)).as_mut().unwrap();
                    if kit.tick != tick || !kit.live {
                        kit.begin_tick(main, tick);
                    }
                    *aborted.at(k) = !kit.run_island(main, k, h, sweep0, ct0);
                }
            };
            match &w {
                Some(w) if w.count > 1 => w.run(count, &job),
                _ => {
                    for x in 0..count {
                        job(x, 0);
                    }
                }
            }
        }
        self.merge_island_kits(&mut kits, tick);
        self.isl.kits = kits;
        let mut aborted = 0usize;
        for k in 0..count {
            if self.isl.plan.aborted[k] {
                self.move_island_to_world(k);
                aborted += 1;
            }
        }
        self.isl.aborts += aborted as i64;
        // Подшаги мира — по его частицам: маленький мир без раздачи по воркерам.
        let mut world_cells = self.n as i64;
        for k in 0..count {
            if !self.isl.plan.aborted[k] {
                world_cells -= self.isl.plan.cells[k] as i64;
            }
        }
        self.isl.scope_particles = world_cells;
        self.isl.cells_last_tick = (self.n as i64 - world_cells) as usize;
        let aw = self.active_workers();
        if let Some(ct) = self.contacts.as_mut() {
            ct.workers = aw;
        }
        self.keep_world_lerp();
        aborted
    }

    /// `mergeIslandKits`: счётчики теней — в мир (суммы разностей, наибольшие значения), метки касаний — наибольшие;
    /// свои части теней — обратно в комплекты.
    fn merge_island_kits(&mut self, kits: &mut [Option<Box<IslandKit>>], tick: i64) {
        for kit in kits.iter_mut().flatten() {
            if kit.tick != tick || !kit.live {
                continue;
            }
            let (s0, l0, b0) = (kit.speed_cap_hits0, kit.link_cap_hits0, kit.bone_cap_hits0);
            {
                let s = kit.shadow();
                self.speed_cap_hits += s.speed_cap_hits - s0;
                self.link_cap_hits += s.link_cap_hits - l0;
                self.bone_cap_hits += s.bone_cap_hits - b0;
                if s.peak_speed2 > self.peak_speed2 {
                    self.peak_speed2 = s.peak_speed2;
                }
                if s.vel_touch_pass > self.vel_touch_pass {
                    self.vel_touch_pass = s.vel_touch_pass;
                }
                if let (Some(ct), Some(f)) = (self.contacts.as_mut(), s.contacts.as_ref()) {
                    ct.merge_island_fork(f);
                }
            }
            kit.end_tick();
        }
    }

    /// `moveIslandToWorld`: откатившийся остров [k] — снова мира: его наборы, организмы и частицы считают подшаги мира.
    fn move_island_to_world(&mut self, k: usize) {
        let plan = &self.isl.plan;
        let layout = self.constraint_blocks.as_mut().unwrap();
        for &b in plan.blocks.of(k) {
            layout.bucket_scope[b as usize] = SCOPE_WORLD;
        }
        for &s in plan.seams.of(k) {
            layout.bucket_scope[s as usize] = SCOPE_WORLD;
        }
        for &o in plan.orgs.of(k) {
            self.isl.org_scope[o as usize] = SCOPE_WORLD;
        }
        for x in plan.spans.from(k)..plan.spans.to(k) {
            let (a, b) = (plan.span_from[x] as usize, plan.span_to[x] as usize);
            self.isl.vertex_scope[a..b].fill(SCOPE_WORLD);
        }
    }

    /// `keepWorldLerp`: списки плавности мышц и роста мира — только его записи (и откатившихся островов: они теперь
    /// мира), в прежнем порядке. Записи островов уже отработали тени по своим копиям.
    fn keep_world_lerp(&mut self) {
        let plan = &self.isl.plan;
        let mut m = 0usize;
        for x in 0..self.lerp_edge_n {
            if !plan.world_entry(0, x) {
                continue;
            }
            self.lerp_edge_ids[m] = self.lerp_edge_ids[x];
            self.lerp_edge_from[m] = self.lerp_edge_from[x];
            self.lerp_edge_to[m] = self.lerp_edge_to[x];
            m += 1;
        }
        self.lerp_edge_n = m;
        m = 0;
        for x in 0..self.lerp_tri_n {
            if !plan.world_entry(1, x) {
                continue;
            }
            self.lerp_tri_ids[m] = self.lerp_tri_ids[x];
            self.lerp_tri_from[m] = self.lerp_tri_from[x];
            self.lerp_tri_to[m] = self.lerp_tri_to[x];
            m += 1;
        }
        self.lerp_tri_n = m;
        m = 0;
        for x in 0..self.grow_lerp_n {
            if !plan.world_entry(2, x) {
                continue;
            }
            self.grow_lerp_edge[m] = self.grow_lerp_edge[x];
            self.grow_lerp_from[m] = self.grow_lerp_from[x];
            self.grow_lerp_to[m] = self.grow_lerp_to[x];
            m += 1;
        }
        self.grow_lerp_n = m;
        m = 0;
        for x in 0..self.grow_lerp_tri_n {
            if !plan.world_entry(3, x) {
                continue;
            }
            self.grow_lerp_tri[m] = self.grow_lerp_tri[x];
            self.grow_lerp_tri_from[m] = self.grow_lerp_tri_from[x];
            self.grow_lerp_tri_to[m] = self.grow_lerp_tri_to[x];
            m += 1;
        }
        self.grow_lerp_tri_n = m;
    }
}
