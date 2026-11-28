//! Живая топология — `topology/TopologyRuntime.kt`, функции BodyState из `topology/BoundaryLinkLimits.kt` и
//! `topology/TriangleDamage.kt`: сборка организмов, масс, зондов, контура, рабочих связей, касаний; гибель ребра и
//! треугольника, пересборка после разрывов, обновление поверхности, долгая инверсия треугольника.

use std::sync::Arc;

use crate::contacts::{BoundaryContacts, ContactCtx, ContactTopology};
use crate::jmath::{sqrtf, strict_acos};
use crate::params::{CCD_CORE, CONTACT_RESTITUTION, CONTACT_SCALE, INVERTED_TRIANGLE_HOLD_TICKS, KILL_DEPTH, PROBE_ANGLE_EPS};
use crate::state::BodyState;
use crate::topology::boundary::{BoundaryInputs, LocalBoundaryConstraints};
use crate::topology::components::{LocalComponents, OrgArrays};
use crate::topology::surface::{LocalSurface, SurfaceCells};

/// Массивы хозяина для касаний (`ContactCtx`) — из полей состояния, не занимая `contacts`.
#[macro_export]
macro_rules! contact_ctx {
    ($s:expr) => {
        $crate::contacts::ContactCtx {
            inv_mass: &$s.inv_mass,
            organism_of: &$s.organism_of,
            no_contact: &$s.cell_no_contact,
            group: &$s.cell_group,
            contact_ramp: &$s.contact_ramp,
            immune_stamp: &$s.grow_stamp,
            bone_of: if $s.bones_rigid { Some(&$s.bone_of[..]) } else { None },
            bone_ids: &$s.rigid_bones,
            vertex_scope: &$s.isl.vertex_scope,
        }
    };
}

impl BodyState {
    /// `probeCells`: зонды по углу или только свободные клетки.
    #[inline]
    pub fn probe_cells(&self) -> &[bool] {
        if self.probe_on {
            &self.probe
        } else {
            &self.is_free
        }
    }

    /// Пересчитать местную поверхность вершины [v] (LocalSurface.update).
    pub fn surface_update(&mut self, v: usize) {
        let mut cells = SurfaceCells {
            body: &self.body,
            topology: &self.baked,
            bone_of: &self.bone_of,
            dead: &self.cell_dead,
            cell_radius: &self.radius,
            free: &mut self.is_free,
            probe: &mut self.probe,
        };
        self.local_surface.update(v, &mut cells);
    }

    /// Пересчитать контур вершины [v] (LocalBoundaryConstraints.updateVertex).
    pub fn boundary_update_vertex(&mut self, v: usize) {
        let inp = BoundaryInputs { body: &self.body, topology: &self.baked, bone_of: &self.bone_of };
        self.local_boundary.update_vertex(v, &inp, self.constraint_blocks.as_mut());
    }

    /// `buildOrganisms`: связные компоненты полного графа связей; [relabel] — разметка заново.
    pub fn build_organisms(&mut self, relabel: bool) {
        let n = self.n;
        if !self.organism_of_init || self.organism_of.len() != n {
            self.organism_of = vec![0; n];
            self.org_queue = vec![0; n];
            self.organism_of_init = true;
        }
        let mut comp = self.organism_count;
        if relabel {
            if self.org_prev.len() != n {
                self.org_prev = vec![0; n];
                self.org_seed = vec![0; n];
            }
            self.org_prev[..n].copy_from_slice(&self.organism_of[..n]);
            self.organism_of.fill(-1);
            let vs = &self.baked.vert_start;
            let vo = &self.baked.vert_other;
            let ve = &self.baked.vert_edge;
            let dead = &self.baked.edge_dead;
            comp = 0;
            for s in 0..n {
                if self.organism_of[s] != -1 {
                    continue;
                }
                self.org_seed[comp] = s as i32;
                let mut head = 0usize;
                let mut tail = 0usize;
                self.organism_of[s] = comp as i32;
                self.org_queue[tail] = s as i32;
                tail += 1;
                while head < tail {
                    let v = self.org_queue[head] as usize;
                    head += 1;
                    for k in vs[v]..vs[v + 1] {
                        let k = k as usize;
                        if dead[ve[k] as usize] {
                            continue;
                        }
                        let u = vo[k] as usize;
                        if self.organism_of[u] == -1 {
                            self.organism_of[u] = comp as i32;
                            self.org_queue[tail] = u as i32;
                            tail += 1;
                        }
                    }
                }
                comp += 1;
            }
            self.organism_count = comp;
            self.reset_sleep();
        }
        let fresh = !self.organism_size_init || self.organism_size.len() < n;
        if fresh {
            self.organism_size = vec![0; n];
            self.organism_mass = vec![0.0; n];
            self.flow_vx = vec![0.0; n];
            self.flow_vy = vec![0.0; n];
            self.com_acc_x = vec![0.0; n];
            self.com_acc_y = vec![0.0; n];
            self.organism_size_init = true;
        }
        if relabel && !fresh {
            if self.org_flow_x.len() < n {
                self.org_flow_x = vec![0.0; n];
                self.org_flow_y = vec![0.0; n];
            }
            self.org_flow_x[..n].copy_from_slice(&self.flow_vx[..n]);
            self.org_flow_y[..n].copy_from_slice(&self.flow_vy[..n]);
            for o in 0..comp {
                let was = self.org_prev[self.org_seed[o] as usize];
                if was < 0 {
                    self.flow_vx[o] = 0.0;
                    self.flow_vy[o] = 0.0;
                } else {
                    self.flow_vx[o] = self.org_flow_x[was as usize];
                    self.flow_vy[o] = self.org_flow_y[was as usize];
                }
            }
        }
        self.com_acc_x[..comp].fill(0.0);
        self.com_acc_y[..comp].fill(0.0);
        if !relabel && self.organisms_counted {
            return;
        }
        self.organisms_counted = true;
        self.organism_size[..comp].fill(0);
        self.organism_mass[..comp].fill(0.0);
        for i in 0..n {
            self.organism_size[self.organism_of[i] as usize] += 1;
        }
        for i in 0..n {
            if self.rest_inv_mass[i] <= 0.0 {
                continue;
            }
            self.organism_mass[self.organism_of[i] as usize] += 1.0f32 / self.rest_inv_mass[i];
        }
    }

