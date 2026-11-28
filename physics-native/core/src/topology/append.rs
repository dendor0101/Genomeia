//! Дописывание тела в живой мир и удаление тела — функции BodyState из `topology/BodyAppend.kt`, дословно (тот же
//! порядок пересборок, те же «копии с запасом» массивов: длина Vec здесь — длина массива Kotlin).
//!
//! Чего в Rust нет: `InternalMomentum` и `DistanceSweepOrder` (в тике игры не участвуют), `dbgSealMinR` (отладка).
//! `syncBoundaryArrays` — пустое место: в Rust читатели берут контур прямо из `local_boundary`. Ссылки касаний на
//! массивы хозяина (`noContact`, `group`, `organismOf`, `pressure`) в Rust — параметры (`ContactCtx`); устаревшая
//! ссылка `lastInvMass` (длина массива до дописывания) повторена длиной `inv_mass_len_seen`.

use crate::contact_ctx;
use crate::contacts::{bake_rest_pairs, max_contact_radius};
use crate::damage::fracture::FractureOrientation;
use crate::definition::BodyDefinition;
use crate::jmath::sqrtf;
use crate::params::{CONTACT_SCALE, PROBE_ANGLE_EPS};
use crate::state::BodyState;
use crate::topology::components::LocalComponents;
use crate::topology::linked::LinkedPairs;

