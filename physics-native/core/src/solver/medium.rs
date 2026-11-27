//! Среда и внешние силы — `solver/MediumSolver.kt`: интегрирование с рамкой мира, продольная вязкость, запас
//! увлечённой среды и сопротивление рёбер контура, гравитация мира, тяга мышью, потолок скорости, рамка в конце шага.
//! Порядок обхода — по блокам, затем швы (как у воркеров Kotlin: `edgesByBlocksSerial`, `comSumsSerial`).

use crate::jmath::sqrtf;
use crate::params::{
    DRAG_TAU, DT, FLOW_DECAY, FLOW_ENTRAIN, FLOW_MASS, GRAVITY, GROUND_FRICTION, GROUND_Y, MAX_SPEED_CELLS_PER_TICK, SPIN_MIN_ARM,
    VISCOSITY,
};
use crate::solver::bones::BoneWallHits;
use crate::solver::parallel::SpanPass;
use crate::solver::orientation::orient_edge;
use crate::state::BodyState;

/// `viscousLinkAt`: продольная вязкость одной связи (без корня: (Δv·d)·d/|d|²).
#[allow(clippy::too_many_arguments)]
#[inline(always)]
fn viscous_link_at(c: usize, k: f32, dead: &[bool], a: &[i32], b: &[i32], w: &[f32], px: &[f32], py: &[f32], vx: &mut [f32], vy: &mut [f32]) {
    if dead[c] {
        return;
    }
    let i = a[c] as usize;
    let j = b[c] as usize;
    let wi = w[i];
    let wj = w[j];
    let ww = wi + wj;
    if ww == 0.0 {
        return;
    }
    let dx = px[j] - px[i];
    let dy = py[j] - py[i];
    let len2 = dx * dx + dy * dy;
    if len2 < 1e-24f32 {
        return;
    }
    let q = ((vx[j] - vx[i]) * dx + (vy[j] - vy[i]) * dy) * k / (ww * len2);
    let qi = q * wi;
    let qj = q * wj;
    vx[i] += dx * qi;
    vy[i] += dy * qi;
    vx[j] -= dx * qj;
    vy[j] -= dy * qj;
}

/// `normalDragEdgeAt`: сопротивление среды ребру контура — нормальная скорость середины относительно запаса среды.
#[allow(clippy::too_many_arguments)]
#[inline(always)]
fn normal_drag_edge_at(
    e: usize,
    h: f32,
    bound_a: &[i32],
    bound_b: &[i32],
    px: &[f32],
    py: &[f32],
    vx: &mut [f32],
    vy: &mut [f32],
    organism_of: &[i32],
    flow_vx: &[f32],
    flow_vy: &[f32],
    tangent_drag: f32,
    normal_drag: f32,
    normal_drag_q: f32,
) {
    let i = bound_a[e] as usize;
    let j = bound_b[e] as usize;
    let ex = px[j] - px[i];
    let ey = py[j] - py[i];
    let len = sqrtf(ex * ex + ey * ey);
    if len < 1e-12f32 {
        return;
    }
    let inv_len = 1.0f32 / len;
    let nx = -ey * inv_len;
    let ny = ex * inv_len;
    let o = organism_of[i] as usize;
    let vmx = (vx[i] + vx[j]) * 0.5f32 - flow_vx[o];
    let vmy = (vy[i] + vy[j]) * 0.5f32 - flow_vy[o];
    let vn = vmx * nx + vmy * ny;
    let mut k = (normal_drag + normal_drag_q * vn.abs()) * len * h;
    if k > 0.5 {
        k = 0.5;
    }
    let dv = -vn * k;
    vx[i] += dv * nx;
    vy[i] += dv * ny;
    vx[j] += dv * nx;
    vy[j] += dv * ny;
    if tangent_drag > 0.0 {
        let tx = ex * inv_len;
        let ty = ey * inv_len;
        let vt = vmx * tx + vmy * ty;
        let mut kt = tangent_drag * normal_drag * len * h;
        if kt > 0.5 {
            kt = 0.5;
        }
        let dvt = -vt * kt;
        vx[i] += dvt * tx;
        vy[i] += dvt * ty;
        vx[j] += dvt * tx;
        vy[j] += dvt * ty;
    }
}

