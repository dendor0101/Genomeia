//! Уплотнение мира — функции BodyState из `topology/WorldCompaction.kt`, дословно: удалённые частицы выбрасываются,
//! остальные сдвигаются вниз с сохранением порядка; рёбра, треугольники, мышцы, кости и организмы перенумеровываются
//! по картам; производное (поверхность, контур, касания, блоки) собирается заново, как при загрузке.
//!
//! Чего в Rust нет: отладочные массивы стенда (`dbgOnly`, `dbgSnap*`, `watchCell`, ловушка `trapCrash*`),
//! `InternalMomentum`, `DistanceSweepOrder`, замер времени по стадиям (`lap`).

use std::sync::Arc;

use crate::contacts::{ContactTopology, RestPairs};
use crate::damage::fracture::FractureOrientation;
use crate::definition::BodyDefinition;
use crate::params::{CONTACT_SCALE, PROBE_ANGLE_EPS};
use crate::state::BodyState;
use crate::topology::baked::BakedTopology;
use crate::topology::boundary::LocalBoundaryConstraints;
use crate::topology::components::LocalComponents;
use crate::topology::linked::LinkedPairs;
use crate::topology::surface::LocalSurface;

/// `numbering`: новые номера оставшихся подряд, -1 у выброшенных.
fn numbering(kept: &[bool]) -> Vec<i32> {
    let mut k = 0i32;
    kept.iter()
        .map(|&on| {
            if on {
                k += 1;
                k - 1
            } else {
                -1
            }
        })
        .collect()
}

/// `pick`: элементы [a] с номерами 0..min(count, a.len()) — на места `map[i]` массива длины [cap].
fn pick<T: Copy + Default>(a: &[T], map: &[i32], count: usize, cap: usize) -> Vec<T> {
    let mut r = vec![T::default(); cap];
    for i in 0..count.min(a.len()) {
        let j = map[i];
        if j >= 0 {
            r[j as usize] = a[i];
        }
    }
    r
}

/// `pick` для номеров: значение тоже перенумеровывается картой [values] (отрицательные — как есть).
fn pick_ids(a: &[i32], map: &[i32], count: usize, cap: usize, values: Option<&[i32]>) -> Vec<i32> {
    let mut r = vec![0i32; cap];
    for i in 0..count.min(a.len()) {
        let j = map[i];
        if j < 0 {
            continue;
        }
        let v = a[i];
        r[j as usize] = match values {
            Some(vals) if v >= 0 => vals[v as usize],
            _ => v,
        };
    }
    r
}

/// `p(a)` уплотнения: массив на частицу; короче мира (ещё не заведён) — как есть.
fn per_particle<T: Copy + Default>(a: &mut Vec<T>, p_new: &[i32], n_old: usize, n_new: usize) {
    if a.len() < n_old {
        return;
    }
    *a = pick(a, p_new, n_old, n_new);
}

fn per_particle_ids(a: &mut Vec<i32>, p_new: &[i32], n_old: usize, n_new: usize, values: Option<&[i32]>) {
    if a.len() < n_old {
        return;
    }
    *a = pick_ids(a, p_new, n_old, n_new, values);
}

