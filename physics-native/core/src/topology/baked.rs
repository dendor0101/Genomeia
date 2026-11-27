//! Запечённая топология целого тела и что в ней ещё живо — `topology/BakedTopology.kt`.
//!
//! Рёбра — уникальные пары связей, отсортированные ключом (меньший номер << 32 | больший).
//! Описание тела здесь не хранится: методы, которым оно нужно, получают его параметром
//! (в Kotlin это поле `body`, общее с BodyState, — та же единственная копия).

use crate::definition::BodyDefinition;
use crate::jmath::{coerce_inf, sqrtf, strict_acos};

#[derive(Clone, Debug, Default)]
pub struct BakedTopology {
    pub edge_count: usize,
    pub link_edge: Vec<i32>,
    edge_keys: Vec<i64>,
    pub tri_edge: Vec<i32>,
    pub edge_tri_start: Vec<i32>,
    pub edge_tris: Vec<i32>,
    use_init: Vec<i32>,
    pub vert_start: Vec<i32>,
    pub vert_other: Vec<i32>,
    pub vert_edge: Vec<i32>,
    pub edge_dead: Vec<bool>,
    pub edge_unborn: Vec<bool>,
    pub tri_unborn: Vec<bool>,
    pub tri_alive: Vec<bool>,
    pub inverted_ticks: Vec<i8>,
    pub triangle_revision: i64,
    pub live_triangle_count: i32,
    pub tri_use: Vec<i32>,
    pub dirty_vertices: Vec<i32>,
    dirty: Vec<bool>,
    pub dirty_count: usize,
    pub vertex_triangles: Vec<i32>,
    pub vertex_tri_start: Vec<i32>,
    rest_angles: Vec<f32>,
    pub bare_edges: i32,
}

#[inline]
fn key(a: i32, b: i32) -> i64 {
    ((a.min(b) as i64) << 32) | (a.max(b) as i64)
}

fn edge_index(edge_keys: &[i64], a: i32, b: i32) -> i32 {
    if a == b {
        return -1;
    }
    match edge_keys.binary_search(&key(a, b)) {
        Ok(i) => i as i32,
        Err(_) => -1,
    }
}

/// Уникальные ключи в порядке возрастания (`uniq.sort()` + сжатие повторов).
fn sorted_unique(mut keys: Vec<i64>) -> Vec<i64> {
    keys.sort_unstable();
    keys.dedup();
    keys
}

impl BakedTopology {
    pub fn new(body: &BodyDefinition) -> BakedTopology {
        let mut t = BakedTopology {
            edge_tri_start: vec![0; 1],
            vert_start: vec![0; 1],
            vertex_tri_start: vec![0; 1],
            inverted_ticks: vec![0; body.tri_count()],
            live_triangle_count: body.tri_count() as i32,
            dirty_vertices: vec![0; body.count()],
            dirty: vec![false; body.count()],
            ..Default::default()
        };
        t.build_all(body);
        t
    }

    /// Пустая оболочка под уплотнение (`BakedTopology(body, build = false)`).
    pub fn empty_for(body: &BodyDefinition) -> BakedTopology {
        BakedTopology {
            edge_tri_start: vec![0; 1],
            vert_start: vec![0; 1],
            vertex_tri_start: vec![0; 1],
            inverted_ticks: vec![0; body.tri_count()],
            live_triangle_count: body.tri_count() as i32,
            dirty_vertices: vec![0; body.count()],
            dirty: vec![false; body.count()],
            ..Default::default()
        }
    }

    #[inline]
    pub fn touch(&mut self, v: i32) {
        let vu = v as usize;
        if self.dirty[vu] {
            return;
        }
        self.dirty[vu] = true;
        self.dirty_vertices[self.dirty_count] = v;
        self.dirty_count += 1;
    }

    pub fn clear_dirty(&mut self) {
        for k in 0..self.dirty_count {
            let v = self.dirty_vertices[k] as usize;
            self.dirty[v] = false;
        }
        self.dirty_count = 0;
    }