impl BodyState {
    /// `appendBody`: тело [def] — после прежних, без пересчёта прежних тел. [born] — какие клетки рождены (рост),
    /// [bone_on] — какие кости включены (мутация в кость).
    pub fn append_body(&mut self, def: &BodyDefinition, born: Option<&[bool]>, bone_on: Option<&[bool]>) {
        assert!(self.n > 0, "первое тело загружается load, дописывается — следующее");
        let n0 = self.n;
        let l0 = self.body.link_count();
        let t0 = self.body.tri_count();
        let bones0 = self.body.bone_clusters.len();
        let muscles0 = self.body.muscle_clusters.len();
        let lnk0 = self.lnk_count;
        let tri0 = self.tri_count;
        let con0 = self.con_count;
        let combined = self.body.concat(def);
        self.body = combined;
        self.n = self.body.count();
        let n = self.n;
        let lc = self.body.link_count();
        let tc = self.body.tri_count();

        // --- частицы ---
        self.grow_particle_arrays(n0);
        let mean_area = self.world_scale.expect("масштаб мира").mean_cell_area;
        for i in n0..n {
            self.px[i] = self.body.x[i];
            self.py[i] = self.body.y[i];
            let r = self.body.radius[i];
            let m = if mean_area > 0.0 { (r * r) / mean_area } else { 1.0 };
            self.rest_inv_mass[i] = if m > 1e-12f32 { 1.0f32 / m } else { 1.0 };
            self.inv_mass[i] = self.rest_inv_mass[i];
            self.match_weight[i] = 1.0;
            self.radius[i] = r;
            self.bone_of[i] = -1;
            self.bone_cluster_of[i] = -1;
            self.muscle_of[i] = -1;
        }
        if let Some(born) = born {
            assert!(born.len() == n - n0, "born — по клетке нового тела");
            for (k, &b) in born.iter().enumerate() {
                if !b {
                    let v = n0 + k;
                    self.cell_dead[v] = true;
                    self.cell_unborn[v] = true;
                    self.inv_mass[v] = 0.0;
                    self.match_weight[v] = 0.0;
                    self.prev_x[v] = self.px[v];
                    self.prev_y[v] = self.py[v];
                }
            }
        }

        // --- кости ---
        let mut new_bones: Vec<Vec<i32>> = Vec::new();
        for k in bones0..self.body.bone_clusters.len() {
            let ids = &self.body.bone_clusters[k];
            if ids.len() >= 3 {
                new_bones.push(ids.clone());
            } else {
                self.degenerate_bones += 1;
            }
        }
        let b0 = self.rigid_bones.len();
        for ids in new_bones {
            self.bone_rest_qx.push(vec![0.0; ids.len()]);
            self.bone_rest_qy.push(vec![0.0; ids.len()]);
            self.rigid_bones.push(ids);
        }
        let mut max_bone = self.bone_dx.len();
        for b in b0..self.rigid_bones.len() {
            let len = self.rigid_bones[b].len();
            for k in 0..len {
                let i = self.rigid_bones[b][k] as usize;
                self.bone_of[i] = b as i32;
                self.bone_cluster_of[i] = b as i32;
            }
            if len > max_bone {
                max_bone = len;
            }
        }
        if let Some(bone_on) = bone_on {
            assert!(bone_on.len() == n - n0, "boneOn — по клетке нового тела");
            for (k, &on) in bone_on.iter().enumerate() {
                if !on && self.bone_of[n0 + k] >= 0 {
                    self.bone_of[n0 + k] = -1;
                    self.match_weight[n0 + k] = 0.0;
                }
            }
        }
        for b in b0..self.rigid_bones.len() {
            self.rest_pose_of_born_bone(b);
        }
        if max_bone > self.bone_dx.len() {
            self.bone_dx = vec![0.0; max_bone];
            self.bone_dy = vec![0.0; max_bone];
        }

        // --- мышцы ---
        let m0 = muscles0;
        for m in m0..self.body.muscle_clusters.len() {
            for k in 0..self.body.muscle_clusters[m].len() {
                let i = self.body.muscle_clusters[m][k] as usize;
                self.muscle_of[i] = m as i32;
            }
        }
        self.grow_muscles(t0);
        let mc = self.body.muscle_clusters.len();
        self.muscle_activation.resize(mc, 0.0);
        self.muscle_scale_of.resize(mc, 0.0);
        self.muscle_scale_of[m0..mc].fill(1.0);
        self.muscle_target.resize(mc, 0.0);
        if self.muscle_activation_prev.len() == m0 {
            self.muscle_activation_prev.resize(mc, 0.0);
        }

        // --- топология ---
        self.baked.append(&self.body, n0, l0, t0);
        self.fracture = FractureOrientation::new(self.body.tri_count());
        let add_links = lc - l0;
        let lnk_cap = self.lnk_a.len() + add_links;
        self.lnk_a.resize(lnk_cap, 0);
        self.lnk_b.resize(lnk_cap, 0);
        self.lnk_edge.resize(lnk_cap, 0);
        for k in 0..add_links {
            self.lnk_a[lnk0 + k] = self.body.link_a[l0 + k];
            self.lnk_b[lnk0 + k] = self.body.link_b[l0 + k];
            self.lnk_edge[lnk0 + k] = self.baked.link_edge[l0 + k];
        }
        self.lnk_count = lnk0 + add_links;
        let add_tris = tc - t0;
        let tri_cap = self.tri_a.len() + add_tris;
        self.tri_a.resize(tri_cap, 0);
        self.tri_b.resize(tri_cap, 0);
        self.tri_c.resize(tri_cap, 0);
        self.tri_rest_area2.resize(tri_cap, 0.0);
        self.tri_id.resize(tri_cap, 0);
        self.tri_muscle.resize(tri_cap, 0);
        self.tri_inverted.resize(tri_cap, false);
        self.tri_cell_scale.resize(tri_cap, 0.0);
        self.tri_cell_scale[tri0..tri_cap].fill(1.0);
        self.tri_softness.resize(tri_cap, 0.0);
        self.tri_softness[tri0..tri_cap].fill(1.0);
        if !self.tri_hold.is_empty() {
            let cap = self.tri_hold.len().max(tri_cap);
            self.tri_hold.resize(cap, 0);
        }
        for k in 0..add_tris {
            let t = tri0 + k;
            self.tri_a[t] = self.body.tri_a[t0 + k];
            self.tri_b[t] = self.body.tri_b[t0 + k];
            self.tri_c[t] = self.body.tri_c[t0 + k];
            self.tri_rest_area2[t] = self.body.tri_rest_area2[t0 + k];
            self.tri_id[t] = (t0 + k) as i32;
            self.tri_muscle[t] = self.muscle_of_tri(self.tri_a[t], self.tri_b[t], self.tri_c[t]);
        }
        self.tri_count = tri0 + add_tris;

        // --- рабочие связи ---
        let con_cap = self.con_a.len() + add_links;
        self.con_a.resize(con_cap, 0);
        self.con_b.resize(con_cap, 0);
        self.con_rest.resize(con_cap, 0.0);
        self.con_muscle.resize(con_cap, 0);
        self.con_edge.resize(con_cap, 0);
        self.con_cell_scale.resize(con_cap, 0.0);
        self.con_cell_scale[con0..con_cap].fill(1.0);
        self.con_softness.resize(con_cap, 0.0);
        self.con_softness[con0..con_cap].fill(1.0);
        let mut c = con0;
        for k in lnk0..self.lnk_count {
            let i = self.lnk_a[k];
            let j = self.lnk_b[k];
            let (iu, ju) = (i as usize, j as usize);
            if self.bone_of[iu] != -1 && self.bone_of[iu] == self.bone_of[ju] {
                continue;
            }
            let dx = self.body.x[iu] - self.body.x[ju];
            let dy = self.body.y[iu] - self.body.y[ju];
            self.con_a[c] = i;
            self.con_b[c] = j;
            self.con_rest[c] = sqrtf(dx * dx + dy * dy);
            self.con_edge[c] = self.lnk_edge[k];
            self.con_muscle[c] = self.muscle_of_pair(i, j);
            c += 1;
        }
        self.con_count = c;
        self.link_torn.resize(con_cap, false);
        self.con_dead.resize(con_cap, false);
        self.con_boundary.resize(con_cap, false);
        self.con_tendon.resize(con_cap, false);
        if !self.con_hold.is_empty() {
            let cap = self.con_hold.len().max(con_cap);
            self.con_hold.resize(cap, 0);
        }
        // copyOf(edgeCount) с -1 в новых: прежняя длина не больше edgeCount (рёбра только дописываются).
        self.con_of_edge.resize(self.baked.edge_count, -1);
        self.con_next_for_edge.resize(con_cap, 0);
        self.boundary_link_limits.grow(con_cap);
        for k in con0..self.con_count {
            let e = self.con_edge[k] as usize;
            self.con_dead[k] = false;
            self.link_torn[k] = false;
            self.con_next_for_edge[k] = self.con_of_edge[e];
            self.con_of_edge[e] = k as i32;
            self.con_boundary[k] = self.baked.tri_use[e] <= 1;
            self.con_tendon[k] = (self.muscle_of[self.con_a[k] as usize] >= 0) != (self.muscle_of[self.con_b[k] as usize] >= 0);
            if self.con_boundary[k] {
                self.boundary_link_limits.add(k as i32);
            }
        }

        // --- организмы и сон ---
        let org0 = self.organism_count;
        self.append_organisms(n0);
        self.sleep.append(n, &self.organism_of, n0, org0, self.organism_count);
        if born.is_some() {
            self.mark_unborn_edges(n0);
        }

        // --- свободные и зонды ---
        for i in n0..n {
            self.is_free[i] = true;
        }
        for k in lnk0..self.lnk_count {
            self.is_free[self.lnk_a[k] as usize] = false;
            self.is_free[self.lnk_b[k] as usize] = false;
        }
        // Kotlin: `2.0f * Math.PI - PROBE_ANGLE_EPS` — double, сравнение во double (как build_probes).
        let full = 2.0f64 * std::f64::consts::PI - PROBE_ANGLE_EPS as f64;
        for t in tri0..self.tri_count {
            let a = self.tri_a[t];
            let b = self.tri_b[t];
            let cc = self.tri_c[t];
            let aa = self.angle_at(a, b, cc);
            self.probe_angle[a as usize] += aa;
            let ab = self.angle_at(b, a, cc);
            self.probe_angle[b as usize] += ab;
            let ac = self.angle_at(cc, a, b);
            self.probe_angle[cc as usize] += ac;
        }
        for i in n0..n {
            self.probe[i] = self.is_free[i] || (self.probe_angle[i] as f64) < full;
        }

        // --- пары покоя нового тела (в номерах тела) ---
        let add_n = n - n0;
        let temp_edge = self.temp_edge_mask();
        let mut permanent = 0usize;
        for k in 0..add_links {
            if !self.body.is_temp_link(l0 + k) {
                permanent += 1;
            }
        }
        let mut local_a = vec![0i32; permanent];
        let mut local_b = vec![0i32; permanent];
        {
            let mut m = 0usize;
            for k in 0..add_links {
                if !self.body.is_temp_link(l0 + k) {
                    local_a[m] = self.body.link_a[l0 + k] - n0 as i32;
                    local_b[m] = self.body.link_b[l0 + k] - n0 as i32;
                    m += 1;
                }
            }
        }
        let local_x: Vec<f32> = (0..add_n).map(|it| self.body.x[n0 + it]).collect();
        let local_y: Vec<f32> = (0..add_n).map(|it| self.body.y[n0 + it]).collect();
        let local_r: Vec<f32> = (0..add_n).map(|it| self.radius[n0 + it]).collect();
        let mut lcount = 0usize;
        let mut local_con_a = vec![0i32; self.con_count - con0];
        let mut local_con_b = vec![0i32; self.con_count - con0];
        for k in con0..self.con_count {
            if let Some(te) = temp_edge.as_ref() {
                if te[self.con_edge[k] as usize] {
                    continue;
                }
            }
            local_con_a[lcount] = self.con_a[k] - n0 as i32;
            local_con_b[lcount] = self.con_b[k] - n0 as i32;
            lcount += 1;
        }
        let local_linked = LinkedPairs::from(&local_con_a, &local_con_b, lcount);
        let max_r = max_contact_radius(
            add_n,
            &local_a,
            &local_b,
            permanent,
            &local_r,
            CONTACT_SCALE,
            &local_x,
            &local_y,
            self.cfg.contacts.contact_seal,
        );
        let add_pairs = bake_rest_pairs(add_n, &max_r, &local_x, &local_y, &local_linked);
        if self.ever_linked.is_some() {
            let add = self.permanent_linked_pairs(con0, self.con_count);
            let merged = self.ever_linked.as_ref().unwrap().plus(&add);
            self.ever_linked = Some(merged);
        }

        // --- поверхность и контур: только новые вершины ---
        self.local_boundary.detach_blocks();
        self.local_surface.grow(n, &self.baked);
        self.local_boundary.grow(n, &self.baked);
        for v in n0..n {
            self.surface_update(v);
            self.boundary_update_vertex(v);
        }
        if born.is_some() {
            self.baked.clear_dirty();
        }
        self.local_components = LocalComponents::new(n, self.organism_count as i32);

        // --- касания ---
        {
            let ct = self.contacts.as_mut().expect("касания мира");
            ct.grow(n);
            let rest = ct.append_rest_pairs(&add_pairs, n0);
            self.rest_pairs = Some(rest);
        }
        {
            let ct = self.contacts.as_mut().unwrap();
            ct.attach_surface(&self.local_surface, &self.baked.vert_start);
        }
        let fresh: Vec<i32> = (n0..n).map(|v| v as i32).collect();
        if let Some(ct) = self.contacts.as_mut() {
            let ctx = contact_ctx!(self);
            ct.update_surface(&self.local_surface, &fresh, add_n, &ctx);
        }
        if self.contact_pressure.len() != n {
            self.contact_pressure = vec![0.0; n];
        }
        if self.contact_pressure_n.len() != n {
            self.contact_pressure_n = vec![0; n];
        }

        // --- блоки решателя: заново, как при загрузке ---
        self.constraint_blocks = None;
        self.initialize_constraint_blocks();
        self.rebuild_temp_retire();
        self.structure_version = self.structure_version.wrapping_add(1);
    }

