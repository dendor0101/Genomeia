//! Подготовка подшага (prepare), сборка касаний, решение касаний (XPBD с накоплением множителя), упоры в
//! неподвижное, силы на клетку с потолком, жёсткие кости, отскок — из `BoundaryContacts.kt`. Только путь одного
//! потока (параллельный в Kotlin даёт те же биты).

use super::helpers::ContactSolveSink;
use super::parallel_prepare::SCAN_TOUCH;
use super::*;
use crate::jmath::{maxf, sqrtf};

/// Касаний подшага, с которых решение идёт по воркерам (`PARALLEL_SOLVE_MIN` в BoundaryContacts.kt).
const PARALLEL_SOLVE_MIN: usize = 2048;
/// Жёстких кластеров, с которых их масса, центр и момент считаются по воркерам (`updateBones`: 64).
const PARALLEL_BONES_MIN: usize = 64;

impl BoundaryContacts {
    /// `boneIdx`: номер жёсткого кластера клетки или -1.
    #[inline]
    pub(crate) fn bone_idx(&self, i: usize, bo: Option<&[i32]>) -> i32 {
        let bo = match bo {
            Some(bo) => bo,
            None => return -1,
        };
        let b = bo[i];
        if b < 0 || b as usize >= self.bone_m.len() || self.bone_m[b as usize] <= 0.0 {
            return -1;
        }
        b
    }

    /// `prepare`: сетка, CCD, пробой, список касаний подшага. Зовётся после интегрирования.
    #[allow(clippy::too_many_arguments)]
    pub fn prepare(
        &mut self,
        px: &mut [f32],
        py: &mut [f32],
        qx: &[f32],
        qy: &[f32],
        vx: &[f32],
        vy: &[f32],
        inv_mass: &[f32],
        continuing: bool,
        ctx: &ContactCtx,
    ) {
        self.inv_mass_seen = true;
        self.inv_mass_len_seen = inv_mass.len();
        if continuing {
            self.pass_cache.save(self.c_n, &self.c_i, &self.c_j, &self.c_lam_tot, &self.c_lam_init, &self.c_vn);
        }
        if !self.opt_ab_legacy {
            self.lam.clear();
        }
        if self.opt_xpbd_warm_start && !self.opt_ab_legacy {
            for c in 0..self.c_n {
                if self.c_lam_tot[c] > 0.0 {
                    self.lam.put(Self::pair_key(self.c_i[c], self.c_j[c]), self.c_lam_tot[c]);
                }
            }
        }
        if !self.skip_candidates_now || !self.bp_valid || !self.static_valid {
            self.update_candidates(px, py, qx, qy, inv_mass, ctx);
        }
        // CCD, пробой и касания одной задачей по кускам пар (parallel_prepare.rs, `prepareFused`).
        if self.fused_prepare_allowed(continuing) {
            let w = self.workers.clone().unwrap();
            self.prepare_fused(&w, px, py, qx, qy, vx, vy, inv_mass, ctx);
            return;
        }
        self.ccd_clamp(px, py, qx, qy, inv_mass, ctx);
        if self.opt_pierce_tear && !continuing {
            self.find_pierce(px, py, qx, qy, ctx);
        }
        self.build_contacts(px, py, vx, vy, continuing, ctx);
    }

    pub(crate) fn build_contacts(&mut self, px: &[f32], py: &[f32], vx: &[f32], vy: &[f32], continuing: bool, ctx: &ContactCtx) {
        self.c_n = 0;
        if !continuing {
            if let Some(w) = self.prepare_workers(self.near_n) {
                // Фильтр с сохранением порядка: перекрытые пары по кускам, запись со своего смещения (`ParallelBuild`).
                let mut p = self.take_parallel_prepare(w.count);
                let near = self.near_n;
                // Счёт касаний не читает начала подшага: qx, qy — пустые.
                self.scan_pairs_parallel(&w, &mut p, SCAN_TOUCH, near, px, py, &[], &[], ctx.inv_mass, ctx);
                let total = self.write_contacts_parallel(&w, &mut p, px, py, vx, vy, ctx);
                self.c_n = total;
                self.last_contacts = total;
                self.parallel_prepare = Some(p);
                return;
            }
        }
        for p in 0..self.near_n {
            if !self.contact_touches(p, px, py, ctx) {
                continue;
            }
            self.ensure_contact_capacity(self.c_n + 1);
            let c = self.c_n;
            self.write_contact(p, c, px, py, vx, vy, ctx);
            if continuing {
                let (a, b) = (self.pair_a[p], self.pair_b[p]);
                self.pass_cache.restore(c, a, b, &mut self.c_lam_tot, &mut self.c_lam_init, &mut self.c_vn);
            }
            self.c_n += 1;
        }
        self.last_contacts = self.c_n;
    }

