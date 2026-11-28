//! Местный контур — `topology/LocalBoundaryConstraints.kt`: рёбра контура со знаком
//! ориентации, пары изгиба «через одну» и пары голых соседей (лоскуты) по вершине.
//!
//! Привязка к блокам решателя (`blockLayout`/`blockIndex` в Kotlin) — флаг [attached]:
//! пока он поднят, правки слотов сразу переносятся в списки блоков ConstraintBlocks,
//! которые приходят параметром.

use crate::definition::BodyDefinition;
use crate::jmath::sqrtf;
use crate::solver::blocks::{BlockIndex, ConstraintBlocks, block_of2, block_of3};
use crate::topology::baked::BakedTopology;

/// Пары частиц, привязанные к вершине-владельцу (`VertexPairs`).
#[derive(Clone, Debug)]
pub struct VertexPairs {
    head: Vec<i32>,
    /// Вершина пары: у створки — клетка, при которой угол между голыми связями к a и b.
    pub owner: Vec<i32>,
    next: Vec<i32>,
    prev: Vec<i32>,
    pub a: Vec<i32>,
    pub b: Vec<i32>,
    pub rest: Vec<f32>,
    pub count: usize,
    /// Ядро пары читает и вершину (створка): пара идёт в блок, только если в нём все три клетки, иначе — в швы.
    vertex_in_block: bool,
}

/// Список блоков и раскладка для пересчёта номера блока пары.
pub struct BlockSink<'a> {
    pub particle_block: &'a [i32],
    pub seam_bucket: &'a [i32],
    pub index: &'a mut BlockIndex,
}

impl VertexPairs {
    pub fn new(n: usize, vertex_in_block: bool) -> VertexPairs {
        let cap = n.max(16);
        VertexPairs {
            head: vec![-1; n],
            owner: vec![0; cap],
            next: vec![0; cap],
            prev: vec![0; cap],
            a: vec![0; cap],
            b: vec![0; cap],
            rest: vec![0.0; cap],
            count: 0,
            vertex_in_block,
        }
    }

    pub fn grow(&mut self, n: usize) {
        self.head.resize(n, -1);
    }

    pub fn clear(&mut self, v: usize, mut sink: Option<&mut BlockSink>) {
        while self.head[v] >= 0 {
            let slot = self.head[v] as usize;
            if let Some(s) = sink.as_deref_mut() {
                s.index.remove(slot as i32);
            }
            self.unlink(slot);
            self.count -= 1;
            let last = self.count;
            if slot == last {
                continue;
            }
            if let Some(s) = sink.as_deref_mut() {
                s.index.move_item(last as i32, slot as i32);
            }
            self.a[slot] = self.a[last];
            self.b[slot] = self.b[last];
            self.rest[slot] = self.rest[last];
            self.owner[slot] = self.owner[last];
            self.next[slot] = self.next[last];
            self.prev[slot] = self.prev[last];
            if self.prev[slot] < 0 {
                let o = self.owner[slot] as usize;
                self.head[o] = slot as i32;
            } else {
                let p = self.prev[slot] as usize;
                self.next[p] = slot as i32;
            }
            if self.next[slot] >= 0 {
                let q = self.next[slot] as usize;
                self.prev[q] = slot as i32;
            }
        }
    }

    fn unlink(&mut self, slot: usize) {
        if self.prev[slot] < 0 {
            let o = self.owner[slot] as usize;
            self.head[o] = self.next[slot];
        } else {
            let p = self.prev[slot] as usize;
            self.next[p] = self.next[slot];
        }
        if self.next[slot] >= 0 {
            let q = self.next[slot] as usize;
            self.prev[q] = self.prev[slot];
        }
    }

