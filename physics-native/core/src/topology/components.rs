//! Организмы как связные компоненты — местное обновление после разрыва ребра
//! (`topology/LocalComponents.kt`): два встречных обхода, меньший кусок получает новый номер.

use crate::solver::sleep::BodySleep;
use crate::topology::baked::BakedTopology;

#[derive(Clone, Debug, Default)]
pub struct LocalComponents {
    seen: Vec<i32>,
    front_a: Vec<i32>,
    front_b: Vec<i32>,
    stamp: i32,
    pub count: i32,
}

/// Массивы организмов BodyState, которые правит разбиение.
pub struct OrgArrays<'a> {
    pub of: &'a mut [i32],
    pub size: &'a mut [i32],
    pub mass: &'a mut [f32],
    pub rest_inv_mass: &'a [f32],
    pub dead: &'a [bool],
    pub flow_x: &'a mut [f32],
    pub flow_y: &'a mut [f32],
}

impl LocalComponents {
    pub fn new(n: usize, count: i32) -> LocalComponents {
        LocalComponents { seen: vec![0; n], front_a: vec![0; n], front_b: vec![0; n], stamp: 0, count }
    }

    pub fn add_cell(v: usize, of: &[i32], size: &mut [i32], mass: &mut [f32], rest_inv_mass: &[f32]) {
        let o = of[v] as usize;
        size[o] += 1;
        if rest_inv_mass[v] > 0.0 {
            mass[o] += 1.0 / rest_inv_mass[v];
        }
    }

    pub fn remove_cell(v: usize, of: &[i32], size: &mut [i32], mass: &mut [f32], rest_inv_mass: &[f32]) {
        let o = of[v] as usize;
        size[o] -= 1;
        if rest_inv_mass[v] > 0.0 {
            mass[o] -= 1.0 / rest_inv_mass[v];
        }
    }

    pub fn split(&mut self, edge: usize, topology: &BakedTopology, a: &mut OrgArrays, sleep: &mut BodySleep) {
        let u = topology.edge_u(edge) as usize;
        let v = topology.edge_v(edge) as usize;
        if a.of[u] != a.of[v] {
            return;
        }
        if self.stamp > i32::MAX - 3 {
            self.seen.fill(0);
            self.stamp = 0;
        }
        self.stamp += 2;
        let a_mark = self.stamp;
        let b_mark = self.stamp + 1;
        self.seen[u] = a_mark;
        self.seen[v] = b_mark;
        self.front_a[0] = u as i32;
        self.front_b[0] = v as i32;
        let (mut ah, mut at, mut bh, mut bt) = (0usize, 1usize, 0usize, 1usize);
        while ah < at && bh < bt {
            let x = self.front_a[ah] as usize;
            ah += 1;
            for s in topology.vert_start[x]..topology.vert_start[x + 1] {
                let s = s as usize;
                if topology.edge_dead[topology.vert_edge[s] as usize] {
                    continue;
                }
                let w = topology.vert_other[s] as usize;
                if self.seen[w] == b_mark {
                    return;
                }
                if self.seen[w] != a_mark {
                    self.seen[w] = a_mark;
                    self.front_a[at] = w as i32;
                    at += 1;
                }
            }
            let y = self.front_b[bh] as usize;
            bh += 1;
            for s in topology.vert_start[y]..topology.vert_start[y + 1] {
                let s = s as usize;
                if topology.edge_dead[topology.vert_edge[s] as usize] {
                    continue;
                }
                let w = topology.vert_other[s] as usize;
                if self.seen[w] == a_mark {
                    return;
                }
                if self.seen[w] != b_mark {
                    self.seen[w] = b_mark;
                    self.front_b[bt] = w as i32;
                    bt += 1;
                }
            }
        }
        let (queue, length) = if ah == at { (&self.front_a, at) } else { (&self.front_b, bt) };
        let old = a.of[queue[0] as usize] as usize;
        let fresh = self.count as usize;
        self.count += 1;
        a.size[fresh] = 0;
        a.mass[fresh] = 0.0;
        a.flow_x[fresh] = a.flow_x[old];
        a.flow_y[fresh] = a.flow_y[old];
        for &w in queue.iter().take(length) {
            let w = w as usize;
            a.of[w] = fresh as i32;
            if a.dead[w] {
                continue;
            }
            a.size[fresh] += 1;
            if a.rest_inv_mass[w] > 0.0 {
                a.mass[fresh] += 1.0 / a.rest_inv_mass[w];
            }
        }
        a.size[old] -= a.size[fresh];
        a.mass[old] -= a.mass[fresh];
        sleep.inherit_split(fresh, old, &queue[..length]);
    }
}