    /// `buildContacts`: признаки свободных и зондов, рабочие связи по рёбрам, касания, поверхность, контур, компоненты.
    pub fn build_contacts(&mut self) {
        let n = self.n;
        if !self.radius_init {
            self.radius = self.body.radius[..n].to_vec();
            self.radius_init = true;
        }
        if !self.is_free_init {
            self.is_free = vec![false; n];
            self.is_free_init = true;
        }
        self.is_free.fill(true);
        for k in 0..self.lnk_count {
            self.is_free[self.lnk_a[k] as usize] = false;
            self.is_free[self.lnk_b[k] as usize] = false;
        }
        self.build_probes();
        let cap = self.con_a.len();
        if self.link_torn.len() != cap || cap == 0 {
            self.link_torn = vec![false; cap];
            self.con_dead = vec![false; cap];
            self.con_boundary = vec![false; cap];
            self.con_tendon = vec![false; cap];
        } else {
            self.link_torn.fill(false);
            self.con_dead.fill(false);
        }
        if self.con_of_edge.len() != self.baked.edge_count {
            self.con_of_edge = vec![-1; self.baked.edge_count];
        }
        self.con_of_edge.fill(-1);
        if self.con_next_for_edge.len() < self.con_a.len() {
            self.con_next_for_edge = vec![0; self.con_a.len()];
        }
        for c in 0..self.con_count {
            let e = self.con_edge[c] as usize;
            self.con_next_for_edge[c] = self.con_of_edge[e];
            self.con_of_edge[e] = c as i32;
            self.con_boundary[c] = self.baked.tri_use[e] <= 1;
            let a = self.con_a[c] as usize;
            let b = self.con_b[c] as usize;
            self.con_tendon[c] = (self.muscle_of[a] >= 0) != (self.muscle_of[b] >= 0);
        }
        self.rebuild_boundary_link_limits();
        if self.ever_linked.is_none() {
            self.ever_linked = Some(self.permanent_linked_pairs(0, self.con_count));
        }
        if self.rest_pairs.is_none() {
            let rp = self.bake_body_rest_pairs(self.ever_linked.as_ref().unwrap());
            self.rest_pairs = Some(Arc::new(rp));
        }
        self.build_boundary();
        let rest = self.rest_pairs.clone().unwrap();
        {
            let topo = ContactTopology {
                con_a: &self.con_a,
                con_b: &self.con_b,
                con_count: self.con_count,
                bound_a: &self.bound_a,
                bound_b: &self.bound_b,
                bound_count: self.bound_count,
                bound_opp: &self.bound_opp,
                rest_x: &self.body.x,
                rest_y: &self.body.y,
                isolated: Some(if self.probe_on { &self.probe } else { &self.is_free }),
                dead: Some(&self.cell_dead),
                radius: &self.radius,
            };
            match self.contacts.as_mut() {
                None => {
                    let ct = BoundaryContacts::build(
                        n,
                        &topo,
                        CONTACT_SCALE,
                        CCD_CORE,
                        CONTACT_RESTITUTION,
                        self.environment.contact_friction,
                        self.link_scale,
                        self.cfg.contact_max_step,
                        &rest,
                        &self.cfg.contacts,
                    );
                    self.contacts = Some(Box::new(ct));
                }
                Some(ct) => ct.reconfigure(&topo, &rest),
            }
        }
        self.local_surface =
            LocalSurface::new(n, &self.baked, CONTACT_SCALE, self.cfg.contacts.contact_seal, PROBE_ANGLE_EPS, self.probe_on);
        self.local_boundary = LocalBoundaryConstraints::new(n, &self.baked);
        for v in 0..n {
            self.surface_update(v);
            self.boundary_update_vertex(v);
        }
        {
            let ct = self.contacts.as_mut().unwrap();
            ct.attach_surface(&self.local_surface, &self.baked.vert_start);
        }
        self.local_components = LocalComponents::new(n, self.organism_count as i32);
        self.baked.clear_dirty();
        {
            let ct = self.contacts.as_mut().unwrap();
            ct.kill_on_deep = self.deep_kill_on;
            ct.kill_depth = KILL_DEPTH;
        }
        if self.contact_pressure.len() != n {
            self.contact_pressure = vec![0.0; n];
        }
        if self.contact_pressure_n.len() != n {
            self.contact_pressure_n = vec![0; n];
        }
        if self.crushed_cell.len() != n {
            self.crushed_cell = vec![false; n];
        }
        self.apply_no_contact();
    }

