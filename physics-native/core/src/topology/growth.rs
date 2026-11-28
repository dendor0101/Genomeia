//! Рост тела по клетке — `topology/BodyGrowth.kt`: нерождённые рёбра, рождение клетки, дорастание связей и площадей
//! (раз в тик, по подшагам — плавно), включение кости, неуязвимость и мягкий старт касаний новорождённой, место
//! рождения (подгонка по соседям, деление, свободное место).

use crate::jmath::{maxf, minf, sqrtf};
use crate::params::{BIRTH_CLEAR, BIRTH_GROW_TICKS, BIRTH_MAX_PULL, BIRTH_MIN_FRACTION, BIRTH_PUSH_ITERATIONS, BIRTH_SEARCH_RINGS, DIVISION_START_FRACTION};
use crate::state::BodyState;
use crate::topology::components::LocalComponents;
use crate::topology::runtime::rest_pose_from_definition;

/// Опорные клетки подгонки рождения и кольца поиска свободного места: размеры как у Kotlin (64 и 128).
const BIRTH_REF_CAP: usize = 64;
const BIRTH_NEAR_CAP: usize = 128;

impl BodyState {
    /// `markUnbornEdges`: рёбра нерождённых клеток нового тела [n0, n) — нерождённые (мертвы, но в рабочих списках).
    pub fn mark_unborn_edges(&mut self, n0: usize) {
        let blocks = self.constraint_blocks.take();
        for v in n0..self.n {
            if !self.cell_unborn[v] {
                continue;
            }
            for s in self.baked.vert_start[v]..self.baked.vert_start[v + 1] {
                let e = self.baked.vert_edge[s as usize] as usize;
                if self.baked.edge_dead[e] {
                    continue;
                }
                self.baked.mark_unborn(e, &self.body);
                let mut c = self.con_of_edge[e];
                while c >= 0 {
                    self.con_dead[c as usize] = true;
                    c = self.con_next_for_edge[c as usize];
                }
                self.update_boundary_link_limit(e);
                for q in self.baked.edge_tri_start[e]..self.baked.edge_tri_start[e + 1] {
                    let t = self.baked.edge_tris[q as usize] as usize;
                    for j in 0..3 {
                        let a = self.baked.tri_edge[3 * t + j];
                        if a >= 0 {
                            self.update_boundary_link_limit(a as usize);
                        }
                    }
                }
            }
        }
        self.constraint_blocks = blocks;
    }

    /// `retireUnbornEdge`: нерождённое ребро уже не родится — просто мёртвое; [quiet] — временная связь роста.
    pub fn retire_unborn_edge(&mut self, e: usize, quiet: bool) {
        self.baked.forget_unborn(e);
        for q in self.baked.edge_tri_start[e]..self.baked.edge_tri_start[e + 1] {
            let t = self.baked.edge_tris[q as usize] as usize;
            if self.baked.is_tri_unborn(t) {
                self.baked.forget_tri_unborn(t);
                if let Some(cb) = self.constraint_blocks.as_mut() {
                    cb.remove_triangle(t);
                }
            }
        }
        self.update_boundary_link_limit(e);
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
    }