    /// `contactTouches`: перекрыта ли ближняя пара [p].
    #[inline]
    pub(crate) fn contact_touches(&mut self, p: usize, px: &[f32], py: &[f32], ctx: &ContactCtx) -> bool {
        let i = self.pair_a[p] as usize;
        let j = self.pair_b[p] as usize;
        let dx = px[i] - px[j];
        let dy = py[i] - py[j];
        let d2 = dx * dx + dy * dy;
        let rr0 = self.contact_radius[i] + self.contact_radius[j];
        if d2 >= rr0 * rr0 {
            return false;
        }
        let rr = self.pair_contact_distance(p, rr0, ctx);
        d2 < rr * rr
    }

    pub(crate) fn ensure_contact_capacity(&mut self, need: usize) {
        if need <= self.c_i.len() {
            return;
        }
        let mut cap = self.c_i.len() * 2;
        while cap < need {
            cap *= 2;
        }
        self.c_rr.resize(cap, 0.0);
        self.c_bone.resize(cap, false);
        self.c_fixed.resize(cap, 0);
        self.c_i.resize(cap, 0);
        self.c_j.resize(cap, 0);
        self.c_nx.resize(cap, 0.0);
        self.c_ny.resize(cap, 0.0);
        self.c_vn.resize(cap, 0.0);
        self.c_lam.resize(cap, 0.0);
        self.c_lam_tot.resize(cap, 0.0);
        self.c_lam_init.resize(cap, 0.0);
        self.c_ax.resize(cap, 0.0);
        self.c_ay.resize(cap, 0.0);
    }

    /// `writeContact`: касание [c] из перекрытой ближней пары [p].
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn write_contact(&mut self, p: usize, c: usize, px: &[f32], py: &[f32], vx: &[f32], vy: &[f32], ctx: &ContactCtx) {
        let i = self.pair_a[p];
        let j = self.pair_b[p];
        let (iu, ju) = (i as usize, j as usize);
        let dx = px[iu] - px[ju];
        let dy = py[iu] - py[ju];
        let d2 = dx * dx + dy * dy;
        let rr = self.pair_contact_distance(p, self.contact_radius[iu] + self.contact_radius[ju], ctx);
        let d = sqrtf(d2);
        let (nx, ny) = if d > 1e-12f32 { (dx / d, dy / d) } else { (1.0f32, 0.0f32) };
        self.c_i[c] = i;
        self.c_j[c] = j;
        self.c_nx[c] = nx;
        self.c_ny[c] = ny;
        self.c_rr[c] = rr;
        self.c_bone[c] = match ctx.bone_of {
            Some(bo) => bo[iu] >= 0 || bo[ju] >= 0,
            None => false,
        };
        self.c_fixed[c] = self.contact_kind(i, j, ctx);
        self.c_vn[c] = (vx[iu] - vx[ju]) * nx + (vy[iu] - vy[ju]) * ny;
        self.c_lam[c] = 0.0;
        self.c_lam_tot[c] =
            if self.opt_xpbd_warm_start || self.opt_ab_legacy { self.lam.get_or_default(Self::pair_key(i, j), 0.0) } else { 0.0 };
        self.c_lam_init[c] = -1.0;
    }

    /// `updateBones`: масса, центр и момент инерции жёстких кластеров на подшаг.
    pub fn update_bones(&mut self, px: &[f32], py: &[f32], inv_mass: &[f32], bo: Option<&[i32]>, ids: &[Vec<i32>]) {
        let bo = match bo {
            Some(bo) if !ids.is_empty() => bo,
            _ => return,
        };
        if self.bone_m.len() != ids.len() {
            self.bone_m = vec![0.0; ids.len()];
            self.bone_cx = vec![0.0; ids.len()];
            self.bone_cy = vec![0.0; ids.len()];
            self.bone_i = vec![0.0; ids.len()];
        }
        if self.c_n == 0 && self.start_deep.count == 0 {
            return;
        }
        // Копия острова считает только свои кости: чужие пишет их остров или мир, и касаний с ними нет.
        if self.island_bones_on {
            for x in 0..self.island_bones.len() {
                let b = self.island_bones[x] as usize;
                self.update_bone(b, px, py, inv_mass, bo, ids);
            }
            return;
        }
        if let Some(w) = self.workers.clone().filter(|w| w.count > 1 && ids.len() >= PARALLEL_BONES_MIN) {
            // Кластеры независимы: у каждого свои масса, центр и момент — части по 4 на воркер (`ParallelBones`).
            let parts = 4 * w.count;
            let bones = ids.len();
            let ct = crate::solver::pool::SharedMut(self as *mut BoundaryContacts);
            w.run(parts, &|k, _| {
                let from = (bones as i64 * k as i64 / parts as i64) as usize;
                let to = (bones as i64 * (k as i64 + 1) / parts as i64) as usize;
                // SAFETY: часть k пишет массу, центр и момент только своих кластеров.
                let ct = unsafe { ct.get() };
                for b in from..to {
                    ct.update_bone(b, px, py, inv_mass, bo, ids);
                }
            });
            return;
        }
        for b in 0..ids.len() {
            self.update_bone(b, px, py, inv_mass, bo, ids);
        }
    }

