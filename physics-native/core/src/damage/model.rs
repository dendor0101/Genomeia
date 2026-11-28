//! Разрушение — `damage/DamageModel.kt`: пробой мембраны рвёт ребро, заявки на гибель от касаний, гибель по воле
//! клиента, пластическое течение, гибель от давления и раздавливания (отлов застрявших — выключен настройкой).

use crate::contacts::ContactCtx;
use crate::params::{PLASTIC_HOLD, PLASTIC_RATE, PLASTIC_YIELD};
use crate::state::BodyState;
use crate::topology::components::LocalComponents;
use crate::jmath::sqrtf;

/// `plasticLinkAt`: пластическое течение связи (мышцы не текут).
#[allow(clippy::too_many_arguments)]
#[inline(always)]
fn plastic_link_at(c: usize, k: f32, edge_dead: &[bool], con_edge: &[i32], con_muscle: &[i32], cs_on: bool, cs: &[f32], con_rest: &mut [f32], px: &[f32], py: &[f32], con_a: &[i32], con_b: &[i32], hold: &mut [i8]) {
    if edge_dead[con_edge[c] as usize] {
        return;
    }
    if con_muscle[c] >= 0 || (cs_on && cs[c] != 1.0) {
        return;
    }
    let target = con_rest[c];
    if target < 1e-12f32 {
        return;
    }
    let (a, b) = (con_a[c] as usize, con_b[c] as usize);
    let dx = px[a] - px[b];
    let dy = py[a] - py[b];
    let len = sqrtf(dx * dx + dy * dy);
    let strain = len / target - 1.0f32;
    let over = if strain > PLASTIC_YIELD {
        strain - PLASTIC_YIELD
    } else if strain < -PLASTIC_YIELD {
        strain + PLASTIC_YIELD
    } else {
        0.0
    };
    if over == 0.0 {
        hold[c] = 0;
        return;
    }
    if (hold[c] as i32) < PLASTIC_HOLD {
        hold[c] += 1;
        return;
    }
    con_rest[c] *= 1.0f32 + over * k;
}

/// `plasticTriangleAt`: пластическое течение площади треугольника (вывернутым и мышечным — нельзя).
#[allow(clippy::too_many_arguments)]
#[inline(always)]
fn plastic_triangle_at(t: usize, k: f32, tri_alive: &[bool], tri_id: &[i32], tri_muscle: &[i32], ts_on: bool, ts: &[f32], rest2: &mut [f32], px: &[f32], py: &[f32], ta: &[i32], tb: &[i32], tc: &[i32], hold: &mut [i8]) {
    if !tri_alive[tri_id[t] as usize] {
        return;
    }
    if tri_muscle[t] >= 0 || (ts_on && ts[t] != 1.0) {
        return;
    }
    let r2 = rest2[t];
    if r2.abs() < 1e-18f32 {
        return;
    }
    let (i0, i1, i2) = (ta[t] as usize, tb[t] as usize, tc[t] as usize);
    let area2 = (px[i1] - px[i0]) * (py[i2] - py[i0]) - (py[i1] - py[i0]) * (px[i2] - px[i0]);
    if area2 <= 0.0 {
        return;
    }
    let strain = area2 / r2 - 1.0f32;
    let over = if strain > PLASTIC_YIELD {
        strain - PLASTIC_YIELD
    } else if strain < -PLASTIC_YIELD {
        strain + PLASTIC_YIELD
    } else {
        0.0
    };
    if over == 0.0 {
        hold[t] = 0;
        return;
    }
    if (hold[t] as i32) < PLASTIC_HOLD {
        hold[t] += 1;
        return;
    }
    rest2[t] *= 1.0f32 + over * k;
}

impl BodyState {
    /// `applyPierce`: пробитая мембрана рвётся (временная связь роста снимается тихо).
    pub fn apply_pierce(&mut self) {
        // Событие топологии: тень острова его не исполняет — остров откатывается и идёт с миром (island_run.rs).
        if self.isl.shadow {
            self.isl.abort = true;
            return;
        }
        let mut edges: Vec<i32> = Vec::new();
        let pierce_n = self.contacts.as_ref().unwrap().pierce_n;
        for k in 0..pierce_n {
            let ct = self.contacts.as_ref().unwrap();
            let e = self.baked.edge_of(ct.pierce_a[k] as usize, ct.pierce_b[k]);
            if e < 0 || self.baked.edge_dead[e as usize] {
                continue;
            }
            let c = self.con_of_edge[e as usize];
            if c < 0 || c as usize >= self.con_count || self.con_dead[c as usize] {
                continue;
            }
            edges.push(e);
        }
        self.fracture.solve_pierced(&edges, &self.baked, &self.body, &mut self.px, &mut self.py, &self.inv_mass);
        for k in 0..pierce_n {
            let (a, b) = {
                let ct = self.contacts.as_ref().unwrap();
                (ct.pierce_a[k], ct.pierce_b[k])
            };
            let e = self.baked.edge_of(a as usize, b);
            if e < 0 || self.baked.edge_dead[e as usize] {
                continue;
            }
            let e = e as usize;
            let c = self.con_of_edge[e];
            if c < 0 || c as usize >= self.con_count || self.con_dead[c as usize] {
                continue;
            }
            if self.is_temp_edge(e) {
                self.retire_temp_edge(e);
                continue;
            }
            let c = c as usize;
            self.link_torn[c] = true;
            self.con_dead[c] = true;
            self.tears_pending = true;
            self.kill_edge_now(e);
            self.pierced_count += 1;
        }
        self.contacts.as_mut().unwrap().clear_pierce();
        self.refresh_surface();
    }