/// `BodyDefinition.keep`: описание без удалённых частиц, связей, треугольников, костей и мышц.
fn keep_definition(
    d: &BodyDefinition,
    removed: &[bool],
    p_new: &[i32],
    n_new: usize,
    link_kept: &[bool],
    tri_kept: &[bool],
    muscle_kept: &[bool],
) -> BodyDefinition {
    let mut idx = vec![0usize; n_new];
    let mut k = 0usize;
    for i in 0..d.count() {
        if !removed[i] {
            idx[k] = i;
            k += 1;
        }
    }
    let links: Vec<usize> = (0..d.link_count()).filter(|&l| link_kept[l]).collect();
    let tris: Vec<usize> = (0..d.tri_count()).filter(|&t| tri_kept[t]).collect();
    let remap = |c: &Vec<i32>| -> Vec<i32> { c.iter().map(|&v| p_new[v as usize]).collect() };
    let bones: Vec<Vec<i32>> = d.bone_clusters.iter().filter(|c| !c.is_empty() && !removed[c[0] as usize]).map(remap).collect();
    let muscles: Vec<Vec<i32>> = (0..d.muscle_clusters.len()).filter(|&m| muscle_kept[m]).map(|m| remap(&d.muscle_clusters[m])).collect();
    let mut retire_start: Vec<i32> = Vec::new();
    let mut retire_cells: Vec<i32> = Vec::new();
    if d.has_temp_links() {
        retire_start = vec![0; links.len() + 1];
        let mut cells: Vec<i32> = Vec::new();
        for (k, &l) in links.iter().enumerate() {
            for s in d.link_retire_start[l]..d.link_retire_start[l + 1] {
                let s = s as usize;
                let c = p_new[d.link_retire_cells[2 * s] as usize];
                let p0 = d.link_retire_cells[2 * s + 1];
                let p = if p0 < 0 { -1 } else { p_new[p0 as usize] };
                if c >= 0 && (p0 < 0 || p >= 0) {
                    cells.push(c);
                    cells.push(p);
                }
            }
            retire_start[k + 1] = (cells.len() / 2) as i32;
        }
        retire_cells = cells;
        if retire_cells.is_empty() {
            retire_start = Vec::new();
        }
    }
    BodyDefinition {
        x: (0..n_new).map(|it| d.x[idx[it]]).collect(),
        y: (0..n_new).map(|it| d.y[idx[it]]).collect(),
        radius: (0..n_new).map(|it| d.radius[idx[it]]).collect(),
        is_bone: (0..n_new).map(|it| d.is_bone[idx[it]]).collect(),
        is_muscle: (0..n_new).map(|it| d.is_muscle[idx[it]]).collect(),
        link_a: links.iter().map(|&l| p_new[d.link_a[l] as usize]).collect(),
        link_b: links.iter().map(|&l| p_new[d.link_b[l] as usize]).collect(),
        tri_a: tris.iter().map(|&t| p_new[d.tri_a[t] as usize]).collect(),
        tri_b: tris.iter().map(|&t| p_new[d.tri_b[t] as usize]).collect(),
        tri_c: tris.iter().map(|&t| p_new[d.tri_c[t] as usize]).collect(),
        tri_rest_area2: tris.iter().map(|&t| d.tri_rest_area2[t]).collect(),
        bone_clusters: bones,
        muscle_clusters: muscles,
        scale: d.scale,
        link_retire_start: retire_start,
        link_retire_cells: retire_cells,
    }
}

/// `RestPairs.keep`: пары покоя без удалённых частиц, в новых номерах.
fn keep_rest_pairs(rp: &RestPairs, p_new: &[i32], n_new: usize) -> RestPairs {
    let n_old = rp.start.len() - 1;
    let mut s_new = vec![-1i32; rp.other.len()];
    let mut st = vec![0i32; n_new + 1];
    let mut total = 0i32;
    for i in 0..n_old {
        let j = p_new[i];
        if j < 0 {
            continue;
        }
        for s in rp.start[i]..rp.start[i + 1] {
            let s = s as usize;
            if p_new[rp.other[s] as usize] >= 0 {
                s_new[s] = total;
                total += 1;
            }
        }
        st[j as usize + 1] = total;
    }
    let total = total as usize;
    let mut o = vec![0i32; total];
    let mut mi = vec![0i32; total];
    let mut dd = vec![0.0f32; total];
    let mut di = vec![0.0f32; total];
    let mut ev = vec![false; total];
    for s in 0..rp.other.len() {
        let k = s_new[s];
        if k < 0 {
            continue;
        }
        let k = k as usize;
        o[k] = p_new[rp.other[s] as usize];
        mi[k] = s_new[rp.mirror[s] as usize];
        dd[k] = rp.d2[s];
        di[k] = rp.dist[s];
        ev[k] = rp.ever[s];
    }
    RestPairs { start: st, other: o, mirror: mi, d2: dd, dist: di, ever: ev }
}