    /// `updateBone`: масса, центр и момент инерции кластера [b] (только включённые клетки).
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn update_bone(&mut self, b: usize, px: &[f32], py: &[f32], inv_mass: &[f32], bo: &[i32], ids: &[Vec<i32>]) {
        let mut m = 0.0f32;
        let mut cx = 0.0f32;
        let mut cy = 0.0f32;
        for &k in &ids[b] {
            let k = k as usize;
            if inv_mass[k] <= 0.0 || bo[k] != b as i32 {
                continue;
            }
            let w = 1.0f32 / inv_mass[k];
            m += w;
            cx += w * px[k];
            cy += w * py[k];
        }
        self.bone_m[b] = m;
        if m <= 0.0 {
            self.bone_i[b] = 0.0;
            return;
        }
        cx /= m;
        cy /= m;
        self.bone_cx[b] = cx;
        self.bone_cy[b] = cy;
        let mut inert = 0.0f32;
        for &k in &ids[b] {
            let k = k as usize;
            if inv_mass[k] <= 0.0 || bo[k] != b as i32 {
                continue;
            }
            let rx = px[k] - cx;
            let ry = py[k] - cy;
            inert += (rx * rx + ry * ry) / inv_mass[k];
        }
        self.bone_i[b] = inert;
    }

    // --- счёт касаний «кость — сосед» ---

    /// `boneTouchKey`: кость [b] и сосед частицы [other] — её кость, все неподвижные разом или её организм.
    #[inline]
    fn bone_touch_key(&self, b: i32, other: usize, inv_mass: &[f32], bo: Option<&[i32]>, org: &[i32]) -> i64 {
        let ob = self.bone_idx(other, bo);
        let partner = if ob >= 0 {
            ob
        } else if inv_mass[other] <= 0.0 {
            -2
        } else {
            (-3i32).wrapping_sub(org[other])
        };
        ((b as i64) << 32) | (partner as i64 & 0xFFFF_FFFF)
    }

    #[inline]
    fn bt_slot(&self, key: i64) -> usize {
        let mask = self.bt_keys.len() as i32 - 1;
        let mut h = ((key.wrapping_mul(-7046029254386353131i64) as u64 >> 40) as i32) & mask;
        while self.bt_stamp[h as usize] == self.bt_gen && self.bt_keys[h as usize] != key {
            h = (h + 1) & mask;
        }
        h as usize
    }

    #[inline]
    fn bone_touch_count(&self, key: i64) -> i32 {
        let h = self.bt_slot(key);
        if self.bt_stamp[h] == self.bt_gen {
            self.bt_vals[h]
        } else {
            0
        }
    }

    fn bump_bone_touch(&mut self, key: i64) {
        if self.bt_used * 2 >= self.bt_keys.len() {
            self.grow_bone_touch();
        }
        let h = self.bt_slot(key);
        if self.bt_stamp[h] != self.bt_gen {
            self.bt_stamp[h] = self.bt_gen;
            self.bt_keys[h] = key;
            self.bt_vals[h] = 0;
            self.bt_used += 1;
        }
        self.bt_vals[h] += 1;
    }

    fn grow_bone_touch(&mut self) {
        let keys = std::mem::take(&mut self.bt_keys);
        let vals = std::mem::take(&mut self.bt_vals);
        let stamp = std::mem::take(&mut self.bt_stamp);
        let old_gen = self.bt_gen;
        self.bt_keys = vec![0; keys.len() * 2];
        self.bt_vals = vec![0; keys.len() * 2];
        self.bt_stamp = vec![0; keys.len() * 2];
        self.bt_gen = 1;
        self.bt_used = 0;
        for s in 0..keys.len() {
            if stamp[s] == old_gen {
                let h = self.bt_slot(keys[s]);
                self.bt_stamp[h] = self.bt_gen;
                self.bt_keys[h] = keys[s];
                self.bt_vals[h] = vals[s];
                self.bt_used += 1;
            }
        }
    }

    /// `countBoneContacts`: перекрытые касания по парам «кость — сосед», до позиционного прохода.
    fn count_bone_contacts(&mut self, px: &[f32], py: &[f32], inv_mass: &[f32], ctx: &ContactCtx) {
        if self.bt_gen == i32::MAX {
            self.bt_stamp.fill(0);
            self.bt_gen = 0;
        }
        self.bt_gen += 1;
        self.bt_used = 0;
        let bo = ctx.bone_of;
        if bo.is_none() || self.bone_m.is_empty() {
            return;
        }
        for c in 0..self.c_n {
            if !self.c_bone[c] {
                continue;
            }
            let i = self.c_i[c] as usize;
            let j = self.c_j[c] as usize;
            let dx = px[i] - px[j];
            let dy = py[i] - py[j];
            let rr = self.c_rr[c];
            if dx * dx + dy * dy >= rr * rr {
                continue;
            }
            let bi = self.bone_idx(i, bo);
            let bj = self.bone_idx(j, bo);
            if bi >= 0 {
                let k = self.bone_touch_key(bi, j, inv_mass, bo, ctx.organism_of);
                self.bump_bone_touch(k);
            }
            if bj >= 0 {
                let k = self.bone_touch_key(bj, i, inv_mass, bo, ctx.organism_of);
                self.bump_bone_touch(k);
            }
        }
    }

