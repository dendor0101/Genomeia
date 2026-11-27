//! Обход блоков — `solver/BlockedIteration.kt`: сборка раскладки блоков по запечённым телам и итерация подшага
//! (блоки по чётности, затем наборы швов, затем предел длины, если швы есть). Путь одного потока.

use crate::params::CONSTRAINT_BLOCK_SIZE;
use crate::solver::blocks::ConstraintBlocks;
use crate::solver::bones::BoneWallHits;
use crate::state::BodyState;

impl BodyState {
    /// `bakedBodies`: связные части запечённого графа по ВСЕМ рёбрам (и порванным, и нерождённым).
    fn baked_bodies(&self) -> Vec<i32> {
        let n = self.n;
        let mut body = vec![-1i32; n];
        let mut queue = vec![0i32; n];
        let start = &self.baked.vert_start;
        let other = &self.baked.vert_other;
        let mut count = 0i32;
        for s in 0..n {
            if body[s] >= 0 {
                continue;
            }
            let mut head = 0usize;
            let mut tail = 0usize;
            body[s] = count;
            queue[tail] = s as i32;
            tail += 1;
            while head < tail {
                let v = queue[head] as usize;
                head += 1;
                for k in start[v]..start[v + 1] {
                    let u = other[k as usize] as usize;
                    if body[u] < 0 {
                        body[u] = count;
                        queue[tail] = u as i32;
                        tail += 1;
                    }
                }
            }
            count += 1;
        }
        body
    }

    /// `initializeConstraintBlocks`: раскладка блоков (заводится один раз) и все списки ограничений по ней.
    pub fn initialize_constraint_blocks(&mut self) {
        if self.wall_hits.capacity != self.rigid_bones.len() {
            self.wall_hits = BoneWallHits::new(self.rigid_bones.len());
        }
        if self.constraint_blocks.is_none() {
            let bodies = self.baked_bodies();
            self.constraint_blocks = Some(ConstraintBlocks::new(
                self.n,
                &bodies,
                CONSTRAINT_BLOCK_SIZE,
                self.con_a.len(),
                self.tri_a.len(),
                self.rigid_bones.len(),
            ));
        }
        let mut layout = self.constraint_blocks.take().unwrap();
        layout.links.clear();
        layout.limits.clear();
        layout.areas.clear();
        layout.bones.clear();
        layout.bends.clear();
        layout.flaps.clear();
        layout.edges.clear();
        layout.triangle_slot.fill(-1);
        for c in 0..self.con_count {
            let block = layout.block_of(self.con_a[c], self.con_b[c]);
            layout.links.add(c as i32, block);
            if self.con_boundary[c] && !self.con_dead[c] {
                layout.add_limit(c as i32, self.con_a[c], self.con_b[c]);
            }
        }
        for t in 0..self.tri_count {
            let block = layout.block_of3(self.tri_a[t], self.tri_b[t], self.tri_c[t]);
            layout.areas.add(t as i32, block);
            layout.triangle_slot[self.tri_id[t] as usize] = t as i32;
        }
        layout.near_count.fill(0);
        layout.links.finish_build();
        layout.areas.finish_build();
        layout.build_dispatch_order();
        // Порядок по цветам (закон с 27.11, color_order.rs): место под раскраску; сами цвета — при первом обходе набора.
        if self.cfg.color_order_on {
            layout.enable_colors(self.n);
        }
        for b in 0..self.rigid_bones.len() {
            self.place_bone_in_blocks(b, &mut layout);
        }
        let bend = &self.local_boundary.bend;
        for c in 0..bend.count {
            let block = layout.block_of(bend.a[c], bend.b[c]);
            layout.bends.add(c as i32, block);
        }
        layout.awake.fill(0);
        for i in 0..self.n {
            if !self.sleep.is_asleep(self.organism_of[i]) {
                let b = layout.particle_block[i] as usize;
                layout.awake[b] += 1;
            }
        }
        let flap = &self.local_boundary.flap;
        for c in 0..flap.count {
            let block = layout.block_of3(flap.a[c], flap.b[c], flap.owner[c]);
            layout.flaps.add(c as i32, block);
        }
        for k in 0..self.local_boundary.count {
            let block = self.local_boundary.block_of_slot(k, &layout.particle_block, &layout.seam_bucket);
            layout.edges.add(k as i32, block);
        }
        self.local_boundary.attached = true;
        self.constraint_blocks = Some(layout);
        self.compute_serial_blocks();
        // Черновики воркеров — по размерам этой топологии: собрать заново.
        self.parallel = crate::solver::parallel::ParallelState::default();
    }
}