    pub fn add(&mut self, v: usize, i: i32, j: i32, body: &BodyDefinition, sink: Option<&mut BlockSink>) {
        if i == j {
            return;
        }
        if self.count == self.a.len() {
            let cap = self.a.len() * 2;
            self.a.resize(cap, 0);
            self.b.resize(cap, 0);
            self.rest.resize(cap, 0.0);
            self.owner.resize(cap, 0);
            self.next.resize(cap, 0);
            self.prev.resize(cap, 0);
        }
        let dx = body.x[i as usize] - body.x[j as usize];
        let dy = body.y[i as usize] - body.y[j as usize];
        let k = self.count;
        self.a[k] = i;
        self.b[k] = j;
        self.rest[k] = sqrtf(dx * dx + dy * dy);
        self.owner[k] = v as i32;
        self.prev[k] = -1;
        self.next[k] = self.head[v];
        if self.head[v] >= 0 {
            let h = self.head[v] as usize;
            self.prev[h] = k as i32;
        }
        if let Some(s) = sink {
            let block = if self.vertex_in_block {
                block_of3(s.particle_block, s.seam_bucket, i, j, v as i32)
            } else {
                block_of2(s.particle_block, s.seam_bucket, i, j)
            };
            s.index.add(k as i32, block);
        }
        self.head[v] = k as i32;
        self.count += 1;
    }
}

#[derive(Clone, Debug)]
pub struct LocalBoundaryConstraints {
    pub a: Vec<i32>,
    pub b: Vec<i32>,
    pub opposite: Vec<i32>,
    pub orientation: Vec<i8>,
    edge_at: Vec<i32>,
    slot_of: Vec<i32>,
    pub count: usize,
    /// Привязаны ли контур, изгиб и лоскуты к спискам блоков (`blockIndex != null`).
    pub attached: bool,
    pub bend: VertexPairs,
    pub flap: VertexPairs,
    neighbors: Vec<i32>,
}

/// Только для чтения: то, по чему считается контур вершины.
pub struct BoundaryInputs<'a> {
    pub body: &'a BodyDefinition,
    pub topology: &'a BakedTopology,
    pub bone_of: &'a [i32],
}

impl LocalBoundaryConstraints {
    pub fn new(n: usize, topology: &BakedTopology) -> LocalBoundaryConstraints {
        let m = topology.edge_count;
        LocalBoundaryConstraints {
            a: vec![0; m],
            b: vec![0; m],
            opposite: vec![0; m],
            orientation: vec![0; m],
            edge_at: vec![0; m],
            slot_of: vec![-1; m],
            count: 0,
            attached: false,
            bend: VertexPairs::new(n, false),
            flap: VertexPairs::new(n, true),
            neighbors: vec![0; n],
        }
    }

    pub fn grow(&mut self, n: usize, topology: &BakedTopology) {
        let m = topology.edge_count;
        self.a.resize(m, 0);
        self.b.resize(m, 0);
        self.opposite.resize(m, 0);
        self.orientation.resize(m, 0);
        self.edge_at.resize(m, 0);
        self.slot_of.resize(m, -1);
        self.bend.grow(n);
        self.flap.grow(n);
        self.neighbors = vec![0; n];
    }

    pub fn detach_blocks(&mut self) {
        self.attached = false;
    }

    pub fn block_of_slot(&self, k: usize, particle_block: &[i32], seam_bucket: &[i32]) -> i32 {
        let c = self.opposite[k];
        if c < 0 {
            block_of2(particle_block, seam_bucket, self.a[k], self.b[k])
        } else {
            block_of3(particle_block, seam_bucket, self.a[k], self.b[k], c)
        }
    }