    /// `effInvMassSplit`: эффективная обратная масса точки вдоль нормали; масса кости делится между её касаниями
    /// с этим соседом.
    #[allow(clippy::too_many_arguments)]
    #[inline]
    fn eff_inv_mass_split(&self, i: usize, other: usize, nx: f32, ny: f32, px: &[f32], py: &[f32], inv_mass: &[f32], ctx: &ContactCtx) -> f32 {
        let bo = match ctx.bone_of {
            Some(bo) => bo,
            None => return inv_mass[i],
        };
        let b = bo[i];
        if b < 0 || b as usize >= self.bone_m.len() || self.bone_m[b as usize] <= 0.0 {
            return inv_mass[i];
        }
        let bu = b as usize;
        let rx = px[i] - self.bone_cx[bu];
        let ry = py[i] - self.bone_cy[bu];
        let rn = rx * ny - ry * nx;
        let rot = if self.bone_i[bu] > self.bone_m[bu] * self.min_bone_arm2 { rn * rn / self.bone_i[bu] } else { 0.0 };
        let z = self.bone_touch_count(self.bone_touch_key(b, other, inv_mass, ctx.bone_of, ctx.organism_of));
        if z > 1 {
            (1.0f32 / self.bone_m[bu] + rot) * z as f32
        } else {
            1.0f32 / self.bone_m[bu] + rot
        }
    }

    fn bump_z(&mut self, i: usize, bo: Option<&[i32]>) {
        let b = self.bone_idx(i, bo);
        if b < 0 {
            if self.frc_stamp[i] != self.pass {
                self.frc_stamp[i] = self.pass;
                self.frc_z[i] = 0;
            }
            self.frc_z[i] += 1;
            return;
        }
        let bu = b as usize;
        if self.bone_z.len() < self.bone_m.len() {
            self.bone_z = vec![0; self.bone_m.len()];
            self.bone_z_stamp = vec![-1; self.bone_m.len()];
        }
        if self.bone_z_stamp[bu] != self.pass {
            self.bone_z_stamp[bu] = self.pass;
            self.bone_z[bu] = 0;
        }
        self.bone_z[bu] += 1;
    }

    #[inline]
    fn z_of(&self, i: usize, bo: Option<&[i32]>) -> i32 {
        let b = self.bone_idx(i, bo);
        let z = if b < 0 { self.frc_z[i] } else { self.bone_z[b as usize] };
        if z < 1 {
            1
        } else {
            z
        }
    }

    fn add_kill(&mut self, v: i32) {
        self.damage.add(v);
    }

    fn add_force(&mut self, k: usize, ax: f32, ay: f32, w: f32) {
        if self.frc_stamp[k] != self.pass {
            self.frc_stamp[k] = self.pass;
            self.frc_x[k] = 0.0;
            self.frc_y[k] = 0.0;
            self.frc_w[k] = 0.0;
            if self.frc_n >= self.frc_list.len() {
                let cap = self.frc_list.len() * 2;
                self.frc_list.resize(cap, 0);
            }
            self.frc_list[self.frc_n] = k as i32;
            self.frc_n += 1;
        }
        self.frc_x[k] += ax;
        self.frc_y[k] += ay;
        if w > self.frc_w[k] {
            self.frc_w[k] = w;
        }
    }

    pub(crate) fn add_solved(&mut self, sink: &ContactSolveSink) {
        for k in 0..sink.kill_n {
            self.add_kill(sink.kills[k]);
        }
        self.attract_n += sink.attract;
        self.impulse_accum += sink.impulse;
    }

    /// `requestKill`: центр одной клетки в радиусе другой — заявка на гибель (кость бессмертна).
    fn request_kill(&self, i: usize, j: usize, sink: &mut ContactSolveSink, ctx: &ContactCtx) {
        if self.kill_immune(i as i32, j as i32, ctx) {
            return;
        }
        if self.bone_idx(i, ctx.bone_of) < 0 {
            sink.add_kill(i as i32);
        }
        if self.bone_idx(j, ctx.bone_of) < 0 {
            sink.add_kill(j as i32);
        }
    }