    /// `applyKills`: заявки на гибель, накопленные касанием за проход.
    pub fn apply_kills(&mut self) {
        // Событие топологии: тень острова его не исполняет — остров откатывается и идёт с миром (island_run.rs).
        if self.isl.shadow {
            self.isl.abort = true;
            return;
        }
        let (list, n) = {
            let ct = self.contacts.as_ref().unwrap();
            (ct.kill_list()[..ct.kill_n()].to_vec(), ct.kill_n())
        };
        self.fracture.solve(&list[..n], &self.baked, &self.body, &mut self.px, &mut self.py, &self.inv_mass);
        let mut any = false;
        for &v in &list[..n] {
            let v = v as usize;
            if self.cell_dead[v] || self.is_fixed(v) {
                continue;
            }
            self.cell_dead[v] = true;
            self.dead_count += 1;
            any = true;
            self.kill_incident_edges(v);
            self.vx[v] = 0.0;
            self.vy[v] = 0.0;
            self.inv_mass[v] = 0.0;
            if let Some(ct) = self.contacts.as_mut() {
                ct.drop_from_contacts(v);
            }
        }
        if any {
            self.refresh_surface();
        }
    }

    /// `killCell`: клетку убивает клиент (между тиками).
    pub fn kill_cell(&mut self, v: usize) {
        if self.cell_dead[v] || self.is_fixed(v) {
            return;
        }
        if self.drag_id == v as i32 {
            self.match_weight[v] = 1.0;
            self.drag_id = -1;
        }
        self.cell_dead[v] = true;
        self.dead_count += 1;
        self.kill_incident_edges(v);
        self.vx[v] = 0.0;
        self.vy[v] = 0.0;
        self.inv_mass[v] = 0.0;
        if let Some(ct) = self.contacts.as_mut() {
            ct.drop_from_contacts(v);
        }
        self.refresh_surface();
        self.structure_version = self.structure_version.wrapping_add(1);
        self.split_dirty_bones();
    }

    /// `killIncidentEdges`: гибель клетки — гибель всех её связей; поверхность клетки — сразу.
    pub fn kill_incident_edges(&mut self, v: usize) {
        if self.cell_death_n == self.cell_death_log.len() {
            let cap = self.cell_death_n * 2;
            self.cell_death_log.resize(cap, 0);
        }
        self.cell_death_log[self.cell_death_n] = v as i32;
        self.cell_death_n += 1;
        LocalComponents::remove_cell(v, &self.organism_of, &mut self.organism_size, &mut self.organism_mass, &self.rest_inv_mass);
        if self.bone_cluster_of[v] >= 0 {
            self.mark_bone_dirty(self.bone_cluster_of[v] as usize);
        }
        for s in self.baked.vert_start[v]..self.baked.vert_start[v + 1] {
            let e = self.baked.vert_edge[s as usize] as usize;
            let c = self.con_of_edge[e];
            if c >= 0 {
                self.con_dead[c as usize] = true;
            }
            self.kill_edge_now(e);
        }
        self.surface_update(v);
        if let Some(ct) = self.contacts.as_mut() {
            let ctx: ContactCtx = crate::contact_ctx!(self);
            let one = [v as i32];
            ct.update_surface(&self.local_surface, &one, 1, &ctx);
        }
    }

    /// `applyPlasticity`: пластическое течение раз в тик (связи и треугольники текут независимо).
    pub fn apply_plasticity(&mut self, dt: f32) {
        if PLASTIC_RATE <= 0.0 {
            return;
        }
        let mut k = PLASTIC_RATE * dt;
        if k > 1.0 {
            k = 1.0;
        }
        if self.con_hold.len() < self.con_a.len() {
            let n = self.con_a.len();
            self.con_hold.resize(n, 0);
        }
        if self.tri_hold.len() < self.tri_a.len() {
            let n = self.tri_a.len();
            self.tri_hold.resize(n, 0);
        }
        // Каждая связь и каждый треугольник текут независимо: по воркерам — тот же набор (solver/parallel.rs).
        if let Some(w) = self.active_workers() {
            self.plasticity_parallel(&w, k);
            return;
        }
        if self.sleep.asleep_count == 0 || self.constraint_blocks.is_none() {
            let (con_n, tri_n) = (self.con_count, self.tri_count);
            self.plastic_ranges(0, con_n, 0, tri_n, k);
            return;
        }
        let buckets = self.constraint_blocks.as_ref().unwrap().buckets;
        for block in 0..buckets {
            self.plastic_block(block, k);
        }
    }