    pub fn surface_angle(&self, v: usize) -> f32 {
        let mut angle = 0.0f32;
        for s in self.vertex_tri_start[v]..self.vertex_tri_start[v + 1] {
            let corner = self.vertex_triangles[s as usize];
            if self.tri_alive[(corner / 3) as usize] {
                angle += self.rest_angles[corner as usize];
            }
        }
        angle
    }

    pub fn opposite(&self, e: usize, body: &BodyDefinition) -> i32 {
        if self.tri_use[e] != 1 {
            return -1;
        }
        let t = self.live_tri(e);
        if t < 0 {
            return -1;
        }
        let u = self.edge_u(e);
        let v = self.edge_v(e);
        let t = t as usize;
        let a = body.tri_a[t];
        let b = body.tri_b[t];
        let c = body.tri_c[t];
        if a != u && a != v {
            a
        } else if b != u && b != v {
            b
        } else {
            c
        }
    }

    fn build_all(&mut self, body: &BodyDefinition) {
        let n = body.count();
        let lc = body.link_count();
        let keys: Vec<i64> = (0..lc).map(|i| key(body.link_a[i], body.link_b[i])).collect();
        let uniq = sorted_unique(keys.clone());
        let m = uniq.len();
        self.edge_count = m;
        self.edge_keys = uniq;
        self.link_edge = keys.iter().map(|k| self.edge_keys.binary_search(k).expect("ребро связи") as i32).collect();

        let tc = body.tri_count();
        self.tri_edge = vec![0; tc * 3];
        for t in 0..tc {
            let a = body.tri_a[t];
            let b = body.tri_b[t];
            let c = body.tri_c[t];
            self.tri_edge[3 * t] = edge_index(&self.edge_keys, a, b);
            self.tri_edge[3 * t + 1] = edge_index(&self.edge_keys, b, c);
            self.tri_edge[3 * t + 2] = edge_index(&self.edge_keys, c, a);
        }
        self.use_init = vec![0; m];
        for &e in &self.tri_edge {
            if e >= 0 {
                self.use_init[e as usize] += 1;
            }
        }
        self.edge_tri_start = vec![0; m + 1];
        for e in 0..m {
            self.edge_tri_start[e + 1] = self.edge_tri_start[e] + self.use_init[e];
        }
        self.edge_tris = vec![0; self.edge_tri_start[m] as usize];
        {
            let mut fill = self.edge_tri_start.clone();
            for s in 0..self.tri_edge.len() {
                let e = self.tri_edge[s];
                if e >= 0 {
                    self.edge_tris[fill[e as usize] as usize] = (s / 3) as i32;
                    fill[e as usize] += 1;
                }
            }
        }

        let mut deg = vec![0i32; n];
        for e in 0..m {
            deg[(self.edge_keys[e] as u64 >> 32) as usize] += 1;
            deg[self.edge_keys[e] as i32 as usize] += 1;
        }
        self.vert_start = vec![0; n + 1];
        for i in 0..n {
            self.vert_start[i + 1] = self.vert_start[i] + deg[i];
        }
        self.vert_other = vec![0; self.vert_start[n] as usize];
        self.vert_edge = vec![0; self.vert_start[n] as usize];
        {
            let mut fill = self.vert_start.clone();
            for e in 0..m {
                let u = (self.edge_keys[e] as u64 >> 32) as usize;
                let v = self.edge_keys[e] as i32 as usize;
                self.vert_other[fill[u] as usize] = v as i32;
                self.vert_edge[fill[u] as usize] = e as i32;
                fill[u] += 1;
                self.vert_other[fill[v] as usize] = u as i32;
                self.vert_edge[fill[v] as usize] = e as i32;
                fill[v] += 1;
            }
        }

        self.vertex_tri_start = vec![0; n + 1];
        for t in 0..tc {
            self.vertex_tri_start[body.tri_a[t] as usize + 1] += 1;
            self.vertex_tri_start[body.tri_b[t] as usize + 1] += 1;
            self.vertex_tri_start[body.tri_c[t] as usize + 1] += 1;
        }
        for v in 0..n {
            self.vertex_tri_start[v + 1] += self.vertex_tri_start[v];
        }
        self.vertex_triangles = vec![0; tc * 3];
        self.rest_angles = vec![0.0; tc * 3];
        let mut fill_tri = self.vertex_tri_start.clone();
        for t in 0..tc {
            let a = body.tri_a[t];
            let b = body.tri_b[t];
            let c = body.tri_c[t];
            self.vertex_triangles[fill_tri[a as usize] as usize] = (3 * t) as i32;
            fill_tri[a as usize] += 1;
            self.vertex_triangles[fill_tri[b as usize] as usize] = (3 * t + 1) as i32;
            fill_tri[b as usize] += 1;
            self.vertex_triangles[fill_tri[c as usize] as usize] = (3 * t + 2) as i32;
            fill_tri[c as usize] += 1;
            self.rest_angles[3 * t] = angle(body, a, b, c);
            self.rest_angles[3 * t + 1] = angle(body, b, a, c);
            self.rest_angles[3 * t + 2] = angle(body, c, a, b);
        }

        self.edge_dead = vec![false; m];
        self.tri_alive = vec![true; tc];
        self.tri_use = self.use_init.clone();
        for e in 0..self.tri_use.len() {
            if self.tri_use[e] == 0 {
                self.bare_edges += 1;
            }
        }
    }