impl BodyState {
    /// `integrate`: интегрирование бодрствующих частиц (отдельным проходом — только при снятии сноса).
    pub fn integrate(&mut self, h: f32) {
        self.prepare_awake_spans();
        for k in 0..self.sleep.span_count {
            let (from, to) = (self.sleep.span_start[k] as usize, self.sleep.span_end[k] as usize);
            self.integrate_range(from, to, h);
        }
    }

    /// `integrateRange`: частицы [from, to) — сначала все чтения, потом все записи; рамка мира у мягкой ткани сразу.
    pub fn integrate_range(&mut self, from: usize, to: usize, h: f32) {
        // Удары костей о рамку — в общий список мира (у воркера — в свой черновик: integrate_range_into).
        let mut hits = std::mem::replace(&mut self.wall_hits, BoneWallHits::new(0));
        self.integrate_range_into(from, to, h, &mut hits);
        self.wall_hits = hits;
    }

    /// `integrateRange(from, to, h, wallHits)`: то же, удары костей о рамку — в [wall_hits].
    pub fn integrate_range_into(&mut self, from: usize, to: usize, h: f32, wall_hits: &mut BoneWallHits) {
        let bounds_on = self.bounds_on;
        let (bmin_x, bmax_x, bmin_y, bmax_y) = (self.bounds_min_x, self.bounds_max_x, self.bounds_min_y, self.bounds_max_y);
        let bones_rigid = self.bones_rigid;
        let cancel = self.cancel_spin_on;
        for i in from..to {
            let x = self.px[i];
            let y = self.py[i];
            if self.inv_mass[i] == 0.0 {
                self.prev_x[i] = x;
                self.prev_y[i] = y;
                self.in_contact[i] = false;
                continue;
            }
            let nvy = self.vy[i] + GRAVITY * h;
            let mut nx = x + self.vx[i] * h;
            let mut ny = y + nvy * h;
            let mut ground = false;
            if ny < GROUND_Y {
                nx += (x - nx) * GROUND_FRICTION;
                ny = GROUND_Y;
                ground = true;
            }
            if bounds_on {
                let r = self.radius[i];
                if nx < bmin_x + r || nx > bmax_x - r || ny < bmin_y + r || ny > bmax_y - r {
                    let bone = if bones_rigid { self.bone_of[i] } else { -1 };
                    if bone >= 0 {
                        wall_hits.add(bone as usize);
                    } else {
                        if nx < bmin_x + r {
                            nx = bmin_x + r;
                        } else if nx > bmax_x - r {
                            nx = bmax_x - r;
                        }
                        if ny < bmin_y + r {
                            ny = bmin_y + r;
                        } else if ny > bmax_y - r {
                            ny = bmax_y - r;
                        }
                        if cancel {
                            self.tick_momentum().block(i);
                        }
                    }
                }
            }
            self.prev_x[i] = x;
            self.prev_y[i] = y;
            self.vy[i] = nvy;
            self.px[i] = nx;
            self.py[i] = ny;
            self.in_contact[i] = ground;
        }
    }

    /// `applyViscosity`: продольная вязкость связей — по блокам, затем швы.
    pub fn apply_viscosity(&mut self, h: f32) {
        let mut k = VISCOSITY * h;
        if k > 0.5 {
            k = 0.5;
        }
        // С воркерами (и не одним организмом) — наборы блоков, затем наборы швов, по воркерам (solver/parallel.rs).
        if self.organism_count > 1 && self.constraint_blocks.is_some() {
            if let Some(w) = self.active_workers() {
                self.viscosity_parallel(&w, k);
                return;
            }
        }
        if self.constraint_blocks.is_none() {
            let s = self;
            for c in 0..s.con_count {
                viscous_link_at(c, k, &s.con_dead, &s.con_a, &s.con_b, &s.inv_mass, &s.px, &s.py, &mut s.vx, &mut s.vy);
            }
            return;
        }
        let buckets = self.constraint_blocks.as_ref().unwrap().buckets;
        let mut walk = self.constraint_blocks.as_ref().unwrap().walk(0, buckets);
        while let Some(block) = self.constraint_blocks.as_ref().unwrap().next_active(&mut walk) {
            self.viscosity_bucket(block, k);
        }
    }

    /// Вязкость связей набора [block] (если он бодрствует): связи набора трогают только его частицы.
    pub fn viscosity_bucket(&mut self, block: usize, k: f32) {
        let s = self;
        let layout = s.constraint_blocks.as_ref().unwrap();
        if !layout.active(block) {
            return;
        }
        let links = &layout.links;
        let from = links.start[block] as usize;
        let to = from + links.count[block] as usize;
        for slot in from..to {
            let c = links.items[slot];
            if c >= 0 {
                viscous_link_at(c as usize, k, &s.con_dead, &s.con_a, &s.con_b, &s.inv_mass, &s.px, &s.py, &mut s.vx, &mut s.vy);
            }
        }
    }

