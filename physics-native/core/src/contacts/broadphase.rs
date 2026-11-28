//! Широкая фаза касаний — `updateCandidates` и соседи из `BoundaryContacts.kt`: перекладка уехавших вершин по
//! подвижной сетке, неподвижная сетка, поиск пар 3x3 по следу DDA, слияние в канонический порядок
//! (меньший номер, потом больший). Поиск пар по воркерам — parallel_broadphase.rs; перекладка, отбор уцелевших и
//! слияние пока в одном потоке (в Kotlin — ParallelReinsert, ParallelPairs; биты те же).

use super::helpers::PairKeyBuffer;
use super::parallel_broadphase::PARALLEL_QUERY_MIN;
use crate::solver::blocks::{SCOPE_ALL, SCOPE_WORLD};
use super::parallel_prepare::PARALLEL_PAIRS_MIN;
use super::{BoundaryContacts, ContactCtx};
use crate::jmath::sqrtf;

impl BoundaryContacts {
    #[inline]
    pub(crate) fn pack_key(lo: i32, hi: i32, near: bool) -> i64 {
        ((((lo as i64) << 32) | hi as i64) << 1) | if near { 1 } else { 0 }
    }

    /// Порог перекладки: дальше половины запаса от привязки.
    #[inline]
    fn moved_limit2(&self) -> f32 {
        let lim = 0.5f32 * self.bp_margin;
        lim * lim
    }

    /// `writeMoved`: уехавшие из movers[from, to) в moved начиная с [at]; возвращает конец.
    pub(crate) fn write_moved(&mut self, from: usize, to: usize, at: usize, px: &[f32], py: &[f32]) -> usize {
        let lim2 = self.moved_limit2();
        let mut o = at;
        for a in from..to {
            let v = self.movers[a] as usize;
            let dx = px[v] - self.bp_x[v];
            let dy = py[v] - self.bp_y[v];
            if dx * dx + dy * dy > lim2 {
                self.moved[o] = v as i32;
                o += 1;
            }
        }
        o
    }

    /// `shardOf`: часть подвижной сетки для ячейки [cell].
    #[inline]
    pub(crate) fn shard_of(&self, cell: i64) -> usize {
        let n = self.shards.len();
        if n == 1 {
            0
        } else {
            (((cell.wrapping_mul(super::helpers::GOLDEN) as u64) >> 33) as usize) % n
        }
    }

    /// `reshard`: разложить записи по [count] частям; набор записей и привязки прежние.
    pub(crate) fn reshard(&mut self, count: usize) {
        let mut vs: Vec<i32> = Vec::new();
        let mut ks: Vec<i64> = Vec::new();
        for g in &self.shards {
            g.live_entries(&mut vs, &mut ks);
        }
        self.shards = (0..count).map(|_| super::helpers::AnchoredContactGrid::new(self.n)).collect();
        for k in 0..vs.len() {
            let s = self.shard_of(ks[k]);
            self.shards[s].insert(vs[k], ks[k]);
        }
    }

    /// `countMoved`: сколько вершин movers[from, to) уехало от привязки дальше половины запаса.
    pub(crate) fn count_moved(&self, from: usize, to: usize, px: &[f32], py: &[f32]) -> usize {
        let lim2 = self.moved_limit2();
        let mut c = 0usize;
        for a in from..to {
            let v = self.movers[a] as usize;
            let dx = px[v] - self.bp_x[v];
            let dy = py[v] - self.bp_y[v];
            if dx * dx + dy * dy > lim2 {
                c += 1;
            }
        }
        c
    }

    /// `countKept`: сколько пар pair[from, to) уцелеет (ни одна вершина не перекладывается).
    pub(crate) fn count_kept(&self, from: usize, to: usize) -> usize {
        let mut c = 0usize;
        for p in from..to {
            if self.moved_mark[self.pair_a[p] as usize] != self.moved_stamp && self.moved_mark[self.pair_b[p] as usize] != self.moved_stamp {
                c += 1;
            }
        }
        c
    }

    /// `writeKept`: уцелевшие пары из pair[from, to) в kept_keys начиная с [at].
    pub(crate) fn write_kept(&mut self, from: usize, to: usize, at: usize) -> usize {
        let mut o = at;
        for p in from..to {
            let i = self.pair_a[p];
            let j = self.pair_b[p];
            if self.moved_mark[i as usize] == self.moved_stamp || self.moved_mark[j as usize] == self.moved_stamp {
                continue;
            }
            self.kept_keys[o] = Self::pack_key(i, j, true);
            self.kept_slot[o] = self.near_rest_slot[p];
            o += 1;
        }
        o
    }

