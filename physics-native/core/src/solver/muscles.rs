//! Привод мышц — `solver/MuscleDriver.kt`: мышцы кластерами (стенд), мышцы по клетке (игра) с кольцом соседей,
//! плавность по подшагам, мягкость ткани. Путь одного потока (ParallelMuscles в Kotlin даёт те же биты; меняется
//! только порядок записей плавности, а они друг от друга не зависят).

use crate::jmath::minf;
use crate::params::CELL_SCALE_SNAP;
use crate::solver::fiber::{link_fiber_c2_at, triangle_fiber_at};
use crate::state::BodyState;

/// Записей плавности, с которых подшаговое доведение множителей идёт по воркерам (`LERP_PARALLEL_MIN`, MuscleDriver.kt).
const LERP_PARALLEL_MIN: usize = 4096;
/// Записей в куске воркера (`ParallelMuscles.LERP_CHUNK`).
const LERP_CHUNK: usize = 2048;

/// `strongerScale`: сильнейшая мышца из двух — множитель дальше от 1, при равном удалении — меньший.
#[inline(always)]
pub fn stronger_scale(a: f32, b: f32) -> f32 {
    let da = (a - 1.0f32).abs();
    let db = (b - 1.0f32).abs();
    if da > db || (da == db && a <= b) {
        a
    } else {
        b
    }
}

/// `weakerScale`: слабейшая мышца из двух — множитель ближе к 1, при равном удалении — больший.
#[inline(always)]
pub fn weaker_scale(a: f32, b: f32) -> f32 {
    if stronger_scale(a, b) == a && a != b {
        b
    } else {
        a
    }
}

/// `triangleScaleAt`: множитель треугольника по действующим множителям вершин; у треугольника с костью — 1.
#[inline(always)]
fn triangle_scale_at(t: usize, ta: &[i32], tb: &[i32], tc: &[i32], bone: &[i32], eff: &[f32]) -> f32 {
    let i0 = ta[t] as usize;
    let i1 = tb[t] as usize;
    let i2 = tc[t] as usize;
    if bone[i0] >= 0 || bone[i1] >= 0 || bone[i2] >= 0 {
        return 1.0;
    }
    weaker_scale(weaker_scale(eff[i0], eff[i1]), eff[i2])
}

/// `effectiveSourceAt`: чей множитель действует на клетку [v] — сильнейший из своего и соседей по живому треугольнику.
#[inline(always)]
fn effective_source_at(v: usize, vert_start: &[i32], vert_edge: &[i32], edge_dead: &[bool], tri_use: &[i32], vert_other: &[i32], scale: &[f32]) -> usize {
    let mut src = v;
    let mut best = scale[v];
    for q in vert_start[v]..vert_start[v + 1] {
        let q = q as usize;
        let e = vert_edge[q] as usize;
        if edge_dead[e] || tri_use[e] == 0 {
            continue;
        }
        let o = vert_other[q] as usize;
        let s = scale[o];
        if stronger_scale(best, s) != best {
            best = s;
            src = o;
        }
    }
    src
}

impl BodyState {
    pub fn muscle_of_pair(&self, i: i32, j: i32) -> i32 {
        let a = self.muscle_of[i as usize];
        let b = self.muscle_of[j as usize];
        if a == b {
            return a;
        }
        if self.muscle_touch == 0 {
            return -1;
        }
        if a >= 0 && b >= 0 {
            return if a < b { a } else { b };
        }
        if self.muscle_touch != 2 {
            return -1;
        }
        if a < 0 {
            b
        } else {
            a
        }
    }