    /// Дописать тело (`append`): его рёбра идут после прежних, прежние номера не меняются.
    pub fn append(&mut self, combined: &BodyDefinition, n0: usize, l0: usize, t0: usize) {
        let n = combined.count();
        let lc = combined.link_count();
        let tc = combined.tri_count();
        let keys: Vec<i64> = (0..lc - l0).map(|it| key(combined.link_a[l0 + it], combined.link_b[l0 + it])).collect();
        let uniq = sorted_unique(keys.clone());
        let m1 = uniq.len();
        let m0 = self.edge_count;
        let m = m0 + m1;
        assert!(m1 == 0 || m0 == 0 || uniq[0] > self.edge_keys[m0 - 1], "новое тело обязано идти после прежних");
        self.edge_keys.truncate(m0);
        self.edge_keys.extend_from_slice(&uniq);
        self.edge_count = m;
        self.link_edge.resize(lc, 0);
        for k in l0..lc {
            self.link_edge[k] = (m0 + uniq.binary_search(&keys[k - l0]).expect("ребро")) as i32;
        }

        self.tri_edge.resize(tc * 3, 0);
        for t in t0..tc {
            let a = combined.tri_a[t];
            let b = combined.tri_b[t];
            let c = combined.tri_c[t];
            self.tri_edge[3 * t] = edge_index(&self.edge_keys, a, b);
            self.tri_edge[3 * t + 1] = edge_index(&self.edge_keys, b, c);
            self.tri_edge[3 * t + 2] = edge_index(&self.edge_keys, c, a);
        }
        self.use_init.resize(m, 0);
        for q in 3 * t0..3 * tc {
            let e = self.tri_edge[q];
            if e >= 0 {
                self.use_init[e as usize] += 1;
            }
        }
        self.edge_tri_start.resize(m + 1, 0);
        for e in m0..m {
            self.edge_tri_start[e + 1] = self.edge_tri_start[e] + self.use_init[e];
        }
        self.edge_tris.resize(self.edge_tri_start[m] as usize, 0);
        {
            let mut fill = self.edge_tri_start.clone();
            for q in 3 * t0..3 * tc {
                let e = self.tri_edge[q];
                if e >= 0 {
                    self.edge_tris[fill[e as usize] as usize] = (q / 3) as i32;
                    fill[e as usize] += 1;
                }
            }
        }

        let mut deg = vec![0i32; n - n0];
        for e in m0..m {
            deg[(self.edge_keys[e] as u64 >> 32) as usize - n0] += 1;
            deg[self.edge_keys[e] as i32 as usize - n0] += 1;
        }
        self.vert_start.resize(n + 1, 0);
        for i in n0..n {
            self.vert_start[i + 1] = self.vert_start[i] + deg[i - n0];
        }
        self.vert_other.resize(self.vert_start[n] as usize, 0);
        self.vert_edge.resize(self.vert_start[n] as usize, 0);
        {
            let mut fill = self.vert_start.clone();
            for e in m0..m {
                let u = (self.edge_keys[e] as u64 >> 32) as usize;
                let v = self.edge_keys[e] as i32 as usize;
                self.vert_other[fill[u] as usize] = v as i32;
                self.vert_edge[fill[u] as usize] = e as i32;
                fill[u] += 1;
                self.vert_other[fill[v] as usize] = u as i32;
                self.vert_edge[fill[v] as usize] = e as i32;
                fill[v] += 1;
            }
        }

        self.vertex_tri_start.resize(n + 1, 0);
        for t in t0..tc {
            self.vertex_tri_start[combined.tri_a[t] as usize + 1] += 1;
            self.vertex_tri_start[combined.tri_b[t] as usize + 1] += 1;
            self.vertex_tri_start[combined.tri_c[t] as usize + 1] += 1;
        }
        for v in n0..n {
            self.vertex_tri_start[v + 1] += self.vertex_tri_start[v];
        }
        self.vertex_triangles.resize(tc * 3, 0);
        self.rest_angles.resize(tc * 3, 0.0);
        {
            let mut fill_tri = self.vertex_tri_start.clone();
            for t in t0..tc {
                let a = combined.tri_a[t];
                let b = combined.tri_b[t];
                let c = combined.tri_c[t];
                self.vertex_triangles[fill_tri[a as usize] as usize] = (3 * t) as i32;
                fill_tri[a as usize] += 1;
                self.vertex_triangles[fill_tri[b as usize] as usize] = (3 * t + 1) as i32;
                fill_tri[b as usize] += 1;
                self.vertex_triangles[fill_tri[c as usize] as usize] = (3 * t + 2) as i32;
                fill_tri[c as usize] += 1;
                self.rest_angles[3 * t] = angle(combined, a, b, c);
                self.rest_angles[3 * t + 1] = angle(combined, b, a, c);
                self.rest_angles[3 * t + 2] = angle(combined, c, a, b);
            }
        }

        self.edge_dead.resize(m, false);
        self.edge_unborn.resize(m, false);
        self.tri_unborn.resize(tc, false);
        self.tri_alive.resize(tc, false);
        for t in t0..tc {
            self.tri_alive[t] = true;
        }
        self.inverted_ticks.resize(tc, 0);
        self.live_triangle_count += (tc - t0) as i32;
        self.tri_use.resize(m, 0);
        for e in m0..m {
            self.tri_use[e] = self.use_init[e];
            if self.tri_use[e] == 0 {
                self.bare_edges += 1;
            }
        }
        self.dirty_vertices.resize(n, 0);
        self.dirty.resize(n, false);
    }

