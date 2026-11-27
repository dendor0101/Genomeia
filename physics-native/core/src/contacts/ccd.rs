//! CCD (обрезка подшага по времени первого удара, острова отката, кости целиком) и пробой мембраны — из
//! `BoundaryContacts.kt`. Отсев CCD и поиск пробоя по воркерам — в parallel_prepare.rs.

use super::helpers::PierceBuffer;
use super::parallel_prepare::{BOUNDS_PARALLEL_MIN, SCAN_CCD, SCAN_PIERCE};
use super::{BoundaryContacts, ContactCtx};
use crate::jmath::{maxf, sqrtf};

impl BoundaryContacts {
    // --- ПРОБОЙ ---

    pub(crate) fn pierce(&mut self, a: i32, b: i32) {
        for k in 0..self.pierce_n {
            if (self.pierce_a[k] == a && self.pierce_b[k] == b) || (self.pierce_a[k] == b && self.pierce_b[k] == a) {
                return;
            }
        }
        if self.pierce_n == self.pierce_a.len() {
            let cap = self.pierce_n * 2;
            self.pierce_a.resize(cap, 0);
            self.pierce_b.resize(cap, 0);
        }
        self.pierce_a[self.pierce_n] = a;
        self.pierce_b[self.pierce_n] = b;
        self.pierce_n += 1;
    }

    /// `inward`: клетка [i] перешла ребро (a, b) снаружи внутрь за подшаг — в системе самого ребра.
    #[allow(clippy::too_many_arguments)]
    #[inline]
    fn inward(qx: &[f32], qy: &[f32], px: &[f32], py: &[f32], i: usize, a: usize, b: usize, opp: i32) -> bool {
        if opp < 0 {
            return false;
        }
        let opp = opp as usize;
        let ex0 = qx[b] - qx[a];
        let ey0 = qy[b] - qy[a];
        let ex1 = px[b] - px[a];
        let ey1 = py[b] - py[a];
        if ex0 * ex0 + ey0 * ey0 < 1e-18f32 || ex1 * ex1 + ey1 * ey1 < 1e-18f32 {
            return false;
        }
        let mut n0x = -ey0;
        let mut n0y = ex0;
        if n0x * (qx[opp] - qx[a]) + n0y * (qy[opp] - qy[a]) > 0.0 {
            n0x = -n0x;
            n0y = -n0y;
        }
        let mut n1x = -ey1;
        let mut n1y = ex1;
        if n1x * (px[opp] - px[a]) + n1y * (py[opp] - py[a]) > 0.0 {
            n1x = -n1x;
            n1y = -n1y;
        }
        let s0 = n0x * (qx[i] - qx[a]) + n0y * (qy[i] - qy[a]);
        let s1 = n1x * (px[i] - px[a]) + n1y * (py[i] - py[a]);
        let el2 = ex1 * ex1 + ey1 * ey1;
        let t = ((px[i] - px[a]) * ex1 + (py[i] - py[a]) * ey1) / el2;
        if s0 <= 0.0 || s1 >= 0.0 {
            return false;
        }
        t > -0.1f32 && t < 1.1f32
    }

    /// `pierceAround`: пробой клетки [i] по рёбрам вокруг клетки контура [j]; найденные рёбра — в [out].
    #[allow(clippy::too_many_arguments)]
    fn pierce_around(&self, qx: &[f32], qy: &[f32], px: &[f32], py: &[f32], i: usize, j: usize, ctx: &ContactCtx, out: &mut PierceBuffer) {
        let org = ctx.organism_of;
        if org[i] == org[j] {
            return;
        }
        let nc = ctx.no_contact;
        let g = ctx.group;
        if g[j] != 0 {
            return;
        }
        for s2 in self.bnd_start[j]..self.bnd_end[j] {
            let s2 = s2 as usize;
            let o = self.bnd_other[s2] as usize;
            if nc[o] {
                continue;
            }
            if o != i && org[o] == org[j] && Self::inward(qx, qy, px, py, i, j, o, self.bnd_opp[s2]) {
                out.add(j as i32, o as i32);
            }
        }
    }

