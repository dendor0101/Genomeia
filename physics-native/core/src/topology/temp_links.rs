//! Временные связи роста — функции BodyState из `topology/TempLinks.kt`: какие рёбра снимает рождение клетки,
//! снятие с проверкой «не мост ли ребро», пары связей без временных. Сборка описания с временными связями
//! (`withTempLinks`, сетка пересечений) живёт на стороне Kotlin: описание приходит в Rust готовым.

use crate::contacts::RestPairs;
use crate::definition::BodyDefinition;
use crate::params::TEMP_BRIDGE_REACH;
use crate::state::BodyState;
use crate::topology::linked::LinkedPairs;

impl BodyDefinition {
    /// `permanentLinkA`/`permanentLinkB`: связи описания без временных.
    pub fn permanent_links(&self) -> (Vec<i32>, Vec<i32>) {
        if !self.has_temp_links() {
            return (self.link_a.clone(), self.link_b.clone());
        }
        let mut a = Vec::with_capacity(self.link_count());
        let mut b = Vec::with_capacity(self.link_count());
        for l in 0..self.link_count() {
            if !self.is_temp_link(l) {
                a.push(self.link_a[l]);
                b.push(self.link_b[l]);
            }
        }
        (a, b)
    }
}

impl BodyState {
    /// `tempEdgeMask`: признак временного ребра по номеру ребра; None — временных связей нет.
    pub fn temp_edge_mask(&self) -> Option<Vec<bool>> {
        let d = &self.body;
        if !d.has_temp_links() {
            return None;
        }
        let mut mask = vec![false; self.baked.edge_count];
        for l in 0..d.link_count() {
            if d.is_temp_link(l) {
                mask[self.baked.link_edge[l] as usize] = true;
            }
        }
        Some(mask)
    }

    /// `permanentLinkedPairs`: пары связей решателя [from, to) без временных.
    pub fn permanent_linked_pairs(&self, from: usize, to: usize) -> LinkedPairs {
        match self.temp_edge_mask() {
            None => LinkedPairs::from(&self.con_a[from..to], &self.con_b[from..to], to - from),
            Some(mask) => {
                let mut a = Vec::with_capacity(to - from);
                let mut b = Vec::with_capacity(to - from);
                for c in from..to {
                    if !mask[self.con_edge[c] as usize] {
                        a.push(self.con_a[c]);
                        b.push(self.con_b[c]);
                    }
                }
                let m = a.len();
                LinkedPairs::from(&a, &b, m)
            }
        }
    }

    #[inline]
    fn temp_edge_live(&self, e: usize) -> bool {
        !self.baked.edge_dead[e] || self.baked.is_unborn(e)
    }

    #[inline]
    fn born_alive(&self, c: usize) -> bool {
        !self.cell_unborn[c] && !self.cell_dead[c]
    }

    /// `rebuildTempRetire`: указатель «клетка → снимаемые её рождением рёбра» и ожидающие рёбра.
    pub fn rebuild_temp_retire(&mut self) {
        self.temp_pending_n = 0;
        if !self.body.has_temp_links() {
            self.temp_retire_start = Vec::new();
            self.temp_retire_edge = Vec::new();
            self.temp_retire_other = Vec::new();
            self.temp_edge = Vec::new();
            return;
        }
        let n = self.n;
        let link_count = self.body.link_count();
        let mut start = vec![0i32; n + 1];
        let mut mask = vec![false; self.baked.edge_count];
        for l in 0..link_count {
            let (s0, s1) = (self.body.link_retire_start[l], self.body.link_retire_start[l + 1]);
            if s1 == s0 {
                continue;
            }
            let e = self.baked.link_edge[l] as usize;
            mask[e] = true;
            if !self.temp_edge_live(e) {
                continue;
            }
            for s in s0..s1 {
                let s = s as usize;
                let rc = &self.body.link_retire_cells;
                start[rc[2 * s] as usize + 1] += 1;
                if rc[2 * s + 1] >= 0 {
                    start[rc[2 * s + 1] as usize + 1] += 1;
                }
            }
        }
        for i in 0..n {
            start[i + 1] += start[i];
        }
        let total = start[n] as usize;
        let mut edges = vec![0i32; total];
        let mut other = vec![0i32; total];
        let mut fill = start.clone();
        for l in 0..link_count {
            let (s0, s1) = (self.body.link_retire_start[l], self.body.link_retire_start[l + 1]);
            if s1 == s0 {
                continue;
            }
            let e = self.baked.link_edge[l] as usize;
            if !self.temp_edge_live(e) {
                continue;
            }
            let mut due = false;
            for s in s0..s1 {
                let s = s as usize;
                let c = self.body.link_retire_cells[2 * s];
                let p = self.body.link_retire_cells[2 * s + 1];
                let fc = fill[c as usize] as usize;
                edges[fc] = e as i32;
                other[fc] = p;
                fill[c as usize] += 1;
                if p >= 0 {
                    let fp = fill[p as usize] as usize;
                    edges[fp] = e as i32;
                    other[fp] = c;
                    fill[p as usize] += 1;
                }
                if self.born_alive(c as usize) && (p < 0 || self.born_alive(p as usize)) {
                    due = true;
                }
            }
            if due && !self.baked.edge_dead[e] {
                self.add_temp_pending(e as i32);
            }
        }
        self.temp_retire_start = start;
        self.temp_retire_edge = edges;
        self.temp_retire_other = other;
        self.temp_edge = mask;
    }