    pub fn mark_unborn(&mut self, e: usize, body: &BodyDefinition) {
        if self.edge_unborn.len() < self.edge_count {
            self.edge_unborn.resize(self.edge_count, false);
        }
        if self.tri_unborn.len() < body.tri_count() {
            self.tri_unborn.resize(body.tri_count(), false);
        }
        if self.edge_dead[e] {
            return;
        }
        for s in self.edge_tri_start[e]..self.edge_tri_start[e + 1] {
            let t = self.edge_tris[s as usize] as usize;
            if self.tri_alive[t] {
                self.tri_unborn[t] = true;
            }
        }
        self.kill(e, body);
        self.edge_unborn[e] = true;
    }

    #[inline]
    pub fn is_unborn(&self, e: usize) -> bool {
        e < self.edge_unborn.len() && self.edge_unborn[e]
    }
    #[inline]
    pub fn is_tri_unborn(&self, t: usize) -> bool {
        t < self.tri_unborn.len() && self.tri_unborn[t]
    }
    pub fn forget_unborn(&mut self, e: usize) {
        if self.is_unborn(e) {
            self.edge_unborn[e] = false;
        }
    }
    pub fn forget_tri_unborn(&mut self, t: usize) {
        if self.is_tri_unborn(t) {
            self.tri_unborn[t] = false;
        }
    }