    pub fn muscle_of_tri(&self, i0: i32, i1: i32, i2: i32) -> i32 {
        let bo = &self.bone_of;
        if bo[i0 as usize] >= 0 || bo[i1 as usize] >= 0 || bo[i2 as usize] >= 0 {
            return -1;
        }
        let a = self.muscle_of[i0 as usize];
        let b = self.muscle_of[i1 as usize];
        let c = self.muscle_of[i2 as usize];
        if a >= 0 && a == b && a == c {
            return a;
        }
        if self.muscle_touch == 0 {
            return -1;
        }
        if self.muscle_touch != 2 && (a < 0 || b < 0 || c < 0) {
            return -1;
        }
        let mut best = -1;
        let mut votes = 0;
        if a >= 0 {
            let v = 1 + (if b == a { 1 } else { 0 }) + (if c == a { 1 } else { 0 });
            if v > votes || (v == votes && a < best) {
                best = a;
                votes = v;
            }
        }
        if b >= 0 {
            let v = 1 + (if a == b { 1 } else { 0 }) + (if c == b { 1 } else { 0 });
            if v > votes || (v == votes && b < best) {
                best = b;
                votes = v;
            }
        }
        if c >= 0 {
            let v = 1 + (if a == c { 1 } else { 0 }) + (if b == c { 1 } else { 0 });
            if v > votes || (v == votes && c < best) {
                best = c;
            }
        }
        best
    }

    /// `growMuscles`: кольцо вокруг мышцы уходит в мышцу (MUSCLE_TOUCH 1). [from_tri] — первый треугольник нового тела.
    pub fn grow_muscles(&mut self, from_tri: usize) {
        if self.muscle_touch != 1 {
            return;
        }
        let core = &self.muscle_of;
        let mut grown = core.clone();
        for t in from_tri..self.body.tri_count() {
            let i0 = self.body.tri_a[t] as usize;
            let i1 = self.body.tri_b[t] as usize;
            let i2 = self.body.tri_c[t] as usize;
            let a = core[i0];
            let b = core[i1];
            let c = core[i2];
            let mut m = -1;
            if a >= 0 {
                m = a;
            }
            if b >= 0 && (m < 0 || b < m) {
                m = b;
            }
            if c >= 0 && (m < 0 || c < m) {
                m = c;
            }
            if m < 0 {
                continue;
            }
            if a < 0 && (grown[i0] < 0 || m < grown[i0]) {
                grown[i0] = m;
            }
            if b < 0 && (grown[i1] < 0 || m < grown[i1]) {
                grown[i1] = m;
            }
            if c < 0 && (grown[i2] < 0 || m < grown[i2]) {
                grown[i2] = m;
            }
        }
        self.muscle_of = grown;
    }

    #[inline]
    pub fn muscle_scale(&self, m: i32) -> f32 {
        if m < 0 {
            1.0
        } else {
            self.muscle_scale_of[m as usize]
        }
    }

    #[inline]
    fn link_cell_scale(&self, c: usize) -> f32 {
        weaker_scale(self.cell_scale_eff[self.con_a[c] as usize], self.cell_scale_eff[self.con_b[c] as usize])
    }

    #[inline]
    fn triangle_cell_scale(&self, t: usize) -> f32 {
        triangle_scale_at(t, &self.tri_a, &self.tri_b, &self.tri_c, &self.bone_of, &self.cell_scale_eff)
    }

    #[inline]
    fn link_softness(&self, c: usize) -> f32 {
        (self.cell_softness[self.con_a[c] as usize] + self.cell_softness[self.con_b[c] as usize]) * 0.5f32
    }

    #[inline]
    fn triangle_softness(&self, t: usize) -> f32 {
        (self.cell_softness[self.tri_a[t] as usize] + self.cell_softness[self.tri_b[t] as usize] + self.cell_softness[self.tri_c[t] as usize])
            * (1.0f32 / 3.0f32)
    }

    fn effective_source(&self, v: usize) -> usize {
        let b = &self.baked;
        effective_source_at(v, &b.vert_start, &b.vert_edge, &b.edge_dead, &b.tri_use, &b.vert_other, &self.cell_scale)
    }

    /// `recomputeCellScales`: множители всех клеток, связей и треугольников — после перестройки структуры.
    pub fn recompute_cell_scales(&mut self) {
        let n = self.n;
        if self.cell_scale_eff.len() != n {
            self.cell_scale_eff = vec![0.0; n];
        }
        if self.cell_scale_src.len() != n {
            self.cell_scale_src = vec![0; n];
        }
        for v in 0..n {
            let src = self.effective_source(v);
            self.cell_scale_src[v] = src as i32;
            self.cell_scale_eff[v] = self.cell_scale[src];
        }
        for c in 0..self.con_count {
            self.con_cell_scale[c] = self.link_cell_scale(c);
            self.refresh_link_fiber(c);
        }
        for t in 0..self.tri_count {
            self.tri_cell_scale[t] = self.triangle_cell_scale(t);
            self.refresh_triangle_fiber(t);
        }
        if self.softness_on {
            for c in 0..self.con_count {
                self.con_softness[c] = self.link_softness(c);
            }
            for t in 0..self.tri_count {
                self.tri_softness[t] = self.triangle_softness(t);
            }
        }
        self.cell_scales_dirty = false;
    }

