//! Конвейер решателя — `solver/BodySimulation.kt` (тик и подшаги), `solver/BodyInitialization.kt` (загрузка тела,
//! сброс) и обход блоков из `solver/BlockedIteration.kt`. Путь одного потока, без островов (биты от них не зависят)
//! и без наблюдателя стенда.

use crate::damage::fracture::FractureOrientation;
use crate::definition::{BodyDefinition, WorldScale};
use crate::params::{BLOCK_STAGE_LIMITS_EARLY, BLOCK_STAGE_LIMITS_LATE, BLOCK_STAGE_START, CANCEL_INTERNAL_DRIFT, DT, SLOW_CONSTRAINT_PERIOD, SOLVER_ITERS};
use crate::state::BodyState;
use crate::topology::baked::BakedTopology;

impl BodyState {
    /// `initialize`: рабочие массивы из описания тела (порядок построения — как в Kotlin).
    pub fn initialize(&mut self, definition: BodyDefinition) {
        self.body = definition;
        let n = self.body.count();
        self.n = n;
        let scale = match self.world_scale {
            Some(s) => s,
            None => WorldScale::of(&self.body),
        };
        self.world_scale = Some(scale);
        self.link_scale = scale.mean_link_length;
        self.cell_dead = vec![false; n];
        self.cell_unborn = vec![false; n];
        self.con_grow_target = Vec::new();
        self.con_grow_age = Vec::new();
        self.tri_grow_target = Vec::new();
        self.grow_edge_n = 0;
        self.grow_tri_n = 0;
        self.px = vec![0.0; n];
        self.py = vec![0.0; n];
        self.prev_x = vec![0.0; n];
        self.prev_y = vec![0.0; n];
        self.vx = vec![0.0; n];
        self.vy = vec![0.0; n];
        self.inv_mass = vec![0.0; n];
        self.match_weight = vec![0.0; n];
        self.in_contact = vec![false; n];
        self.bone_of = vec![-1; n];
        self.muscle_of = vec![-1; n];
        let rigid: Vec<Vec<i32>> = self.body.bone_clusters.iter().filter(|c| c.len() >= 3).cloned().collect();
        self.degenerate_bones = (self.body.bone_clusters.len() - rigid.len()) as i32;
        self.rigid_bones = rigid;
        for (b, ids) in self.rigid_bones.iter().enumerate() {
            for &i in ids {
                self.bone_of[i as usize] = b as i32;
            }
        }
        self.bone_cluster_of = self.bone_of.clone();
        self.build_bone_rest_pose();
        for (m, ids) in self.body.muscle_clusters.iter().enumerate() {
            for &i in ids {
                self.muscle_of[i as usize] = m as i32;
            }
        }
        self.grow_muscles(0);
        let nm = self.body.muscle_clusters.len();
        self.muscle_activation = vec![0.0; nm];
        self.muscle_scale_of = vec![1.0; nm];
        self.muscle_target = vec![0.0; nm];
        self.lnk_count = self.body.link_count();
        self.lnk_a = self.body.link_a.clone();
        self.lnk_b = self.body.link_b.clone();
        self.tri_count = self.body.tri_count();
        self.baked = BakedTopology::new(&self.body);
        self.fracture = FractureOrientation::new(self.body.tri_count());
        self.lnk_edge = self.baked.link_edge.clone();
        self.tri_id = (0..self.tri_count as i32).collect();
        self.tri_a = self.body.tri_a.clone();
        self.tri_b = self.body.tri_b.clone();
        self.tri_c = self.body.tri_c.clone();
        self.tri_rest_area2 = self.body.tri_rest_area2.clone();
        let lc = self.lnk_count;
        self.con_a = vec![0; lc];
        self.con_b = vec![0; lc];
        self.con_rest = vec![0.0; lc];
        self.con_muscle = vec![0; lc];
        self.con_edge = vec![0; lc];
        self.con_count = self.fill_constraints();
        self.tri_muscle = (0..self.tri_count).map(|t| self.muscle_of_tri(self.tri_a[t], self.tri_b[t], self.tri_c[t])).collect();
        self.tri_inverted = vec![false; self.tri_count];
        self.cell_scale = vec![1.0; n];
        self.cell_scale_target = vec![1.0; n];
        self.drag_scale = vec![1.0; n];
        self.con_cell_scale = vec![1.0; self.con_a.len()];
        self.tri_cell_scale = vec![1.0; self.tri_a.len()];
        self.cell_softness = vec![1.0; n];
        self.cell_fiber_x = vec![0.0; n];
        self.cell_fiber_y = vec![0.0; n];
        self.cell_no_contact = vec![false; n];
        self.cell_group = vec![0; n];
        self.cell_grip = vec![0.0; n];
        self.grips_dirty = true;
        self.cell_home_compliance = vec![0.0; n];
        self.cell_home_x = vec![0.0; n];
        self.cell_home_y = vec![0.0; n];
        self.con_softness = vec![1.0; self.con_a.len()];
        self.tri_softness = vec![1.0; self.tri_a.len()];
        self.build_masses();
        self.build_organisms(true);
        self.build_contacts();
        self.initialize_constraint_blocks();
        self.rebuild_temp_retire();
    }

