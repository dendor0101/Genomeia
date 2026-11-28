//! Разделение костей — `topology/BoneSplit.kt`: кость, потерявшая клетку или внутреннюю связь, делится между
//! тиками на связные куски; кусок из одной клетки становится мягкой тканью. Сброс мира возвращает кости к описанию.

use crate::solver::blocks::ConstraintBlocks;
use crate::solver::bones::BoneWallHits;
use crate::state::BodyState;

impl BodyState {
    pub fn mark_bone_dirty(&mut self, b: usize) {
        if self.bone_dirty_mark.len() <= b {
            let cap = self.rigid_bones.len().max(b + 1);
            self.bone_dirty_mark.resize(cap, false);
        }
        if self.bone_dirty_mark[b] {
            return;
        }
        self.bone_dirty_mark[b] = true;
        if self.bone_dirty_n == self.bone_dirty.len() {
            let cap = self.bone_dirty_n * 2;
            self.bone_dirty.resize(cap, 0);
        }
        self.bone_dirty[self.bone_dirty_n] = b as i32;
        self.bone_dirty_n += 1;
    }

    /// `noteBoneEdgeDeath`: ребро между клетками одной кости — её связность пересмотрят между тиками.
    pub fn note_bone_edge_death(&mut self, e: usize) {
        let b = self.bone_cluster_of[self.baked.edge_u(e) as usize];
        if b >= 0 && b == self.bone_cluster_of[self.baked.edge_v(e) as usize] {
            self.mark_bone_dirty(b as usize);
        }
    }

    /// `splitDirtyBones`: кости, потерявшие клетку или внутреннюю связь, — до всех стадий тика.
    pub fn split_dirty_bones(&mut self) {
        if self.bone_dirty_n == 0 {
            return;
        }
        let count = self.bone_dirty_n;
        let mut changed = false;
        for k in 0..count {
            let b = self.bone_dirty[k] as usize;
            self.bone_dirty_mark[b] = false;
            if b < self.rigid_bones.len() && self.split_bone(b) {
                changed = true;
            }
        }
        self.bone_dirty_n = 0;
        if !changed {
            return;
        }
        if self.wall_hits.capacity != self.rigid_bones.len() {
            self.wall_hits = BoneWallHits::new(self.rigid_bones.len());
        }
        if let Some(ct) = self.contacts.as_mut() {
            ct.invalidate_static();
        }
        self.refresh_surface();
        self.structure_version = self.structure_version.wrapping_add(1);
    }

    fn split_bone(&mut self, b: usize) -> bool {
        let ids = self.rigid_bones[b].clone();
        if ids.is_empty() {
            return false;
        }
        if self.bone_local.len() < self.n {
            self.bone_local = vec![0; self.n];
        }
        let mut live = 0usize;
        for k in 0..ids.len() {
            let i = ids[k] as usize;
            if self.cell_dead[i] {
                self.leave_bone(i, true);
                continue;
            }
            self.bone_local[i] = k as i32 + 1;
            live += 1;
        }
        let mut piece = vec![-1i32; ids.len()];
        let mut queue = vec![0i32; live];
        let mut pieces = 0i32;
        let mut largest = -1i32;
        let mut largest_size = 0usize;
        for k in 0..ids.len() {
            if self.bone_local[ids[k] as usize] == 0 || piece[k] >= 0 {
                continue;
            }
            let mut head = 0usize;
            let mut tail = 0usize;
            piece[k] = pieces;
            queue[tail] = ids[k];
            tail += 1;
            while head < tail {
                let v = queue[head] as usize;
                head += 1;
                for s in self.baked.vert_start[v]..self.baked.vert_start[v + 1] {
                    let s = s as usize;
                    let e = self.baked.vert_edge[s] as usize;
                    if self.baked.edge_dead[e] && !self.baked.is_unborn(e) {
                        continue;
                    }
                    let u = self.baked.vert_other[s] as usize;
                    let lu = self.bone_local[u];
                    if lu == 0 || piece[(lu - 1) as usize] >= 0 {
                        continue;
                    }
                    piece[(lu - 1) as usize] = pieces;
                    queue[tail] = u as i32;
                    tail += 1;
                }
            }
            if tail > largest_size {
                largest_size = tail;
                largest = pieces;
            }
            pieces += 1;
        }
        for &i in &ids {
            self.bone_local[i as usize] = 0;
        }
        if pieces == 1 && live == ids.len() {
            return false;
        }
        self.bones_split = true;
        let old_qx = self.bone_rest_qx[b].clone();
        let old_qy = self.bone_rest_qy[b].clone();
        let pieces_n = pieces as usize;
        let mut sizes = vec![0usize; pieces_n];
        for k in 0..ids.len() {
            if piece[k] >= 0 {
                sizes[piece[k] as usize] += 1;
            }
        }
        let mut bone_of_piece = vec![-1i32; pieces_n];
        let mut extra = 0usize;
        for p in 0..pieces_n {
            if sizes[p] >= 2 {
                bone_of_piece[p] = if p as i32 == largest {
                    b as i32
                } else {
                    let v = (self.rigid_bones.len() + extra) as i32;
                    extra += 1;
                    v
                };
            }
        }
        if extra > 0 {
            let b0 = self.rigid_bones.len();
            self.rigid_bones.resize(b0 + extra, Vec::new());
            self.bone_rest_qx.resize(b0 + extra, Vec::new());
            self.bone_rest_qy.resize(b0 + extra, Vec::new());
        }
        let was_member: Vec<bool> = ids.iter().map(|&i| self.bone_of[i as usize] == b as i32).collect();
        let mut fill = vec![0usize; pieces_n];
        for p in 0..pieces_n {
            let to = bone_of_piece[p];
            if to < 0 {
                continue;
            }
            let to = to as usize;
            self.rigid_bones[to] = vec![0; sizes[p]];
            self.bone_rest_qx[to] = vec![0.0; sizes[p]];
            self.bone_rest_qy[to] = vec![0.0; sizes[p]];
        }
        if largest < 0 || bone_of_piece[largest as usize] < 0 {
            self.rigid_bones[b] = Vec::new();
            self.bone_rest_qx[b] = Vec::new();
            self.bone_rest_qy[b] = Vec::new();
        }
        for k in 0..ids.len() {
            let p = piece[k];
            if p < 0 {
                continue;
            }
            let i = ids[k] as usize;
            let to = bone_of_piece[p as usize];
            if to < 0 {
                self.leave_bone(i, false);
                continue;
            }
            let to = to as usize;
            let at = fill[p as usize];
            fill[p as usize] += 1;
            self.rigid_bones[to][at] = i as i32;
            self.bone_rest_qx[to][at] = old_qx[k];
            self.bone_rest_qy[to][at] = old_qy[k];
            self.bone_cluster_of[i] = to as i32;
            if was_member[k] {
                self.bone_of[i] = to as i32;
            }
        }
        for p in 0..pieces_n {
            if bone_of_piece[p] >= 0 {
                self.recentre_bone_pose(bone_of_piece[p] as usize);
            }
        }
        if let Some(mut layout) = self.constraint_blocks.take() {
            layout.bones.remove(b as i32);
            for p in 0..pieces_n {
                if bone_of_piece[p] >= 0 {
                    self.place_bone_in_blocks(bone_of_piece[p] as usize, &mut layout);
                }
            }
            self.constraint_blocks = Some(layout);
        }
        for &i in &ids {
            self.baked.touch(i);
            if !self.cell_dead[i as usize] {
                self.sleep.request_wake(i);
            }
        }
        true
    }