    /// `growParticleArrays`: массивы на частицу — с копированием прежних (copyOf(n)).
    fn grow_particle_arrays(&mut self, n0: usize) {
        let n = self.n;
        self.cell_dead.resize(n, false);
        self.cell_unborn.resize(n, false);
        self.cell_scale.resize(n, 0.0);
        self.cell_scale[n0..n].fill(1.0);
        self.cell_scale_target.resize(n, 0.0);
        self.cell_scale_target[n0..n].fill(1.0);
        self.cell_softness.resize(n, 0.0);
        self.cell_softness[n0..n].fill(1.0);
        self.cell_fiber_x.resize(n, 0.0);
        self.cell_fiber_y.resize(n, 0.0);
        self.cell_no_contact.resize(n, false);
        self.cell_group.resize(n, 0);
        self.cell_grip.resize(n, 0.0);
        self.cell_home_compliance.resize(n, 0.0);
        self.cell_home_x.resize(n, 0.0);
        self.cell_home_y.resize(n, 0.0);
        self.drag_scale.resize(n, 0.0);
        self.drag_scale[n0..n].fill(1.0);
        self.px.resize(n, 0.0);
        self.py.resize(n, 0.0);
        self.prev_x.resize(n, 0.0);
        self.prev_y.resize(n, 0.0);
        self.vx.resize(n, 0.0);
        self.vy.resize(n, 0.0);
        self.inv_mass.resize(n, 0.0);
        self.rest_inv_mass.resize(n, 0.0);
        self.match_weight.resize(n, 0.0);
        self.in_contact.resize(n, false);
        self.bone_of.resize(n, 0);
        self.bone_cluster_of.resize(n, 0);
        self.muscle_of.resize(n, 0);
        self.radius.resize(n, 0.0);
        self.is_free.resize(n, false);
        self.probe.resize(n, false);
        self.probe_angle.resize(n, 0.0);
        self.crushed_cell.resize(n, false);
        if self.trap_ticks.len() == n0 {
            self.trap_ticks.resize(n, 0);
        }
        if self.trap_org.len() == n0 {
            self.trap_org.resize(n, -1);
        }
        if self.fold_ticks.len() == n0 {
            self.fold_ticks.resize(n, 0);
        }
        if self.sleep_anchor_x.len() >= n0 {
            self.sleep_anchor_x.resize(n, 0.0);
            self.sleep_anchor_y.resize(n, 0.0);
            self.org_sleep_drift2.resize(n, 0.0);
        }
        self.organism_of.resize(n, 0);
        self.org_queue = vec![0; n];
        if self.org_prev.len() >= n0 {
            self.org_prev.resize(n, 0);
            self.org_seed.resize(n, 0);
        }
        self.organism_size.resize(n, 0);
        self.organism_mass.resize(n, 0.0);
        self.flow_vx.resize(n, 0.0);
        self.flow_vy.resize(n, 0.0);
        self.com_acc_x.resize(n, 0.0);
        self.com_acc_y.resize(n, 0.0);
        if self.org_flow_x.len() >= n0 {
            self.org_flow_x.resize(n, 0.0);
            self.org_flow_y.resize(n, 0.0);
        }
    }