    /// `refreshCellScaleRing`: множитель клетки [v] или её кольцо изменились — пересчёт по месту.
    pub fn refresh_cell_scale_ring(&mut self, v: usize) {
        if !self.cell_scales_on && !self.softness_on {
            return;
        }
        if self.cell_scale_eff.len() != self.n || self.cell_scale_src.len() != self.n || self.constraint_blocks.is_none() {
            self.cell_scales_dirty = true;
            return;
        }
        self.refresh_effective(v, true);
        for q in self.baked.vert_start[v]..self.baked.vert_start[v + 1] {
            let u = self.baked.vert_other[q as usize] as usize;
            self.refresh_effective(u, false);
        }
    }

    fn refresh_effective(&mut self, u: usize, always: bool) {
        let src = self.effective_source(u);
        let e = self.cell_scale[src];
        if e == self.cell_scale_eff[u] && (!self.fibers_on || src as i32 == self.cell_scale_src[u]) && !always {
            return;
        }
        self.cell_scale_eff[u] = e;
        self.cell_scale_src[u] = src as i32;
        self.refresh_cell_scale_around(u);
    }

    /// `refreshCellFiberRing`: волокно клетки [v] сменилось.
    pub fn refresh_cell_fiber_ring(&mut self, v: usize) {
        if !self.cell_scales_on && !self.softness_on {
            return;
        }
        if self.cell_scale_eff.len() != self.n || self.cell_scale_src.len() != self.n || self.constraint_blocks.is_none() {
            self.cell_scales_dirty = true;
            return;
        }
        self.refresh_cell_scale_around(v);
        for q in self.baked.vert_start[v]..self.baked.vert_start[v + 1] {
            let u = self.baked.vert_other[q as usize] as usize;
            self.refresh_cell_scale_around(u);
        }
    }

    /// `refreshCellScaleAround`: множители связей и треугольников у клетки [v] — по её рёбрам (без плавности).
    fn refresh_cell_scale_around(&mut self, v: usize) {
        for q in self.baked.vert_start[v]..self.baked.vert_start[v + 1] {
            let e = self.baked.vert_edge[q as usize] as usize;
            let mut c = self.con_of_edge[e];
            while c >= 0 {
                let cu = c as usize;
                self.con_cell_scale[cu] = self.link_cell_scale(cu);
                self.refresh_link_fiber(cu);
                if self.softness_on {
                    self.con_softness[cu] = self.link_softness(cu);
                }
                c = self.con_next_for_edge[cu];
            }
            for r in self.baked.edge_tri_start[e]..self.baked.edge_tri_start[e + 1] {
                let id = self.baked.edge_tris[r as usize] as usize;
                let w = self.constraint_blocks.as_ref().unwrap().triangle_slot[id];
                if w >= 0 && (w as usize) < self.tri_count {
                    let w = w as usize;
                    self.tri_cell_scale[w] = self.triangle_cell_scale(w);
                    self.refresh_triangle_fiber(w);
                    if self.softness_on {
                        self.tri_softness[w] = self.triangle_softness(w);
                    }
                }
            }
        }
    }

    /// Первый проход шага мышц: действующие множители сдвинувшихся клеток и соседей; изменившиеся — в cell_scale_changed.
    pub(crate) fn refresh_effective_of(&mut self, moved_n: usize) -> usize {
        let mut changed = std::mem::take(&mut self.cell_scale_changed);
        let mut changed_n = 0usize;
        self.refresh_effective_range(0, moved_n, &mut changed, &mut changed_n);
        self.cell_scale_changed = changed;
        changed_n
    }