    /// `applyNormalDrag`: запас увлечённой среды по организмам, плата за его разгон, сопротивление рёбер контура.
    pub fn apply_normal_drag(&mut self, h: f32) {
        if self.dbg_drag_off {
            return;
        }
        let mut kd = FLOW_DECAY * h;
        if kd > 1.0 {
            kd = 1.0;
        }
        if FLOW_ENTRAIN > 0.0 && FLOW_MASS > 0.0 {
            // Только организмы текущей области (острова): запас среды чужой области не трогается.
            for x in 0..self.scope_org_slots() {
                if let Some(o) = self.scope_org_at(x) {
                    self.com_acc_x[o] = 0.0;
                    self.com_acc_y[o] = 0.0;
                }
            }
            self.prepare_awake_spans();
            let cw = self.active_workers();
            if let Some(w) = cw.as_deref() {
                // С воркерами (и с одним) — суммы кусками, как у учёта импульса: порядок сложения от их числа не зависит.
                self.com_sums_parallel(w);
            } else if self.constraint_blocks.is_some() {
                self.com_sums_serial();
            } else {
                for q in 0..self.sleep.span_count {
                    let (from, to) = (self.sleep.span_start[q] as usize, self.sleep.span_end[q] as usize);
                    for i in from..to {
                        if self.inv_mass[i] <= 0.0 {
                            continue;
                        }
                        let o = self.organism_of[i] as usize;
                        let m = 1.0f32 / self.inv_mass[i];
                        self.com_acc_x[o] += m * self.vx[i];
                        self.com_acc_y[o] += m * self.vy[i];
                    }
                }
            }
            let mut kf = FLOW_ENTRAIN * h;
            let limit = 0.9f32 / (1.0f32 + FLOW_MASS);
            if kf > limit {
                kf = limit;
            }
            for x in 0..self.scope_org_slots() {
                if let Some(o) = self.scope_org_at(x) {
                    self.flow_step(o, kf, kd);
                }
            }
            if let Some(w) = self.active_workers().filter(|w| w.count > 1) {
                self.span_parts_run(&w, SpanPass::FlowPayment);
            } else {
                for q in 0..self.sleep.span_count {
                    let (from, to) = (self.sleep.span_start[q] as usize, self.sleep.span_end[q] as usize);
                    self.flow_payment_range(from, to);
                }
            }
        }
        // Рёбра контура — по наборам блоков (с воркерами — по воркерам, порядок наборов тот же).
        if let Some(w) = self.active_workers() {
            if self.constraint_blocks.is_some() {
                self.edges_parallel(&w, 1, h);
                return;
            }
        }
        if self.constraint_blocks.is_some() {
            self.edges_by_blocks_serial(1, h);
            return;
        }
        let s = self;
        let lb = &s.local_boundary;
        for e in 0..lb.count {
            normal_drag_edge_at(
                e,
                h,
                &lb.a,
                &lb.b,
                &s.px,
                &s.py,
                &mut s.vx,
                &mut s.vy,
                &s.organism_of,
                &s.flow_vx,
                &s.flow_vy,
                s.tangent_drag,
                s.environment.normal_drag,
                s.environment.normal_drag_quadratic,
            );
        }
    }

    /// `flowStep`: запас среды организма догоняет скорость его центра масс и рассеивается.
    fn flow_step(&mut self, o: usize, kf: f32, kd: f32) {
        let mo = self.organism_mass[o];
        if mo <= 0.0 {
            return;
        }
        let com_vx = self.com_acc_x[o] / mo;
        let com_vy = self.com_acc_y[o] / mo;
        let dfx = (com_vx - self.flow_vx[o]) * kf;
        let dfy = (com_vy - self.flow_vy[o]) * kf;
        self.flow_vx[o] += dfx;
        self.flow_vy[o] += dfy;
        self.com_acc_x[o] = dfx;
        self.com_acc_y[o] = dfy;
        self.flow_vx[o] -= self.flow_vx[o] * kd;
        self.flow_vy[o] -= self.flow_vy[o] * kd;
    }