    /// `solveContactPair`: касание [c] от состояния на входе в подшаг; true — есть приращение (cAx/cAy, массы в sink).
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn solve_contact_pair(
        &mut self,
        c: usize,
        px: &[f32],
        py: &[f32],
        vx: &[f32],
        vy: &[f32],
        inv_mass: &[f32],
        h: f32,
        sink: &mut ContactSolveSink,
        ctx: &ContactCtx,
    ) -> bool {
        let i = self.c_i[c] as usize;
        let j = self.c_j[c] as usize;
        let dx = px[i] - px[j];
        let dy = py[i] - py[j];
        let d = sqrtf(dx * dx + dy * dy);
        let rr = self.c_rr[c];
        let (nx, ny, cc) = if d < 1e-12f32 { (self.c_nx[c], self.c_ny[c], -rr) } else { (dx / d, dy / d, d - rr) };
        self.c_nx[c] = nx;
        self.c_ny[c] = ny;
        self.c_lam[c] = 0.0;
        self.c_ax[c] = 0.0;
        self.c_ay[c] = 0.0;
        if cc >= 0.0 {
            self.c_lam_tot[c] = 0.0;
            self.c_lam_init[c] = 0.0;
            if self.opt_ab_legacy {
                self.lam.remove(Self::pair_key(i as i32, j as i32));
            }
            return false;
        }
        let kind = self.c_fixed[c];
        let fixed = kind == KIND_FIXED || kind == KIND_PUSHED;
        if fixed != self.solving_fixed {
            return false;
        }
        let bo = ctx.bone_of;
        let mut wi = self.eff_inv_mass_split(i, j, nx, ny, px, py, inv_mass, ctx);
        let mut wj = self.eff_inv_mass_split(j, i, nx, ny, px, py, inv_mass, ctx);
        if fixed {
            if self.bone_idx(i, bo) < 0 {
                wi *= self.z_of(i, bo) as f32;
            }
            if self.bone_idx(j, bo) < 0 {
                wj *= self.z_of(j, bo) as f32;
            }
        }
        let w = wi + wj;
        if w <= 0.0 {
            return false;
        }
        if self.kill_on_deep && self.fixed_sweep_now == 0 && d < self.kill_depth * maxf(self.contact_radius[i], self.contact_radius[j]) {
            self.request_kill(i, j, sink, ctx);
        }
        let _ = (vx, vy);
        // XPBD: касание как ограничение с накоплением множителя (CONTACT_OMEGA_PAIR — податливость на пару).
        let alpha_t = if fixed {
            w / (FIXED_CONTACT_OMEGA * FIXED_CONTACT_OMEGA)
        } else if self.opt_contact_omega_pair > 0.0 {
            w / (self.opt_contact_omega_pair * self.opt_contact_omega_pair)
        } else {
            self.opt_contact_compliance / (h * h)
        };
        let mut l = self.c_lam_tot[c];
        if self.opt_ab_legacy {
            self.c_lam_init[c] = 0.0;
        }
        if self.c_lam_init[c] < 0.0 {
            let cap = -cc / alpha_t;
            if l > cap {
                l = cap;
            }
            self.c_lam_init[c] = l;
        }
        let mut d_lam = (-cc - alpha_t * l) / (w + alpha_t);
        if d_lam < 0.0 {
            sink.attract += 1;
        }
        let floor = self.c_lam_init[c];
        if l + d_lam < floor {
            d_lam = floor - l;
        }
        l += d_lam;
        self.c_lam_tot[c] = l;
        if self.opt_ab_legacy {
            self.lam.put(Self::pair_key(i as i32, j as i32), l);
        }
        self.c_lam[c] = if d_lam > 0.0 { d_lam } else { 0.0 };
        sink.impulse += self.c_lam[c];
        if d_lam == 0.0 {
            return false;
        }
        if d_lam > 0.0 {
            if fixed {
                self.c_fixed[c] = KIND_PUSHED;
            } else if kind == KIND_OWN {
                self.c_fixed[c] = KIND_OWN_PUSHED;
            }
        }
        self.c_ax[c] = d_lam * nx;
        self.c_ay[c] = d_lam * ny;
        sink.wi = wi;
        sink.wj = wj;
        true
    }