    /// `refreshEffectiveOf(moved, from, to)`: сдвинувшиеся клетки [from, to) списка и их соседи; изменившиеся — в
    /// [changed] (кусок воркера — в свой буфер).
    pub(crate) fn refresh_effective_range(&mut self, from: usize, to: usize, changed: &mut Vec<i32>, changed_n: &mut usize) {
        let now = self.lerp_stamp_now;
        let fibers = self.fibers_on;
        for q in from..to {
            let v = self.cell_scale_moved[q] as usize;
            let b = &self.baked;
            let src = effective_source_at(v, &b.vert_start, &b.vert_edge, &b.edge_dead, &b.tri_use, &b.vert_other, &self.cell_scale);
            self.cell_scale_eff[v] = self.cell_scale[src];
            self.cell_scale_src[v] = src as i32;
            push_changed(changed, changed_n, v);
        }
        for q in from..to {
            let v = self.cell_scale_moved[q] as usize;
            for r in self.baked.vert_start[v]..self.baked.vert_start[v + 1] {
                let u = self.baked.vert_other[r as usize] as usize;
                if self.lerp_cell_stamp[u] == now {
                    continue;
                }
                self.lerp_cell_stamp[u] = now;
                let b = &self.baked;
                let src = effective_source_at(u, &b.vert_start, &b.vert_edge, &b.edge_dead, &b.tri_use, &b.vert_other, &self.cell_scale);
                let e = self.cell_scale[src];
                if e == self.cell_scale_eff[u] && (!fibers || src as i32 == self.cell_scale_src[u]) {
                    continue;
                }
                self.cell_scale_eff[u] = e;
                self.cell_scale_src[u] = src as i32;
                push_changed(changed, changed_n, u);
            }
        }
    }

    /// Второй проход: связи и треугольники вокруг изменившихся клеток — множитель, волокно, мягкость и «было».
    pub(crate) fn refresh_around_changed(&mut self, count: usize) {
        let list = std::mem::take(&mut self.cell_scale_changed);
        self.refresh_around_list(&list, count, None);
        self.cell_scale_changed = list;
    }

    /// `refreshAroundChanged(changed, count, out)`: «было» — в [out] (буфер куска воркера) или в общие списки.
    pub(crate) fn refresh_around_list(&mut self, list: &[i32], count: usize, mut out: Option<&mut crate::solver::muscle_rings::CellScaleLerpBuffer>) {
        let now = self.lerp_stamp_now;
        let soft = self.softness_on;
        let fibers = self.fibers_on;
        for k in 0..count {
            let v = list[k] as usize;
            for q in self.baked.vert_start[v]..self.baked.vert_start[v + 1] {
                let e = self.baked.vert_edge[q as usize] as usize;
                if self.lerp_edge_stamp[e] == now {
                    continue;
                }
                self.lerp_edge_stamp[e] = now;
                let mut c = self.con_of_edge[e];
                if c >= 0 {
                    let from = self.con_cell_scale[c as usize];
                    match out.as_deref_mut() {
                        Some(b) => b.add_edge(e as i32, from),
                        None => self.note_edge_lerp(e, from),
                    }
                    while c >= 0 {
                        let cu = c as usize;
                        self.con_cell_scale[cu] = weaker_scale(self.cell_scale_eff[self.con_a[cu] as usize], self.cell_scale_eff[self.con_b[cu] as usize]);
                        if fibers {
                            self.con_fiber_c2[cu] = link_fiber_c2_at(
                                cu,
                                &self.con_a,
                                &self.con_b,
                                &self.cell_scale_eff,
                                &self.cell_scale_src,
                                &self.cell_fiber_x,
                                &self.cell_fiber_y,
                                &self.body.x,
                                &self.body.y,
                            );
                        }
                        if soft {
                            self.con_softness[cu] = (self.cell_softness[self.con_a[cu] as usize] + self.cell_softness[self.con_b[cu] as usize]) * 0.5f32;
                        }
                        c = self.con_next_for_edge[cu];
                    }
                }
                for r in self.baked.edge_tri_start[e]..self.baked.edge_tri_start[e + 1] {
                    let id = self.baked.edge_tris[r as usize] as usize;
                    if self.lerp_tri_stamp[id] == now {
                        continue;
                    }
                    let w = self.constraint_blocks.as_ref().unwrap().triangle_slot[id];
                    if w < 0 || w as usize >= self.tri_count {
                        continue;
                    }
                    let w = w as usize;
                    self.lerp_tri_stamp[id] = now;
                    let from = self.tri_cell_scale[w];
                    match out.as_deref_mut() {
                        Some(b) => b.add_tri(id as i32, from),
                        None => self.note_tri_lerp(id, from),
                    }
                    self.tri_cell_scale[w] = triangle_scale_at(w, &self.tri_a, &self.tri_b, &self.tri_c, &self.bone_of, &self.cell_scale_eff);
                    if fibers {
                        let tid = self.tri_id[w] as usize;
                        self.tri_fiber_by_id[tid] = triangle_fiber_at(
                            w,
                            &self.tri_a,
                            &self.tri_b,
                            &self.tri_c,
                            &self.bone_of,
                            &self.cell_scale_eff,
                            &self.cell_scale_src,
                            &self.cell_fiber_x,
                            &self.cell_fiber_y,
                        );
                    }
                    if soft {
                        self.tri_softness[w] = (self.cell_softness[self.tri_a[w] as usize]
                            + self.cell_softness[self.tri_b[w] as usize]
                            + self.cell_softness[self.tri_c[w] as usize])
                            * (1.0f32 / 3.0f32);
                    }
                }
            }
        }
    }