    /// `activateCell`: родить клетку [i] в точке (x, y) со скоростью (vx0, vy0).
    pub fn activate_cell(&mut self, i: usize, x: f32, y: f32, vx0: f32, vy0: f32) {
        assert!(self.cell_unborn[i], "клетка {i} не ждёт рождения");
        self.cell_unborn[i] = false;
        self.cell_dead[i] = false;
        self.inv_mass[i] = self.rest_inv_mass[i];
        self.match_weight[i] = self.rest_match_weight(i);
        self.px[i] = x;
        self.py[i] = y;
        self.prev_x[i] = x;
        self.prev_y[i] = y;
        self.vx[i] = vx0;
        self.vy[i] = vy0;
        LocalComponents::add_cell(i, &self.organism_of, &mut self.organism_size, &mut self.organism_mass, &self.rest_inv_mass);
        self.baked.touch(i as i32);
        let org = self.organism_of[i];
        let edges0 = self.grow_edge_n;
        for s in self.baked.vert_start[i]..self.baked.vert_start[i + 1] {
            let s = s as usize;
            let e = self.baked.vert_edge[s] as usize;
            let other = self.baked.vert_other[s] as usize;
            if !self.baked.is_unborn(e) || self.cell_unborn[other] {
                continue;
            }
            if self.cell_dead[other] || self.organism_of[other] != org {
                self.retire_unborn_edge(e, false);
                continue;
            }
            self.baked.revive(e);
            let mut c = self.con_of_edge[e];
            while c >= 0 {
                self.con_dead[c as usize] = false;
                self.link_torn[c as usize] = false;
                c = self.con_next_for_edge[c as usize];
            }
            if self.grow_edge_n == self.grow_edges.len() {
                let cap = self.grow_edges.len() * 2;
                self.grow_edges.resize(cap, 0);
            }
            self.grow_edges[self.grow_edge_n] = e as i32;
            self.grow_edge_n += 1;
            self.update_boundary_link_limit(e);
        }
        self.retire_temp_edges_of(i);
        let mut tris = 0usize;
        for s in self.baked.vertex_tri_start[i]..self.baked.vertex_tri_start[i + 1] {
            let t = (self.baked.vertex_triangles[s as usize] / 3) as usize;
            if !self.baked.revive_triangle(t, &self.body) {
                continue;
            }
            if tris == self.born_tri_scratch.len() {
                let cap = tris * 2;
                self.born_tri_scratch.resize(cap, 0);
            }
            self.born_tri_scratch[tris] = t as i32;
            tris += 1;
            for j in 0..3 {
                let a = self.baked.tri_edge[3 * t + j];
                if a >= 0 {
                    self.update_boundary_link_limit(a as usize);
                }
            }
        }
        self.refresh_cell_scale_ring(i);
        for k in edges0..self.grow_edge_n {
            let mut c = self.con_of_edge[self.grow_edges[k] as usize];
            while c >= 0 {
                self.start_link_growth(c as usize);
                c = self.con_next_for_edge[c as usize];
            }
        }
        for k in 0..tris {
            let t = self.born_tri_scratch[k] as usize;
            self.start_triangle_growth(t);
        }
        if self.bone_of[i] >= 0 {
            self.rest_pose_of_born_bone(self.bone_of[i] as usize);
        }
        self.sleep.request_wake(i as i32);
        self.refresh_surface();
        self.structure_version = self.structure_version.wrapping_add(1);
    }

    /// `startLinkGrowth`: связь родилась — длина покоя фактическая (в долях сокращения мышцы), цель — прежняя.
    fn start_link_growth(&mut self, c: usize) {
        if self.con_grow_target.len() < self.con_a.len() {
            let n = self.con_a.len();
            self.con_grow_target.resize(n, 0.0);
        }
        let target = self.con_rest[c];
        if target <= 0.0 {
            return;
        }
        let (a, b) = (self.con_a[c] as usize, self.con_b[c] as usize);
        let dx = self.px[a] - self.px[b];
        let dy = self.py[a] - self.py[b];
        let scale = self.muscle_scale(self.con_muscle[c]) * self.con_cell_scale[c];
        let start = sqrtf(dx * dx + dy * dy) / self.muscle_length_factor(c, scale);
        self.con_grow_target[c] = target;
        self.con_rest[c] = maxf(start, BIRTH_MIN_FRACTION * target);
        if self.con_grow_age.len() < self.con_a.len() {
            let n = self.con_a.len();
            self.con_grow_age.resize(n, 0);
        }
        self.con_grow_age[c] = 0;
    }

    /// `startTriangleGrowth`: треугольник [id] родился — площадь покоя фактическая, цель — прежняя.
    fn start_triangle_growth(&mut self, id: usize) {
        let w = self.constraint_blocks.as_ref().unwrap().triangle_slot[id];
        if w < 0 {
            return;
        }
        let w = w as usize;
        if self.tri_grow_target.len() < self.body.tri_count() {
            let n = self.body.tri_count();
            self.tri_grow_target.resize(n, 0.0);
        }
        let target = self.tri_rest_area2[w];
        if target <= 0.0 {
            return;
        }
        let (i0, i1, i2) = (self.tri_a[w] as usize, self.tri_b[w] as usize, self.tri_c[w] as usize);
        let (px, py) = (&self.px, &self.py);
        let area2 = (px[i1] - px[i0]) * (py[i2] - py[i0]) - (py[i1] - py[i0]) * (px[i2] - px[i0]);
        let sa = self.muscle_scale(self.tri_muscle[w]) * self.tri_cell_scale[w];
        self.tri_grow_target[id] = target;
        let factor = self.triangle_area_factor(w, sa);
        self.tri_rest_area2[w] = maxf(area2 / factor, BIRTH_MIN_FRACTION * target);
        if self.grow_tri_n == self.grow_tris.len() {
            let cap = self.grow_tris.len() * 2;
            self.grow_tris.resize(cap, 0);
        }
        self.grow_tris[self.grow_tri_n] = id as i32;
        self.grow_tri_n += 1;
    }