    /// `flowPaymentRange`: плата частиц за разгон запаса среды.
    pub fn flow_payment_range(&mut self, from: usize, to: usize) {
        for i in from..to {
            let o = self.organism_of[i] as usize;
            self.vx[i] -= FLOW_MASS * self.com_acc_x[o];
            self.vy[i] -= FLOW_MASS * self.com_acc_y[o];
        }
    }

    /// `comSumsSerial`: импульс организмов для запаса среды — кусками (те же куски, что у воркеров), суммы в double.
    pub fn com_sums_serial(&mut self) {
        self.ensure_pass_chunks();
        let mut sums = std::mem::take(&mut self.com_sums_scratch);
        let pc = self.take_active_chunks();
        for c in 0..pc.count {
            sums.clear();
            for p in pc.part_start[c]..pc.part_start[c + 1] {
                let p = p as usize;
                for i in pc.part_from[p] as usize..pc.part_to[p] as usize {
                    if self.inv_mass[i] <= 0.0 {
                        continue;
                    }
                    let m = 1.0f32 / self.inv_mass[i];
                    let at = sums.at(self.organism_of[i]);
                    // Kotlin: v[at] += m * vx[i] — Float·Float во float, затем в double.
                    sums.values[at] += (m * self.vx[i]) as f64;
                    sums.values[at + 1] += (m * self.vy[i]) as f64;
                }
            }
            for x in 0..sums.count() {
                let o = sums.key_at(x) as usize;
                let at = sums.offset_at(x);
                self.com_acc_x[o] += sums.values[at] as f32;
                self.com_acc_y[o] += sums.values[at + 1] as f32;
            }
        }
        self.put_active_chunks(pc);
        self.com_sums_scratch = sums;
    }

    /// `edgesByBlocksSerial`: рёбра контура по блокам, затем швы — [mode] 0 ориентация, 1 сопротивление среды.
    pub fn edges_by_blocks_serial(&mut self, mode: i32, h: f32) {
        let buckets = self.constraint_blocks.as_ref().unwrap().buckets;
        let mut walk = self.constraint_blocks.as_ref().unwrap().walk(0, buckets);
        while let Some(bucket) = self.constraint_blocks.as_ref().unwrap().next_active(&mut walk) {
            self.edges_bucket(bucket, mode, h);
        }
    }

    /// Рёбра контура набора [bucket] (если он бодрствует): ориентация ([mode] 0) или сопротивление среды (1). Набор
    /// трогает только частицы своего блока (шов — своего тела): годится и воркеру.
    pub fn edges_bucket(&mut self, bucket: usize, mode: i32, h: f32) {
        {
            if !self.constraint_blocks.as_ref().unwrap().active(bucket) {
                return;
            }
            let items = {
                let layout = self.constraint_blocks.as_mut().unwrap();
                layout.edges.dense(bucket);
                layout.edges.take_dense(bucket)
            };
            let n = self.constraint_blocks.as_ref().unwrap().edges.dense_count[bucket] as usize;
            {
                let s = &mut *self;
                let lb = &s.local_boundary;
                if mode == 0 {
                    let bone = if s.bones_rigid { Some(&s.bone_of[..]) } else { None };
                    for x in 0..n {
                        orient_edge(items[x] as usize, &lb.a, &lb.b, &lb.opposite, &lb.orientation, &mut s.px, &mut s.py, &s.inv_mass, bone);
                    }
                } else {
                    for x in 0..n {
                        normal_drag_edge_at(
                            items[x] as usize,
                            h,
                            &lb.a,
                            &lb.b,
                            &s.px,
                            &s.py,
                            &mut s.vx,
                            &mut s.vy,
                            &s.organism_of,
                            &s.flow_vx,
                            &s.flow_vy,
                            s.tangent_drag,
                            s.environment.normal_drag,
                            s.environment.normal_drag_quadratic,
                        );
                    }
                }
            }
            self.constraint_blocks.as_mut().unwrap().edges.put_dense(bucket, items);
        }
    }

    /// `solveBoundaryOrientationStage`: ориентация контура — по блокам, затем швы.
    pub fn solve_boundary_orientation_stage(&mut self) {
        if self.constraint_blocks.is_some() {
            if let Some(w) = self.active_workers() {
                self.edges_parallel(&w, 0, 0.0);
                return;
            }
            self.edges_by_blocks_serial(0, 0.0);
            return;
        }
        let s = self;
        let lb = &s.local_boundary;
        let bone = if s.bones_rigid { Some(&s.bone_of[..]) } else { None };
        for k in 0..lb.count {
            orient_edge(k, &lb.a, &lb.b, &lb.opposite, &lb.orientation, &mut s.px, &mut s.py, &s.inv_mass, bone);
        }
    }