    fn leave_bone(&mut self, i: usize, dead: bool) {
        self.bone_of[i] = -1;
        self.bone_cluster_of[i] = -1;
        if dead {
            self.match_weight[i] = 0.0;
        } else if self.drag_id != i as i32 {
            self.match_weight[i] = 1.0;
        }
        if !dead {
            self.refresh_cell_scale_ring(i);
        }
    }

    fn recentre_bone_pose(&mut self, b: usize) {
        let ids = &self.rigid_bones[b];
        let qx = &mut self.bone_rest_qx[b];
        let qy = &mut self.bone_rest_qy[b];
        let mut posed = 0i32;
        for k in 0..ids.len() {
            let i = ids[k] as usize;
            if self.bone_of[i] == b as i32 && !self.cell_unborn[i] {
                posed += 1;
            } else {
                qx[k] = 0.0;
                qy[k] = 0.0;
            }
        }
        if posed == 0 {
            return;
        }
        for _pass in 0..3 {
            let mut sx = 0.0f32;
            let mut sy = 0.0f32;
            for k in 0..ids.len() {
                sx += qx[k];
                sy += qy[k];
            }
            if sx == 0.0 && sy == 0.0 {
                break;
            }
            let dx = sx / posed as f32;
            let dy = sy / posed as f32;
            for k in 0..ids.len() {
                let i = ids[k] as usize;
                if self.bone_of[i] == b as i32 && !self.cell_unborn[i] {
                    qx[k] -= dx;
                    qy[k] -= dy;
                }
            }
        }
    }

    /// `placeBoneInBlocks`: кость — в корзину блоков (свою или шва).
    pub fn place_bone_in_blocks(&self, b: usize, layout: &mut ConstraintBlocks) {
        let ids = &self.rigid_bones[b];
        if ids.is_empty() {
            return;
        }
        layout.bones.remove(b as i32);
        let bucket = layout.bone_bucket(ids, &self.baked.vert_start, &self.baked.vert_other);
        layout.bones.add(b as i32, bucket);
    }

    /// `restoreSplitBones`: сброс мира — кости снова по описанию.
    pub fn restore_split_bones(&mut self) {
        if !self.bones_split {
            return;
        }
        self.bones_split = false;
        self.rigid_bones = self.body.bone_clusters.iter().filter(|c| c.len() >= 3).cloned().collect();
        self.bone_of.fill(-1);
        for (b, ids) in self.rigid_bones.iter().enumerate() {
            for &i in ids {
                self.bone_of[i as usize] = b as i32;
            }
        }
        self.bone_cluster_of = self.bone_of.clone();
        self.build_bone_rest_pose();
        self.bone_dirty_n = 0;
        self.bone_dirty_mark.fill(false);
        if self.wall_hits.capacity != self.rigid_bones.len() {
            self.wall_hits = BoneWallHits::new(self.rigid_bones.len());
        }
    }
}