    pub fn revive(&mut self, e: usize) -> bool {
        if e >= self.edge_unborn.len() || !self.edge_unborn[e] {
            return false;
        }
        self.edge_unborn[e] = false;
        self.edge_dead[e] = false;
        if self.tri_use[e] == 0 {
            self.bare_edges += 1;
        }
        let (u, v) = (self.edge_u(e), self.edge_v(e));
        self.touch(u);
        self.touch(v);
        true
    }

    pub fn revive_triangle(&mut self, t: usize, body: &BodyDefinition) -> bool {
        if t >= self.tri_unborn.len() || !self.tri_unborn[t] {
            return false;
        }
        for k in 0..3 {
            let e = self.tri_edge[3 * t + k];
            if e >= 0 && self.edge_dead[e as usize] {
                return false;
            }
        }
        self.tri_unborn[t] = false;
        self.tri_alive[t] = true;
        self.triangle_revision += 1;
        self.live_triangle_count += 1;
        self.touch(body.tri_a[t]);
        self.touch(body.tri_b[t]);
        self.touch(body.tri_c[t]);
        for k in 0..3 {
            let e = self.tri_edge[3 * t + k];
            if e >= 0 {
                let eu = e as usize;
                let was = self.tri_use[eu];
                self.tri_use[eu] += 1;
                if was == 0 && !self.edge_dead[eu] {
                    self.bare_edges -= 1;
                }
            }
        }
        true
    }

    pub fn restore(&mut self, body: &BodyDefinition) {
        self.clear_dirty();
        self.edge_dead.fill(false);
        self.edge_unborn.fill(false);
        self.tri_unborn.fill(false);
        self.tri_alive.fill(true);
        self.triangle_revision += 1;
        self.inverted_ticks.fill(0);
        self.live_triangle_count = body.tri_count() as i32;
        self.tri_use.copy_from_slice(&self.use_init);
        self.bare_edges = 0;
        for e in 0..self.tri_use.len() {
            if self.tri_use[e] == 0 {
                self.bare_edges += 1;
            }
        }
    }

    pub fn kill(&mut self, e: usize, body: &BodyDefinition) -> bool {
        if self.edge_dead[e] {
            return false;
        }
        if self.tri_use[e] == 0 {
            self.bare_edges -= 1;
        }
        self.edge_dead[e] = true;
        let (u, v) = (self.edge_u(e), self.edge_v(e));
        self.touch(u);
        self.touch(v);
        for s in self.edge_tri_start[e]..self.edge_tri_start[e + 1] {
            let t = self.edge_tris[s as usize] as usize;
            self.kill_triangle(t, body);
        }
        true
    }

    pub fn kill_triangle(&mut self, t: usize, body: &BodyDefinition) -> bool {
        if !self.tri_alive[t] {
            return false;
        }
        self.tri_alive[t] = false;
        self.triangle_revision += 1;
        self.inverted_ticks[t] = 0;
        self.live_triangle_count -= 1;
        self.touch(body.tri_a[t]);
        self.touch(body.tri_b[t]);
        self.touch(body.tri_c[t]);
        for k in 0..3 {
            let e = self.tri_edge[3 * t + k];
            if e >= 0 {
                let eu = e as usize;
                self.tri_use[eu] -= 1;
                if self.tri_use[eu] == 0 && !self.edge_dead[eu] {
                    self.bare_edges += 1;
                }
            }
        }
        true
    }

    pub fn live_tri(&self, e: usize) -> i32 {
        for s in self.edge_tri_start[e]..self.edge_tri_start[e + 1] {
            let t = self.edge_tris[s as usize];
            if self.tri_alive[t as usize] {
                return t;
            }
        }
        -1
    }

    #[inline]
    pub fn edge_u(&self, e: usize) -> i32 {
        (self.edge_keys[e] as u64 >> 32) as i32
    }