    /// `solveContacts`: касания подшага — обход (направление чередуется), силы на клетку, затем упоры.
    #[allow(clippy::too_many_arguments)]
    pub fn solve_contacts(
        &mut self,
        px: &mut [f32],
        py: &mut [f32],
        vx: &mut [f32],
        vy: &mut [f32],
        inv_mass: &[f32],
        h: f32,
        to_velocity: bool,
        pressure: &mut [f32],
        pressure_n: &mut [i32],
        ctx: &ContactCtx,
    ) {
        self.to_velocity = to_velocity;
        self.vel_h = h;
        self.pass = self.pass.wrapping_add(1);
        self.frc_n = 0;
        self.damage.clear();
        if self.kill_on_deep {
            for k in 0..self.start_deep.count {
                let v = self.start_deep.ids[k];
                if self.bone_idx(v as usize, ctx.bone_of) < 0 {
                    self.add_kill(v);
                }
            }
        }
        self.pass = self.pass.wrapping_add(1);
        if self.opt_ct_sweep_flip {
            self.ct_backwards = !self.ct_backwards;
        }
        self.count_bone_contacts(px, py, inv_mass, ctx);
        // По воркерам — от PARALLEL_SOLVE_MIN касаний, как в Kotlin (старый контакт ablegacy — только в одном потоке).
        let w = self.workers.clone();
        if let Some(w) = w.as_deref().filter(|w| w.count > 1 && self.c_n >= PARALLEL_SOLVE_MIN && !self.opt_ab_legacy) {
            let (vxo, vyo) = (vx, vy);
            self.solve_contacts_parallel(w, px, py, vxo, vyo, inv_mass, h, pressure, pressure_n, ctx);
            self.fixed_n = 0;
            if !to_velocity {
                self.solve_fixed_contacts(px, py, vxo, vyo, inv_mass, h, pressure, pressure_n, ctx);
            }
            return;
        }
        let step: isize = if self.ct_backwards { -1 } else { 1 };
        let mut cc0: isize = if self.ct_backwards { self.c_n as isize - 1 } else { 0 };
        let mut left = self.c_n;
        let mut sink = std::mem::take(&mut self.serial_sink);
        sink.reset();
        while left > 0 {
            left -= 1;
            let c = cc0 as usize;
            cc0 += step;
            if self.solve_contact_pair(c, px, py, vx, vy, inv_mass, h, &mut sink, ctx) {
                let (ci, cj) = (self.c_i[c] as usize, self.c_j[c] as usize);
                let (ax, ay) = (self.c_ax[c], self.c_ay[c]);
                self.add_force(ci, ax, ay, sink.wi);
                self.add_force(cj, -ax, -ay, sink.wj);
            }
        }
        self.add_solved(&sink);
        self.serial_sink = sink;
        self.apply_forces(px, py, inv_mass, vx, vy, None, self.c_n, pressure, pressure_n, ctx);
        self.fixed_n = 0;
        if !to_velocity {
            self.solve_fixed_contacts(px, py, vx, vy, inv_mass, h, pressure, pressure_n, ctx);
        }
    }

    /// `solveFixedContacts`: упоры в неподвижное — после остальных касаний, до FIXED_CONTACT_SWEEPS проходов.
    #[allow(clippy::too_many_arguments)]
    fn solve_fixed_contacts(
        &mut self,
        px: &mut [f32],
        py: &mut [f32],
        vx: &mut [f32],
        vy: &mut [f32],
        inv_mass: &[f32],
        h: f32,
        pressure: &mut [f32],
        pressure_n: &mut [i32],
        ctx: &ContactCtx,
    ) {
        let mut m = 0usize;
        for c in 0..self.c_n {
            if self.c_fixed[c] != KIND_FIXED {
                continue;
            }
            if m == self.fixed_list.len() {
                let cap = m * 2;
                self.fixed_list.resize(cap, 0);
            }
            self.fixed_list[m] = c as i32;
            m += 1;
        }
        self.fixed_n = m;
        if m == 0 {
            return;
        }
        let mut sink = std::mem::take(&mut self.serial_sink);
        sink.reset();
        self.solving_fixed = true;
        let bo = ctx.bone_of;
        for sweep in 0..FIXED_CONTACT_SWEEPS {
            self.fixed_sweep_now = sweep;
            self.pass = self.pass.wrapping_add(1);
            for k in 0..m {
                let c = self.fixed_list[k] as usize;
                let i = self.c_i[c] as usize;
                let j = self.c_j[c] as usize;
                let dx = px[i] - px[j];
                let dy = py[i] - py[j];
                let d = sqrtf(dx * dx + dy * dy);
                let cc = if d < 1e-12f32 { -self.c_rr[c] } else { d - self.c_rr[c] };
                if cc >= 0.0 {
                    continue;
                }
                self.bump_z(i, bo);
                self.bump_z(j, bo);
            }
            self.pass = self.pass.wrapping_add(1);
            self.frc_n = 0;
            let mut moved = false;
            for k in 0..m {
                let c = self.fixed_list[k] as usize;
                if self.solve_contact_pair(c, px, py, vx, vy, inv_mass, h, &mut sink, ctx) {
                    let (ci, cj) = (self.c_i[c] as usize, self.c_j[c] as usize);
                    let (ax, ay) = (self.c_ax[c], self.c_ay[c]);
                    self.add_force(ci, ax, ay, sink.wi);
                    self.add_force(cj, -ax, -ay, sink.wj);
                    moved = true;
                }
            }
            if !moved {
                break;
            }
            let list = std::mem::take(&mut self.fixed_list);
            self.apply_forces(px, py, inv_mass, vx, vy, Some(&list), m, pressure, pressure_n, ctx);
            self.fixed_list = list;
        }
        self.solving_fixed = false;
        self.fixed_sweep_now = 0;
        self.add_solved(&sink);
        self.serial_sink = sink;
    }