impl BodyState {
    /// `compactWorld`: выбросить частицы [removed] (по частице мира) и всё, что на них держится.
    pub fn compact_world(&mut self, removed: &[bool]) {
        if self.tears_pending {
            self.rebuild_timed();
        }
        self.split_dirty_bones();
        let n_old = self.n;
        assert!(removed.len() >= n_old, "removed — по частице мира");
        let old = std::mem::take(&mut self.body);
        let mut p_new = vec![0i32; n_old];
        let mut n_new = 0usize;
        for i in 0..n_old {
            p_new[i] = if removed[i] {
                -1
            } else {
                n_new += 1;
                (n_new - 1) as i32
            };
        }
        let link_kept: Vec<bool> = (0..old.link_count()).map(|l| !removed[old.link_a[l] as usize]).collect();
        let tri_kept: Vec<bool> = (0..old.tri_count()).map(|t| !removed[old.tri_a[t] as usize]).collect();
        let t_new = numbering(&tri_kept);
        let edge_kept: Vec<bool> = (0..self.baked.edge_count).map(|e| !removed[self.baked.edge_u(e) as usize]).collect();
        let e_new = numbering(&edge_kept);
        let muscle_kept: Vec<bool> =
            old.muscle_clusters.iter().map(|c| !c.is_empty() && !removed[c[0] as usize]).collect();
        let m_new = numbering(&muscle_kept);
        let bone_kept: Vec<bool> = self.rigid_bones.iter().map(|c| !c.is_empty() && !removed[c[0] as usize]).collect();
        let b_new = numbering(&bone_kept);
        let org_count = self.organism_count;
        let mut org_kept = vec![true; org_count];
        for i in 0..n_old {
            if removed[i] {
                let o = self.organism_of[i];
                if o >= 0 && (o as usize) < org_count {
                    org_kept[o as usize] = false;
                }
            }
        }
        let o_new = numbering(&org_kept);
        let org_new_count = o_new.iter().filter(|&&v| v >= 0).count();
        let def = keep_definition(&old, removed, &p_new, n_new, &link_kept, &tri_kept, &muscle_kept);

        // --- массивы на частицу ---
        let (pn, no, nn) = (&p_new[..], n_old, n_new);
        per_particle(&mut self.cell_dead, pn, no, nn);
        per_particle(&mut self.cell_unborn, pn, no, nn);
        per_particle(&mut self.cell_scale, pn, no, nn);
        per_particle(&mut self.cell_scale_target, pn, no, nn);
        per_particle(&mut self.drag_scale, pn, no, nn);
        per_particle(&mut self.cell_softness, pn, no, nn);
        per_particle(&mut self.cell_no_contact, pn, no, nn);
        per_particle_ids(&mut self.cell_group, pn, no, nn, None);
        per_particle(&mut self.cell_fiber_x, pn, no, nn);
        per_particle(&mut self.cell_fiber_y, pn, no, nn);
        per_particle(&mut self.cell_grip, pn, no, nn);
        self.grips_dirty = true;
        per_particle(&mut self.cell_home_compliance, pn, no, nn);
        per_particle(&mut self.cell_home_x, pn, no, nn);
        per_particle(&mut self.cell_home_y, pn, no, nn);
        per_particle(&mut self.px, pn, no, nn);
        per_particle(&mut self.py, pn, no, nn);
        per_particle(&mut self.prev_x, pn, no, nn);
        per_particle(&mut self.prev_y, pn, no, nn);
        per_particle(&mut self.vx, pn, no, nn);
        per_particle(&mut self.vy, pn, no, nn);
        per_particle(&mut self.inv_mass, pn, no, nn);
        per_particle(&mut self.rest_inv_mass, pn, no, nn);
        per_particle(&mut self.match_weight, pn, no, nn);
        per_particle(&mut self.in_contact, pn, no, nn);
        per_particle(&mut self.radius, pn, no, nn);
        per_particle(&mut self.is_free, pn, no, nn);
        per_particle(&mut self.probe, pn, no, nn);
        per_particle(&mut self.probe_angle, pn, no, nn);
        per_particle(&mut self.crushed_cell, pn, no, nn);
        per_particle_ids(&mut self.bone_of, pn, no, nn, Some(&b_new));
        per_particle_ids(&mut self.bone_cluster_of, pn, no, nn, Some(&b_new));
        per_particle_ids(&mut self.muscle_of, pn, no, nn, Some(&m_new));
        per_particle_ids(&mut self.organism_of, pn, no, nn, Some(&o_new));
        per_particle_ids(&mut self.trap_ticks, pn, no, nn, None);
        per_particle_ids(&mut self.trap_org, pn, no, nn, Some(&o_new));
        per_particle_ids(&mut self.fold_ticks, pn, no, nn, None);
        per_particle(&mut self.sleep_anchor_x, pn, no, nn);
        per_particle(&mut self.sleep_anchor_y, pn, no, nn);
        per_particle(&mut self.tick_jitter_x, pn, no, nn);
        per_particle(&mut self.tick_jitter_y, pn, no, nn);
        per_particle(&mut self.contact_pressure, pn, no, nn);
        per_particle_ids(&mut self.contact_pressure_n, pn, no, nn, None);
        self.org_queue = vec![0; n_new];
        if self.drag_id >= 0 {
            self.drag_id = p_new[self.drag_id as usize];
        }
        self.remap_joints(&p_new);

        // --- организмы ---
        self.organism_size = pick(&self.organism_size, &o_new, org_count, n_new);
        self.organism_mass = pick(&self.organism_mass, &o_new, org_count, n_new);
        self.flow_vx = pick(&self.flow_vx, &o_new, org_count, n_new);
        self.flow_vy = pick(&self.flow_vy, &o_new, org_count, n_new);
        if self.org_sleep_drift2.len() >= org_count {
            self.org_sleep_drift2 = pick(&self.org_sleep_drift2, &o_new, org_count, n_new);
        }
        self.com_acc_x = vec![0.0; n_new];
        self.com_acc_y = vec![0.0; n_new];
        if !self.org_prev.is_empty() {
            self.org_prev = vec![0; n_new];
            self.org_seed = vec![0; n_new];
        }
        if !self.org_flow_x.is_empty() {
            self.org_flow_x = vec![0.0; n_new];
            self.org_flow_y = vec![0.0; n_new];
        }
        self.organism_count = org_new_count;

        // --- кости и мышцы ---
        let mut bones: Vec<Vec<i32>> = Vec::new();
        let mut qx: Vec<Vec<f32>> = Vec::new();
        let mut qy: Vec<Vec<f32>> = Vec::new();
        for b in 0..self.rigid_bones.len() {
            if bone_kept[b] {
                bones.push(self.rigid_bones[b].iter().map(|&v| p_new[v as usize]).collect());
                qx.push(std::mem::take(&mut self.bone_rest_qx[b]));
                qy.push(std::mem::take(&mut self.bone_rest_qy[b]));
            }
        }
        self.rigid_bones = bones;
        self.bone_rest_qx = qx;
        self.bone_rest_qy = qy;
        self.degenerate_bones = def.bone_clusters.iter().filter(|c| c.len() < 3).count() as i32;
        let mc = old.muscle_clusters.len();
        let mc_new = def.muscle_clusters.len();
        self.muscle_activation = pick(&self.muscle_activation, &m_new, mc, mc_new);
        self.muscle_target = pick(&self.muscle_target, &m_new, mc, mc_new);
        let short = self.muscle_scale_of.len() < mc;
        self.muscle_scale_of = pick(&self.muscle_scale_of, &m_new, mc, mc_new);
        if short {
            self.muscle_scale_of.fill(1.0);
        }
        if self.muscle_activation_prev.len() >= mc {
            self.muscle_activation_prev = pick(&self.muscle_activation_prev, &m_new, mc, mc_new);
        }

        // --- связи, рабочие связи, треугольники ---
        self.compact_links(&p_new, &e_new, def.link_count());
        self.compact_constraints(&p_new, &e_new, &m_new, def.link_count());
        self.compact_triangle_list(&p_new, &t_new, &m_new, def.tri_count());
        if !self.tri_grow_target.is_empty() {
            let count = old.tri_count().min(self.tri_grow_target.len());
            self.tri_grow_target = pick(&self.tri_grow_target, &t_new, count, def.tri_count());
        }
        let mut m = 0usize;
        for k in 0..self.grow_edge_n {
            let e = e_new[self.grow_edges[k] as usize];
            if e >= 0 {
                self.grow_edges[m] = e;
                m += 1;
            }
        }
        self.grow_edge_n = m;
        m = 0;
        for k in 0..self.grow_tri_n {
            let t = t_new[self.grow_tris[k] as usize];
            if t >= 0 {
                self.grow_tris[m] = t;
                m += 1;
            }
        }
        self.grow_tri_n = m;
        let (ge, gt) = (self.grow_edge_n, self.grow_tri_n);
        self.grow_edges[ge..].fill(0);
        self.grow_tris[gt..].fill(0);
        self.tear_queue_n = 0;
        self.tear_queue_quiet = 0;
        self.tear_queue.fill(0);

        // --- топология, пары покоя, сон ---
        self.n = n_new;
        let mut baked = BakedTopology::empty_for(&def);
        baked.compact_from(&self.baked, &old, &def, &p_new, &e_new, &t_new, &link_kept);
        let old_baked = std::mem::replace(&mut self.baked, baked);
        self.body = def;
        if let Some(rp) = self.rest_pairs.as_ref() {
            let kept = keep_rest_pairs(rp, &p_new, n_new);
            self.rest_pairs = Some(Arc::new(kept));
        }
        self.sleep.compact(&o_new, org_count, &p_new, self.n);
        let old_surface = std::mem::take(&mut self.local_surface);
        self.rebuild_derived(Some((&old_surface, &old_baked.vert_start, &p_new)));
    }