    /// `resetState`: топология — к целой, частицы — в позу покоя.
    pub fn reset_state(&mut self) {
        self.restore_topology();
        for i in 0..self.n {
            self.px[i] = self.body.x[i];
            self.py[i] = self.body.y[i];
            self.vx[i] = 0.0;
            self.vy[i] = 0.0;
            self.inv_mass[i] = self.rest_inv_mass[i];
            self.match_weight[i] = self.rest_match_weight(i);
        }
        self.trap_ticks.fill(0);
        self.fold_ticks.fill(0);
        self.flow_vx.fill(0.0);
        self.flow_vy.fill(0.0);
        self.muscle_activation.fill(0.0);
        self.muscle_target.fill(0.0);
        self.drag_id = -1;
        self.inverted_peak = 0;
        self.bone_cap_hits = 0;
        self.link_cap_hits = 0;
        self.link_torn.fill(false);
        self.speed_cap_hits = 0;
    }

    /// `solveTick`: тик решателя.
    pub fn solve_tick(&mut self) {
        // Острова по замеру: время решает, как считать, но не что получится — биты те же.
        let auto = self.cfg.islands_mode == crate::solver::island_plan::ISLANDS_AUTO;
        let tick_start = if auto {
            self.isl.on = self.isl.chooser.want_islands();
            Some(std::time::Instant::now())
        } else {
            None
        };
        self.split_dirty_bones();
        self.contact_pressure.fill(0.0);
        self.contact_pressure_n.fill(0);
        self.crushed_cell.fill(false);
        if self.tears_pending {
            self.refresh_surface();
        }
        // Воркеры могли смениться (размер мира, сборка касаний): касаниям — каждый тик (BodySimulation.kt).
        let aw = self.active_workers();
        if let Some(ct) = self.contacts.as_mut() {
            ct.workers = aw;
        }
        let h = DT / self.substeps() as f32;
        self.wake_by_input();
        self.begin_tick_motion();
        if self.cancel_spin_on {
            self.prepare_awake_spans();
            self.momentum_begin();
        }
        if self.cancel_spin_on {
            self.note_joints_momentum();
        }
        // ОСТРОВА (island_plan.rs, island_run.rs): подшаги островов — по воркерам, каждый остров целиком на тени мира,
        // потом подшаги мира. Каждая область начинает подшаги с той же фазы обхода связей и касаний, что и мир без
        // островов. Остров с событием топологии откатывается и идёт с миром.
        let sweep0 = self.sweep_backwards;
        let islands = self.partition_islands();
        let mut aborted = 0usize;
        if islands > 0 {
            self.isl.tick += 1;
            let tick = self.isl.tick;
            aborted = self.run_islands(h, sweep0, tick);
            self.enter_world_scope();
            self.sweep_backwards = sweep0;
        }
        self.isl.last_tick = islands - aborted;
        if islands == 0 {
            self.isl.cells_last_tick = 0;
        }
        self.solve_substeps(h);
        if islands > 0 {
            self.isl.scope_particles = -1;
            self.set_tick_scope(crate::solver::blocks::SCOPE_ALL);
        }
        self.refresh_surface();
        self.apply_plasticity(DT);
        self.burst_by_pressure();
        self.remove_persistent_inversions();
        if self.tears_pending && self.compaction_due() {
            self.rebuild_timed();
        }
        self.update_sleep();
        if islands > 0 {
            self.clear_islands();
        }
        if let Some(t0) = tick_start {
            self.isl.chooser.record(t0.elapsed().as_nanos() as i64, islands);
        }
    }