    /// `ensureNearCapacity`: список ближних — сам список пар.
    pub(crate) fn ensure_near_capacity(&mut self) {
        self.near_n = self.pair_n;
        if self.near_rest_slot.len() < self.pair_n {
            let cap = self.pair_n * 2;
            self.near_rest_slot.resize(cap, 0);
        }
    }

    /// `anchorVertex`: новая привязка перекладываемой вершины — пометка, место, длина пути подшага.
    #[inline]
    pub(crate) fn anchor_vertex(&mut self, v: usize, px: &[f32], py: &[f32], qx: &[f32], qy: &[f32]) {
        self.moved_mark[v] = self.moved_stamp;
        self.bp_x[v] = px[v];
        self.bp_y[v] = py[v];
        let sx = px[v] - qx[v];
        let sy = py[v] - qy[v];
        self.bp_sweep[v] = sqrtf(sx * sx + sy * sy);
    }

    /// `reach`: может ли пара коснуться, пока обе вершины у своих привязок.
    #[inline]
    fn reach(&self, v: usize, j: usize, jx: f32, jy: f32, j_sweep: f32) -> bool {
        let lim = self.contact_radius[v] + self.contact_radius[j] + self.bp_margin + self.bp_sweep[v] + j_sweep;
        let dx = self.bp_x[v] - jx;
        let dy = self.bp_y[v] - jy;
        dx * dx + dy * dy <= lim * lim
    }

    /// `queryVertex`: пары одной перекладываемой вершины [v] в [out] — перебор 3x3 её следа в обеих сетках. Пометка
    /// [mark]/[stamp] и буферы DDA — вызывающего: у каждого воркера свои. Сетки и привязки здесь только читаются.
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn query_vertex_into(
        &self,
        v: usize,
        px: &[f32],
        py: &[f32],
        qx: &[f32],
        qy: &[f32],
        mark: &mut [i32],
        stamp: i32,
        dda_x: &mut [i32],
        dda_y: &mut [i32],
        out: &mut PairKeyBuffer,
        group: &[i32],
    ) {
        mark[v] = stamp;
        let segment = out.new_n;
        let c = Self::dda_into(self.cell_size, qx[v], qy[v], px[v], py[v], dda_x, dda_y);
        let vi = v as i32;
        for k in 0..c {
            let cx = dda_x[k];
            let cy = dda_y[k];
            for ox in -1..=1 {
                for oy in -1..=1 {
                    let cell = Self::key(cx.wrapping_add(ox), cy.wrapping_add(oy));
                    let g = &self.shards[self.shard_of(cell)];
                    let mut e = g.first(cell);
                    while e >= 0 {
                        let j = g.ids()[e as usize];
                        e = g.next()[e as usize];
                        let ju = j as usize;
                        if mark[ju] == stamp {
                            continue;
                        }
                        mark[ju] = stamp;
                        // Пара двух перекладываемых берётся один раз — со стороны меньшего.
                        if self.moved_mark[ju] == self.moved_stamp && j < vi {
                            continue;
                        }
                        if self.bonded(vi, j, group) {
                            continue;
                        }
                        if !self.reach(v, ju, self.bp_x[ju], self.bp_y[ju], self.bp_sweep[ju]) {
                            continue;
                        }
                        if j > vi {
                            out.add_new(Self::pack_key(vi, j, true));
                        } else {
                            out.add_low(Self::pack_key(j, vi, true));
                        }
                    }
                    e = if self.static_count > 0 { self.static_grid.first(cell) } else { -1 };
                    while e >= 0 {
                        let j = self.static_grid.ids[e as usize];
                        e = self.static_grid.next[e as usize];
                        let ju = j as usize;
                        if mark[ju] == stamp {
                            continue;
                        }
                        mark[ju] = stamp;
                        if self.bonded(vi, j, group) {
                            continue;
                        }
                        // Неподвижная вершина не сдвигается: её место и есть привязка.
                        if !self.reach(v, ju, px[ju], py[ju], 0.0) {
                            continue;
                        }
                        if j > vi {
                            out.add_new(Self::pack_key(vi, j, true));
                        } else {
                            out.add_low(Self::pack_key(j, vi, true));
                        }
                    }
                }
            }
        }
        // Пары этой вершины с большими номерами — по возрастанию партнёра (вставками).
        let nk = &mut out.new_keys;
        for x in segment + 1..out.new_n {
            let k = nk[x];
            let mut y = x as isize - 1;
            while y >= segment as isize && nk[y as usize] > k {
                nk[y as usize + 1] = nk[y as usize];
                y -= 1;
            }
            nk[(y + 1) as usize] = k;
        }
    }