    /// `buildProbes`: клетки, которые ткань не закрывает (сумма углов меньше 2π), — зонды.
    pub fn build_probes(&mut self) {
        let n = self.n;
        if self.probe.len() != n {
            self.probe = vec![false; n];
        }
        if self.probe_angle.len() != n {
            self.probe_angle = vec![0.0; n];
        }
        self.probe_angle.fill(0.0);
        for t in 0..self.tri_count {
            let a = self.tri_a[t];
            let b = self.tri_b[t];
            let c = self.tri_c[t];
            let aa = self.angle_at(a, b, c);
            self.probe_angle[a as usize] += aa;
            let ab = self.angle_at(b, a, c);
            self.probe_angle[b as usize] += ab;
            let ac = self.angle_at(c, a, b);
            self.probe_angle[c as usize] += ac;
        }
        // Kotlin: `2.0f * Math.PI - PROBE_ANGLE_EPS` — double, сравнение во double.
        let full = 2.0f64 * std::f64::consts::PI - PROBE_ANGLE_EPS as f64;
        for i in 0..n {
            self.probe[i] = self.is_free[i] || (self.probe_angle[i] as f64) < full;
        }
    }

    /// `angleAt`: угол при вершине v в треугольнике (v, p, q) в позе покоя.
    pub fn angle_at(&self, v: i32, p: i32, q: i32) -> f32 {
        let (v, p, q) = (v as usize, p as usize, q as usize);
        let body = &self.body;
        let ax = body.x[p] - body.x[v];
        let ay = body.y[p] - body.y[v];
        let bx = body.x[q] - body.x[v];
        let by = body.y[q] - body.y[v];
        let na = sqrtf(ax * ax + ay * ay);
        let nb = sqrtf(bx * bx + by * by);
        if na < 1e-12f32 || nb < 1e-12f32 {
            return 0.0;
        }
        let mut c = (ax * bx + ay * by) / (na * nb);
        if c > 1.0 {
            c = 1.0;
        } else if c < -1.0 {
            c = -1.0;
        }
        strict_acos(c as f64) as f32
    }

    /// `buildMasses`: масса из радиуса клетки, нормировка — постоянная мира (WorldScale).
    pub fn build_masses(&mut self) {
        let n = self.n;
        self.rest_inv_mass = vec![0.0; n];
        let mean_area = self.world_scale.expect("масштаб мира").mean_cell_area;
        for i in 0..n {
            let r = self.body.radius[i];
            let m = if mean_area > 0.0 { (r * r) / mean_area } else { 1.0 };
            self.rest_inv_mass[i] = if m > 1e-12f32 { 1.0f32 / m } else { 1.0 };
        }
    }