    /// `easeNewborn`: дорастание раз в тик, до решателя; шаг тика решатель проходит по подшагам (applyGrowthLerp).
    pub fn ease_newborn(&mut self) {
        self.mark_growing_cells();
        self.grow_lerp_n = 0;
        self.grow_lerp_tri_n = 0;
        if self.grow_edge_n == 0 && self.grow_tri_n == 0 {
            return;
        }
        let mut m = 0usize;
        for k in 0..self.grow_edge_n {
            let e = self.grow_edges[k] as usize;
            if self.baked.edge_dead[e] {
                continue;
            }
            let mut growing = false;
            let mut c = self.con_of_edge[e];
            while c >= 0 {
                let cu = c as usize;
                let target = self.con_grow_target[cu];
                let was = self.con_rest[cu];
                if target > 0.0 {
                    let age = (self.con_grow_age[cu] as i32 + 1).min(BIRTH_GROW_TICKS);
                    self.con_grow_age[cu] = age as i8;
                    let (a, b) = (self.con_a[cu] as usize, self.con_b[cu] as usize);
                    let dx = self.px[a] - self.px[b];
                    let dy = self.py[a] - self.py[b];
                    let scale = self.muscle_scale(self.con_muscle[cu]) * self.con_cell_scale[cu];
                    let len = sqrtf(dx * dx + dy * dy) / self.muscle_length_factor(cu, scale);
                    let pull = BIRTH_MAX_PULL * target;
                    if age >= BIRTH_GROW_TICKS && len - target <= pull {
                        self.con_rest[cu] = target;
                        self.con_grow_target[cu] = 0.0;
                    } else {
                        let eased = len + (target - len) * (age as f32 / BIRTH_GROW_TICKS as f32);
                        // Kotlin maxOf(a, b, c) = max(a, max(b, c)).
                        self.con_rest[cu] = maxf(eased, maxf(len - pull, BIRTH_MIN_FRACTION * target));
                        growing = true;
                    }
                }
                let to = self.con_rest[cu];
                self.note_growth_lerp(e as i32, was, to);
                self.con_rest[cu] = was;
                c = self.con_next_for_edge[cu];
            }
            if growing {
                self.grow_edges[m] = e as i32;
                m += 1;
            }
        }
        self.grow_edge_n = m;
        m = 0;
        for k in 0..self.grow_tri_n {
            let id = self.grow_tris[k] as usize;
            let target = self.tri_grow_target[id];
            let w = self.constraint_blocks.as_ref().unwrap().triangle_slot[id];
            if w < 0 || !self.baked.tri_alive[id] {
                self.tri_grow_target[id] = 0.0;
                continue;
            }
            let step = target / BIRTH_GROW_TICKS as f32;
            let r = self.tri_rest_area2[w as usize];
            let next = if r < target { minf(r + step, target) } else { maxf(r - step, target) };
            self.note_growth_lerp_tri(id as i32, r, next);
            if next == target {
                self.tri_grow_target[id] = 0.0;
            } else {
                self.grow_tris[m] = id as i32;
                m += 1;
            }
        }
        self.grow_tri_n = m;
    }

    fn note_growth_lerp(&mut self, e: i32, from: f32, to: f32) {
        if self.grow_lerp_n == self.grow_lerp_edge.len() {
            let cap = self.grow_lerp_n * 2;
            self.grow_lerp_edge.resize(cap, 0);
            self.grow_lerp_from.resize(cap, 0.0);
            self.grow_lerp_to.resize(cap, 0.0);
        }
        self.grow_lerp_edge[self.grow_lerp_n] = e;
        self.grow_lerp_from[self.grow_lerp_n] = from;
        self.grow_lerp_to[self.grow_lerp_n] = to;
        self.grow_lerp_n += 1;
    }

