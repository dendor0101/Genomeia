//! Местная поверхность — `topology/LocalSurface.kt`: соседи для касаний, рёбра контура,
//! радиус касания и «на краю ли клетка» по каждой вершине; пересчёт по одной вершине.
//!
//! Массивы free/probe/dead/radius клеток живут в BodyState (в Kotlin это общие ссылки),
//! сюда они приходят параметрами.

use crate::definition::BodyDefinition;
use crate::jmath;
use crate::topology::baked::BakedTopology;

#[derive(Clone, Debug, Default)]
pub struct LocalSurface {
    pub scale: f32,
    pub seal: f32,
    pub angle_eps: f32,
    pub probes_enabled: bool,
    pub adj: Vec<i32>,
    pub adj_end: Vec<i32>,
    pub boundary: Vec<i32>,
    pub opposite: Vec<i32>,
    pub boundary_end: Vec<i32>,
    pub radius: Vec<f32>,
    pub active: Vec<bool>,
    pub active_changed: Vec<i32>,
    pub active_changed_n: usize,
    active_changed_mark: Vec<bool>,
}

/// Общие массивы клеток, которые читает и пишет пересчёт поверхности.
pub struct SurfaceCells<'a> {
    pub body: &'a BodyDefinition,
    pub topology: &'a BakedTopology,
    pub bone_of: &'a [i32],
    pub dead: &'a [bool],
    pub cell_radius: &'a [f32],
    pub free: &'a mut [bool],
    pub probe: &'a mut [bool],
}

impl LocalSurface {
    pub fn new(
        n: usize,
        topology: &BakedTopology,
        scale: f32,
        seal: f32,
        angle_eps: f32,
        probes_enabled: bool,
    ) -> LocalSurface {
        let adj_len = topology.vert_other.len();
        LocalSurface {
            scale,
            seal,
            angle_eps,
            probes_enabled,
            adj: vec![0; adj_len],
            adj_end: vec![0; n],
            boundary: vec![0; adj_len],
            opposite: vec![0; adj_len],
            boundary_end: vec![0; n],
            radius: vec![0.0; n],
            active: vec![false; n],
            active_changed: vec![0; 16],
            active_changed_n: 0,
            active_changed_mark: vec![false; n],
        }
    }

    pub fn clear_active_changed(&mut self) {
        for k in 0..self.active_changed_n {
            let v = self.active_changed[k] as usize;
            self.active_changed_mark[v] = false;
        }
        self.active_changed_n = 0;
    }

    fn note_active_changed(&mut self, v: usize) {
        if self.active_changed_mark[v] {
            return;
        }
        self.active_changed_mark[v] = true;
        if self.active_changed_n == self.active_changed.len() {
            let cap = self.active_changed_n * 2;
            self.active_changed.resize(cap, 0);
        }
        self.active_changed[self.active_changed_n] = v as i32;
        self.active_changed_n += 1;
    }

    /// Мир вырос (`grow`): массивы дотягиваются до новой топологии и числа клеток.
    pub fn grow(&mut self, n: usize, topology: &BakedTopology) {
        let adj_len = topology.vert_other.len();
        self.adj.resize(adj_len, 0);
        self.boundary.resize(adj_len, 0);
        self.opposite.resize(adj_len, 0);
        self.adj_end.resize(n, 0);
        self.boundary_end.resize(n, 0);
        self.radius.resize(n, 0.0);
        self.active.resize(n, false);
        self.active_changed_mark.resize(n, false);
    }

    /// Перенос по картам уплотнения (`compactFrom`); `old_start` — vertStart старой топологии.
    pub fn compact_from(&mut self, old: &LocalSurface, old_start: &[i32], start: &[i32], p_new: &[i32]) {
        for i in 0..old.adj_end.len() {
            let j = p_new[i];
            if j < 0 {
                continue;
            }
            let j = j as usize;
            let from = old_start[i];
            let to = start[j];
            for s in from..old.adj_end[i] {
                self.adj[(to + s - from) as usize] = p_new[old.adj[s as usize] as usize];
            }
            self.adj_end[j] = to + old.adj_end[i] - from;
            for s in from..old.boundary_end[i] {
                self.boundary[(to + s - from) as usize] = p_new[old.boundary[s as usize] as usize];
                let c = old.opposite[s as usize];
                self.opposite[(to + s - from) as usize] = if c < 0 { c } else { p_new[c as usize] };
            }
            self.boundary_end[j] = to + old.boundary_end[i] - from;
            self.radius[j] = old.radius[i];
            self.active[j] = old.active[i];
        }
    }

    pub fn update(&mut self, v: usize, c: &mut SurfaceCells) {
        let topology = c.topology;
        let start = &topology.vert_start;
        let mut a = start[v];
        let mut b = a;
        let mut degree = 0;
        let mut r = 0.0f32;
        for s in start[v]..start[v + 1] {
            let s = s as usize;
            let edge = topology.vert_edge[s] as usize;
            if topology.edge_dead[edge] {
                continue;
            }
            let other = topology.vert_other[s];
            degree += 1;
            if c.bone_of[v] < 0 || c.bone_of[v] != c.bone_of[other as usize] {
                self.adj[a as usize] = other;
                a += 1;
            }
            if topology.tri_use[edge] > 1 {
                continue;
            }
            self.boundary[b as usize] = other;
            self.opposite[b as usize] = topology.opposite(edge, c.body);
            b += 1;
            let dx = c.body.x[v] - c.body.x[other as usize];
            let dy = c.body.y[v] - c.body.y[other as usize];
            r = jmath::maxf(r, 0.5f32 * jmath::sqrtf(dx * dx + dy * dy) * self.seal);
        }
        self.adj_end[v] = a;
        self.boundary_end[v] = b;
        c.free[v] = degree == 0;
        // Kotlin: `surfaceAngle(v) < 2.0f * Math.PI - angleEps` — Math.PI double, всё сравнение в double.
        c.probe[v] = c.free[v]
            || (topology.surface_angle(v) as f64) < 2.0f64 * std::f64::consts::PI - self.angle_eps as f64;
        let exposed = c.free[v] || (self.probes_enabled && c.probe[v]);
        let was = self.active[v];
        self.active[v] = !c.dead[v] && (b > start[v] || exposed);
        if self.active[v] != was {
            self.note_active_changed(v);
        }
        if r == 0.0 && (a == start[v] || exposed) {
            r = c.cell_radius[v] * self.scale;
        }
        self.radius[v] = if c.dead[v] { 0.0 } else { r };
    }
}