    /// `rebuildDerived`: производное заново — по картам уплотнения ([from]: прежняя поверхность, vertStart прежней
    /// топологии, карта частиц) или с нуля (снимок).
    pub fn rebuild_derived(&mut self, from: Option<(&LocalSurface, &[i32], &[i32])>) {
        self.fracture = FractureOrientation::new(self.body.tri_count());
        self.con_of_edge = vec![-1; self.baked.edge_count];
        self.con_next_for_edge = vec![0; self.con_a.len()];
        for c in 0..self.con_count {
            let e = self.con_edge[c] as usize;
            self.con_next_for_edge[c] = self.con_of_edge[e];
            self.con_of_edge[e] = c as i32;
        }
        if self.ever_linked.is_some() {
            self.ever_linked = Some(self.ever_linked_of());
        }
        self.dead_count = 0;
        for i in 0..self.n {
            if self.cell_dead[i] && !self.cell_unborn[i] {
                self.dead_count += 1;
            }
        }
        let n = self.n;
        self.local_surface =
            LocalSurface::new(n, &self.baked, CONTACT_SCALE, self.cfg.contacts.contact_seal, PROBE_ANGLE_EPS, self.probe_on);
        match from {
            Some((old_surface, old_start, p_new)) => {
                self.local_surface.compact_from(old_surface, old_start, &self.baked.vert_start, p_new);
            }
            None => {
                for v in 0..n {
                    self.surface_update(v);
                }
            }
        }
        self.local_boundary = LocalBoundaryConstraints::new(n, &self.baked);
        for v in 0..n {
            let mut surface = false;
            for s in self.baked.vert_start[v]..self.baked.vert_start[v + 1] {
                let e = self.baked.vert_edge[s as usize] as usize;
                if !self.baked.edge_dead[e] && self.baked.tri_use[e] <= 1 {
                    surface = true;
                    break;
                }
            }
            if surface {
                self.boundary_update_vertex(v);
            }
        }
        self.baked.clear_dirty();
        self.local_components = LocalComponents::new(n, self.organism_count as i32);
        for c in 0..self.con_count {
            let e = self.con_edge[c] as usize;
            self.con_boundary[c] = !self.baked.edge_dead[e] && self.baked.tri_use[e] <= 1;
            self.con_tendon[c] =
                (self.muscle_of[self.con_a[c] as usize] >= 0) != (self.muscle_of[self.con_b[c] as usize] >= 0);
        }
        self.rebuild_boundary_link_limits();
        if self.contacts.is_some() {
            let rest = self.rest_pairs.clone().expect("пары покоя мира");
            let topo = ContactTopology {
                con_a: &self.con_a,
                con_b: &self.con_b,
                con_count: self.con_count,
                bound_a: &self.local_boundary.a,
                bound_b: &self.local_boundary.b,
                bound_count: self.local_boundary.count,
                bound_opp: &self.local_boundary.opposite,
                rest_x: &self.body.x,
                rest_y: &self.body.y,
                isolated: Some(if self.probe_on { &self.probe } else { &self.is_free }),
                dead: Some(&self.cell_dead),
                radius: &self.radius,
            };
            let ct = self.contacts.as_mut().unwrap();
            ct.resize(n);
            ct.reconfigure(&topo, &rest);
            ct.attach_surface(&self.local_surface, &self.baked.vert_start);
        }
        self.apply_no_contact();
        self.con_cell_scale = vec![1.0; self.con_a.len()];
        self.tri_cell_scale = vec![1.0; self.tri_a.len()];
        self.con_softness = vec![1.0; self.con_a.len()];
        self.tri_softness = vec![1.0; self.tri_a.len()];
        self.recompute_cell_scales();
        self.constraint_blocks = None;
        self.initialize_constraint_blocks();
        self.rebuild_temp_retire();
        self.structure_version = self.structure_version.wrapping_add(1);
    }