    /// `appendOrganisms`: компоненты нового тела — новыми номерами после прежних.
    fn append_organisms(&mut self, n0: usize) {
        let n = self.n;
        self.organism_of[n0..n].fill(-1);
        let mut comp = self.organism_count;
        for s in n0..n {
            if self.organism_of[s] != -1 {
                continue;
            }
            let mut head = 0usize;
            let mut tail = 0usize;
            self.organism_of[s] = comp as i32;
            self.org_queue[tail] = s as i32;
            tail += 1;
            while head < tail {
                let v = self.org_queue[head] as usize;
                head += 1;
                for k in self.baked.vert_start[v]..self.baked.vert_start[v + 1] {
                    let k = k as usize;
                    if self.baked.edge_dead[self.baked.vert_edge[k] as usize] {
                        continue;
                    }
                    let u = self.baked.vert_other[k] as usize;
                    if self.organism_of[u] == -1 {
                        self.organism_of[u] = comp as i32;
                        self.org_queue[tail] = u as i32;
                        tail += 1;
                    }
                }
            }
            self.organism_size[comp] = 0;
            self.organism_mass[comp] = 0.0;
            self.flow_vx[comp] = 0.0;
            self.flow_vy[comp] = 0.0;
            self.com_acc_x[comp] = 0.0;
            self.com_acc_y[comp] = 0.0;
            comp += 1;
        }
        for i in n0..n {
            if !self.cell_dead[i] {
                self.organism_size[self.organism_of[i] as usize] += 1;
            }
        }
        for i in n0..n {
            if !self.cell_dead[i] && self.rest_inv_mass[i] > 0.0 {
                self.organism_mass[self.organism_of[i] as usize] += 1.0f32 / self.rest_inv_mass[i];
            }
        }
        self.organism_count = comp;
    }