    fn note_growth_lerp_tri(&mut self, id: i32, from: f32, to: f32) {
        if self.grow_lerp_tri_n == self.grow_lerp_tri.len() {
            let cap = self.grow_lerp_tri_n * 2;
            self.grow_lerp_tri.resize(cap, 0);
            self.grow_lerp_tri_from.resize(cap, 0.0);
            self.grow_lerp_tri_to.resize(cap, 0.0);
        }
        self.grow_lerp_tri[self.grow_lerp_tri_n] = id;
        self.grow_lerp_tri_from[self.grow_lerp_tri_n] = from;
        self.grow_lerp_tri_to[self.grow_lerp_tri_n] = to;
        self.grow_lerp_tri_n += 1;
    }

    /// `applyGrowthLerp`: подшаг [step] из [count] — молодые связи и треугольники на долю (step+1)/count пути.
    pub fn apply_growth_lerp(&mut self, step: i32, count: i32) {
        if self.grow_lerp_n == 0 && self.grow_lerp_tri_n == 0 {
            return;
        }
        let last = step == count - 1;
        let f = (step + 1) as f32 / count as f32;
        let mut k = 0usize;
        while k < self.grow_lerp_n {
            let e = self.grow_lerp_edge[k];
            let mut c = if self.baked.edge_dead[e as usize] { -1 } else { self.con_of_edge[e as usize] };
            while k < self.grow_lerp_n && self.grow_lerp_edge[k] == e {
                if c >= 0 {
                    let from = self.grow_lerp_from[k];
                    let to = self.grow_lerp_to[k];
                    self.con_rest[c as usize] = if last { to } else { from + (to - from) * f };
                    c = self.con_next_for_edge[c as usize];
                }
                k += 1;
            }
        }
        if let Some(cb) = self.constraint_blocks.as_ref() {
            for q in 0..self.grow_lerp_tri_n {
                let id = self.grow_lerp_tri[q] as usize;
                let w = cb.triangle_slot[id];
                if w < 0 || w as usize >= self.tri_count || !self.baked.tri_alive[id] {
                    continue;
                }
                let from = self.grow_lerp_tri_from[q];
                let to = self.grow_lerp_tri_to[q];
                self.tri_rest_area2[w as usize] = if last { to } else { from + (to - from) * f };
            }
        }
        if last {
            self.grow_lerp_n = 0;
            self.grow_lerp_tri_n = 0;
        }
    }

    /// `restPoseOfBone` (BodyAppend.kt): поза покоя кости по описанию.
    pub fn rest_pose_of_bone(&mut self, b: usize) {
        rest_pose_from_definition(&self.body, &self.rigid_bones[b], &mut self.bone_rest_qx[b], &mut self.bone_rest_qy[b]);
    }

    /// `restPoseOfBornBone`: поза кости по рождённым клеткам; целиком рождённая — по описанию.
    pub fn rest_pose_of_born_bone(&mut self, b: usize) {
        let ids = &self.rigid_bones[b];
        let mut born_count = 0usize;
        for &i in ids {
            if !self.cell_unborn[i as usize] && self.bone_of[i as usize] == b as i32 {
                born_count += 1;
            }
        }
        if born_count == ids.len() {
            self.rest_pose_of_bone(b);
            return;
        }
        let qx = &mut self.bone_rest_qx[b];
        let qy = &mut self.bone_rest_qy[b];
        qx.fill(0.0);
        qy.fill(0.0);
        if born_count == 0 {
            return;
        }
        let mut cx = 0.0f32;
        let mut cy = 0.0f32;
        for &i in ids {
            let i = i as usize;
            if !self.cell_unborn[i] && self.bone_of[i] == b as i32 {
                cx += self.body.x[i];
                cy += self.body.y[i];
            }
        }
        cx /= born_count as f32;
        cy /= born_count as f32;
        for k in 0..ids.len() {
            let i = ids[k] as usize;
            if !self.cell_unborn[i] && self.bone_of[i] == b as i32 {
                qx[k] = self.body.x[i] - cx;
                qy[k] = self.body.y[i] - cy;
            }
        }
    }