    /// `staticReach`: граница неподвижной вершины — половина самого длинного её ребра.
    fn static_reach(&self, v: usize, px: &[f32], py: &[f32]) -> f32 {
        let mut max_len2 = 0.0f32;
        for s in self.bnd_start[v]..self.bnd_end[v] {
            let o = self.bnd_other[s as usize] as usize;
            let ex = px[o] - px[v];
            let ey = py[o] - py[v];
            let len2 = ex * ex + ey * ey;
            if len2 > max_len2 {
                max_len2 = len2;
            }
        }
        0.5f32 * sqrtf(max_len2)
    }

    /// `pierceBoundsRange`: границы отсева для подвижных вершин movers[from, to). Каждая вершина пишет только свои ячейки.
    pub(crate) fn pierce_bounds_range(&mut self, from: usize, to: usize, px: &[f32], py: &[f32], qx: &[f32], qy: &[f32]) {
        for a in from..to {
            let v = self.movers[a] as usize;
            let mx = px[v] - qx[v];
            let my = py[v] - qy[v];
            let own2 = mx * mx + my * my;
            let mut max_move2 = own2;
            let mut max_len2 = 0.0f32;
            for s in self.bnd_start[v]..self.bnd_end[v] {
                let o = self.bnd_other[s as usize] as usize;
                let ex = px[o] - px[v];
                let ey = py[o] - py[v];
                let len2 = ex * ex + ey * ey;
                if len2 > max_len2 {
                    max_len2 = len2;
                }
                let ox = px[o] - qx[o];
                let oy = py[o] - qy[o];
                let move2 = ox * ox + oy * oy;
                if move2 > max_move2 {
                    max_move2 = move2;
                }
            }
            self.pierce_move[v] = sqrtf(own2);
            self.pierce_reach[v] = 0.5f32 * sqrtf(max_len2) + 3.0f32 * sqrtf(max_move2);
        }
    }

    /// `findPierceRange`: пары [from, to) — найденные рёбра в [out], в порядке пар.
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn find_pierce_range(
        &self,
        from: usize,
        to: usize,
        px: &[f32],
        py: &[f32],
        qx: &[f32],
        qy: &[f32],
        ctx: &ContactCtx,
        out: &mut PierceBuffer,
    ) {
        let min_move = 1e-3f32 * self.mean_link;
        let org = ctx.organism_of;
        let grp = ctx.group;
        let inv = ctx.inv_mass;
        for p in from..to {
            let i = self.pair_a[p] as usize;
            let j = self.pair_b[p] as usize;
            if org[i] == org[j] {
                continue;
            }
            if grp[i] != 0 || grp[j] != 0 {
                continue;
            }
            let dx = px[i] - px[j];
            let dy = py[i] - py[j];
            let d2 = dx * dx + dy * dy;
            let mi = sqrtf((px[i] - qx[i]) * (px[i] - qx[i]) + (py[i] - qy[i]) * (py[i] - qy[i]));
            let mj = sqrtf((px[j] - qx[j]) * (px[j] - qx[j]) + (py[j] - qy[j]) * (py[j] - qy[j]));
            let ri = (if inv[j] > 0.0 { self.pierce_reach[j] } else { self.static_reach(j, px, py) }) + mi;
            if mi > min_move && d2 <= ri * ri {
                self.pierce_around(qx, qy, px, py, i, j, ctx, out);
            }
            let rj = (if inv[i] > 0.0 { self.pierce_reach[i] } else { self.static_reach(i, px, py) }) + mj;
            if mj > min_move && d2 <= rj * rj {
                self.pierce_around(qx, qy, px, py, j, i, ctx, out);
            }
        }
    }