    pub fn update_edge(&mut self, e: usize, inp: &BoundaryInputs, blocks: Option<&mut ConstraintBlocks>) {
        let blocks = if self.attached { blocks } else { None };
        let topology = inp.topology;
        let slot = self.slot_of[e];
        if topology.edge_dead[e] || topology.tri_use[e] > 1 {
            if slot < 0 {
                return;
            }
            let slot = slot as usize;
            self.count -= 1;
            let last = self.count;
            if let Some(cb) = blocks {
                cb.edges.remove(slot as i32);
                cb.edges.move_item(last as i32, slot as i32);
            }
            self.a[slot] = self.a[last];
            self.b[slot] = self.b[last];
            self.opposite[slot] = self.opposite[last];
            self.orientation[slot] = self.orientation[last];
            self.edge_at[slot] = self.edge_at[last];
            let ea = self.edge_at[slot] as usize;
            self.slot_of[ea] = slot as i32;
            self.slot_of[e] = -1;
        } else {
            let dst = if slot >= 0 {
                slot as usize
            } else {
                let d = self.count;
                self.count += 1;
                d
            };
            let body = inp.body;
            self.a[dst] = topology.edge_u(e);
            self.b[dst] = topology.edge_v(e);
            self.opposite[dst] = topology.opposite(e, body);
            self.edge_at[dst] = e as i32;
            self.slot_of[e] = dst as i32;
            let i = self.a[dst] as usize;
            let j = self.b[dst] as usize;
            let c = self.opposite[dst];
            self.orientation[dst] = if c < 0 {
                0
            } else {
                let c = c as usize;
                let area = (body.x[j] - body.x[i]) * (body.y[c] - body.y[i]) - (body.y[j] - body.y[i]) * (body.x[c] - body.x[i]);
                if area > 0.0 {
                    1
                } else if area < 0.0 {
                    -1
                } else {
                    0
                }
            };
            if let Some(cb) = blocks {
                let block = self.block_of_slot(dst, &cb.particle_block, &cb.seam_bucket);
                cb.edges.remove(dst as i32);
                cb.edges.add(dst as i32, block);
            }
        }
    }

    pub fn update_vertex(&mut self, v: usize, inp: &BoundaryInputs, mut blocks: Option<&mut ConstraintBlocks>) {
        let attached = self.attached;
        {
            let cb = if attached { blocks.as_deref_mut() } else { None };
            match cb {
                Some(cb) => {
                    let ConstraintBlocks { particle_block, seam_bucket, bends, flaps, .. } = cb;
                    self.bend.clear(v, Some(&mut BlockSink { particle_block, seam_bucket, index: bends }));
                    self.flap.clear(v, Some(&mut BlockSink { particle_block, seam_bucket, index: flaps }));
                }
                None => {
                    self.bend.clear(v, None);
                    self.flap.clear(v, None);
                }
            }
        }
        let topology = inp.topology;
        let mut nb0 = -1;
        let mut nb1 = -1;
        let mut degree = 0;
        let mut bare = 0usize;
        for s in topology.vert_start[v]..topology.vert_start[v + 1] {
            let s = s as usize;
            let e = topology.vert_edge[s] as usize;
            self.update_edge(e, inp, blocks.as_deref_mut());
            if topology.edge_dead[e] {
                continue;
            }
            let u = topology.vert_other[s];
            let usage = topology.tri_use[e];
            if usage <= 1 {
                if degree == 0 {
                    nb0 = u;
                } else if degree == 1 {
                    nb1 = u;
                }
                degree += 1;
            }
            if usage == 0 && (inp.bone_of[v] < 0 || inp.bone_of[v] != inp.bone_of[u as usize]) {
                self.neighbors[bare] = u;
                bare += 1;
            }
        }
        let cb = if attached { blocks } else { None };
        match cb {
            Some(cb) => {
                let ConstraintBlocks { particle_block, seam_bucket, bends, flaps, .. } = cb;
                if degree == 2 {
                    self.bend.add(
                        v,
                        nb0,
                        nb1,
                        inp.body,
                        Some(&mut BlockSink { particle_block, seam_bucket, index: bends }),
                    );
                }
                for p in 0..bare {
                    for q in p + 1..bare {
                        let (i, j) = (self.neighbors[p], self.neighbors[q]);
                        self.flap.add(
                            v,
                            i,
                            j,
                            inp.body,
                            Some(&mut BlockSink { particle_block, seam_bucket, index: flaps }),
                        );
                    }
                }
            }
            None => {
                if degree == 2 {
                    self.bend.add(v, nb0, nb1, inp.body, None);
                }
                for p in 0..bare {
                    for q in p + 1..bare {
                        let (i, j) = (self.neighbors[p], self.neighbors[q]);
                        self.flap.add(v, i, j, inp.body, None);
                    }
                }
            }
        }
    }
}