    /// `everLinkedOf`: пары, связанные в описании, — кроме внутрикостных и временных связей роста.
    pub fn ever_linked_of(&self) -> LinkedPairs {
        let d = &self.body;
        let mut a = vec![0i32; d.link_count()];
        let mut b = vec![0i32; d.link_count()];
        let mut m = 0usize;
        for l in 0..d.link_count() {
            let i = d.link_a[l];
            let j = d.link_b[l];
            let (iu, ju) = (i as usize, j as usize);
            if self.bone_of[iu] != -1 && self.bone_of[iu] == self.bone_of[ju] {
                continue;
            }
            if d.is_temp_link(l) {
                continue;
            }
            a[m] = i;
            b[m] = j;
            m += 1;
        }
        LinkedPairs::from(&a, &b, m)
    }

    fn compact_links(&mut self, p_new: &[i32], e_new: &[i32], cap: usize) {
        let mut a = vec![0i32; cap];
        let mut b = vec![0i32; cap];
        let mut e = vec![0i32; cap];
        let mut m = 0usize;
        for k in 0..self.lnk_count {
            let i = p_new[self.lnk_a[k] as usize];
            if i < 0 {
                continue;
            }
            a[m] = i;
            b[m] = p_new[self.lnk_b[k] as usize];
            e[m] = e_new[self.lnk_edge[k] as usize];
            m += 1;
        }
        self.lnk_a = a;
        self.lnk_b = b;
        self.lnk_edge = e;
        self.lnk_count = m;
    }