    /// `findPierce`: поиск пробоя по готовому списку пар.
    pub(crate) fn find_pierce(&mut self, px: &[f32], py: &[f32], qx: &[f32], qy: &[f32], ctx: &ContactCtx) {
        if self.bnd_start.len() != self.n + 1 {
            return;
        }
        if self.pierce_reach.len() < self.n {
            self.pierce_reach = vec![0.0; self.n];
            self.pierce_move = vec![0.0; self.n];
        }
        // По воркерам (`ParallelPierce`): куски пар копят рёбра у себя, склейка — в порядке кусков.
        if let Some(w) = self.prepare_workers(self.pair_n) {
            let mut p = self.take_parallel_prepare(w.count);
            let pairs = self.pair_n;
            self.scan_pairs_parallel(&w, &mut p, SCAN_PIERCE, pairs, px, py, qx, qy, ctx.inv_mass, ctx);
            for k in 0..p.chunks() {
                self.add_pierces(p.pierces(k));
            }
            self.parallel_prepare = Some(p);
            return;
        }
        if !self.skip_candidates_now {
            match &self.workers {
                Some(w) if w.count > 1 && self.mover_count >= BOUNDS_PARALLEL_MIN => {
                    let w = w.clone();
                    self.pierce_bounds_parallel(&w, px, py, qx, qy);
                }
                _ => self.pierce_bounds_range(0, self.mover_count, px, py, qx, qy),
            }
        }
        // Буфер одного потока берётся у касаний на время прохода (без выделения: пустой Vec не выделяет).
        let mut out = PierceBuffer { edges: std::mem::take(&mut self.serial_pierce.edges), n: 0 };
        self.find_pierce_range(0, self.pair_n, px, py, qx, qy, ctx, &mut out);
        self.add_pierces(&out);
        self.serial_pierce = out;
    }

    /// `scanPierce`: проверка пробоя после итерации (RB_PIERCE_ITER).
    pub fn scan_pierce(&mut self, px: &[f32], py: &[f32], qx: &[f32], qy: &[f32], ctx: &ContactCtx) {
        if self.opt_pierce_tear {
            self.find_pierce(px, py, qx, qy, ctx);
        }
    }

    // --- CCD ---

    fn ccd_find(&mut self, v: i32) -> i32 {
        let p = &mut self.ccd_parent;
        let mut r = v;
        while p[r as usize] != r {
            r = p[r as usize];
        }
        let mut x = v;
        while p[x as usize] != r {
            let nx = p[x as usize];
            p[x as usize] = r;
            x = nx;
        }
        r
    }

    /// `ccdPair`: тело цикла CCD для пары [p]; возвращает «было ли пересечение».
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn ccd_pair(&mut self, p: usize, hit_before: bool, px: &[f32], py: &[f32], qx: &[f32], qy: &[f32], inv_mass: &[f32], ctx: &ContactCtx) -> bool {
        let mut hit = hit_before;
        let a = self.pair_a[p] as usize;
        let j = self.pair_b[p] as usize;
        let d0x = qx[a] - qx[j];
        let d0y = qy[a] - qy[j];
        let dvx = (px[a] - qx[a]) - (px[j] - qx[j]);
        let dvy = (py[a] - qy[a]) - (py[j] - qy[j]);
        let rc = self.ccd_core * (self.contact_radius[a] + self.contact_radius[j]);
        let d02 = d0x * d0x + d0y * d0y;
        if self.kill_on_deep {
            let depth = self.kill_depth * maxf(self.contact_radius[a], self.contact_radius[j]);
            if d02 < depth * depth && inv_mass[a] + inv_mass[j] > 0.0 && !self.kill_immune(a as i32, j as i32, ctx) {
                self.start_deep.add(a as i32);
                self.start_deep.add(j as i32);
            }
        }
        let move2 = dvx * dvx + dvy * dvy;
        let gate = self.opt_ccd_gate * (self.contact_radius[a] + self.contact_radius[j]);
        if move2 <= gate * gate {
            if d02 > 4.0f32 * (rc * rc + move2) {
                return hit;
            }
            let reach = rc + sqrtf(move2);
            if d02 >= reach * reach {
                return hit;
            }
        }
        let c = d02 - rc * rc;
        if c <= 0.0 {
            return hit;
        }
        let aa = dvx * dvx + dvy * dvy;
        if aa < 1e-18f32 {
            return hit;
        }
        let bb = 2.0f32 * (d0x * dvx + d0y * dvy);
        if bb >= 0.0 {
            return hit;
        }
        let disc = bb * bb - 4.0f32 * aa * c;
        if disc < 0.0 {
            return hit;
        }
        let t = (-bb - sqrtf(disc)) / (2.0f32 * aa);
        if t < 0.0 || t >= 1.0 {
            return hit;
        }
        if !hit {
            for vi in 0..self.vert_count {
                let v = self.verts[vi] as usize;
                self.toi[v] = 1.0;
                self.ccd_parent[v] = v as i32;
            }
            hit = true;
        }
        if t < self.toi[a] {
            self.toi[a] = t;
        }
        if t < self.toi[j] {
            self.toi[j] = t;
        }
        let ra = self.ccd_find(a as i32);
        let rj = self.ccd_find(j as i32);
        if ra != rj {
            self.ccd_parent[ra as usize] = rj;
        }
        hit
    }