    /// `removeBody`: гибель тела на месте, без пересчёта мира; частицы остаются до уплотнения.
    pub fn remove_body(&mut self, first: usize, count: usize) {
        let end = first + count;
        if self.drag_id >= first as i32 && self.drag_id < end as i32 {
            self.match_weight[self.drag_id as usize] = 1.0;
            self.drag_id = -1;
        }
        for v in first..end {
            if self.cell_dead[v] {
                continue;
            }
            self.cell_dead[v] = true;
            self.dead_count += 1;
            self.vx[v] = 0.0;
            self.vy[v] = 0.0;
            self.inv_mass[v] = 0.0;
            LocalComponents::remove_cell(v, &self.organism_of, &mut self.organism_size, &mut self.organism_mass, &self.rest_inv_mass);
        }
        for v in first..end {
            self.cell_unborn[v] = false;
            let mut s = self.baked.vert_start[v];
            while s < self.baked.vert_start[v + 1] {
                let e = self.baked.vert_edge[s as usize] as usize;
                s += 1;
                if self.baked.is_unborn(e) {
                    self.retire_unborn_edge(e, false);
                    continue;
                }
                self.kill_edge(e, false, false);
            }
        }
        if let Some(ct) = self.contacts.as_mut() {
            for v in first..end {
                ct.drop_from_contacts(v);
            }
        }
        self.refresh_surface();
        self.structure_version = self.structure_version.wrapping_add(1);
    }
}