    fn compact_constraints(&mut self, p_new: &[i32], e_new: &[i32], m_new: &[i32], cap: usize) {
        let mut a = vec![0i32; cap];
        let mut b = vec![0i32; cap];
        let mut rest = vec![0.0f32; cap];
        let mut mus = vec![0i32; cap];
        let mut edge = vec![0i32; cap];
        let mut dead = vec![false; cap];
        let mut torn = vec![false; cap];
        let mut hold = if !self.con_hold.is_empty() { vec![0i8; cap] } else { std::mem::take(&mut self.con_hold) };
        let mut grow = if !self.con_grow_target.is_empty() { vec![0.0f32; cap] } else { std::mem::take(&mut self.con_grow_target) };
        let mut grow_age = if !self.con_grow_age.is_empty() { vec![0i8; cap] } else { std::mem::take(&mut self.con_grow_age) };
        let mut m = 0usize;
        for c in 0..self.con_count {
            let i = p_new[self.con_a[c] as usize];
            if i < 0 {
                continue;
            }
            a[m] = i;
            b[m] = p_new[self.con_b[c] as usize];
            rest[m] = self.con_rest[c];
            mus[m] = if self.con_muscle[c] < 0 { self.con_muscle[c] } else { m_new[self.con_muscle[c] as usize] };
            edge[m] = e_new[self.con_edge[c] as usize];
            dead[m] = self.con_dead[c];
            torn[m] = self.link_torn[c];
            if !hold.is_empty() && c < self.con_hold.len() {
                hold[m] = self.con_hold[c];
            }
            if !grow.is_empty() && c < self.con_grow_target.len() {
                grow[m] = self.con_grow_target[c];
            }
            if !grow_age.is_empty() && c < self.con_grow_age.len() {
                grow_age[m] = self.con_grow_age[c];
            }
            m += 1;
        }
        self.con_a = a;
        self.con_b = b;
        self.con_rest = rest;
        self.con_muscle = mus;
        self.con_edge = edge;
        self.con_dead = dead;
        self.link_torn = torn;
        self.con_hold = hold;
        self.con_grow_target = grow;
        self.con_grow_age = grow_age;
        self.con_boundary = vec![false; cap];
        self.con_tendon = vec![false; cap];
        self.con_count = m;
    }

