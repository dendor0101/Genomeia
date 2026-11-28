//! Мир физики — `PhysicsWorld.kt`: тела по номерам (дописывание, удаление, уплотнение), пул свободных и неподвижных
//! частиц, шаг, сброс, входы тика (мышцы, множители клеток, мягкость, волокна, касания, хватка, пружина к месту,
//! группы, липучка, масса, среда мира, рамка, мышь), выходы. Снимок мира — `snapshot_core.rs`.

use crate::config::Config;
use crate::definition::{BodyDefinition, WorldScale};
use crate::jmath::sqrtf;
use crate::params::{
    AREA_COMPLIANCE, COMPACT_DEAD_SHARE, CONTACT_FRICTION, DRAG_MATCH_WEIGHT, DT, FREE_POOL_BLOCK, MEDIUM_DRAG, NORMAL_DRAG,
    NORMAL_DRAG_QUADRATIC, SOFT_COMPLIANCE,
};
use crate::state::BodyState;

/// Проверок занятости до сна у ждущего воркера (`SpinPhysicsWorkers.spinLimit` по умолчанию).
pub const WORKER_SPIN_LIMIT: u32 = 200_000;

pub struct PhysicsWorld {
    pub state: BodyState,
    pub(crate) body_first_of: Vec<i32>,
    pub(crate) body_count_of: Vec<i32>,
    pub(crate) body_muscle_first_of: Vec<i32>,
    pub(crate) body_muscle_count_of: Vec<i32>,
    /// 0 — тела нет, 1 — живо, 2 — удалено и ждёт уплотнения.
    pub(crate) body_state_of: Vec<i8>,
    pub body_ids: i32,
    pub removed_particles: i32,
    pub compactions: i32,
    // --- пул свободных частиц (FreeParticles.kt): тела из одиночных нерождённых клеток ---
    pub(crate) free_block_body: Vec<i32>,
    pub(crate) free_block_start: Vec<i32>,
    pub(crate) free_block_n: usize,
    pub(crate) free_slots: usize,
    pub(crate) free_stack: Vec<i32>,
    pub(crate) free_stack_n: usize,
    pub(crate) free_live: Vec<bool>,
    pub free_count: i32,
}

impl PhysicsWorld {
    pub fn new(cfg: Config, scale: Option<WorldScale>) -> PhysicsWorld {
        let workers = cfg.workers;
        let mut state = BodyState::new(cfg);
        state.world_scale = scale;
        // Как PhysicsWorld Kotlin: при WORKERS > 1 — общий пул на это число. Спин и приоритет потоков — от обвязки
        // (RustEngine: в игре — как у пула игры, PlatformTuning; на стендах и в тени — спин SpinPhysicsWorkers).
        if workers > 1 {
            use crate::solver::pool::{PoolTuning, Workers};
            let c = &state.cfg;
            let tuning = PoolTuning {
                spin_limit: if c.worker_spin_limit > 0 { c.worker_spin_limit } else { WORKER_SPIN_LIMIT },
                nice: c.worker_nice,
                park_max_us: if c.worker_park_max_us > 0 { c.worker_park_max_us } else { PoolTuning::DEFAULT.park_max_us },
            };
            state.workers = Some(Workers::shared(workers as usize, tuning));
        }
        PhysicsWorld {
            state,
            body_first_of: vec![0; 8],
            body_count_of: vec![0; 8],
            body_muscle_first_of: vec![0; 8],
            body_muscle_count_of: vec![0; 8],
            body_state_of: vec![0; 8],
            body_ids: 0,
            removed_particles: 0,
            compactions: 0,
            free_block_body: vec![0; 4],
            free_block_start: vec![0; 4],
            free_block_n: 0,
            free_slots: 0,
            free_stack: Vec::new(),
            free_stack_n: 0,
            free_live: Vec::new(),
            free_count: 0,
        }
    }

    /// `load`: набор тел целиком — одно тело с номером 0 (временных связей у целого тела нет; связи — «волнами»).
    pub fn load(&mut self, definition: &BodyDefinition) {
        let d = definition.for_born(None).in_wave_order();
        self.load_ordered(d);
    }