    #[inline]
    pub fn edge_v(&self, e: usize) -> i32 {
        self.edge_keys[e] as i32
    }

    pub fn edge_of(&self, i: usize, j: i32) -> i32 {
        for s in self.vert_start[i]..self.vert_start[i + 1] {
            if self.vert_other[s as usize] == j {
                return self.vert_edge[s as usize];
            }
        }
        -1
    }

    /// Уплотнение мира (`compactFrom`): перенумерация рёбер и треугольников по картам.
    pub fn compact_from(
        &mut self,
        old: &BakedTopology,
        old_body: &BodyDefinition,
        body: &BodyDefinition,
        p_new: &[i32],
        e_new: &[i32],
        t_new: &[i32],
        link_kept: &[bool],
    ) {
        let n = body.count();
        let tc = body.tri_count();
        let mut m = 0usize;
        for e in 0..old.edge_count {
            if e_new[e] >= 0 {
                m += 1;
            }
        }
        self.edge_count = m;
        self.edge_keys = vec![0; m];
        self.use_init = vec![0; m];
        self.tri_use = vec![0; m];
        self.edge_dead = vec![false; m];
        self.edge_unborn = vec![false; if old.edge_unborn.len() >= old.edge_count { m } else { 0 }];
        self.edge_tri_start = vec![0; m + 1];
        for e in 0..old.edge_count {
            let k = e_new[e];
            if k < 0 {
                continue;
            }
            let k = k as usize;
            self.edge_keys[k] = key(p_new[old.edge_u(e) as usize], p_new[old.edge_v(e) as usize]);
            self.use_init[k] = old.use_init[e];
            self.tri_use[k] = old.tri_use[e];
            self.edge_dead[k] = old.edge_dead[e];
            if !self.edge_unborn.is_empty() {
                self.edge_unborn[k] = old.edge_unborn[e];
            }
            self.edge_tri_start[k + 1] = self.edge_tri_start[k] + (old.edge_tri_start[e + 1] - old.edge_tri_start[e]);
        }
        self.edge_tris = vec![0; self.edge_tri_start[m] as usize];
        for e in 0..old.edge_count {
            let k = e_new[e];
            if k < 0 {
                continue;
            }
            let mut w = self.edge_tri_start[k as usize] as usize;
            for s in old.edge_tri_start[e]..old.edge_tri_start[e + 1] {
                self.edge_tris[w] = t_new[old.edge_tris[s as usize] as usize];
                w += 1;
            }
        }
        self.link_edge = vec![0; body.link_count()];
        {
            let mut l = 0usize;
            for o in 0..link_kept.len() {
                if link_kept[o] {
                    self.link_edge[l] = e_new[old.link_edge[o] as usize];
                    l += 1;
                }
            }
        }
        self.tri_edge = vec![0; tc * 3];
        self.rest_angles = vec![0.0; tc * 3];
        self.tri_alive = vec![false; tc];
        self.inverted_ticks = vec![0; tc];
        self.tri_unborn = vec![false; if old.tri_unborn.len() >= old_body.tri_count() { tc } else { 0 }];
        for t in 0..old_body.tri_count() {
            let k = t_new[t];
            if k < 0 {
                continue;
            }
            let k = k as usize;
            for j in 0..3 {
                let e = old.tri_edge[3 * t + j];
                self.tri_edge[3 * k + j] = if e < 0 { -1 } else { e_new[e as usize] };
                self.rest_angles[3 * k + j] = old.rest_angles[3 * t + j];
            }
            self.tri_alive[k] = old.tri_alive[t];
            self.inverted_ticks[k] = old.inverted_ticks[t];
            if !self.tri_unborn.is_empty() {
                self.tri_unborn[k] = old.tri_unborn[t];
            }
        }
        self.vert_start = vec![0; n + 1];
        self.vertex_tri_start = vec![0; n + 1];
        for i in 0..old_body.count() {
            let j = p_new[i];
            if j < 0 {
                continue;
            }
            let j = j as usize;
            self.vert_start[j + 1] = self.vert_start[j] + (old.vert_start[i + 1] - old.vert_start[i]);
            self.vertex_tri_start[j + 1] =
                self.vertex_tri_start[j] + (old.vertex_tri_start[i + 1] - old.vertex_tri_start[i]);
        }
        self.vert_other = vec![0; self.vert_start[n] as usize];
        self.vert_edge = vec![0; self.vert_start[n] as usize];
        self.vertex_triangles = vec![0; self.vertex_tri_start[n] as usize];
        for i in 0..old_body.count() {
            let j = p_new[i];
            if j < 0 {
                continue;
            }
            let j = j as usize;
            let mut w = self.vert_start[j] as usize;
            for s in old.vert_start[i]..old.vert_start[i + 1] {
                let s = s as usize;
                self.vert_other[w] = p_new[old.vert_other[s] as usize];
                self.vert_edge[w] = e_new[old.vert_edge[s] as usize];
                w += 1;
            }
            w = self.vertex_tri_start[j] as usize;
            for s in old.vertex_tri_start[i]..old.vertex_tri_start[i + 1] {
                let corner = old.vertex_triangles[s as usize];
                self.vertex_triangles[w] = 3 * t_new[(corner / 3) as usize] + corner % 3;
                w += 1;
            }
        }
        self.live_triangle_count = 0;
        for k in 0..tc {
            if self.tri_alive[k] {
                self.live_triangle_count += 1;
            }
        }
        self.bare_edges = 0;
        for k in 0..m {
            if !self.edge_dead[k] && self.tri_use[k] == 0 {
                self.bare_edges += 1;
            }
        }
        self.dirty_vertices = vec![0; n];
        self.dirty = vec![false; n];
        self.dirty_count = 0;
    }