    /// `buildBoneRestPose`: смещения от центроида костей, досадка суммы в ноль.
    pub fn build_bone_rest_pose(&mut self) {
        let bones = self.rigid_bones.len();
        self.bone_rest_qx = (0..bones).map(|b| vec![0.0f32; self.rigid_bones[b].len()]).collect();
        self.bone_rest_qy = (0..bones).map(|b| vec![0.0f32; self.rigid_bones[b].len()]).collect();
        let mut max_size = 1usize;
        for b in 0..bones {
            let ids = &self.rigid_bones[b];
            if ids.len() > max_size {
                max_size = ids.len();
            }
            rest_pose_from_definition(&self.body, ids, &mut self.bone_rest_qx[b], &mut self.bone_rest_qy[b]);
        }
        self.bone_dx = vec![0.0; max_size];
        self.bone_dy = vec![0.0; max_size];
    }

    /// `buildBoundary`: рёбра с не больше чем одним живым треугольником — для сборки касаний.
    pub fn build_boundary(&mut self) {
        let lc = self.lnk_count;
        if self.bound_a.len() < lc {
            self.bound_a = vec![0; lc];
            self.bound_b = vec![0; lc];
            self.bound_opp = vec![0; lc];
        }
        let mut m = 0usize;
        for k in 0..lc {
            let e = self.lnk_edge[k] as usize;
            let usage = self.baked.tri_use[e];
            if usage > 1 {
                continue;
            }
            self.bound_a[m] = self.lnk_a[k];
            self.bound_b[m] = self.lnk_b[k];
            self.bound_opp[m] = -1;
            if usage == 1 {
                let t = self.baked.live_tri(e);
                if t >= 0 {
                    let t = t as usize;
                    let a = self.lnk_a[k];
                    let b = self.lnk_b[k];
                    let t0 = self.body.tri_a[t];
                    let t1 = self.body.tri_b[t];
                    let t2 = self.body.tri_c[t];
                    self.bound_opp[m] = if t0 != a && t0 != b {
                        t0
                    } else if t1 != a && t1 != b {
                        t1
                    } else {
                        t2
                    };
                }
            }
            m += 1;
        }
        self.bound_count = m;
    }

    /// `fillConstraints`: рабочие связи решателя из полного списка, без внутрикостных.
    pub fn fill_constraints(&mut self) -> usize {
        let mut m = 0usize;
        for k in 0..self.lnk_count {
            let i = self.lnk_a[k];
            let j = self.lnk_b[k];
            let (iu, ju) = (i as usize, j as usize);
            if self.bone_of[iu] != -1 && self.bone_of[iu] == self.bone_of[ju] {
                continue;
            }
            let dx = self.body.x[iu] - self.body.x[ju];
            let dy = self.body.y[iu] - self.body.y[ju];
            self.con_a[m] = i;
            self.con_b[m] = j;
            self.con_rest[m] = sqrtf(dx * dx + dy * dy);
            self.con_edge[m] = self.lnk_edge[k];
            self.con_muscle[m] = self.muscle_of_pair(i, j);
            m += 1;
        }
        m
    }

    /// `compactionDue`: уплотнять, когда мёртвых накопилось 1/64.
    pub fn compaction_due(&self) -> bool {
        self.tear_queue_n * 64 >= self.con_count
            || (self.tri_count as i64 - self.baked.live_triangle_count as i64) * 64 >= self.tri_count as i64
    }

    pub fn rebuild_timed(&mut self) {
        self.rebuilding = true;
        self.rebuild_after_tear();
        self.rebuilding = false;
    }

    #[inline]
    pub fn kill_edge_now(&mut self, e: usize) {
        self.kill_edge(e, true, false);
    }

