//! Выворот треугольников у места разрыва — `damage/FractureOrientation.kt`: перед гибелью
//! клетки или пробоем ребра вывернутые живые треугольники вокруг возвращаются в ориентацию.
//! Порядок обхода — по возрастанию номера треугольника (битовая маска по словам).

use crate::definition::BodyDefinition;
use crate::solver::orientation::project_boundary_orientation;
use crate::topology::baked::BakedTopology;

#[derive(Clone, Debug, Default)]
pub struct FractureOrientation {
    words: Vec<i64>,
    occupied: Vec<i32>,
}

impl FractureOrientation {
    pub fn new(tri_count: usize) -> FractureOrientation {
        let w = (tri_count + 63) >> 6;
        FractureOrientation { words: vec![0; w], occupied: vec![0; w] }
    }

    pub fn solve(
        &mut self,
        cells: &[i32],
        topology: &BakedTopology,
        body: &BodyDefinition,
        x: &mut [f32],
        y: &mut [f32],
        w: &[f32],
    ) {
        let mut used = 0usize;
        for &v in cells {
            let v = v as usize;
            for s in topology.vertex_tri_start[v]..topology.vertex_tri_start[v + 1] {
                let t = topology.vertex_triangles[s as usize] / 3;
                used = self.collect(t as usize, used, topology);
            }
        }
        self.project(used, body, x, y, w);
    }

    /// Пробитые рёбра: треугольники каждого ещё живого ребра с живой связью.
    pub fn solve_pierced(
        &mut self,
        edges: &[i32],
        topology: &BakedTopology,
        body: &BodyDefinition,
        x: &mut [f32],
        y: &mut [f32],
        w: &[f32],
    ) {
        let mut used = 0usize;
        for &e in edges {
            let e = e as usize;
            for s in topology.edge_tri_start[e]..topology.edge_tri_start[e + 1] {
                used = self.collect(topology.edge_tris[s as usize] as usize, used, topology);
            }
        }
        self.project(used, body, x, y, w);
    }

    fn collect(&mut self, t: usize, used: usize, topology: &BakedTopology) -> usize {
        if !topology.tri_alive[t] {
            return used;
        }
        let word = t >> 6;
        let fresh = self.words[word] == 0;
        if fresh {
            self.occupied[used] = word as i32;
        }
        self.words[word] |= 1i64 << (t & 63);
        if fresh { used + 1 } else { used }
    }

    fn project(&mut self, used: usize, body: &BodyDefinition, x: &mut [f32], y: &mut [f32], w: &[f32]) {
        self.occupied[..used].sort_unstable();
        for k in 0..used {
            let word = self.occupied[k] as usize;
            let mut bits = self.words[word];
            self.words[word] = 0;
            while bits != 0 {
                let t = (word << 6) + bits.trailing_zeros() as usize;
                bits &= bits - 1;
                let a = body.tri_a[t] as usize;
                let b = body.tri_b[t] as usize;
                let c = body.tri_c[t] as usize;
                let area = (x[b] - x[a]) * (y[c] - y[a]) - (y[b] - y[a]) * (x[c] - x[a]);
                if area < 0.0 {
                    project_boundary_orientation(a, b, c, area, x, y, w, None);
                }
            }
        }
    }
}