    /// `applyGravity`: гравитация мира по скоростям бодрствующих подвижных частиц.
    pub fn apply_gravity(&mut self, h: f32) {
        let g = self.environment.gravity_y;
        if g == 0.0 {
            return;
        }
        let dv = g * h;
        self.prepare_awake_spans();
        for k in 0..self.sleep.span_count {
            let (from, to) = (self.sleep.span_start[k] as usize, self.sleep.span_end[k] as usize);
            for i in from..to {
                if self.inv_mass[i] > 0.0 {
                    self.vy[i] += dv;
                }
            }
        }
    }

    /// `applyDragVelocity`: тяга мышью — сила, а не телепорт.
    pub fn apply_drag_velocity(&mut self, h: f32) {
        let i = self.drag_id;
        if i >= 0 {
            self.pull_toward(i as usize, self.mouse_x, self.mouse_y, h);
        }
    }

    /// `pullToward`: тяга клетки [i] к цели; клетка кости тянет кость целиком (импульс в точку захвата).
    pub fn pull_toward(&mut self, i: usize, target_x: f32, target_y: f32, h: f32) {
        if self.inv_mass[i] == 0.0 {
            return;
        }
        let dx = target_x - self.px[i];
        let dy = target_y - self.py[i];
        let d = sqrtf(dx * dx + dy * dy);
        if d < 1e-12f32 {
            return;
        }
        let b = if self.bones_rigid { self.bone_of[i] } else { -1 };
        let mut m_total = 0.0f32;
        let mut cx = 0.0f32;
        let mut cy = 0.0f32;
        if b >= 0 {
            for &k in &self.rigid_bones[b as usize] {
                let k = k as usize;
                if self.bone_of[k] != b || self.inv_mass[k] <= 0.0 {
                    continue;
                }
                let m = 1.0f32 / self.inv_mass[k];
                m_total += m;
                cx += m * self.px[k];
                cy += m * self.py[k];
            }
            if m_total <= 0.0 {
                return;
            }
            cx /= m_total;
            cy /= m_total;
        }
        let force = self.cfg.drag_accel * self.link_scale / (DT * DT);
        let v_limit = self.cfg.drag_speed_limit * MAX_SPEED_CELLS_PER_TICK * self.link_scale / DT;
        let mut v_want = d / DRAG_TAU;
        if v_want > v_limit {
            v_want = v_limit;
        }
        let want_x = dx / d * v_want - self.vx[i];
        let want_y = dy / d * v_want - self.vy[i];
        if b < 0 {
            let mut jx = want_x / self.inv_mass[i];
            let mut jy = want_y / self.inv_mass[i];
            let jm = sqrtf(jx * jx + jy * jy);
            let j_max = force * h;
            if jm > j_max {
                let s = j_max / jm;
                jx *= s;
                jy *= s;
            }
            let mut nvx = self.vx[i] + jx * self.inv_mass[i];
            let mut nvy = self.vy[i] + jy * self.inv_mass[i];
            let v = sqrtf(nvx * nvx + nvy * nvy);
            if v > v_limit {
                let sc = v_limit / v;
                nvx *= sc;
                nvy *= sc;
            }
            self.vx[i] = nvx;
            self.vy[i] = nvy;
            return;
        }
        let bu = b as usize;
        let mut inertia = 0.0f32;
        for &k in &self.rigid_bones[bu] {
            let k = k as usize;
            if self.bone_of[k] != b || self.inv_mass[k] <= 0.0 {
                continue;
            }
            let rx = self.px[k] - cx;
            let ry = self.py[k] - cy;
            let r2 = rx * rx + ry * ry;
            inertia += r2 / self.inv_mass[k];
        }
        let rgx = self.px[i] - cx;
        let rgy = self.py[i] - cy;
        let arm = SPIN_MIN_ARM * self.link_scale;
        let inv_i = if inertia > m_total * arm * arm { 1.0f32 / inertia } else { 0.0 };
        let inv_m = 1.0f32 / m_total;
        let a11 = inv_m + rgy * rgy * inv_i;
        let a12 = -rgx * rgy * inv_i;
        let a22 = inv_m + rgx * rgx * inv_i;
        let det = a11 * a22 - a12 * a12;
        if det < 1e-24f32 {
            return;
        }
        let mut jx = (a22 * want_x - a12 * want_y) / det;
        let mut jy = (a11 * want_y - a12 * want_x) / det;
        let jm = sqrtf(jx * jx + jy * jy);
        let j_max = force * h;
        if jm > j_max {
            let s_j = j_max / jm;
            jx *= s_j;
            jy *= s_j;
        }
        let d_omega = (rgx * jy - rgy * jx) * inv_i;
        let dvx = jx / m_total;
        let dvy = jy / m_total;
        let mut worst2 = 0.0f32;
        for &k in &self.rigid_bones[bu] {
            let k = k as usize;
            if self.bone_of[k] != b || self.inv_mass[k] <= 0.0 {
                continue;
            }
            let rx = self.px[k] - cx;
            let ry = self.py[k] - cy;
            let nvx = self.vx[k] + dvx - d_omega * ry;
            let nvy = self.vy[k] + dvy + d_omega * rx;
            let v2 = nvx * nvx + nvy * nvy;
            if v2 > worst2 {
                worst2 = v2;
            }
        }
        let worst = sqrtf(worst2);
        let mut sc = 1.0f32;
        if worst > v_limit && worst > 1e-18f32 {
            sc = v_limit / worst;
        }
        for q in 0..self.rigid_bones[bu].len() {
            let k = self.rigid_bones[bu][q] as usize;
            if self.bone_of[k] != b || self.inv_mass[k] <= 0.0 {
                continue;
            }
            let rx = self.px[k] - cx;
            let ry = self.py[k] - cy;
            self.vx[k] += sc * (dvx - d_omega * ry);
            self.vy[k] += sc * (dvy + d_omega * rx);
        }
    }