    /// `killEdge`: погасить ребро; [split] — делить организм, [quiet] — снята временная связь роста.
    pub fn kill_edge(&mut self, e: usize, split: bool, quiet: bool) {
        if !self.baked.kill(e, &self.body) {
            return;
        }
        self.structure_version = self.structure_version.wrapping_add(1);
        if split {
            if !quiet {
                self.note_bone_edge_death(e);
                if 2 * self.edge_death_n + 2 > self.edge_death_log.len() {
                    let cap = self.edge_death_log.len() * 2;
                    self.edge_death_log.resize(cap, 0);
                }
                self.edge_death_log[2 * self.edge_death_n] = self.baked.edge_u(e);
                self.edge_death_log[2 * self.edge_death_n + 1] = self.baked.edge_v(e);
                self.edge_death_n += 1;
            }
            self.sleep.request_wake(self.baked.edge_u(e));
            let mut arrays = OrgArrays {
                of: &mut self.organism_of,
                size: &mut self.organism_size,
                mass: &mut self.organism_mass,
                rest_inv_mass: &self.rest_inv_mass,
                dead: &self.cell_dead,
                flow_x: &mut self.flow_vx,
                flow_y: &mut self.flow_vy,
            };
            self.local_components.split(e, &self.baked, &mut arrays, &mut self.sleep);
            self.organism_count = self.local_components.count as usize;
        }
        let mut c = self.con_of_edge[e];
        while c >= 0 {
            self.con_dead[c as usize] = true;
            c = self.con_next_for_edge[c as usize];
        }
        self.update_boundary_link_limit(e);
        for s in self.baked.edge_tri_start[e]..self.baked.edge_tri_start[e + 1] {
            let t = self.baked.edge_tris[s as usize] as usize;
            self.baked.forget_tri_unborn(t);
            if !self.baked.tri_alive[t] {
                if let Some(cb) = self.constraint_blocks.as_mut() {
                    cb.remove_triangle(t);
                }
            }
            for k in 0..3 {
                let adjacent = self.baked.tri_edge[3 * t + k];
                if adjacent >= 0 {
                    self.update_boundary_link_limit(adjacent as usize);
                }
            }
        }
        if self.tear_queue_n == self.tear_queue.len() {
            let cap = self.tear_queue.len() * 2;
            self.tear_queue.resize(cap, 0);
        }
        self.tear_queue[self.tear_queue_n] = e as i32;
        self.tear_queue_n += 1;
        if quiet {
            self.tear_queue_quiet += 1;
        }
        self.tears_pending = true;
        let (u, v) = (self.baked.edge_u(e) as usize, self.baked.edge_v(e) as usize);
        self.refresh_cell_scale_ring(u);
        self.refresh_cell_scale_ring(v);
    }

    /// `restoreTopology`: топология — снова целая, вместе с позами (сброс).
    pub fn restore_topology(&mut self) {
        self.restore_split_bones();
        self.cell_dead.fill(false);
        self.cell_unborn.fill(false);
        self.con_grow_target.fill(0.0);
        self.con_grow_age.fill(0);
        self.tri_grow_target.fill(0.0);
        self.grow_edge_n = 0;
        self.grow_tri_n = 0;
        self.dead_count = 0;
        self.crushed_cell.fill(false);
        self.lnk_count = self.body.link_count();
        self.lnk_a = self.body.link_a.clone();
        self.lnk_b = self.body.link_b.clone();
        self.tri_count = self.body.tri_count();
        self.baked.restore(&self.body);
        self.lnk_edge = self.baked.link_edge.clone();
        self.tri_id = (0..self.tri_count as i32).collect();
        self.tri_a = self.body.tri_a.clone();
        self.tri_b = self.body.tri_b.clone();
        self.tri_c = self.body.tri_c.clone();
        self.tri_rest_area2 = self.body.tri_rest_area2.clone();
        self.tri_muscle = (0..self.tri_count).map(|t| self.muscle_of_tri(self.tri_a[t], self.tri_b[t], self.tri_c[t])).collect();
        self.tri_inverted = vec![false; self.tri_count];
        if self.con_a.len() < self.lnk_count {
            let l = self.lnk_count;
            self.con_a = vec![0; l];
            self.con_b = vec![0; l];
            self.con_rest = vec![0.0; l];
            self.con_muscle = vec![0; l];
            self.con_edge = vec![0; l];
        }
        self.con_count = self.fill_constraints();
        self.tears_pending = false;
        self.tear_queue_n = 0;
        self.tear_queue_quiet = 0;
        self.torn_total = 0;
        self.con_hold.fill(0);
        self.tri_hold.fill(0);
        if self.con_cell_scale.len() < self.con_a.len() {
            self.con_cell_scale = vec![1.0; self.con_a.len()];
        }
        if self.tri_cell_scale.len() < self.tri_a.len() {
            self.tri_cell_scale = vec![1.0; self.tri_a.len()];
        }
        if self.con_softness.len() < self.con_a.len() {
            self.con_softness = vec![1.0; self.con_a.len()];
        }
        if self.tri_softness.len() < self.tri_a.len() {
            self.tri_softness = vec![1.0; self.tri_a.len()];
        }
        self.recompute_cell_scales();
        self.build_organisms(true);
        self.build_contacts();
        self.initialize_constraint_blocks();
        self.rebuild_temp_retire();
        if self.retire_all_temp_edges() {
            self.rebuild_after_tear();
            self.rebuild_temp_retire();
        }
    }