    /// `advanceCellScales`: множители клеток — шаг к цели с темпом мышцы; кольца — после шага всех клеток.
    pub fn advance_cell_scales(&mut self, dt: f32) {
        self.ensure_fiber_size();
        let by_edges = self.constraint_blocks.is_some() && !self.cell_scales_dirty && self.cell_scale_eff.len() == self.n;
        if by_edges {
            self.begin_cell_scale_lerp();
        }
        let mut moved_n = 0usize;
        let mut moving = false;
        for v in 0..self.n {
            let t = self.cell_scale_target[v];
            let cur = self.cell_scale[v];
            if cur == t {
                continue;
            }
            let away = (t - 1.0f32).abs() > (cur - 1.0f32).abs();
            let k = minf(1.0, (if away { self.muscle_rate_contract } else { self.muscle_rate_relax }) * dt);
            let mut next = cur + (t - cur) * k;
            if (t - next).abs() < CELL_SCALE_SNAP {
                next = t;
            } else {
                moving = true;
            }
            self.cell_scale[v] = next;
            self.sleep.request_wake(v as i32);
            if !by_edges {
                continue;
            }
            self.lerp_cell_stamp[v] = self.lerp_stamp_now;
            if moved_n == self.cell_scale_moved.len() {
                let cap = moved_n * 2;
                self.cell_scale_moved.resize(cap, 0);
            }
            self.cell_scale_moved[moved_n] = v as i32;
            moved_n += 1;
        }
        self.cell_scales_moving = moving;
        if by_edges {
            // Кольца — после шага всех клеток; с воркерами — кусками по блокам (solver/muscle_rings.rs).
            if let Some(w) = self.active_workers().filter(|w| w.count > 1) {
                self.refresh_rings_parallel(&w, moved_n);
            } else {
                let changed = self.refresh_effective_of(moved_n);
                self.refresh_around_changed(changed);
            }
            self.end_cell_scale_lerp();
            return;
        }
        let lerp = self.capture_all_for_lerp();
        self.recompute_cell_scales();
        if lerp {
            self.end_cell_scale_lerp();
        }
    }

    fn capture_all_for_lerp(&mut self) -> bool {
        if self.constraint_blocks.is_none() {
            return false;
        }
        if self.con_cell_scale.len() < self.con_count || self.tri_cell_scale.len() < self.tri_count {
            return false;
        }
        self.begin_cell_scale_lerp();
        for e in 0..self.baked.edge_count {
            if !self.baked.edge_dead[e] {
                self.capture_edge_lerp(e);
            }
        }
        let slots = self.constraint_blocks.as_ref().unwrap().triangle_slot.len();
        for id in 0..self.body.tri_count().min(slots) {
            let w = self.constraint_blocks.as_ref().unwrap().triangle_slot[id];
            if w >= 0 && (w as usize) < self.tri_count {
                self.capture_tri_lerp(id, w as usize);
            }
        }
        true
    }