    /// `inScope`: вершина [v] — текущей области (без островов — всегда).
    #[inline]
    pub(crate) fn in_scope(&self, v: usize, vertex_scope: &[i32]) -> bool {
        if !self.vertex_scope_on || self.scope == SCOPE_ALL {
            return true;
        }
        let vs = if v < vertex_scope.len() { vertex_scope[v] } else { SCOPE_WORLD };
        vs == self.scope
    }

    /// `setScope`: область [sc] для широкой фазы — она пересоберётся с нуля.
    pub fn set_scope(&mut self, sc: i32) {
        self.scope = sc;
        self.bp_valid = false;
        self.static_valid = false;
    }

    /// `rebuildStaticGrid`: вершины контура с нулевой обратной массой — в неподвижную сетку.
    fn rebuild_static_grid(&mut self, px: &[f32], py: &[f32], qx: &[f32], qy: &[f32], inv_mass: &[f32], ctx: &ContactCtx) {
        self.static_count = 0;
        for a in 0..self.vert_count {
            let v = self.verts[a] as usize;
            if inv_mass[v] <= 0.0 && self.in_scope(v, ctx.vertex_scope) {
                self.static_count += 1;
            }
        }
        self.static_grid.begin(self.static_count);
        // Без неподвижных вершин прошлой сборки чистить нечего: у копии острова их не бывает, а проход по всем
        // частицам мира шёл бы на каждый остров.
        if self.in_static.len() != self.n {
            self.in_static = vec![false; self.n];
        } else if self.in_static_any {
            self.in_static.fill(false);
        }
        self.in_static_any = self.static_count > 0;
        for a in 0..self.vert_count {
            let v = self.verts[a] as usize;
            if inv_mass[v] > 0.0 || !self.in_scope(v, ctx.vertex_scope) {
                continue;
            }
            self.in_static[v] = true;
            let c = Self::dda_into(self.cell_size, qx[v], qy[v], px[v], py[v], &mut self.dda_x, &mut self.dda_y);
            for k in 0..c {
                self.static_grid.insert(Self::key(self.dda_x[k], self.dda_y[k]), v as i32);
            }
        }
        self.static_valid = true;
    }

    /// `mergePairs`: уцелевшие, новые и нижние ключи — одним слиянием в канонический порядок.
    fn merge_pairs(&mut self, kept: usize, fresh: usize, low: usize) {
        self.keys.low_keys[..low].sort_unstable();
        let total = kept + fresh + low;
        if self.pair_a.len() < total {
            let mut cap = self.pair_a.len() * 2;
            while cap < total {
                cap *= 2;
            }
            self.pair_a.resize(cap, 0);
            self.pair_b.resize(cap, 0);
        }
        let (mut i, mut j, mut l, mut out) = (0usize, 0usize, 0usize, 0usize);
        while out < total {
            let mut k = i64::MAX;
            let mut from = 0;
            if i < kept {
                k = self.kept_keys[i];
                from = 1;
            }
            if j < fresh && self.keys.new_keys[j] < k {
                k = self.keys.new_keys[j];
                from = 2;
            }
            if l < low && self.keys.low_keys[l] < k {
                k = self.keys.low_keys[l];
                from = 3;
            }
            self.near_rest_slot[out] = if from == 1 { self.kept_slot[i] } else { -2 };
            match from {
                1 => i += 1,
                2 => j += 1,
                _ => l += 1,
            }
            let pair = (k as u64) >> 1;
            self.pair_a[out] = (pair >> 32) as i32;
            self.pair_b[out] = (pair & 0xFFFF_FFFF) as i32;
            out += 1;
        }
        self.pair_n = out;
    }