    fn load_ordered(&mut self, definition: BodyDefinition) {
        assert!(self.state.n == 0, "BodySimulation.load вызывается один раз; для новой топологии создайте новый решатель");
        let count = definition.count() as i32;
        let muscles = definition.muscle_clusters.len() as i32;
        self.state.initialize(definition);
        self.body_ids = 0;
        self.removed_particles = 0;
        self.body_state_of.fill(0);
        self.record(0, count, 0, muscles);
        self.free_block_n = 0;
        self.free_slots = 0;
        self.free_stack_n = 0;
        self.free_count = 0;
    }

    fn record(&mut self, first: i32, count: i32, muscle_first: i32, muscle_count: i32) -> i32 {
        let id = self.body_ids as usize;
        self.body_ids += 1;
        if id == self.body_first_of.len() {
            let cap = id * 2;
            self.body_first_of.resize(cap, 0);
            self.body_count_of.resize(cap, 0);
            self.body_muscle_first_of.resize(cap, 0);
            self.body_muscle_count_of.resize(cap, 0);
            self.body_state_of.resize(cap, 0);
        }
        self.body_first_of[id] = first;
        self.body_count_of[id] = count;
        self.body_muscle_first_of[id] = muscle_first;
        self.body_muscle_count_of[id] = muscle_count;
        self.body_state_of[id] = 1;
        id as i32
    }

    /// `addBody`: первое тело — загрузка и сброс; следующие (и растущее первое — после пула свободных частиц)
    /// дописываются после прежних (`appendBody`). Возвращает номер тела.
    pub fn add_body(&mut self, body: &BodyDefinition, born: Option<&[bool]>, bone_on: Option<&[bool]>) -> i32 {
        // Временные связи роста, которые рождённые клетки уже сняли бы, в мир не идут; связи — «волнами».
        let definition = body.for_born(born).in_wave_order();
        if self.state.n == 0 {
            if born.is_none() && bone_on.is_none() {
                self.load_ordered(definition);
                self.reset();
                return 0;
            }
            // Растущее тело дописывается в непустой мир: первым телом встаёт пул свободных частиц.
            self.grow_free_pool();
        }
        let first = self.state.n as i32;
        let muscles = self.state.muscle_target.len() as i32;
        self.state.append_body(&definition, born, bone_on);
        self.record(first, definition.count() as i32, muscles, definition.muscle_clusters.len() as i32)
    }

    pub fn is_body_alive(&self, body: i32) -> bool {
        body >= 0 && body < self.body_ids && self.body_state_of[body as usize] == 1
    }

    fn check_body(&self, body: i32) -> usize {
        assert!(self.is_body_alive(body), "тела {body} нет в мире");
        body as usize
    }

    pub fn body_first(&self, body: i32) -> i32 {
        self.body_first_of[self.check_body(body)]
    }

    pub fn body_count(&self, body: i32) -> i32 {
        self.body_count_of[self.check_body(body)]
    }

    pub fn body_muscle_first(&self, body: i32) -> i32 {
        self.body_muscle_first_of[self.check_body(body)]
    }

    pub fn body_muscle_count(&self, body: i32) -> i32 {
        self.body_muscle_count_of[self.check_body(body)]
    }

    /// `removeBody`: гибель тела на месте; накопилось COMPACT_DEAD_SHARE мира — уплотнение.
    pub fn remove_body(&mut self, body: i32) {
        let b = self.check_body(body);
        self.state.remove_body(self.body_first_of[b] as usize, self.body_count_of[b] as usize);
        self.body_state_of[b] = 2;
        self.removed_particles += self.body_count_of[b];
        if self.removed_particles as f32 >= COMPACT_DEAD_SHARE * self.state.n as f32 {
            self.compact();
        }
    }

    /// `compact`: выбросить удалённые тела, остальные сдвинуть вниз; производное — заново, как при загрузке.
    pub fn compact(&mut self) {
        let mut removed = vec![false; self.state.n];
        for id in 0..self.body_ids as usize {
            if self.body_state_of[id] == 2 {
                let first = self.body_first_of[id] as usize;
                removed[first..first + self.body_count_of[id] as usize].fill(true);
            }
        }
        self.state.compact_world(&removed);
        let mut particles_before = 0i32;
        let mut muscles_before = 0i32;
        for id in 0..self.body_ids as usize {
            match self.body_state_of[id] {
                1 => {
                    self.body_first_of[id] -= particles_before;
                    self.body_muscle_first_of[id] -= muscles_before;
                }
                2 => {
                    particles_before += self.body_count_of[id];
                    muscles_before += self.body_muscle_count_of[id];
                    self.body_state_of[id] = 0;
                    self.body_first_of[id] = -1;
                }
                _ => {}
            }
        }
        self.removed_particles = 0;
        self.compactions += 1;
        self.state.clear_death_logs();
    }