    fn begin_cell_scale_lerp(&mut self) {
        self.lerp_edge_n = 0;
        self.lerp_tri_n = 0;
        self.lerp_stamp_now = self.lerp_stamp_now.wrapping_add(1);
        if self.lerp_edge_stamp.len() < self.baked.edge_count {
            self.lerp_edge_stamp = vec![0; self.baked.edge_count];
        }
        if self.lerp_tri_stamp.len() < self.body.tri_count() {
            self.lerp_tri_stamp = vec![0; self.body.tri_count()];
        }
        if self.lerp_cell_stamp.len() < self.n {
            self.lerp_cell_stamp = vec![0; self.n];
        }
    }

    fn capture_edge_lerp(&mut self, e: usize) {
        if self.lerp_edge_stamp[e] == self.lerp_stamp_now {
            return;
        }
        let c = self.con_of_edge[e];
        if c < 0 {
            return;
        }
        self.lerp_edge_stamp[e] = self.lerp_stamp_now;
        let from = self.con_cell_scale[c as usize];
        self.note_edge_lerp(e, from);
    }

    fn capture_tri_lerp(&mut self, id: usize, w: usize) {
        if self.lerp_tri_stamp[id] == self.lerp_stamp_now {
            return;
        }
        self.lerp_tri_stamp[id] = self.lerp_stamp_now;
        let from = self.tri_cell_scale[w];
        self.note_tri_lerp(id, from);
    }

    fn note_edge_lerp(&mut self, e: usize, from: f32) {
        if self.lerp_edge_n == self.lerp_edge_ids.len() {
            let cap = self.lerp_edge_n * 2;
            self.lerp_edge_ids.resize(cap, 0);
            self.lerp_edge_from.resize(cap, 0.0);
            self.lerp_edge_to.resize(cap, 0.0);
        }
        self.lerp_edge_ids[self.lerp_edge_n] = e as i32;
        self.lerp_edge_from[self.lerp_edge_n] = from;
        self.lerp_edge_n += 1;
    }

    fn note_tri_lerp(&mut self, id: usize, from: f32) {
        if self.lerp_tri_n == self.lerp_tri_ids.len() {
            let cap = self.lerp_tri_n * 2;
            self.lerp_tri_ids.resize(cap, 0);
            self.lerp_tri_from.resize(cap, 0.0);
            self.lerp_tri_to.resize(cap, 0.0);
        }
        self.lerp_tri_ids[self.lerp_tri_n] = id as i32;
        self.lerp_tri_from[self.lerp_tri_n] = from;
        self.lerp_tri_n += 1;
    }

    /// Конец сбора: «станет» — нынешнее значение, действующее возвращается к «было».
    fn end_cell_scale_lerp(&mut self) {
        for k in 0..self.lerp_edge_n {
            let e = self.lerp_edge_ids[k] as usize;
            self.lerp_edge_to[k] = self.con_cell_scale[self.con_of_edge[e] as usize];
            let from = self.lerp_edge_from[k];
            self.set_edge_cell_scale(e, from);
        }
        for k in 0..self.lerp_tri_n {
            let w = self.constraint_blocks.as_ref().unwrap().triangle_slot[self.lerp_tri_ids[k] as usize] as usize;
            self.lerp_tri_to[k] = self.tri_cell_scale[w];
            self.tri_cell_scale[w] = self.lerp_tri_from[k];
        }
    }

    fn set_edge_cell_scale(&mut self, e: usize, value: f32) {
        let mut c = self.con_of_edge[e];
        while c >= 0 {
            self.con_cell_scale[c as usize] = value;
            c = self.con_next_for_edge[c as usize];
        }
    }