    /// `gatherCcdBones`: кости, задетые обрезкой, — одно время удара на кость и один остров.
    fn gather_ccd_bones(&mut self, bo: &[i32], count: usize) -> usize {
        if self.ccd_bone_t.len() < count {
            self.ccd_bone_t = vec![0.0; count];
            self.ccd_bone_rep = vec![0; count];
            self.ccd_bone_stamp = vec![0; count];
            self.ccd_bone_hit = vec![0; count];
        }
        self.ccd_bone_pass = self.ccd_bone_pass.wrapping_add(1);
        let pass = self.ccd_bone_pass;
        let mut hits = 0usize;
        for vi in 0..self.vert_count {
            let v = self.verts[vi] as usize;
            let tt = self.toi[v];
            if tt >= 1.0 {
                continue;
            }
            let b = bo[v];
            if b < 0 || b as usize >= count {
                continue;
            }
            let b = b as usize;
            if self.ccd_bone_stamp[b] != pass {
                self.ccd_bone_stamp[b] = pass;
                self.ccd_bone_t[b] = tt;
                self.ccd_bone_rep[b] = v as i32;
                self.ccd_bone_hit[hits] = b as i32;
                hits += 1;
            } else {
                if tt < self.ccd_bone_t[b] {
                    self.ccd_bone_t[b] = tt;
                }
                let ra = self.ccd_find(v as i32);
                let rep = self.ccd_bone_rep[b];
                let rb = self.ccd_find(rep);
                if ra != rb {
                    self.ccd_parent[ra as usize] = rb;
                }
            }
        }
        hits
    }

    /// `finishCcd`: откат к моменту удара; островам — отнятый у них импульс, поровну на массу.
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn finish_ccd(&mut self, px: &mut [f32], py: &mut [f32], qx: &[f32], qy: &[f32], inv_mass: &[f32], ctx: &ContactCtx) {
        let mut clamps = 0i32;
        let bo = ctx.bone_of;
        let bones_hit = match bo {
            Some(bo) if !ctx.bone_ids.is_empty() => self.gather_ccd_bones(bo, ctx.bone_ids.len()),
            _ => 0,
        };
        for vi in 0..self.vert_count {
            let v = self.verts[vi];
            if self.toi[v as usize] >= 1.0 {
                continue;
            }
            let r = self.ccd_find(v) as usize;
            self.isl_m[r] = 0.0;
            self.isl_x[r] = 0.0;
            self.isl_y[r] = 0.0;
        }
        for vi in 0..self.vert_count {
            let v = self.verts[vi] as usize;
            let tt = self.toi[v];
            if tt >= 1.0 {
                continue;
            }
            if bones_hit > 0 && bo.unwrap()[v] >= 0 {
                continue;
            }
            let ex = (px[v] - qx[v]) * (1.0f32 - tt);
            let ey = (py[v] - qy[v]) * (1.0f32 - tt);
            px[v] -= ex;
            py[v] -= ey;
            clamps += 1;
            if inv_mass[v] <= 0.0 {
                continue;
            }
            let m = 1.0f32 / inv_mass[v];
            let r = self.ccd_find(v as i32) as usize;
            self.isl_m[r] += m;
            self.isl_x[r] += m * ex;
            self.isl_y[r] += m * ey;
        }
        for k in 0..bones_hit {
            let b = self.ccd_bone_hit[k] as usize;
            let cut = 1.0f32 - self.ccd_bone_t[b];
            let rep = self.ccd_bone_rep[b];
            let r = self.ccd_find(rep) as usize;
            let bo = bo.unwrap();
            for &i in &ctx.bone_ids[b] {
                let i = i as usize;
                if bo[i] != b as i32 {
                    continue;
                }
                let ex = (px[i] - qx[i]) * cut;
                let ey = (py[i] - qy[i]) * cut;
                px[i] -= ex;
                py[i] -= ey;
                clamps += 1;
                if inv_mass[i] <= 0.0 {
                    continue;
                }
                let m = 1.0f32 / inv_mass[i];
                self.isl_m[r] += m;
                self.isl_x[r] += m * ex;
                self.isl_y[r] += m * ey;
            }
        }
        if clamps > 0 {
            for vi in 0..self.vert_count {
                let v = self.verts[vi] as usize;
                if self.toi[v] >= 1.0 || inv_mass[v] <= 0.0 {
                    continue;
                }
                if bones_hit > 0 && bo.unwrap()[v] >= 0 {
                    continue;
                }
                let r = self.ccd_find(v as i32) as usize;
                let mm = self.isl_m[r];
                if mm <= 0.0 {
                    continue;
                }
                px[v] += self.isl_x[r] / mm;
                py[v] += self.isl_y[r] / mm;
            }
            for k in 0..bones_hit {
                let b = self.ccd_bone_hit[k] as usize;
                let rep = self.ccd_bone_rep[b];
                let r = self.ccd_find(rep) as usize;
                let mm = self.isl_m[r];
                if mm <= 0.0 {
                    continue;
                }
                let sx = self.isl_x[r] / mm;
                let sy = self.isl_y[r] / mm;
                let bo = bo.unwrap();
                for &i in &ctx.bone_ids[b] {
                    let i = i as usize;
                    if bo[i] == b as i32 && inv_mass[i] > 0.0 {
                        px[i] += sx;
                        py[i] += sy;
                    }
                }
            }
        }
        self.last_toi_clamps = clamps;
    }