    /// `solveSubsteps`: подшаги тика по всему миру.
    pub fn solve_substeps(&mut self, h: f32) {
        let substeps = self.substeps();
        for step in 0..substeps {
            self.slow_constraints_now = step % SLOW_CONSTRAINT_PERIOD == 0;
            self.update_muscle_scale((step + 1) as f32 / substeps as f32);
            self.apply_cell_scale_lerp(step, substeps);
            self.apply_growth_lerp(step, substeps);
            self.apply_wake_requests();
            // CONTACT_BEFORE_INTEGRATE = false: касание до интегрирования не прикладывается.
            let cancel = CANCEL_INTERNAL_DRIFT && self.drag_id < 0;
            debug_assert!(!cancel, "снятие сноса выключено (CANCEL_INTERNAL_DRIFT = false)");
            if self.sweep_flip == 1 {
                self.sweep_backwards = !self.sweep_backwards;
            }
            for iter in 0..SOLVER_ITERS {
                if self.sweep_flip == 2 {
                    self.sweep_backwards = !self.sweep_backwards;
                }
                self.solve_blocked_iteration(h, iter == 0 && !cancel);
            }
            // Тень острова встретила событие топологии: остров откатится и пойдёт с миром (island_run.rs).
            if self.isl.abort {
                return;
            }
            // Липучка — только в мире: тело с ней в остров не идёт.
            let world_scope = self.isl.count == 0 || self.sleep.scope == crate::solver::blocks::SCOPE_WORLD;
            if world_scope {
                self.solve_joints(h);
            }
            if self.joint_active_n > 0 || !self.constraint_blocks.as_ref().is_some_and(|cb| cb.has_seams()) {
                self.solve_link_max_length();
            }
            if self.isl.abort {
                return;
            }
            self.refresh_surface();
            if self.wall_hits.size != 0 {
                self.resolve_bone_bounds(false);
            }
            if self.contacts.is_some() && self.contacts_on {
                self.substep_contacts(step, h);
            }
            if self.isl.abort {
                return;
            }
            let fast_velocity = step != substeps - 1 && self.velocity_fast_allowed();
            if fast_velocity {
                self.mark_velocity_touched();
                self.velocity_pass_fast(h);
            } else {
                self.velocity_pass_before(h);
            }
            self.apply_grip(h);
            self.apply_gravity(h);
            if self.contacts_on && !self.contacts_stale {
                if let Some(mut ct) = self.contacts.take() {
                    {
                        let ctx = crate::contact_ctx!(self);
                        ct.solve_restitution(&self.px, &self.py, &mut self.vx, &mut self.vy, &self.inv_mass, &ctx);
                    }
                    self.contacts = Some(ct);
                }
            }
            // Тело под мышью в остров не идёт: тяга — только в мире.
            if world_scope {
                self.apply_drag_velocity(h);
            }
            if step == substeps - 1 {
                self.apply_viscosity(h * substeps as f32);
            }
            if step == substeps - 1 {
                self.remove_tick_jitter();
                self.apply_normal_drag(h * substeps as f32);
                self.restore_tick_jitter();
            }
            if fast_velocity {
                self.finish_velocity_touched();
            } else {
                self.velocity_pass_after(step == substeps - 1, h * substeps as f32);
            }
            if self.cancel_spin_on && step == substeps - 1 {
                self.momentum_cancel();
                self.clamp_speed();
            }
        }
    }

    /// Касания подшага: подготовка (пробуждение до решения), учёт импульса, кости, решение, гибель, пробой, ориентация.
    fn substep_contacts(&mut self, step: i32, h: f32) {
        let mut ct = self.contacts.take().unwrap();
        ct.skip_candidates_now = (step & 1) == 1 && self.structure_version == self.contacts_version;
        self.contacts_version = self.structure_version;
        {
            let ctx = crate::contact_ctx!(self);
            ct.prepare(&mut self.px, &mut self.py, &self.prev_x, &self.prev_y, &self.vx, &self.vy, &self.inv_mass, false, &ctx);
        }
        self.contacts = Some(ct);
        if self.note_sleep_contacts() {
            self.apply_wake_requests();
            let mut ct = self.contacts.take().unwrap();
            {
                let ctx = crate::contact_ctx!(self);
                ct.prepare(&mut self.px, &mut self.py, &self.prev_x, &self.prev_y, &self.vx, &self.vy, &self.inv_mass, false, &ctx);
            }
            self.contacts = Some(ct);
        }
        if self.cancel_spin_on {
            self.note_contacts_momentum();
        }
        let mut ct = self.contacts.take().unwrap();
        {
            let bo = if self.bones_rigid { Some(&self.bone_of[..]) } else { None };
            ct.update_bones(&self.px, &self.py, &self.inv_mass, bo, &self.rigid_bones);
        }
        {
            let ctx = crate::contact_ctx!(self);
            ct.solve_contacts(
                &mut self.px,
                &mut self.py,
                &mut self.vx,
                &mut self.vy,
                &self.inv_mass,
                h,
                false,
                &mut self.contact_pressure,
                &mut self.contact_pressure_n,
                &ctx,
            );
        }
        self.contacts = Some(ct);
        self.contacts_stale = false;
        if self.contacts.as_ref().unwrap().kill_n() > 0 {
            self.apply_kills();
            self.contacts_stale = true;
        }
        if self.contacts.as_ref().unwrap().pierce_n > 0 {
            self.apply_pierce();
        }
        self.refresh_surface();
        self.solve_boundary_orientation_stage();
    }