    /// `reset`: тела — к позе покоя, целыми, с нулевыми скоростями; удалённые остаются удалёнными, слоты пула — снова
    /// нерождённые.
    pub fn reset(&mut self) {
        if self.removed_particles > 0 {
            self.compact();
        }
        self.state.reset_state();
        if self.free_slots > 0 {
            for b in 0..self.free_block_n {
                let body = self.free_block_body[b] as usize;
                let first = self.body_first_of[body] as usize;
                for k in 0..self.body_count_of[body] as usize {
                    self.state.deactivate_free(first + k, false);
                }
            }
            self.state.refresh_surface();
            self.free_live.fill(false);
            self.free_count = 0;
            self.free_stack_n = 0;
            for id in (0..self.free_slots).rev() {
                self.free_stack[self.free_stack_n] = id as i32;
                self.free_stack_n += 1;
            }
        }
    }

    // --- свободные частицы (субстанции), этап A3 ---

    /// `addFree`: частица в слоте пула; номер стабилен, пока она жива. [mass] NaN — как у клетки того же радиуса.
    #[allow(clippy::too_many_arguments)]
    pub fn add_free(&mut self, x: f32, y: f32, radius: f32, vx: f32, vy: f32, mass: f32, drag: f32) -> i32 {
        if self.free_stack_n == 0 {
            self.grow_free_pool();
        }
        self.free_stack_n -= 1;
        let id = self.free_stack[self.free_stack_n];
        let p = self.free_particle(id) as usize;
        self.state.activate_free(p, x, y, vx, vy, radius, mass, drag);
        self.free_live[id as usize] = true;
        self.free_count += 1;
        id
    }

    /// `addFixed`: неподвижная частица (стенка, рельеф) — свободная с бесконечной массой.
    pub fn add_fixed(&mut self, x: f32, y: f32, radius: f32) -> i32 {
        self.add_free(x, y, radius, 0.0, 0.0, f32::INFINITY, 1.0)
    }

    /// `removeFree`: слот снова ждёт рождения; связи частицы снимаются.
    pub fn remove_free(&mut self, id: i32) {
        assert!(self.is_free_in_use(id), "свободной частицы {id} нет");
        let p = self.free_particle(id);
        self.state.drop_joints_of(p);
        self.state.deactivate_free(p as usize, true);
        self.free_live[id as usize] = false;
        self.free_count -= 1;
        self.free_stack[self.free_stack_n] = id;
        self.free_stack_n += 1;
    }

    pub fn is_free_in_use(&self, id: i32) -> bool {
        id >= 0 && (id as usize) < self.free_slots && self.free_live[id as usize]
    }

    pub fn is_free_alive(&self, id: i32) -> bool {
        self.is_free_in_use(id) && !self.state.cell_dead[self.free_particle(id) as usize]
    }