    /// `rebuildAfterTear`: упаковка рабочих списков после разрывов тика, в прежнем порядке.
    pub fn rebuild_after_tear(&mut self) {
        self.tears_pending = false;
        self.structure_version = self.structure_version.wrapping_add(1);
        self.refresh_surface();
        let nd = self.tear_queue_n;
        self.tear_queue_n = 0;
        if nd == 0 {
            if self.tri_count as i32 != self.baked.live_triangle_count {
                self.compact_triangles();
            }
            return;
        }
        self.torn_total += nd as i32 - self.tear_queue_quiet;
        self.tear_queue_quiet = 0;
        {
            let edge_dead = &self.baked.edge_dead;
            let unborn = &self.baked.edge_unborn;
            let kept = |e: i32| {
                let e = e as usize;
                !edge_dead[e] || (e < unborn.len() && unborn[e])
            };
            let mut m = 0usize;
            while m < self.lnk_count && kept(self.lnk_edge[m]) {
                m += 1;
            }
            let mut k = m;
            while k < self.lnk_count {
                if kept(self.lnk_edge[k]) {
                    self.lnk_a[m] = self.lnk_a[k];
                    self.lnk_b[m] = self.lnk_b[k];
                    self.lnk_edge[m] = self.lnk_edge[k];
                    m += 1;
                }
                k += 1;
            }
            self.lnk_count = m;
        }

        self.compact_triangles();

        {
            let kept = |st: &BodyState, e: i32| {
                let e = e as usize;
                !st.baked.edge_dead[e] || (e < st.baked.edge_unborn.len() && st.baked.edge_unborn[e])
            };
            let mut m = 0usize;
            while m < self.con_count && kept(self, self.con_edge[m]) {
                m += 1;
            }
            let mut c = m;
            while c < self.con_count {
                if kept(self, self.con_edge[c]) {
                    if let Some(cb) = self.constraint_blocks.as_mut() {
                        cb.move_constraint(c as i32, m as i32);
                    }
                    self.con_a[m] = self.con_a[c];
                    self.con_b[m] = self.con_b[c];
                    self.con_rest[m] = self.con_rest[c];
                    self.con_dead[m] = self.con_dead[c];
                    if self.con_hold.len() > c {
                        self.con_hold[m] = self.con_hold[c];
                    }
                    self.con_muscle[m] = self.con_muscle[c];
                    self.con_edge[m] = self.con_edge[c];
                    self.con_cell_scale[m] = self.con_cell_scale[c];
                    self.con_softness[m] = self.con_softness[c];
                    if self.con_fiber_c2.len() > c {
                        self.con_fiber_c2[m] = self.con_fiber_c2[c];
                    }
                    if self.con_grow_target.len() > c {
                        self.con_grow_target[m] = self.con_grow_target[c];
                    }
                    if self.con_grow_age.len() > c {
                        self.con_grow_age[m] = self.con_grow_age[c];
                    }
                    m += 1;
                }
                c += 1;
            }
            if self.con_grow_target.len() > m {
                let end = self.con_count.min(self.con_grow_target.len());
                if end > m {
                    self.con_grow_target[m..end].fill(0.0);
                }
            }
            self.con_count = m;
            if let Some(cb) = self.constraint_blocks.as_mut() {
                cb.links.pack_dirty();
            }
        }

        self.con_of_edge.fill(-1);
        for c in 0..self.con_count {
            let e = self.con_edge[c] as usize;
            self.con_next_for_edge[c] = self.con_of_edge[e];
            self.con_of_edge[e] = c as i32;
            self.link_torn[c] = false;
            self.con_boundary[c] = self.baked.tri_use[e] <= 1;
            self.con_tendon[c] = (self.muscle_of[self.con_a[c] as usize] >= 0) != (self.muscle_of[self.con_b[c] as usize] >= 0);
        }
        self.rebuild_boundary_link_limits();
    }

    /// `refreshSurface`: местная поверхность, контур и пределы — у задетых вершин; касания — следом.
    pub fn refresh_surface(&mut self) {
        let count = self.baked.dirty_count;
        if count == 0 {
            return;
        }
        self.structure_version = self.structure_version.wrapping_add(1);
        for k in 0..count {
            let v = self.baked.dirty_vertices[k] as usize;
            self.surface_update(v);
            self.boundary_update_vertex(v);
            for s in self.baked.vert_start[v]..self.baked.vert_start[v + 1] {
                let e = self.baked.vert_edge[s as usize] as usize;
                self.update_boundary_link_limit(e);
            }
        }
        if let Some(ct) = self.contacts.as_mut() {
            let ctx: ContactCtx = contact_ctx!(self);
            ct.update_surface(&self.local_surface, &self.baked.dirty_vertices, count, &ctx);
        }
        self.baked.clear_dirty();
    }

    /// `triTorn`: порвалась ли хоть одна сторона треугольника.
    pub fn tri_torn(&self, t: usize) -> bool {
        let o = 3 * self.tri_id[t] as usize;
        for k in 0..3 {
            let e = self.baked.tri_edge[o + k];
            if e < 0 {
                continue;
            }
            let c = self.con_of_edge[e as usize];
            if c >= 0 && self.link_torn[c as usize] {
                return true;
            }
        }
        false
    }