    /// `clampSpeed`: потолок скорости бодрствующих частиц (вторая коррекция в конце тика).
    pub fn clamp_speed(&mut self) {
        if self.dbg_no_clamp {
            return;
        }
        let max_v = MAX_SPEED_CELLS_PER_TICK * self.link_scale / DT;
        let max_v2 = max_v * max_v;
        self.prepare_awake_spans();
        let mut peak = self.peak_speed2;
        let mut hits = self.speed_cap_hits;
        for k in 0..self.sleep.span_count {
            let (from, to) = (self.sleep.span_start[k] as usize, self.sleep.span_end[k] as usize);
            for i in from..to {
                crate::solver::fused::clamp_one(i, max_v, max_v2, &mut self.vx, &mut self.vy, &mut peak, &mut hits);
            }
        }
        self.peak_speed2 = peak;
        self.speed_cap_hits = hits;
    }

    /// `clampToBounds`: конец шага — все частицы внутрь рамки мира; скорость в стену гаснет; кости — целиком.
    pub fn clamp_to_bounds(&mut self) {
        for i in 0..self.n {
            let r = self.radius[i];
            let x = self.px[i];
            let y = self.py[i];
            let (bmin_x, bmax_x, bmin_y, bmax_y) = (self.bounds_min_x, self.bounds_max_x, self.bounds_min_y, self.bounds_max_y);
            if x >= bmin_x + r && x <= bmax_x - r && y >= bmin_y + r && y <= bmax_y - r {
                continue;
            }
            let bone = if self.bones_rigid { self.bone_of[i] } else { -1 };
            if bone >= 0 && !self.cell_dead[i] && !self.cell_unborn[i] && !self.is_fixed(i) {
                self.wall_hits.add(bone as usize);
                continue;
            }
            if x < bmin_x + r {
                self.px[i] = bmin_x + r;
                if self.vx[i] < 0.0 {
                    self.vx[i] = 0.0;
                }
            } else if x > bmax_x - r {
                self.px[i] = bmax_x - r;
                if self.vx[i] > 0.0 {
                    self.vx[i] = 0.0;
                }
            }
            if y < bmin_y + r {
                self.py[i] = bmin_y + r;
                if self.vy[i] < 0.0 {
                    self.vy[i] = 0.0;
                }
            } else if y > bmax_y - r {
                self.py[i] = bmax_y - r;
                if self.vy[i] > 0.0 {
                    self.vy[i] = 0.0;
                }
            }
        }
        if self.wall_hits.size != 0 {
            self.resolve_bone_bounds(true);
        }
    }
}