    /// `solveBlockedIteration`: блоки по чётности, затем наборы швов; если швы есть — предел длины по контуру.
    pub fn solve_blocked_iteration(&mut self, h: f32, integrate: bool) {
        let w = self.active_workers();
        if let Some(w) = w.as_deref().filter(|w| self.parallel_blocks_allowed(w)) {
            // Блоки одной чётности — по воркерам; швы и доработка событий — здесь (solver/parallel.rs).
            self.solve_blocks_parallel(w, h, integrate);
        } else {
            // `forActiveBlocks`: блоки чётности по возрастанию номера (блоки одной чётности частиц не делят); у вида
            // острова — только его блоки.
            for parity in 0..2usize {
                let mut walk = self.constraint_blocks.as_ref().unwrap().walk_blocks(parity);
                while let Some(block) = self.constraint_blocks.as_ref().unwrap().next_active(&mut walk) {
                    self.solve_block_serial(h, block, integrate);
                }
            }
        }
        let (seam, buckets) = {
            let cb = self.constraint_blocks.as_ref().unwrap();
            (cb.seam, cb.buckets)
        };
        if let Some(w) = w.as_deref().filter(|w| self.parallel_blocks_allowed(w) && buckets - seam > 1) {
            self.solve_seams_parallel(w, h);
        } else {
            let mut walk = self.constraint_blocks.as_ref().unwrap().walk(seam, buckets);
            while let Some(bucket) = self.constraint_blocks.as_ref().unwrap().next_active(&mut walk) {
                self.solve_constraint_block_from(h, bucket, BLOCK_STAGE_START, 0);
            }
        }
        if self.constraint_blocks.as_ref().unwrap().has_seams() {
            self.solve_link_max_length();
            self.refresh_surface();
        }
    }

    /// `solveBlockSerial`: блок целиком — интегрирование частиц блока (на первой итерации) и его ограничения.
    pub fn solve_block_serial(&mut self, h: f32, block: usize, integrate: bool) {
        if integrate {
            let (from, to) = {
                let cb = self.constraint_blocks.as_ref().unwrap();
                (cb.first_particle[block] as usize, cb.first_particle[block + 1] as usize)
            };
            self.integrate_range(from, to, h);
        }
        self.solve_constraint_block_from(h, block, BLOCK_STAGE_START, 0);
    }

    /// `solveConstraintBlockFrom`: ограничения блока со стадии [stage] и записи [cursor] её очереди пределов.
    pub fn solve_constraint_block_from(&mut self, h: f32, block: usize, stage: i32, cursor: usize) {
        if stage == BLOCK_STAGE_START {
            self.solve_block_links(h, block);
            self.solve_block_flaps(h, block);
        }
        if stage <= BLOCK_STAGE_LIMITS_EARLY {
            let from = if stage == BLOCK_STAGE_LIMITS_EARLY { cursor } else { 0 };
            if self.cfg.maxlen_early {
                self.solve_block_limits(block, from);
                self.refresh_surface();
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
                    if !self.sleep.is_asleep(self.organism_of[first]) {
                        self.project_bone(bu);
                    }
                    b = self.constraint_blocks.as_ref().unwrap().bones.next[bu];
                }
            }
        }
        let from = if stage == BLOCK_STAGE_LIMITS_LATE { cursor } else { 0 };
        if self.cfg.maxlen_early {
            self.solve_block_near_limits(block, from);
        } else {
            self.solve_block_limits(block, from);
        }
        self.refresh_surface();
    }
}