    /// `conIndexOf`: рабочая связь пары клеток или -1.
    pub fn con_index_of(&self, i: usize, j: i32) -> i32 {
        let e = self.baked.edge_of(i, j);
        if e < 0 {
            -1
        } else {
            self.con_of_edge[e as usize]
        }
    }

    /// `applyNoContact`: клетки без касаний — вон из набора клеток касания после полной пересборки.
    pub fn apply_no_contact(&mut self) {
        let nc_len = self.n.min(self.cell_no_contact.len());
        let mut list = Vec::new();
        for i in 0..nc_len {
            if self.cell_no_contact[i] {
                list.push(i as i32);
            }
        }
        if list.is_empty() {
            return;
        }
        if let Some(ct) = self.contacts.as_mut() {
            let ctx: ContactCtx = contact_ctx!(self);
            ct.update_surface(&self.local_surface, &list, list.len(), &ctx);
        }
    }

    // --- BoundaryLinkLimits.kt ---

    pub fn rebuild_boundary_link_limits(&mut self) {
        self.boundary_link_limits.reset(self.con_a.len());
        for c in 0..self.con_count {
            if !self.con_dead[c] && self.con_boundary[c] && !self.sleep.is_asleep(self.organism_of[self.con_a[c] as usize]) {
                self.boundary_link_limits.add(c as i32);
            }
        }
    }

    pub fn update_boundary_link_limit(&mut self, e: usize) {
        let boundary = !self.baked.edge_dead[e] && self.baked.tri_use[e] <= 1;
        let unborn = self.baked.is_unborn(e);
        let mut c = self.con_of_edge[e];
        while c >= 0 {
            let cu = c as usize;
            self.con_boundary[cu] = boundary;
            if boundary && !self.con_dead[cu] && !self.sleep.is_asleep(self.organism_of[self.con_a[cu] as usize]) {
                self.boundary_link_limits.add(c);
            } else {
                self.boundary_link_limits.remove(c);
            }
            if let Some(blocks) = self.constraint_blocks.as_mut() {
                if self.con_dead[cu] && !unborn {
                    blocks.links.remove(c);
                }
                if boundary && !self.con_dead[cu] {
                    blocks.add_limit(c, self.con_a[cu], self.con_b[cu]);
                } else {
                    blocks.limits.remove(c);
                }
            }
            c = self.con_next_for_edge[cu];
        }
    }

    // --- TriangleDamage.kt ---

    /// `removePersistentInversions`: треугольник, вывернутый 11 концов тика подряд, умирает.
    pub fn remove_persistent_inversions(&mut self) {
        if !self.tearing_on {
            self.baked.inverted_ticks.fill(0);
            return;
        }
        // С воркерами — наборы по воркерам, гибель по возрастанию номеров (solver/parallel.rs), как в Kotlin.
        if self.constraint_blocks.is_some() {
            if let Some(w) = self.active_workers() {
                self.scan_inversions_parallel(&w);
                return;
            }
        }
        if self.sleep.asleep_count == 0 || self.constraint_blocks.is_none() {
            self.check_inversions_range(0, self.tri_count);
            return;
        }
        let buckets = self.constraint_blocks.as_ref().unwrap().buckets;
        for block in 0..buckets {
            let (active, from, to) = {
                let cb = self.constraint_blocks.as_ref().unwrap();
                let areas = &cb.areas;
                (cb.active(block), areas.start[block] as usize, (areas.start[block] + areas.count[block]) as usize)
            };
            if !active {
                continue;
            }
            for slot in from..to {
                let t = self.constraint_blocks.as_ref().unwrap().areas.items[slot];
                if t >= 0 && self.inversion_expired_at(t as usize) {
                    let id = self.tri_id[t as usize] as usize;
                    self.kill_triangle_now(id);
                }
            }
        }
    }

    /// `inversionExpiredAt`: счётчик инверсии треугольника [t]; true — пора убить.
    fn inversion_expired_at(&mut self, t: usize) -> bool {
        let id = self.tri_id[t] as usize;
        if !self.baked.tri_alive[id] {
            return false;
        }
        let a = self.tri_a[t] as usize;
        let b = self.tri_b[t] as usize;
        let c = self.tri_c[t] as usize;
        let (px, py) = (&self.px, &self.py);
        let area2 = (px[b] - px[a]) * (py[c] - py[a]) - (py[b] - py[a]) * (px[c] - px[a]);
        if area2 < 0.0 {
            let age = self.baked.inverted_ticks[id] as i32 + 1;
            if age > INVERTED_TRIANGLE_HOLD_TICKS {
                return true;
            }
            self.baked.inverted_ticks[id] = age as i8;
        } else {
            self.baked.inverted_ticks[id] = 0;
        }
        false
    }