    /// `applyForces`: силы на клетке — вектором и один раз; потолок по клетке, урезание — парой.
    #[allow(clippy::too_many_arguments)]
    fn apply_forces(
        &mut self,
        px: &mut [f32],
        py: &mut [f32],
        inv_mass: &[f32],
        px_out: &mut [f32],
        py_out: &mut [f32],
        list: Option<&[i32]>,
        count: usize,
        pressure: &mut [f32],
        pressure_n: &mut [i32],
        ctx: &ContactCtx,
    ) {
        let cap = self.contact_max_step * self.mean_link;
        for k in 0..self.frc_n {
            let i = self.frc_list[k] as usize;
            let w = self.frc_w[i];
            let mut s = 1.0f32;
            if w > 0.0 {
                let d2 = (self.frc_x[i] * self.frc_x[i] + self.frc_y[i] * self.frc_y[i]) * w * w;
                if d2 > cap * cap {
                    s = cap / sqrtf(d2);
                }
            }
            self.frc_s[i] = s;
            self.frc_x[i] = 0.0;
            self.frc_y[i] = 0.0;
        }
        for q in 0..count {
            let c = match list {
                None => q,
                Some(l) => l[q] as usize,
            };
            let ax = self.c_ax[c];
            let ay = self.c_ay[c];
            if ax == 0.0 && ay == 0.0 {
                continue;
            }
            let i = self.c_i[c] as usize;
            let j = self.c_j[c] as usize;
            let si = self.frc_s[i];
            let sj = self.frc_s[j];
            let s = if si < sj { si } else { sj };
            if s < 1.0 {
                self.c_lam_tot[c] -= (1.0f32 - s) * (ax * self.c_nx[c] + ay * self.c_ny[c]);
                self.c_lam[c] *= s;
                if self.opt_ab_legacy {
                    self.lam.put(Self::pair_key(i as i32, j as i32), self.c_lam_tot[c]);
                }
            }
            if s <= 0.0 {
                continue;
            }
            self.frc_x[i] += ax * s;
            self.frc_y[i] += ay * s;
            self.frc_x[j] -= ax * s;
            self.frc_y[j] -= ay * s;
            let push = sqrtf(ax * ax + ay * ay) * s;
            pressure[i] += push * inv_mass[i];
            pressure[j] += push * inv_mass[j];
            pressure_n[i] += 1;
            pressure_n[j] += 1;
        }
        let bo = ctx.bone_of;
        self.rigid_batch.begin(self.bone_m.len());
        for k in 0..self.frc_n {
            let i = self.frc_list[k] as usize;
            let ax = self.frc_x[i];
            let ay = self.frc_y[i];
            if ax == 0.0 && ay == 0.0 {
                continue;
            }
            if self.to_velocity {
                px_out[i] += inv_mass[i] * ax / self.vel_h;
                py_out[i] += inv_mass[i] * ay / self.vel_h;
            } else {
                let b = self.bone_idx(i, bo);
                if b >= 0 {
                    let bu = b as usize;
                    self.rigid_batch.add(bu, px[i] - self.bone_cx[bu], py[i] - self.bone_cy[bu], ax, ay);
                } else {
                    px[i] += inv_mass[i] * ax;
                    py[i] += inv_mass[i] * ay;
                }
            }
        }
        if !self.to_velocity && bo.is_some() {
            self.rigid_batch.apply_positions(
                ctx.bone_ids,
                &self.bone_m,
                &self.bone_i,
                &mut self.bone_cx,
                &mut self.bone_cy,
                px,
                py,
                inv_mass,
            );
        }
    }

    /// `applyRigidVel`: толчок в клетку кости — в общий кластер. false — клетка не в кости.
    #[inline]
    fn apply_rigid_vel(&mut self, i: usize, jx: f32, jy: f32, px: &[f32], py: &[f32], bo: Option<&[i32]>) -> bool {
        let b = self.bone_idx(i, bo);
        if b < 0 {
            return false;
        }
        let bu = b as usize;
        self.rigid_batch.add(bu, px[i] - self.bone_cx[bu], py[i] - self.bone_cy[bu], jx, jy);
        true
    }

    /// Нормаль отскока — по нынешним положениям, пока линия центров не ушла от запомненной дальше 60°.
    #[inline]
    fn restitution_normal(&self, c: usize, i: usize, j: usize, px: &[f32], py: &[f32]) -> (f32, f32) {
        let mut nx = px[i] - px[j];
        let mut ny = py[i] - py[j];
        let nl = sqrtf(nx * nx + ny * ny);
        if nl > 1e-12f32 && nx * self.c_nx[c] + ny * self.c_ny[c] > 0.5f32 * nl {
            nx /= nl;
            ny /= nl;
        } else {
            nx = self.c_nx[c];
            ny = self.c_ny[c];
        }
        (nx, ny)
    }

