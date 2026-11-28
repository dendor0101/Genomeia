//! Кольца шага мышц по воркерам — `solver/ParallelMuscles.kt` (refreshRings, appendCellScaleLerp) на Rust.
//!
//! Сдвинувшиеся клетки (по возрастанию номера) режутся на куски по блокам. Куски блоков одного цвета (номер блока по
//! модулю 3) идут по воркерам одновременно: тело, у которого связи дотягиваются за соседний блок, — в одном потоке
//! (`serial_block`, та же проверка двух колец, что у обхода блоков), поэтому куски одного цвета не делят ни клеток,
//! ни рёбер, ни треугольников. Два прохода, как в одном потоке: сперва действующие множители всех кусков, потом связи
//! и треугольники вокруг изменившихся клеток. Записи плавности куски копят в своих буферах, а сливаются в порядке
//! кусков; порядок записей на итог не влияет (каждая — своя связь или треугольник), значения связей и треугольников
//! — по окончательным множителям клеток. Сверка — RustWorkersParityTest.

use crate::solver::pool::{SharedMut, Workers};
use crate::state::BodyState;

/// Сдвинувшихся клеток меньше этого — кольца в одном потоке (`PARALLEL_MIN_MOVED`).
const PARALLEL_MIN_MOVED: usize = 256;
/// Цветов блоков: соседние блоки — разных цветов.
const COLORS: usize = 3;

/// Буфер куска (`CellScaleLerpBuffer`): изменившиеся клетки и захваченное «было» связей и треугольников.
#[derive(Clone, Debug, Default)]
pub struct CellScaleLerpBuffer {
    pub edge_ids: Vec<i32>,
    pub edge_from: Vec<f32>,
    pub edge_n: usize,
    pub tri_ids: Vec<i32>,
    pub tri_from: Vec<f32>,
    pub tri_n: usize,
    pub changed: Vec<i32>,
    pub changed_n: usize,
}

impl CellScaleLerpBuffer {
    pub fn add_changed(&mut self, u: i32) {
        if self.changed_n == self.changed.len() {
            let cap = (self.changed_n * 2).max(64);
            self.changed.resize(cap, 0);
        }
        self.changed[self.changed_n] = u;
        self.changed_n += 1;
    }

    pub fn add_edge(&mut self, e: i32, from: f32) {
        if self.edge_n == self.edge_ids.len() {
            let cap = (self.edge_n * 2).max(64);
            self.edge_ids.resize(cap, 0);
            self.edge_from.resize(cap, 0.0);
        }
        self.edge_ids[self.edge_n] = e;
        self.edge_from[self.edge_n] = from;
        self.edge_n += 1;
    }

    pub fn add_tri(&mut self, id: i32, from: f32) {
        if self.tri_n == self.tri_ids.len() {
            let cap = (self.tri_n * 2).max(64);
            self.tri_ids.resize(cap, 0);
            self.tri_from.resize(cap, 0.0);
        }
        self.tri_ids[self.tri_n] = id;
        self.tri_from[self.tri_n] = from;
        self.tri_n += 1;
    }
}

/// Куски сдвинувшихся клеток и их буферы (`ParallelMuscles`): живут между тиками.
#[derive(Clone, Debug, Default)]
pub struct MuscleRings {
    from: Vec<usize>,
    to: Vec<usize>,
    block_of: Vec<usize>,
    buffers: Vec<CellScaleLerpBuffer>,
    colored: [Vec<usize>; COLORS],
}