    fn compact_triangle_list(&mut self, p_new: &[i32], t_new: &[i32], m_new: &[i32], cap: usize) {
        let mut a = vec![0i32; cap];
        let mut b = vec![0i32; cap];
        let mut c = vec![0i32; cap];
        let mut area = vec![0.0f32; cap];
        let mut id = vec![0i32; cap];
        let mut mus = vec![0i32; cap];
        let mut inv = vec![false; cap];
        let mut hold = if !self.tri_hold.is_empty() { vec![0i8; cap] } else { std::mem::take(&mut self.tri_hold) };
        let mut m = 0usize;
        for t in 0..self.tri_count {
            let i = p_new[self.tri_a[t] as usize];
            if i < 0 {
                continue;
            }
            a[m] = i;
            b[m] = p_new[self.tri_b[t] as usize];
            c[m] = p_new[self.tri_c[t] as usize];
            area[m] = self.tri_rest_area2[t];
            id[m] = t_new[self.tri_id[t] as usize];
            mus[m] = if self.tri_muscle[t] < 0 { self.tri_muscle[t] } else { m_new[self.tri_muscle[t] as usize] };
            inv[m] = self.tri_inverted[t];
            if !hold.is_empty() && t < self.tri_hold.len() {
                hold[m] = self.tri_hold[t];
            }
            m += 1;
        }
        self.tri_a = a;
        self.tri_b = b;
        self.tri_c = c;
        self.tri_rest_area2 = area;
        self.tri_id = id;
        self.tri_muscle = mus;
        self.tri_inverted = inv;
        self.tri_hold = hold;
        self.tri_count = m;
    }
}