    /// `freeParticle`: частица мира свободной частицы [id] (поиск блока пула делением пополам).
    pub fn free_particle(&self, id: i32) -> i32 {
        assert!(id >= 0 && (id as usize) < self.free_slots, "номера свободной частицы {id} нет");
        let mut lo = 0usize;
        let mut hi = self.free_block_n - 1;
        while lo < hi {
            let mid = (lo + hi + 1) >> 1;
            if self.free_block_start[mid] <= id {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        self.body_first_of[self.free_block_body[lo] as usize] + (id - self.free_block_start[lo])
    }

    /// `growFreePool`: новое тело из одиночных нерождённых клеток, вдвое больше прежнего пула (не меньше
    /// FREE_POOL_BLOCK), слоты разнесены, чтобы между ними не запеклось ни одной пары покоя.
    fn grow_free_pool(&mut self) {
        assert!(self.state.n > 0 || self.state.world_scale.is_some(), "пустому миру со свободными частицами нужен WorldScale");
        let k = (FREE_POOL_BLOCK as usize).max(self.free_slots);
        let r0 = 0.5f32 * if self.state.n > 0 { self.state.link_scale } else { self.state.world_scale.unwrap().mean_link_length };
        // Kotlin: ceil(sqrt(k.toFloat())).toInt() — sqrt и ceil во float через double.
        let side = ((sqrtf(k as f32) as f64).ceil() as f32) as i32 as usize;
        let step = 8.0f32 * r0;
        let def = BodyDefinition {
            x: (0..k).map(|it| (it % side) as f32 * step).collect(),
            y: (0..k).map(|it| (it / side) as f32 * step).collect(),
            radius: vec![r0; k],
            is_bone: vec![false; k],
            is_muscle: vec![false; k],
            scale: if self.state.n > 0 { self.state.body.scale } else { 1.0 },
            ..Default::default()
        };
        let body = if self.state.n == 0 {
            // Пустой мир: пул — первое тело (загрузка целиком), слоты гасятся следом.
            let body = self.add_body(&def, None, None);
            for i in 0..k {
                self.state.deactivate_free(i, false);
            }
            self.state.refresh_surface();
            body
        } else {
            self.add_body(&def, Some(&vec![false; k]), None)
        };
        if self.free_block_n == self.free_block_body.len() {
            let cap = self.free_block_n * 2;
            self.free_block_body.resize(cap, 0);
            self.free_block_start.resize(cap, 0);
        }
        self.free_block_body[self.free_block_n] = body;
        self.free_block_start[self.free_block_n] = self.free_slots as i32;
        self.free_block_n += 1;
        let total = self.free_slots + k;
        self.free_live.resize(total, false);
        self.free_stack.resize(total, 0);
        for id in (self.free_slots..total).rev() {
            self.free_stack[self.free_stack_n] = id as i32;
            self.free_stack_n += 1;
        }
        self.free_slots = total;
    }

    /// `step`: тик — мышцы, множители клеток, дорастание, липучка, решатель, рамка.
    pub fn step(&mut self, dt: f32) {
        let st = &mut self.state;
        if st.n == 0 {
            return;
        }
        st.clear_death_logs();
        st.update_muscles(dt);
        if st.cell_scales_moving {
            st.advance_cell_scales(dt);
        }
        st.ease_newborn();
        st.prepare_joints();
        if st.cell_scales_dirty {
            st.recompute_cell_scales();
        }
        st.solve_tick();
        if st.bounds_on {
            st.clamp_to_bounds();
        }
    }

    pub fn step_dt(&mut self) {
        self.step(DT);
    }

    // --- входы тика ---

    pub fn set_muscle_target(&mut self, m: usize, value: f32) {
        self.state.muscle_target[m] = value;
    }

    pub fn set_all_muscle_targets(&mut self, value: f32) {
        self.state.muscle_target.fill(value);
    }

    /// `setCellScale`: команда мышцы клетки; изменённая цель будит организм.
    pub fn set_cell_scale(&mut self, cell: usize, command: f32) {
        assert!(command > 0.0, "множитель клетки должен быть больше нуля");
        let st = &mut self.state;
        if st.cell_scale_target[cell] != command {
            st.cell_scale_target[cell] = command;
            st.cell_scales_on = true;
            st.sleep.request_wake(cell as i32);
        }
        if st.cell_scale[cell] != command {
            st.cell_scales_moving = true;
        }
    }

    /// `setSoftness`: мягкость ткани клетки.
    pub fn set_softness(&mut self, cell: usize, softness: f32) {
        assert!(softness > 0.0, "мягкость клетки должна быть больше нуля");
        let st = &mut self.state;
        if st.cell_softness[cell] == softness {
            return;
        }
        st.cell_softness[cell] = softness;
        if !st.softness_on {
            st.softness_on = true;
            st.cell_scales_dirty = true;
        } else {
            st.refresh_cell_scale_ring(cell);
        }
    }

    /// `setCellFiber`: волокно мышцы клетки — единичный вектор или ноль.
    pub fn set_cell_fiber(&mut self, cell: usize, x: f32, y: f32) {
        let st = &mut self.state;
        if st.cell_fiber_x[cell] == x && st.cell_fiber_y[cell] == y {
            return;
        }
        assert!((x == 0.0 && y == 0.0) || (x * x + y * y - 1.0f32).abs() < 1e-3f32, "волокно — единичный вектор или ноль");
        st.cell_fiber_x[cell] = x;
        st.cell_fiber_y[cell] = y;
        if !st.fibers_on {
            st.fibers_on = true;
            st.cell_scales_dirty = true;
        } else {
            st.refresh_cell_fiber_ring(cell);
        }
    }

    /// `setContactless`: клетка без касаний.
    pub fn set_contactless(&mut self, cell: usize, on: bool) {
        let st = &mut self.state;
        if st.cell_no_contact[cell] == on {
            return;
        }
        st.cell_no_contact[cell] = on;
        st.baked.touch(cell as i32);
        st.refresh_surface();
    }

    /// `setGrip`: хватка частицы 0..1.
    pub fn set_grip(&mut self, cell: usize, grip: f32) {
        let g = crate::jmath::coerce_inf(grip, 0.0, 1.0);
        let st = &mut self.state;
        if st.cell_grip[cell] == g {
            return;
        }
        st.cell_grip[cell] = g;
        st.grips_dirty = true;
    }

    /// `setHome`: пружина частицы к точке (0 — снять).
    pub fn set_home(&mut self, cell: usize, x: f32, y: f32, compliance: f32) {
        assert!(compliance >= 0.0, "податливость не меньше нуля");
        let st = &mut self.state;
        st.cell_home_x[cell] = x;
        st.cell_home_y[cell] = y;
        if st.cell_home_compliance[cell] != compliance {
            st.cell_home_compliance[cell] = compliance;
            st.grips_dirty = true;
        }
    }

    /// `setContactGroup`: группа касаний частицы.
    pub fn set_contact_group(&mut self, cell: usize, group: i32) {
        let st = &mut self.state;
        if st.cell_group[cell] == group {
            return;
        }
        st.cell_group[cell] = group;
        if let Some(ct) = st.contacts.as_mut() {
            ct.invalidate_broadphase();
        }
    }

    /// `addJoint`: липучка между двумя живыми частицами.
    pub fn add_joint(&mut self, a: i32, b: i32, rest: f32, compliance: f32, wake_strain: f32, wake: bool) -> i32 {
        let st = &mut self.state;
        assert!(a != b && !st.cell_dead[a as usize] && !st.cell_dead[b as usize], "связь — между двумя живыми частицами");
        assert!(rest >= 0.0 && compliance >= 0.0, "длина покоя и податливость не меньше нуля");
        if wake && !st.is_fixed(a as usize) {
            st.sleep.request_wake(a);
        }
        if wake && !st.is_fixed(b as usize) {
            st.sleep.request_wake(b);
        }
        st.alloc_joint(a, b, rest, compliance, wake_strain) as i32
    }

    pub fn remove_joint(&mut self, id: usize) {
        self.state.free_joint(id);
    }

    pub fn set_joint_compliance(&mut self, id: usize, compliance: f32) {
        self.state.joint_compliance[id] = compliance;
    }

    /// `setMass`: масса клетки; у живой — масса организма сразу.
    pub fn set_mass(&mut self, cell: usize, mass: f32) {
        assert!(mass > 0.0, "масса должна быть больше нуля");
        let st = &mut self.state;
        let alive = !st.cell_dead[cell];
        if alive {
            crate::topology::components::LocalComponents::remove_cell(cell, &st.organism_of, &mut st.organism_size, &mut st.organism_mass, &st.rest_inv_mass);
        }
        st.rest_inv_mass[cell] = 1.0f32 / mass;
        if alive {
            st.inv_mass[cell] = st.rest_inv_mass[cell];
            crate::topology::components::LocalComponents::add_cell(cell, &st.organism_of, &mut st.organism_size, &mut st.organism_mass, &st.rest_inv_mass);
        }
    }

    pub fn set_drag(&mut self, cell: usize, scale: f32) {
        assert!(scale >= 0.0, "множитель сопротивления не может быть меньше нуля");
        self.state.drag_scale[cell] = scale;
    }

    pub fn set_deep_kill_on(&mut self, value: bool) {
        self.state.deep_kill_on = value;
        if let Some(ct) = self.state.contacts.as_mut() {
            ct.kill_on_deep = value;
        }
    }

    /// `setEnvironment`: среда мира от клиента (раз в тик до шага; в снимок не входит).
    pub fn set_environment(&mut self, gravity_y: f32, medium_scale: f32, edge_drag_scale: f32, softness: f32, friction_scale: f32) {
        assert!(medium_scale >= 0.0 && edge_drag_scale >= 0.0 && softness > 0.0 && friction_scale >= 0.0, "среда мира вне допустимого");
        if self.state.environment.gravity_y != gravity_y {
            self.state.environment.gravity_y = gravity_y;
            self.wake_all();
        }
        let env = &mut self.state.environment;
        env.medium_drag = MEDIUM_DRAG * medium_scale;
        env.normal_drag = NORMAL_DRAG * edge_drag_scale;
        env.normal_drag_quadratic = NORMAL_DRAG_QUADRATIC * edge_drag_scale;
        env.soft_compliance = SOFT_COMPLIANCE * softness;
        env.area_compliance = AREA_COMPLIANCE * softness;
        let friction = CONTACT_FRICTION * friction_scale;
        env.contact_friction = friction;
        if let Some(ct) = self.state.contacts.as_mut() {
            ct.friction = friction;
        }
    }

    /// `wakeAll`: разбудить все спящие организмы (кроме неподвижных).
    pub fn wake_all(&mut self) {
        let st = &mut self.state;
        if st.sleep.asleep_count == 0 {
            return;
        }
        for o in 0..st.organism_count {
            if !st.sleep.asleep[o] {
                continue;
            }
            let (first, last) = (st.sleep.first[o], st.sleep.last[o]);
            if first > last {
                continue;
            }
            for i in first as usize..=last as usize {
                if st.organism_of[i] != o as i32 {
                    continue;
                }
                if st.rest_inv_mass[i] > 0.0 {
                    st.sleep.request_wake(i as i32);
                }
                break;
            }
        }
    }

    pub fn set_bounds(&mut self, min_x: f32, min_y: f32, max_x: f32, max_y: f32) {
        assert!(max_x > min_x && max_y > min_y, "пустая рамка мира");
        let st = &mut self.state;
        st.bounds_on = true;
        st.bounds_min_x = min_x;
        st.bounds_min_y = min_y;
        st.bounds_max_x = max_x;
        st.bounds_max_y = max_y;
    }

    pub fn clear_bounds(&mut self) {
        self.state.bounds_on = false;
    }

    pub fn kill(&mut self, cell: usize) {
        self.state.kill_cell(cell);
    }

    pub fn set_bone(&mut self, cell: usize) -> bool {
        self.state.activate_bone(cell)
    }

    pub fn grab(&mut self, cell: i32) {
        self.state.drag_id = cell;
        self.state.match_weight[cell as usize] = DRAG_MATCH_WEIGHT;
    }

    pub fn drag_to(&mut self, x: f32, y: f32) {
        self.state.mouse_x = x;
        self.state.mouse_y = y;
    }

    pub fn release(&mut self) {
        let i = self.state.drag_id;
        if i < 0 {
            return;
        }
        self.state.match_weight[i as usize] = self.state.rest_match_weight(i as usize);
        self.state.drag_id = -1;
    }

    pub fn set_velocity(&mut self, i: usize, vx: f32, vy: f32) {
        self.state.vx[i] = vx;
        self.state.vy[i] = vy;
    }

    /// `pullToward`: разовая тяга за точку тела.
    pub fn pull_toward(&mut self, i: usize, x: f32, y: f32) {
        self.state.sleep.request_wake(i as i32);
        self.state.pull_toward(i, x, y, DT);
    }

    pub fn place(&mut self, i: usize, x: f32, y: f32) {
        let st = &mut self.state;
        st.px[i] = x;
        st.py[i] = y;
        st.prev_x[i] = x;
        st.prev_y[i] = y;
    }

    pub fn wake(&mut self, i: i32) {
        self.state.sleep.request_wake(i);
    }

    pub fn sleep_now(&mut self, i: usize) {
        self.state.sleep_now(i);
    }

    pub fn set_sleep_on(&mut self, value: bool) {
        self.state.set_sleep_on(value);
    }

    /// Родить клетку растущего тела в точке (`activate`).
    pub fn activate(&mut self, cell: usize, x: f32, y: f32, vx: f32, vy: f32) {
        self.state.activate_cell(cell, x, y, vx, vy);
    }

    /// Родить клетку там, где ей место сейчас (`activate(cell)`).
    pub fn activate_placed(&mut self, cell: usize) {
        let mut p = [0.0f32; 4];
        self.state.birth_placement(cell, &mut p, -1);
        self.state.activate_cell(cell, p[0], p[1], p[2], p[3]);
    }

    /// Родить клетку делением живой клетки [parent] (`activateFromParent`).
    pub fn activate_from_parent(&mut self, cell: usize, parent: usize) {
        let mut p = [0.0f32; 4];
        self.state.division_placement(cell, parent, &mut p);
        self.state.activate_cell(cell, p[0], p[1], p[2], p[3]);
    }
}