impl BodyState {
    /// `refreshRings`: кольца сдвинувшихся клеток [0, moved_n) списка `cell_scale_moved` — по воркерам.
    pub fn refresh_rings_parallel(&mut self, w: &Workers, moved_n: usize) {
        if moved_n < PARALLEL_MIN_MOVED {
            let changed = self.refresh_effective_of(moved_n);
            self.refresh_around_changed(changed);
            return;
        }
        let mut r = std::mem::take(&mut self.muscle_rings);
        let mut chunks = 0usize;
        for c in r.colored.iter_mut() {
            c.clear();
        }
        {
            let layout = self.constraint_blocks.as_ref().unwrap();
            let block = &layout.particle_block;
            let mut q = 0usize;
            while q < moved_n {
                let b = block[self.cell_scale_moved[q] as usize] as usize;
                let mut e = q + 1;
                while e < moved_n && block[self.cell_scale_moved[e] as usize] as usize == b {
                    e += 1;
                }
                if chunks == r.from.len() {
                    let cap = (chunks * 2).max(64);
                    r.from.resize(cap, 0);
                    r.to.resize(cap, 0);
                    r.block_of.resize(cap, 0);
                    r.buffers.resize_with(cap, CellScaleLerpBuffer::default);
                }
                r.from[chunks] = q;
                r.to[chunks] = e;
                r.block_of[chunks] = b;
                if !layout.serial_block[b] {
                    r.colored[b % COLORS].push(chunks);
                }
                chunks += 1;
                q = e;
            }
        }
        let st = SharedMut(self as *mut BodyState);
        let rp = SharedMut(&mut r as *mut MuscleRings);
        // Первый проход: действующие множители (по цветам, затем куски тел «в одном потоке»).
        for color in 0..COLORS {
            let count = r.colored[color].len();
            w.run(count, &|k, _| {
                // SAFETY: куски одного цвета не делят клеток (см. шапку); буфер — свой.
                unsafe {
                    let r = rp.get();
                    let x = r.colored[color][k];
                    st.get().effective_chunk(r, x);
                }
            });
        }
        for x in 0..chunks {
            if self.constraint_blocks.as_ref().unwrap().serial_block[r.block_of[x]] {
                self.effective_chunk(&mut r, x);
            }
        }
        // Второй проход: связи и треугольники вокруг изменившихся клеток.
        for color in 0..COLORS {
            let count = r.colored[color].len();
            w.run(count, &|k, _| {
                // SAFETY: куски одного цвета не делят рёбер и треугольников (см. шапку); буфер — свой.
                unsafe {
                    let r = rp.get();
                    let x = r.colored[color][k];
                    st.get().around_chunk(r, x);
                }
            });
        }
        for x in 0..chunks {
            if self.constraint_blocks.as_ref().unwrap().serial_block[r.block_of[x]] {
                self.around_chunk(&mut r, x);
            }
        }
        for x in 0..chunks {
            let buf = std::mem::take(&mut r.buffers[x]);
            self.append_cell_scale_lerp(&buf);
            r.buffers[x] = buf;
        }
        self.muscle_rings = r;
    }

    /// `effectiveChunk`: действующие множители клеток куска [x] и их соседей; изменившиеся — в буфер куска.
    fn effective_chunk(&mut self, r: &mut MuscleRings, x: usize) {
        let buf = &mut r.buffers[x];
        buf.changed_n = 0;
        let mut changed = std::mem::take(&mut buf.changed);
        let mut n = 0usize;
        self.refresh_effective_range(r.from[x], r.to[x], &mut changed, &mut n);
        buf.changed = changed;
        buf.changed_n = n;
    }

    /// `aroundChunk`: связи и треугольники вокруг изменившихся клеток куска [x]; «было» — в буфер куска.
    fn around_chunk(&mut self, r: &mut MuscleRings, x: usize) {
        let buf = &mut r.buffers[x];
        buf.edge_n = 0;
        buf.tri_n = 0;
        let changed = std::mem::take(&mut buf.changed);
        let n = buf.changed_n;
        self.refresh_around_list(&changed, n, Some(buf));
        buf.changed = changed;
    }

    /// `appendCellScaleLerp`: записи плавности куска — в конец общих списков.
    fn append_cell_scale_lerp(&mut self, part: &CellScaleLerpBuffer) {
        let edges = self.lerp_edge_n + part.edge_n;
        if edges > self.lerp_edge_ids.len() {
            let cap = edges.max(self.lerp_edge_ids.len() * 2);
            self.lerp_edge_ids.resize(cap, 0);
            self.lerp_edge_from.resize(cap, 0.0);
            self.lerp_edge_to.resize(cap, 0.0);
        }
        let n0 = self.lerp_edge_n;
        self.lerp_edge_ids[n0..edges].copy_from_slice(&part.edge_ids[..part.edge_n]);
        self.lerp_edge_from[n0..edges].copy_from_slice(&part.edge_from[..part.edge_n]);
        self.lerp_edge_n = edges;
        let tris = self.lerp_tri_n + part.tri_n;
        if tris > self.lerp_tri_ids.len() {
            let cap = tris.max(self.lerp_tri_ids.len() * 2);
            self.lerp_tri_ids.resize(cap, 0);
            self.lerp_tri_from.resize(cap, 0.0);
            self.lerp_tri_to.resize(cap, 0.0);
        }
        let t0 = self.lerp_tri_n;
        self.lerp_tri_ids[t0..tris].copy_from_slice(&part.tri_ids[..part.tri_n]);
        self.lerp_tri_from[t0..tris].copy_from_slice(&part.tri_from[..part.tri_n]);
        self.lerp_tri_n = tris;
    }
}