    /// `restMatchWeight`: вес клетки в позе кости без захвата мышью (0 у кости, которую клиент ещё не включил).
    #[inline]
    pub fn rest_match_weight(&self, i: usize) -> f32 {
        if self.bone_cluster_of[i] >= 0 && self.bone_of[i] != self.bone_cluster_of[i] {
            0.0
        } else {
            1.0
        }
    }

    /// `activateBone`: включить кость клетки [i]; false — отказ (клиент повторит).
    pub fn activate_bone(&mut self, i: usize) -> bool {
        let b = self.bone_cluster_of[i];
        if b < 0 || self.cell_dead[i] {
            return false;
        }
        if self.bone_of[i] == b {
            return true;
        }
        if self.is_growing_newborn(i) {
            return false;
        }
        self.bone_of[i] = b;
        if self.drag_id != i as i32 {
            self.match_weight[i] = 1.0;
        }
        for q in self.baked.vert_start[i]..self.baked.vert_start[i + 1] {
            let q = q as usize;
            let j = self.baked.vert_other[q];
            self.baked.touch(j);
            if self.bone_of[j as usize] != b {
                continue;
            }
            let mut c = self.con_of_edge[self.baked.vert_edge[q] as usize];
            while c >= 0 {
                let cu = c as usize;
                if !self.con_dead[cu] {
                    self.con_dead[cu] = true;
                    if let Some(cb) = self.constraint_blocks.as_mut() {
                        cb.links.remove(c);
                    }
                }
                c = self.con_next_for_edge[cu];
            }
        }
        self.rest_pose_of_current_bone(b as usize);
        self.refresh_cell_scale_ring(i);
        self.baked.touch(i as i32);
        if let Some(ct) = self.contacts.as_mut() {
            ct.invalidate_static();
        }
        self.sleep.request_wake(i as i32);
        self.refresh_surface();
        self.structure_version = self.structure_version.wrapping_add(1);
        true
    }

    /// `restPoseOfCurrentBone`: поза кости — нынешнее положение её включённых живых клеток.
    fn rest_pose_of_current_bone(&mut self, b: usize) {
        let ids = &self.rigid_bones[b];
        let qx = &mut self.bone_rest_qx[b];
        let qy = &mut self.bone_rest_qy[b];
        qx.fill(0.0);
        qy.fill(0.0);
        let mut cx = 0.0f32;
        let mut cy = 0.0f32;
        let mut count = 0i32;
        for &i in ids {
            let i = i as usize;
            if self.bone_of[i] == b as i32 && !self.cell_dead[i] {
                cx += self.px[i];
                cy += self.py[i];
                count += 1;
            }
        }
        if count == 0 {
            return;
        }
        cx /= count as f32;
        cy /= count as f32;
        for k in 0..ids.len() {
            let i = ids[k] as usize;
            if self.bone_of[i] == b as i32 && !self.cell_dead[i] {
                qx[k] = self.px[i] - cx;
                qy[k] = self.py[i] - cy;
            }
        }
    }

    /// `markGrowingCells`: метки неуязвимости делящихся — концы дорастающих рёбер; затем мягкий старт касаний.
    pub fn mark_growing_cells(&mut self) {
        self.grow_stamp_now = self.grow_stamp_now.wrapping_add(1);
        if self.grow_stamp.len() != self.n {
            self.grow_stamp = vec![0; self.n];
        }
        let now = self.grow_stamp_now;
        for k in 0..self.grow_edge_n {
            let e = self.grow_edges[k] as usize;
            let (u, v) = (self.baked.edge_u(e) as usize, self.baked.edge_v(e) as usize);
            self.grow_stamp[u] = now;
            self.grow_stamp[v] = now;
        }
        if let Some(ct) = self.contacts.as_mut() {
            ct.immune_now = now;
        }
        self.ramp_newborn_contacts();
    }