    fn add_temp_pending(&mut self, e: i32) {
        if self.temp_pending_n == self.temp_pending.len() {
            let cap = 8usize.max(self.temp_pending_n * 2);
            self.temp_pending.resize(cap, 0);
        }
        self.temp_pending[self.temp_pending_n] = e;
        self.temp_pending_n += 1;
    }

    /// `connectedWithout`: есть ли путь между концами ребра [e] в обход него (не дальше TEMP_BRIDGE_REACH вершин).
    fn connected_without(&mut self, e: usize) -> bool {
        let u = self.baked.edge_u(e);
        let v = self.baked.edge_v(e);
        if self.temp_stamp.len() < self.n {
            self.temp_stamp = vec![0; self.n];
        }
        if self.temp_queue.len() < TEMP_BRIDGE_REACH as usize {
            self.temp_queue = vec![0; TEMP_BRIDGE_REACH as usize];
        }
        if self.temp_stamp_id == i32::MAX {
            self.temp_stamp.fill(0);
            self.temp_stamp_id = 0;
        }
        self.temp_stamp_id += 1;
        let id = self.temp_stamp_id;
        let b = &self.baked;
        let mut head = 0usize;
        let mut tail = 0usize;
        self.temp_queue[tail] = u;
        tail += 1;
        self.temp_stamp[u as usize] = id;
        while head < tail {
            let x = self.temp_queue[head] as usize;
            head += 1;
            for s in b.vert_start[x]..b.vert_start[x + 1] {
                let s = s as usize;
                let f = b.vert_edge[s] as usize;
                if f == e || b.edge_dead[f] {
                    continue;
                }
                let y = b.vert_other[s];
                if y == v {
                    return true;
                }
                if self.temp_stamp[y as usize] == id {
                    continue;
                }
                if tail == TEMP_BRIDGE_REACH as usize {
                    return false;
                }
                self.temp_stamp[y as usize] = id;
                self.temp_queue[tail] = y;
                tail += 1;
            }
        }
        false
    }

    fn retire_or_wait(&mut self, e: usize) {
        if self.baked.is_unborn(e) {
            self.retire_unborn_edge(e, true);
            return;
        }
        if self.baked.edge_dead[e] {
            return;
        }
        if self.connected_without(e) {
            self.kill_edge(e, true, true);
        } else {
            self.add_temp_pending(e as i32);
        }
    }

    /// `retirePendingTempEdges`: ожидающие рёбра, у которых появился обходной путь, — снять.
    pub fn retire_pending_temp_edges(&mut self) {
        let mut m = 0usize;
        for k in 0..self.temp_pending_n {
            let e = self.temp_pending[k] as usize;
            if self.baked.edge_dead[e] {
                continue;
            }
            if self.connected_without(e) {
                self.kill_edge(e, true, true);
            } else {
                self.temp_pending[m] = e as i32;
                m += 1;
            }
        }
        self.temp_pending_n = m;
    }

    /// `retireTempEdgesOf`: родилась клетка [i] — снять временные рёбра, которые она снимает.
    pub fn retire_temp_edges_of(&mut self, i: usize) {
        if self.temp_retire_start.is_empty() {
            return;
        }
        let (s0, s1) = (self.temp_retire_start[i], self.temp_retire_start[i + 1]);
        for s in s0..s1 {
            let s = s as usize;
            let p = self.temp_retire_other[s];
            if p >= 0 && (self.cell_unborn[p as usize] || self.cell_dead[p as usize]) {
                continue;
            }
            let e = self.temp_retire_edge[s] as usize;
            self.retire_or_wait(e);
        }
        if self.temp_pending_n > 0 {
            self.retire_pending_temp_edges();
        }
    }

    pub fn retire_temp_edge(&mut self, e: usize) {
        if self.baked.is_unborn(e) {
            self.retire_unborn_edge(e, true);
        } else if !self.baked.edge_dead[e] {
            self.kill_edge(e, true, true);
        }
    }

    #[inline]
    pub fn is_temp_edge(&self, e: usize) -> bool {
        e < self.temp_edge.len() && self.temp_edge[e]
    }

    /// `retireAllTempEdges`: целые тела — все временные связи сняты.
    pub fn retire_all_temp_edges(&mut self) -> bool {
        if !self.body.has_temp_links() {
            return false;
        }
        let mut any = false;
        for l in 0..self.body.link_count() {
            if !self.body.is_temp_link(l) {
                continue;
            }
            let e = self.baked.link_edge[l] as usize;
            if !self.temp_edge_live(e) {
                continue;
            }
            self.retire_temp_edge(e);
            any = true;
        }
        any
    }

    /// Пары покоя тела по его постоянным связям (как `bakeRestPairs` в buildContacts).
    pub fn bake_body_rest_pairs(&self, ever: &LinkedPairs) -> RestPairs {
        let (pa, pb) = self.body.permanent_links();
        let max_r = crate::contacts::max_contact_radius(
            self.n,
            &pa,
            &pb,
            pa.len(),
            &self.radius,
            crate::params::CONTACT_SCALE,
            &self.body.x,
            &self.body.y,
            self.cfg.contacts.contact_seal,
        );
        crate::contacts::bake_rest_pairs(self.n, &max_r, &self.body.x, &self.body.y, ever)
    }
}