    /// `solveRestitution`: отскок — доля скорости сближения; своему организму — положенная скорость расхождения.
    pub fn solve_restitution(&mut self, px: &[f32], py: &[f32], vx: &mut [f32], vy: &mut [f32], inv_mass: &[f32], ctx: &ContactCtx) {
        if self.restitution <= 0.0 {
            return;
        }
        let bo = ctx.bone_of;
        self.rigid_batch.begin(self.bone_m.len());
        for c in 0..self.c_n {
            let approach = self.c_vn[c];
            let kind = self.c_fixed[c];
            if kind == KIND_PUSHED {
                continue;
            }
            let own = kind == KIND_OWN_PUSHED;
            if approach >= 0.0 && !own {
                continue;
            }
            let i = self.c_i[c] as usize;
            let j = self.c_j[c] as usize;
            let (nx, ny) = self.restitution_normal(c, i, j, px, py);
            let wi = self.eff_inv_mass_split(i, j, nx, ny, px, py, inv_mass, ctx);
            let wj = self.eff_inv_mass_split(j, i, nx, ny, px, py, inv_mass, ctx);
            let w = wi + wj;
            if w <= 0.0 {
                continue;
            }
            let vn = (vx[i] - vx[j]) * nx + (vy[i] - vy[j]) * ny;
            let dvn = (if approach < 0.0 { -self.restitution * approach } else { approach }) - vn;
            if if own { dvn == 0.0 } else { dvn <= 0.0 } {
                continue;
            }
            let p = dvn / w;
            if !self.apply_rigid_vel(i, p * nx, p * ny, px, py, bo) {
                vx[i] += p * nx * inv_mass[i];
                vy[i] += p * ny * inv_mass[i];
            }
            if !self.apply_rigid_vel(j, -p * nx, -p * ny, px, py, bo) {
                vx[j] -= p * nx * inv_mass[j];
                vy[j] -= p * ny * inv_mass[j];
            }
        }
        if bo.is_some() {
            self.rigid_batch.apply_velocities(ctx.bone_ids, &self.bone_m, &self.bone_i, &self.bone_cx, &self.bone_cy, px, py, inv_mass, vx, vy);
        }
        if self.fixed_n > 0 {
            self.fixed_restitution(px, py, vx, vy, inv_mass, ctx);
        }
    }

    /// `fixedRestitution`: рельеф не выстреливает — всем упорам клетки разом, FIXED_CONTACT_SWEEPS раз.
    fn fixed_restitution(&mut self, px: &[f32], py: &[f32], vx: &mut [f32], vy: &mut [f32], inv_mass: &[f32], ctx: &ContactCtx) {
        let mut m = 0usize;
        for k in 0..self.fixed_n {
            let c = self.fixed_list[k];
            if self.c_fixed[c as usize] == KIND_PUSHED {
                self.fixed_list[m] = c;
                m += 1;
            }
        }
        self.fixed_n = 0;
        if m == 0 {
            return;
        }
        let bo = ctx.bone_of;
        for _sweep in 0..FIXED_CONTACT_SWEEPS {
            self.pass = self.pass.wrapping_add(1);
            for k in 0..m {
                let c = self.fixed_list[k] as usize;
                let (ci, cj) = (self.c_i[c] as usize, self.c_j[c] as usize);
                self.bump_z(ci, bo);
                self.bump_z(cj, bo);
            }
            self.pass = self.pass.wrapping_add(1);
            self.frc_n = 0;
            self.rigid_batch.begin(self.bone_m.len());
            let mut changed = false;
            for k in 0..m {
                let c = self.fixed_list[k] as usize;
                let i = self.c_i[c] as usize;
                let j = self.c_j[c] as usize;
                let (nx, ny) = self.restitution_normal(c, i, j, px, py);
                let mut wi = self.eff_inv_mass_split(i, j, nx, ny, px, py, inv_mass, ctx);
                let mut wj = self.eff_inv_mass_split(j, i, nx, ny, px, py, inv_mass, ctx);
                if self.bone_idx(i, bo) < 0 {
                    wi *= self.z_of(i, bo) as f32;
                }
                if self.bone_idx(j, bo) < 0 {
                    wj *= self.z_of(j, bo) as f32;
                }
                let w = wi + wj;
                if w <= 0.0 {
                    continue;
                }
                let approach = self.c_vn[c];
                let vn = (vx[i] - vx[j]) * nx + (vy[i] - vy[j]) * ny;
                let dvn = (if approach < 0.0 { -self.restitution * approach } else { approach }) - vn;
                if dvn == 0.0 {
                    continue;
                }
                changed = true;
                let p = dvn / w;
                if !self.apply_rigid_vel(i, p * nx, p * ny, px, py, bo) {
                    self.add_force(i, p * nx * inv_mass[i], p * ny * inv_mass[i], 0.0);
                }
                if !self.apply_rigid_vel(j, -p * nx, -p * ny, px, py, bo) {
                    self.add_force(j, -p * nx * inv_mass[j], -p * ny * inv_mass[j], 0.0);
                }
            }
            if !changed {
                break;
            }
            for q in 0..self.frc_n {
                let i = self.frc_list[q] as usize;
                vx[i] += self.frc_x[i];
                vy[i] += self.frc_y[i];
            }
            if bo.is_some() {
                self.rigid_batch.apply_velocities(ctx.bone_ids, &self.bone_m, &self.bone_i, &self.bone_cx, &self.bone_cy, px, py, inv_mass, vx, vy);
            }
        }
    }
}