    /// `rampNewbornContacts`: расстояние упора касаний новорождённой — доля age/BIRTH_GROW_TICKS.
    fn ramp_newborn_contacts(&mut self) {
        if self.contact_ramp.len() != self.n {
            self.contact_ramp = vec![1.0; self.n];
        }
        for k in 0..self.ramp_n {
            let v = self.ramp_cells[k] as usize;
            if v < self.n {
                self.contact_ramp[v] = 1.0;
            }
        }
        self.ramp_n = 0;
        for k in 0..self.grow_edge_n {
            let e = self.grow_edges[k] as usize;
            if self.baked.edge_dead[e] {
                continue;
            }
            let c = self.con_of_edge[e];
            if c < 0 || self.con_grow_target[c as usize] <= 0.0 {
                continue;
            }
            let f = self.con_grow_age[c as usize] as f32 / BIRTH_GROW_TICKS as f32;
            let (u, v) = (self.baked.edge_u(e) as usize, self.baked.edge_v(e) as usize);
            self.ramp_if_newborn(u, f);
            self.ramp_if_newborn(v, f);
        }
        let on = self.ramp_n > 0;
        if let Some(ct) = self.contacts.as_mut() {
            ct.ramp_on = on;
        }
    }

    fn ramp_if_newborn(&mut self, v: usize, f: f32) {
        if f >= self.contact_ramp[v] || !self.is_growing_newborn(v) {
            return;
        }
        if self.contact_ramp[v] == 1.0 {
            if self.ramp_n == self.ramp_cells.len() {
                let cap = self.ramp_n * 2;
                self.ramp_cells.resize(cap, 0);
            }
            self.ramp_cells[self.ramp_n] = v as i32;
            self.ramp_n += 1;
        }
        self.contact_ramp[v] = f;
    }

    /// `isGrowingNewborn`: у клетки дорастают ВСЕ живые связи.
    pub fn is_growing_newborn(&self, v: usize) -> bool {
        let mut any = false;
        for s in self.baked.vert_start[v]..self.baked.vert_start[v + 1] {
            let e = self.baked.vert_edge[s as usize] as usize;
            if self.baked.edge_dead[e] {
                continue;
            }
            let c = self.con_of_edge[e];
            if c < 0 || c as usize >= self.con_grow_target.len() || self.con_grow_target[c as usize] <= 0.0 {
                return false;
            }
            any = true;
        }
        any
    }

    /// `divisionPlacement`: точка рождения у родителя — на DIVISION_START_FRACTION пути к точке позы.
    pub fn division_placement(&self, i: usize, parent: usize, out: &mut [f32; 4]) {
        self.birth_placement(i, out, parent as i32);
        if !self.linked_in_layout(i, parent) {
            return;
        }
        let f = DIVISION_START_FRACTION;
        out[0] = self.px[parent] + (out[0] - self.px[parent]) * f;
        out[1] = self.py[parent] + (out[1] - self.py[parent]) * f;
        out[2] = self.vx[parent];
        out[3] = self.vy[parent];
    }

    fn linked_in_layout(&self, i: usize, j: usize) -> bool {
        let vs = &self.baked.vert_start;
        let vo = &self.baked.vert_other;
        for s in vs[i]..vs[i + 1] {
            let s = s as usize;
            if vo[s] == j as i32 && !self.is_temp_edge(self.baked.vert_edge[s] as usize) {
                return true;
            }
        }
        false
    }