    /// Кусок `invJob`: треугольники набора [bucket], если в нём есть бодрствующие частицы; кому пора умереть — в
    /// [kills] (номера из исходной топологии), сама гибель — у вызывающего.
    pub(crate) fn inversions_of_bucket(&mut self, bucket: usize, kills: &mut Vec<i32>) {
        let (from, to) = {
            let cb = self.constraint_blocks.as_ref().unwrap();
            if !cb.active(bucket) {
                return;
            }
            (cb.areas.start[bucket] as usize, (cb.areas.start[bucket] + cb.areas.count[bucket]) as usize)
        };
        for slot in from..to {
            let t = self.constraint_blocks.as_ref().unwrap().areas.items[slot];
            if t >= 0 && self.inversion_expired_at(t as usize) {
                kills.push(self.tri_id[t as usize]);
            }
        }
    }

    fn check_inversions_range(&mut self, t0: usize, t1: usize) {
        for t in t0..t1 {
            if self.inversion_expired_at(t) {
                let id = self.tri_id[t] as usize;
                self.kill_triangle_now(id);
            }
        }
    }

    /// `killTriangleNow`: номер из исходной топологии; контур меняется по трём рёбрам.
    pub fn kill_triangle_now(&mut self, id: usize) {
        if !self.baked.kill_triangle(id, &self.body) {
            return;
        }
        if let Some(cb) = self.constraint_blocks.as_mut() {
            cb.remove_triangle(id);
        }
        for k in 0..3 {
            let e = self.baked.tri_edge[3 * id + k];
            if e >= 0 {
                self.update_boundary_link_limit(e as usize);
            }
        }
        self.tears_pending = true;
    }

    /// `compactTriangles`: упаковка живых треугольников раз за тик, в прежнем порядке.
    pub fn compact_triangles(&mut self) {
        let keep = |st: &BodyState, id: i32| {
            let id = id as usize;
            st.baked.tri_alive[id] || (id < st.baked.tri_unborn.len() && st.baked.tri_unborn[id])
        };
        let mut m = 0usize;
        while m < self.tri_count && keep(self, self.tri_id[m]) {
            m += 1;
        }
        let mut k = m;
        while k < self.tri_count {
            if keep(self, self.tri_id[k]) {
                if let Some(cb) = self.constraint_blocks.as_mut() {
                    cb.move_triangle(k as i32, m as i32, self.tri_id[k] as usize);
                }
                self.tri_a[m] = self.tri_a[k];
                self.tri_b[m] = self.tri_b[k];
                self.tri_c[m] = self.tri_c[k];
                if self.tri_hold.len() > k {
                    self.tri_hold[m] = self.tri_hold[k];
                }
                self.tri_rest_area2[m] = self.tri_rest_area2[k];
                self.tri_muscle[m] = self.tri_muscle[k];
                self.tri_id[m] = self.tri_id[k];
                self.tri_cell_scale[m] = self.tri_cell_scale[k];
                self.tri_softness[m] = self.tri_softness[k];
                m += 1;
            }
            k += 1;
        }
        self.tri_count = m;
        if let Some(cb) = self.constraint_blocks.as_mut() {
            cb.areas.pack_dirty();
        }
        self.tri_inverted.fill(false);
    }
}

/// Поза покоя кости из описания (`buildBoneRestPose`): смещения от центроида и три прохода досадки суммы в ноль.
/// Всё во float, как в Kotlin.
pub fn rest_pose_from_definition(body: &crate::definition::BodyDefinition, ids: &[i32], qx: &mut [f32], qy: &mut [f32]) {
    let mut cx = 0.0f32;
    let mut cy = 0.0f32;
    for &i in ids {
        cx += body.x[i as usize];
        cy += body.y[i as usize];
    }
    cx /= ids.len() as f32;
    cy /= ids.len() as f32;
    for k in 0..ids.len() {
        qx[k] = body.x[ids[k] as usize] - cx;
        qy[k] = body.y[ids[k] as usize] - cy;
    }
    for _pass in 0..3 {
        let mut sx = 0.0f32;
        let mut sy = 0.0f32;
        for k in 0..ids.len() {
            sx += qx[k];
            sy += qy[k];
        }
        if sx == 0.0 && sy == 0.0 {
            break;
        }
        let dx = sx / ids.len() as f32;
        let dy = sy / ids.len() as f32;
        for k in 0..ids.len() {
            qx[k] -= dx;
            qy[k] -= dy;
        }
    }
}