    /// `ccdClamp`: обрезка подшага по времени первого контакта (только ближние кандидаты).
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn ccd_clamp(&mut self, px: &mut [f32], py: &mut [f32], qx: &[f32], qy: &[f32], inv_mass: &[f32], ctx: &ContactCtx) {
        self.last_toi_clamps = 0;
        self.start_deep.clear();
        if self.near_n == 0 {
            return;
        }
        let mut hit = false;
        if let Some(w) = self.prepare_workers(self.near_n) {
            // Пары, которые тело пропустило бы без единой записи, отсеиваются по воркерам; остальные — подряд в
            // исходном порядке (`ParallelCcd`).
            let mut p = self.take_parallel_prepare(w.count);
            let near = self.near_n;
            self.scan_pairs_parallel(&w, &mut p, SCAN_CCD, near, px, py, qx, qy, inv_mass, ctx);
            hit = self.ccd_found_pairs(&p, hit, px, py, qx, qy, inv_mass, ctx);
            self.parallel_prepare = Some(p);
        } else {
            for p in 0..self.near_n {
                hit = self.ccd_pair(p, hit, px, py, qx, qy, inv_mass, ctx);
            }
        }
        if !hit {
            return;
        }
        self.finish_ccd(px, py, qx, qy, inv_mass, ctx);
    }

    /// `ccdSkips`: пару [p] тело CCD пропустит, ничего не записав, — не глубокое перекрытие и путь короче зазора до
    /// ядра. Те же условия и та же арифметика, что в [ccd_pair] (глубокое перекрытие — без проверки неуязвимости:
    /// такую пару тело смотрит само).
    #[allow(clippy::too_many_arguments)]
    #[inline]
    pub(crate) fn ccd_skips(&self, p: usize, px: &[f32], py: &[f32], qx: &[f32], qy: &[f32], inv_mass: &[f32]) -> bool {
        let a = self.pair_a[p] as usize;
        let j = self.pair_b[p] as usize;
        let d0x = qx[a] - qx[j];
        let d0y = qy[a] - qy[j];
        let dvx = (px[a] - qx[a]) - (px[j] - qx[j]);
        let dvy = (py[a] - qy[a]) - (py[j] - qy[j]);
        let rc = self.ccd_core * (self.contact_radius[a] + self.contact_radius[j]);
        let d02 = d0x * d0x + d0y * d0y;
        if self.kill_on_deep {
            let depth = self.kill_depth * maxf(self.contact_radius[a], self.contact_radius[j]);
            if d02 < depth * depth && inv_mass[a] + inv_mass[j] > 0.0 {
                return false;
            }
        }
        let move2 = dvx * dvx + dvy * dvy;
        let gate = self.opt_ccd_gate * (self.contact_radius[a] + self.contact_radius[j]);
        if move2 > gate * gate {
            return false;
        }
        if d02 > 4.0f32 * (rc * rc + move2) {
            return true;
        }
        let reach = rc + sqrtf(move2);
        d02 >= reach * reach
    }
}