    /// `applyCellScaleLerp`: подшаг [step] из [count] — задетые связи и треугольники на долю (step+1)/count пути.
    pub fn apply_cell_scale_lerp(&mut self, step: i32, count: i32) {
        if self.lerp_edge_n == 0 && self.lerp_tri_n == 0 {
            return;
        }
        let last = step == count - 1;
        let f = (step + 1) as f32 / count as f32;
        // Записи независимы (своя связь или треугольник): по воркерам кусками по LERP_CHUNK, если их много
        // (ParallelMuscles.lerp; меньше LERP_PARALLEL_MIN — барьеры дороже работы).
        match self.active_workers().filter(|w| w.count > 1 && self.lerp_edge_n + self.lerp_tri_n >= LERP_PARALLEL_MIN) {
            Some(w) => {
                let edge_chunks = self.lerp_edge_n.div_ceil(LERP_CHUNK);
                let tri_chunks = self.lerp_tri_n.div_ceil(LERP_CHUNK);
                let st = crate::solver::pool::SharedMut(self as *mut BodyState);
                w.run(edge_chunks + tri_chunks, &|k, _| {
                    // SAFETY: кусок — свои записи плавности (свои связи или треугольники).
                    let s = unsafe { st.get() };
                    if k < edge_chunks {
                        s.lerp_edge_range(k * LERP_CHUNK, s.lerp_edge_n.min((k + 1) * LERP_CHUNK), f, last);
                    } else {
                        let t = k - edge_chunks;
                        s.lerp_tri_range(t * LERP_CHUNK, s.lerp_tri_n.min((t + 1) * LERP_CHUNK), f, last);
                    }
                });
            }
            None => {
                self.lerp_edge_range(0, self.lerp_edge_n, f, last);
                self.lerp_tri_range(0, self.lerp_tri_n, f, last);
            }
        }
        if last {
            self.lerp_edge_n = 0;
            self.lerp_tri_n = 0;
        }
    }

    pub fn lerp_edge_range(&mut self, from_k: usize, to_k: usize, f: f32, last: bool) {
        for k in from_k..to_k {
            let from = self.lerp_edge_from[k];
            let to = self.lerp_edge_to[k];
            let value = if last { to } else { from + (to - from) * f };
            let mut c = self.con_of_edge[self.lerp_edge_ids[k] as usize];
            while c >= 0 {
                self.con_cell_scale[c as usize] = value;
                c = self.con_next_for_edge[c as usize];
            }
        }
    }

    pub fn lerp_tri_range(&mut self, from_k: usize, to_k: usize, f: f32, last: bool) {
        let blocks = match self.constraint_blocks.as_ref() {
            Some(b) => b,
            None => return,
        };
        let slot = &blocks.triangle_slot;
        for k in from_k..to_k {
            let w = slot[self.lerp_tri_ids[k] as usize];
            if w < 0 || w as usize >= self.tri_count {
                continue;
            }
            let from = self.lerp_tri_from[k];
            let to = self.lerp_tri_to[k];
            self.tri_cell_scale[w as usize] = if last { to } else { from + (to - from) * f };
        }
    }

    /// `updateMuscleScale`: длина покоя мышцы кластера доезжает до своего значения за тик, по подшагам.
    pub fn update_muscle_scale(&mut self, f: f32) {
        let m_n = self.muscle_activation.len();
        if self.muscle_scale_of.len() != m_n {
            self.muscle_scale_of = vec![0.0; m_n];
        }
        if self.muscle_activation_prev.len() != m_n {
            self.muscle_activation_prev = vec![0.0; m_n];
        }
        for m in 0..m_n {
            let a = self.muscle_activation_prev[m] + (self.muscle_activation[m] - self.muscle_activation_prev[m]) * f;
            self.muscle_scale_of[m] = 1.0f32 - a * (1.0f32 - self.muscle_contraction);
        }
    }

    /// `updateMuscles`: активация мышц кластеров — к цели с разным темпом сокращения и расслабления.
    pub fn update_muscles(&mut self, dt: f32) {
        let m_n = self.muscle_activation.len();
        if self.muscle_activation_prev.len() != m_n {
            self.muscle_activation_prev = vec![0.0; m_n];
        }
        self.muscle_activation_prev.copy_from_slice(&self.muscle_activation);
        for m in 0..m_n {
            let target = self.muscle_target[m];
            let rate = if target > self.muscle_activation[m] { self.muscle_rate_contract } else { self.muscle_rate_relax };
            let mut k = rate * dt;
            if k > 1.0 {
                k = 1.0;
            }
            self.muscle_activation[m] += (target - self.muscle_activation[m]) * k;
        }
    }
}

#[inline]
fn push_changed(list: &mut Vec<i32>, n: &mut usize, v: usize) {
    if *n == list.len() {
        let cap = (*n * 2).max(16);
        list.resize(cap, 0);
    }
    list[*n] = v as i32;
    *n += 1;
}