    /// `plasticLinksRange` + `plasticTrianglesRange`: связи [c0, c1) и треугольники [t0, t1) по номерам (без сна).
    pub(crate) fn plastic_ranges(&mut self, c0: usize, c1: usize, t0: usize, t1: usize, k: f32) {
        let s = self;
        let cs_on = s.cell_scales_on;
        for c in c0..c1 {
            plastic_link_at(c, k, &s.baked.edge_dead, &s.con_edge, &s.con_muscle, cs_on, &s.con_cell_scale, &mut s.con_rest, &s.px, &s.py, &s.con_a, &s.con_b, &mut s.con_hold);
        }
        for t in t0..t1 {
            plastic_triangle_at(t, k, &s.baked.tri_alive, &s.tri_id, &s.tri_muscle, cs_on, &s.tri_cell_scale, &mut s.tri_rest_area2, &s.px, &s.py, &s.tri_a, &s.tri_b, &s.tri_c, &mut s.tri_hold);
        }
    }

    /// `plasticBlock`: связи и треугольники набора [block], если в нём есть бодрствующие частицы (со сном).
    pub(crate) fn plastic_block(&mut self, block: usize, k: f32) {
        let s = self;
        let cs_on = s.cell_scales_on;
        let layout = s.constraint_blocks.as_ref().unwrap();
        if !layout.active(block) {
            return;
        }
        let links = &layout.links;
        let from = links.start[block] as usize;
        let to = from + links.count[block] as usize;
        for slot in from..to {
            let c = links.items[slot];
            if c >= 0 {
                plastic_link_at(c as usize, k, &s.baked.edge_dead, &s.con_edge, &s.con_muscle, cs_on, &s.con_cell_scale, &mut s.con_rest, &s.px, &s.py, &s.con_a, &s.con_b, &mut s.con_hold);
            }
        }
        let areas = &layout.areas;
        let from = areas.start[block] as usize;
        let to = from + areas.count[block] as usize;
        for slot in from..to {
            let t = areas.items[slot];
            if t >= 0 {
                plastic_triangle_at(t as usize, k, &s.baked.tri_alive, &s.tri_id, &s.tri_muscle, cs_on, &s.tri_cell_scale, &mut s.tri_rest_area2, &s.px, &s.py, &s.tri_a, &s.tri_b, &s.tri_c, &mut s.tri_hold);
            }
        }
    }

    /// `burstByPressure`: клетка лопается от давления касаний или раздавленной (без отлова застрявших).
    pub fn burst_by_pressure(&mut self) {
        if !self.tearing_on || self.contact_pressure.len() != self.n {
            return;
        }
        let lim = self.pressure_burst * self.link_scale;
        let mut any = false;
        self.prepare_awake_spans();
        let pn_on = self.contact_pressure_n.len() == self.n;
        let burst = self.pressure_burst;
        let crush_on = self.crush_burst_on;
        let kill_ticks = self.trap_kill_ticks;
        let trap_on = kill_ticks > 0 && self.trap_ticks.len() == self.n;
        let fold_on = kill_ticks > 0 && self.trap_kill_folds && self.fold_ticks.len() == self.n;
        for k in 0..self.sleep.span_count {
            let (from, to) = (self.sleep.span_start[k] as usize, self.sleep.span_end[k] as usize);
            for i in from..to {
                if self.cell_dead[i] || self.bone_of[i] >= 0 || self.inv_mass[i] <= 0.0 {
                    continue;
                }
                let pn = if pn_on { self.contact_pressure_n[i] } else { 1 };
                let by_pressure = burst > 0.0 && self.contact_pressure[i] > lim * 1i32.max(pn) as f32;
                let by_crush = crush_on && self.crushed_cell[i] && self.is_free[i];
                let by_trap = trap_on && self.trap_ticks[i] >= kill_ticks;
                let by_fold = fold_on && self.fold_ticks[i] >= kill_ticks;
                if !by_pressure && !by_crush && !by_trap && !by_fold {
                    continue;
                }
                self.cell_dead[i] = true;
                self.dead_count += 1;
                if by_pressure {
                    self.pressure_burst_count += 1;
                } else if by_crush {
                    self.crush_burst_count += 1;
                } else if by_trap {
                    self.trap_kill_count += 1;
                } else {
                    self.fold_kill_count += 1;
                }
                self.vx[i] = 0.0;
                self.vy[i] = 0.0;
                self.inv_mass[i] = 0.0;
                if let Some(ct) = self.contacts.as_mut() {
                    ct.invalidate_static();
                }
                self.kill_incident_edges(i);
                any = true;
            }
        }
        if any {
            self.refresh_surface();
        }
    }
}