    /// `birthPlacement`: где родиться клетке [i] — подгонка поворотом с масштабом по рождённым соседям.
    pub fn birth_placement(&self, i: usize, out: &mut [f32; 4], seed: i32) {
        let vs = &self.baked.vert_start;
        let vo = &self.baked.vert_other;
        let mut ref_ = [0i32; BIRTH_REF_CAP];
        let mut ref_n = 0usize;
        let add_ref = |u: i32, ref_: &mut [i32; BIRTH_REF_CAP], ref_n: &mut usize| {
            if self.cell_dead[u as usize] || u as usize == i {
                return;
            }
            for k in 0..*ref_n {
                if ref_[k] == u {
                    return;
                }
            }
            if *ref_n < BIRTH_REF_CAP {
                ref_[*ref_n] = u;
                *ref_n += 1;
            }
        };
        for s in vs[i]..vs[i + 1] {
            add_ref(vo[s as usize], &mut ref_, &mut ref_n);
        }
        let own = ref_n;
        if ref_n < 2 {
            for k in 0..own {
                let u = ref_[k] as usize;
                for s in vs[u]..vs[u + 1] {
                    add_ref(vo[s as usize], &mut ref_, &mut ref_n);
                }
            }
        }
        if ref_n == 0 && seed >= 0 && !self.cell_dead[seed as usize] {
            add_ref(seed, &mut ref_, &mut ref_n);
            let su = seed as usize;
            for s in vs[su]..vs[su + 1] {
                add_ref(vo[s as usize], &mut ref_, &mut ref_n);
            }
        }
        if ref_n == 0 {
            out[2] = 0.0;
            out[3] = 0.0;
            self.free_spot(i, self.body.x[i], self.body.y[i], out);
            return;
        }
        let (mut rx, mut ry, mut cx, mut cy, mut mvx, mut mvy) = (0.0f32, 0.0f32, 0.0f32, 0.0f32, 0.0f32, 0.0f32);
        for k in 0..ref_n {
            let u = ref_[k] as usize;
            rx += self.body.x[u];
            ry += self.body.y[u];
            cx += self.px[u];
            cy += self.py[u];
            mvx += self.vx[u];
            mvy += self.vy[u];
        }
        let rn = ref_n as f32;
        rx /= rn;
        ry /= rn;
        cx /= rn;
        cy /= rn;
        mvx /= rn;
        mvy /= rn;
        let (mut dot, mut cross, mut aa) = (0.0f32, 0.0f32, 0.0f32);
        for k in 0..ref_n {
            let u = ref_[k] as usize;
            let ax = self.body.x[u] - rx;
            let ay = self.body.y[u] - ry;
            let bx = self.px[u] - cx;
            let by = self.py[u] - cy;
            dot += ax * bx + ay * by;
            cross += ax * by - ay * bx;
            aa += ax * ax + ay * ay;
        }
        let fit = aa > 1e-30f32 && dot * dot + cross * cross > 1e-60f32;
        let cos = if fit { dot / aa } else { 1.0 };
        let sin = if fit { cross / aa } else { 0.0 };
        let ox = self.body.x[i] - rx;
        let oy = self.body.y[i] - ry;
        out[2] = mvx;
        out[3] = mvy;
        self.free_spot(i, cx + cos * ox - sin * oy, cy + sin * ox + cos * oy, out);
    }

    /// `freeSpot`: свободное место у точки позы — выталкивание из зон смерти соседей 2–3 колец по рёбрам тела.
    fn free_spot(&self, i: usize, x0: f32, y0: f32, out: &mut [f32; 4]) {
        let vs = &self.baked.vert_start;
        let vo = &self.baked.vert_other;
        let mut near = [0i32; BIRTH_NEAR_CAP];
        let mut count = 1usize;
        near[0] = i as i32;
        let mut ring_start = 0usize;
        let mut obstacle_start = count;
        for ring in 1..=BIRTH_SEARCH_RINGS {
            let ring_end = count;
            if ring == 2 {
                obstacle_start = ring_end;
            }
            for k in ring_start..ring_end {
                let v = near[k] as usize;
                for s in vs[v]..vs[v + 1] {
                    let u = vo[s as usize];
                    let mut seen = false;
                    for q in 0..count {
                        if near[q] == u {
                            seen = true;
                            break;
                        }
                    }
                    if !seen && count < BIRTH_NEAR_CAP {
                        near[count] = u;
                        count += 1;
                    }
                }
            }
            ring_start = ring_end;
        }
        let mut x = x0;
        let mut y = y0;
        for _iter in 0..BIRTH_PUSH_ITERATIONS {
            let mut moved = false;
            for k in obstacle_start..count {
                let j = near[k] as usize;
                if self.cell_dead[j] {
                    continue;
                }
                let rdx = self.body.x[i] - self.body.x[j];
                let rdy = self.body.y[i] - self.body.y[j];
                let rest = sqrtf(rdx * rdx + rdy * rdy);
                let clear = minf(rest, BIRTH_CLEAR * maxf(self.radius[i], self.radius[j]));
                let dx = x - self.px[j];
                let dy = y - self.py[j];
                let d2 = dx * dx + dy * dy;
                if d2 >= clear * clear {
                    continue;
                }
                let d = sqrtf(d2);
                if d > 1e-12f32 * clear {
                    x = self.px[j] + dx / d * clear;
                    y = self.py[j] + dy / d * clear;
                } else if rest > 1e-12f32 {
                    x = self.px[j] + rdx / rest * clear;
                    y = self.py[j] + rdy / rest * clear;
                } else {
                    x = self.px[j] + clear;
                }
                moved = true;
            }
            if !moved {
                break;
            }
        }
        out[0] = x;
        out[1] = y;
    }
}