    /// `updateCandidates`: переложить уехавшие вершины и найти их пары. Полная перестройка — все подвижные.
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn update_candidates(&mut self, px: &[f32], py: &[f32], qx: &[f32], qy: &[f32], inv_mass: &[f32], ctx: &ContactCtx) {
        let full = !self.bp_valid || !self.static_valid || self.bp_margin <= 0.0 || self.opt_bp_margin < 0.0;
        if self.moved.len() < self.vert_count {
            self.moved = vec![0; self.vert_count];
        }
        self.moved_count = 0;
        if full {
            if !self.static_valid {
                self.rebuild_static_grid(px, py, qx, qy, inv_mass, ctx);
            }
            if self.movers.len() < self.vert_count {
                self.movers = vec![0; self.vert_count];
            }
            self.mover_count = 0;
            for a in 0..self.vert_count {
                let v = self.verts[a];
                if inv_mass[v as usize] > 0.0 && self.in_scope(v as usize, ctx.vertex_scope) {
                    self.movers[self.mover_count] = v;
                    self.mover_count += 1;
                }
            }
            // По возрастанию номера: на этом держится канонический порядок новых пар.
            self.movers[..self.mover_count].sort_unstable();
            for g in self.shards.iter_mut() {
                g.clear();
            }
            self.moved[..self.mover_count].copy_from_slice(&self.movers[..self.mover_count]);
            self.moved_count = self.mover_count;
            self.bp_valid = true;
        } else {
            self.moved_count = match self.workers.clone() {
                // Счёт по частям, потом запись со своего смещения (`ParallelMoved`).
                Some(w) if w.count > 1 && self.mover_count >= PARALLEL_PAIRS_MIN => self.moved_parallel(&w, px, py),
                _ => self.write_moved(0, self.mover_count, 0, px, py),
            };
            if self.moved_count == 0 {
                return;
            }
        }
        self.moved_stamp = self.moved_stamp.wrapping_add(1);
        match self.workers.clone() {
            // След по частям moved, перекладка — по частям сетки (`ParallelReinsert`, parallel_broadphase.rs).
            Some(w) if w.count > 1 && self.moved_count >= PARALLEL_QUERY_MIN => {
                if self.shards.len() != w.count {
                    self.reshard(w.count);
                }
                self.reinsert_parallel(&w, px, py, qx, qy);
            }
            _ => {
                for a in 0..self.moved_count {
                    let v = self.moved[a] as usize;
                    self.anchor_vertex(v, px, py, qx, qy);
                    for g in self.shards.iter_mut() {
                        g.remove(v as i32);
                    }
                    let c = Self::dda_into(self.cell_size, qx[v], qy[v], px[v], py[v], &mut self.dda_x, &mut self.dda_y);
                    for k in 0..c {
                        let cell = Self::key(self.dda_x[k], self.dda_y[k]);
                        let s = self.shard_of(cell);
                        self.shards[s].insert(v as i32, cell);
                    }
                }
            }
        }
        let mut kept = 0usize;
        if !full {
            if self.kept_keys.len() < self.pair_n {
                self.kept_keys = vec![0; self.pair_n * 2];
                self.kept_slot = vec![0; self.pair_n * 2];
            }
            kept = match self.workers.clone() {
                // Фильтр с сохранением порядка по частям (`ParallelPairs.keep`).
                Some(w) if w.count > 1 && self.pair_n >= PARALLEL_PAIRS_MIN => self.keep_parallel(&w),
                _ => self.write_kept(0, self.pair_n, 0),
            };
        }
        self.keys.new_n = 0;
        self.keys.low_n = 0;
        let mut low_runs = 0usize;
        match &self.workers {
            // Куски подряд по moved: склейка кусков по порядку даёт тот же список новых ключей, что один проход; нижние
            // приходят отсортированными прогонами (`ParallelCandidates`, parallel_broadphase.rs).
            Some(w) if w.count > 1 && self.moved_count >= PARALLEL_QUERY_MIN => {
                let w = w.clone();
                low_runs = self.query_moved_parallel(&w, px, py, qx, qy, ctx.group);
            }
            _ => {
                // Буферы одного потока берутся у касаний на время прохода (пустой Vec не выделяет памяти).
                let mut mark = std::mem::take(&mut self.mark);
                let mut dda_x = std::mem::take(&mut self.dda_x);
                let mut dda_y = std::mem::take(&mut self.dda_y);
                let empty = PairKeyBuffer { new_keys: Vec::new(), new_n: 0, low_keys: Vec::new(), low_n: 0 };
                let mut out = std::mem::replace(&mut self.keys, empty);
                let mut stamp = self.stamp;
                for a in 0..self.moved_count {
                    stamp = stamp.wrapping_add(1);
                    let v = self.moved[a] as usize;
                    self.query_vertex_into(v, px, py, qx, qy, &mut mark, stamp, &mut dda_x, &mut dda_y, &mut out, ctx.group);
                }
                self.stamp = stamp;
                self.mark = mark;
                self.dda_x = dda_x;
                self.dda_y = dda_y;
                self.keys = out;
            }
        }
        let total = kept + self.keys.new_n + self.keys.low_n;
        if self.near_rest_slot.len() < total {
            self.near_rest_slot.resize(total * 2, 0);
        }
        // Слияние по частям между ключами-разделителями (`ParallelPairs.merge`); мелкие источники — подряд.
        if let Some(w) = self.workers.clone().filter(|w| w.count > 1) {
            let (fresh, low) = (self.keys.new_n, self.keys.low_n);
            if total >= PARALLEL_PAIRS_MIN && self.merge_parallel(&w, kept, fresh, low, low_runs) {
                return;
            }
        }
        self.merge_pairs(kept, self.keys.new_n, self.keys.low_n);
        self.ensure_near_capacity();
    }
}