    /// Состояние для снимка (`writeState`) в формате DataOutput.
    pub fn write_state(&self, out: &mut crate::snapshot::Out, body: &BodyDefinition) {
        out.booleans(&self.edge_dead[..self.edge_count]);
        out.ints(&self.tri_use[..self.edge_count]);
        out.booleans(&self.edge_unborn);
        out.booleans(&self.tri_alive[..body.tri_count()]);
        out.bytes(&self.inverted_ticks[..body.tri_count()]);
        out.booleans(&self.tri_unborn);
    }

    pub fn read_state(&mut self, input: &mut crate::snapshot::In, body: &BodyDefinition) {
        self.edge_dead = input.booleans(self.edge_count);
        self.tri_use = input.ints(self.edge_count);
        self.edge_unborn = input.booleans(0);
        self.tri_alive = input.booleans(body.tri_count());
        self.inverted_ticks = input.bytes(body.tri_count());
        self.tri_unborn = input.booleans(0);
        assert!(
            self.edge_dead.len() == self.edge_count && self.tri_alive.len() == body.tri_count(),
            "снимок не от этого описания"
        );
        self.live_triangle_count = 0;
        for t in 0..body.tri_count() {
            if self.tri_alive[t] {
                self.live_triangle_count += 1;
            }
        }
        self.bare_edges = 0;
        for e in 0..self.edge_count {
            if !self.edge_dead[e] && self.tri_use[e] == 0 {
                self.bare_edges += 1;
            }
        }
        self.clear_dirty();
    }
}

/// Угол грани при вершине [v] в позе покоя (`BakedTopology.angle`): всё во float, acos — StrictMath в double.
fn angle(body: &BodyDefinition, v: i32, p: i32, q: i32) -> f32 {
    let (v, p, q) = (v as usize, p as usize, q as usize);
    let ax = body.x[p] - body.x[v];
    let ay = body.y[p] - body.y[v];
    let bx = body.x[q] - body.x[v];
    let by = body.y[q] - body.y[v];
    let na = sqrtf(ax * ax + ay * ay);
    let nb = sqrtf(bx * bx + by * by);
    if na < 1e-12f32 || nb < 1e-12f32 {
        return 0.0;
    }
    strict_acos(coerce_inf((ax * bx + ay * by) / (na * nb), -1.0, 1.0) as f64) as f32
}
