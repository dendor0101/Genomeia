//! Ядра ограничений — `solver/ConstraintSolver.kt` (ядра связей, предел длины, разрыв) и
//! `solver/BlockConstraintKernels.kt` (блоки: связи, лоскуты, площади, изгиб, пределы). Арифметика дословная, во
//! float; «одно деление на связь» (1/len и два умножения), как в Kotlin с 24.11.

use crate::jmath::{coerce_at_mostf, coerce_inf, minf, sqrtf};
use crate::params::{
    AREA_COMPLIANCE_INVERTED, AREA_MAX_STEP, AREA_SMOOTH_RAMP, BEND_LEVELS, FLAP_COMPLIANCE, LIMIT_NEAR_HI2, LIMIT_NEAR_LO2,
    LINK_CRUSH_RATIO, LINK_MAX_STRETCH, MUSCLE_MAX_PULL, RELAXED_MAX_STRETCH,
};
use crate::solver::fiber::{fiber_area_factor, fiber_link_correction, fiber_relaxed_softness};
use crate::state::BodyState;

#[inline(always)]
fn relaxation(scale: f32) -> f32 {
    coerce_at_mostf((scale - 1.0f32) * 2.0f32, 1.0)
}

/// `tensileCompliance`: при +1 податливость под нагрузкой вчетверо выше.
#[inline(always)]
pub fn tensile_compliance(alpha: f32, scale: f32) -> f32 {
    alpha * (1.0f32 + 3.0f32 * relaxation(scale))
}

/// `linkMaxLength`: предел длины связи.
#[inline(always)]
pub fn link_max_length(rest: f32, scale: f32) -> f32 {
    if scale <= 1.0 {
        LINK_MAX_STRETCH * rest
    } else {
        rest * (LINK_MAX_STRETCH + (RELAXED_MAX_STRETCH - LINK_MAX_STRETCH) * relaxation(scale))
    }
}

/// `relaxedLinkCorrection`: связь расслабленной мышцы — мягче на растяжение по [soft], предел — по [scale].
#[inline]
pub fn relaxed_link_correction(len: f32, rest: f32, scale: f32, w: f32, alpha: f32, boundary: &[bool], c: usize, soft: f32) -> f32 {
    let stretch = len - rest;
    if stretch <= 0.0 {
        return -stretch / (w + alpha);
    }
    let elastic = -stretch / (w + tensile_compliance(alpha, soft));
    if len <= LINK_MAX_STRETCH * rest || boundary[c] {
        return elastic;
    }
    minf(elastic, (link_max_length(rest, scale) - len) / w)
}

/// `muscleLinkCorrection`: поправка связи мышцы клетки (с волокном — своя цель у связей не вдоль волокна).
#[allow(clippy::too_many_arguments)]
#[inline(always)]
pub fn muscle_link_correction(len: f32, rest: f32, scale: f32, w: f32, alpha: f32, boundary: &[bool], c: usize, fiber: &[f32], fiber_k: f32) -> f32 {
    if scale <= 1.0 {
        if scale < 1.0 && c < fiber.len() && fiber[c] < 1.0 {
            return fiber_link_correction(len, rest, scale, w, alpha, fiber[c], fiber_k);
        }
        let mut over = len - rest * scale;
        if scale < 1.0 && over > MUSCLE_MAX_PULL * rest {
            over = MUSCLE_MAX_PULL * rest;
        }
        return -over / (w + alpha);
    }
    let soft = if c < fiber.len() && fiber[c] < 1.0 { fiber_relaxed_softness(scale, fiber[c]) } else { scale };
    relaxed_link_correction(len, rest, scale, w, alpha, boundary, c, soft)
}

/// `capAreaStep`: потолок на длину поправки площади; корень — только за потолком.
#[inline(always)]
pub fn cap_area_step(d_l: f32, denom: f32, max_step: f32) -> f32 {
    if d_l * d_l * denom <= max_step * max_step {
        return d_l;
    }
    d_l * (max_step / (d_l.abs() * sqrtf(denom)))
}

/// Общий хвост ядра связи: поправка вдоль единичного направления.
#[inline(always)]
fn apply_pair(px: &mut [f32], py: &mut [f32], i: usize, j: usize, dx: f32, dy: f32, d_l: f32, wi: f32, wj: f32) {
    px[i] += dx * d_l * wi;
    py[i] += dy * d_l * wi;
    px[j] -= dx * d_l * wj;
    py[j] -= dy * d_l * wj;
}

/// Массивы связей для ядер (только чтение) — одна структура вместо десятка параметров.
pub struct LinkArrays<'a> {
    pub con_a: &'a [i32],
    pub con_b: &'a [i32],
    pub con_rest: &'a [f32],
    pub con_muscle: &'a [i32],
    pub con_dead: &'a [bool],
    pub ms: &'a [f32],
    pub cs: &'a [f32],
    pub soft: &'a [f32],
    pub boundary: &'a [bool],
    pub fiber: &'a [f32],
    pub fiber_k: f32,
    pub alpha: f32,
}

/// `solveDistanceConstraint`: связь без множителей клеток (стенд).
#[inline(always)]
pub fn solve_distance_constraint(c: usize, px: &mut [f32], py: &mut [f32], inv_mass: &[f32], l: &LinkArrays) {
    if l.con_dead[c] {
        return;
    }
    let i = l.con_a[c] as usize;
    let j = l.con_b[c] as usize;
    let wi = inv_mass[i];
    let wj = inv_mass[j];
    let w = wi + wj;
    if w == 0.0 {
        return;
    }
    let mut dx = px[i] - px[j];
    let mut dy = py[i] - py[j];
    let len = sqrtf(dx * dx + dy * dy);
    if len < 1e-12f32 {
        return;
    }
    let inv = 1.0f32 / len;
    dx *= inv;
    dy *= inv;
    let m = l.con_muscle[c];
    let rest = l.con_rest[c];
    let scale = if m < 0 { 1.0f32 } else { l.ms[m as usize] };
    let mut over = len - rest * scale;
    if scale < 1.0 && over > MUSCLE_MAX_PULL * rest {
        over = MUSCLE_MAX_PULL * rest;
    }
    let d_l = -over / (w + l.alpha);
    apply_pair(px, py, i, j, dx, dy, d_l, wi, wj);
}

/// `solveDistanceConstraintScaled` / `Soft` / `Cells` / `SoftCells`: связь мышцы клетки; [cells] — без мышц-кластеров
/// (conMuscle не читается: множитель кластера 1.0, а 1.0·cs — ровно cs), [soft] — с мягкостью ткани.
#[inline(always)]
pub fn solve_distance_constraint_cell<const CELLS: bool, const SOFT: bool>(c: usize, px: &mut [f32], py: &mut [f32], inv_mass: &[f32], l: &LinkArrays) {
    if l.con_dead[c] {
        return;
    }
    let i = l.con_a[c] as usize;
    let j = l.con_b[c] as usize;
    let wi = inv_mass[i];
    let wj = inv_mass[j];
    let w = wi + wj;
    if w == 0.0 {
        return;
    }
    let mut dx = px[i] - px[j];
    let mut dy = py[i] - py[j];
    let len = sqrtf(dx * dx + dy * dy);
    if len < 1e-12f32 {
        return;
    }
    let inv = 1.0f32 / len;
    dx *= inv;
    dy *= inv;
    let scale = if CELLS {
        l.cs[c]
    } else {
        let m = l.con_muscle[c];
        (if m < 0 { 1.0f32 } else { l.ms[m as usize] }) * l.cs[c]
    };
    let alpha = if SOFT { l.alpha * l.soft[c] } else { l.alpha };
    let d_l = muscle_link_correction(len, l.con_rest[c], scale, w, alpha, l.boundary, c, l.fiber, l.fiber_k);
    apply_pair(px, py, i, j, dx, dy, d_l, wi, wj);
}

/// `bendOne`: один изгиб [c] (тело цикла `solveBlockBend`): тот же порядок операций в обоих обходах.
#[inline(always)]
#[allow(clippy::too_many_arguments)]
fn bend_one(c: usize, alpha: f32, px: &mut [f32], py: &mut [f32], inv: &[f32], a: &[i32], b: &[i32], rest: &[f32]) {
    let i = a[c] as usize;
    let j = b[c] as usize;
    let wi = inv[i];
    let wj = inv[j];
    let w = wi + wj;
    if w == 0.0 {
        return;
    }
    let mut dx = px[i] - px[j];
    let mut dy = py[i] - py[j];
    let len = sqrtf(dx * dx + dy * dy);
    if len < 1e-12f32 {
        return;
    }
    let inv_len = 1.0f32 / len;
    dx *= inv_len;
    dy *= inv_len;
    let d_l = -(len - rest[c]) / (w + alpha);
    apply_pair(px, py, i, j, dx, dy, d_l, wi, wj);
}

/// Ядро связи выбранного вида по слотам блока [from, to) — вперёд или назад.
#[inline(always)]
fn sweep_links<const KIND: u8>(items: &[i32], from: usize, to: usize, backwards: bool, px: &mut [f32], py: &mut [f32], inv_mass: &[f32], l: &LinkArrays) {
    let kernel = |c: usize, px: &mut [f32], py: &mut [f32]| match KIND {
        0 => solve_distance_constraint(c, px, py, inv_mass, l),
        1 => solve_distance_constraint_cell::<false, false>(c, px, py, inv_mass, l),
        2 => solve_distance_constraint_cell::<false, true>(c, px, py, inv_mass, l),
        3 => solve_distance_constraint_cell::<true, false>(c, px, py, inv_mass, l),
        _ => solve_distance_constraint_cell::<true, true>(c, px, py, inv_mass, l),
    };
    if backwards {
        for slot in (from..to).rev() {
            let c = items[slot];
            if c < 0 {
                continue;
            }
            kernel(c as usize, px, py);
        }
    } else {
        for slot in from..to {
            let c = items[slot];
            if c < 0 {
                continue;
            }
            kernel(c as usize, px, py);
        }
    }
}

impl BodyState {
    /// `bendCompliance`: нынешняя податливость изгиба.
    #[inline]
    pub fn bend_compliance(&self) -> f32 {
        BEND_LEVELS[self.bend_level as usize]
    }

    /// `solveBlockLinks` (+ `solveBlockLinksCells`): связи блока, направление — sweepBackwards.
    pub fn solve_block_links(&mut self, h: f32, block: usize) {
        // Порядок по цветам (закон с 27.11, color_order.rs): тот же сегмент набора, номера — из раскраски.
        let colored = self.cfg.color_order_on;
        if colored {
            self.ensure_colored_links(block);
        }
        let s = self;
        let alpha = s.environment.soft_compliance / (h * h);
        let layout = s.constraint_blocks.as_ref().unwrap();
        let list = &layout.links;
        let from = list.start[block] as usize;
        let count = if colored { list.color_count[block] } else { list.count[block] };
        let to = from + count as usize;
        let empty: [f32; 0] = [];
        let l = LinkArrays {
            con_a: &s.con_a,
            con_b: &s.con_b,
            con_rest: &s.con_rest,
            con_muscle: &s.con_muscle,
            con_dead: &s.con_dead,
            ms: &s.muscle_scale_of,
            cs: &s.con_cell_scale,
            soft: if s.softness_on { &s.con_softness } else { &empty },
            boundary: &s.con_boundary,
            fiber: if s.fibers_on { &s.con_fiber_c2 } else { &empty },
            fiber_k: s.fiber_thicken,
            alpha,
        };
        let back = s.sweep_backwards;
        let (px, py) = (&mut s.px, &mut s.py);
        let inv = &s.inv_mass;
        let items = if colored { &list.color_items } else { &list.items };
        if s.cell_scales_on && s.muscle_scale_of.is_empty() {
            if s.softness_on {
                sweep_links::<4>(items, from, to, back, px, py, inv, &l);
            } else {
                sweep_links::<3>(items, from, to, back, px, py, inv, &l);
            }
        } else if s.softness_on {
            sweep_links::<2>(items, from, to, back, px, py, inv, &l);
        } else if !s.cell_scales_on {
            sweep_links::<0>(items, from, to, back, px, py, inv, &l);
        } else {
            sweep_links::<1>(items, from, to, back, px, py, inv, &l);
        }
    }

    /// `flapAngleRest`: длина створки по взрослому углу при вершине и нынешним расстояниям.
    pub fn flap_angle_rest(&self, v: usize, i: usize, j: usize) -> f32 {
        let body = &self.body;
        let ax = body.x[i] - body.x[v];
        let ay = body.y[i] - body.y[v];
        let bx = body.x[j] - body.x[v];
        let by = body.y[j] - body.y[v];
        let la = sqrtf(ax * ax + ay * ay);
        let lb = sqrtf(bx * bx + by * by);
        if la < 1e-12f32 || lb < 1e-12f32 {
            return 0.0;
        }
        let cos = (ax * bx + ay * by) / (la * lb);
        let dix = self.px[i] - self.px[v];
        let diy = self.py[i] - self.py[v];
        let djx = self.px[j] - self.px[v];
        let djy = self.py[j] - self.py[v];
        let d1 = sqrtf(dix * dix + diy * diy);
        let d2 = sqrtf(djx * djx + djy * djy);
        let r2 = d1 * d1 + d2 * d2 - 2.0f32 * d1 * d2 * cos;
        if r2 > 0.0 {
            sqrtf(r2)
        } else {
            0.0
        }
    }

    /// `solveBlockFlaps`: створки блока — соседи не сходятся ближе взрослого; у новорождённой — по углу.
    pub fn solve_block_flaps(&mut self, h: f32, block: usize) {
        if self.local_boundary.flap.count == 0 {
            return;
        }
        let alpha = FLAP_COMPLIANCE / (h * h);
        let ramp_on = self.ramp_n > 0;
        if self.cfg.color_order_on {
            // Порядок по цветам (закон с 27.11, color_order.rs).
            self.ensure_colored_flaps(block);
            let count = self.constraint_blocks.as_ref().unwrap().flaps.color_count[block] as usize;
            for k in 0..count {
                let c = self.constraint_blocks.as_ref().unwrap().flaps.color_items(block)[k] as usize;
                self.flap_one(c, alpha, ramp_on);
            }
            return;
        }
        let mut cursor = self.constraint_blocks.as_ref().unwrap().flaps.head[block];
        while cursor >= 0 {
            let c = cursor as usize;
            cursor = self.constraint_blocks.as_ref().unwrap().flaps.next[c];
            self.flap_one(c, alpha, ramp_on);
        }
    }

    /// `flapOne`: одна створка [c] (тело цикла `solveBlockFlaps`).
    #[inline(always)]
    fn flap_one(&mut self, c: usize, alpha: f32, ramp_on: bool) {
        let flap = &self.local_boundary.flap;
        let i = flap.a[c] as usize;
        let j = flap.b[c] as usize;
        let v = flap.owner[c] as usize;
        let mut rest = flap.rest[c];
        let wi = self.inv_mass[i];
        let wj = self.inv_mass[j];
        let w = wi + wj;
        if w == 0.0 {
            return;
        }
        let mut dx = self.px[i] - self.px[j];
        let mut dy = self.py[i] - self.py[j];
        let len = sqrtf(dx * dx + dy * dy);
        if ramp_on {
            let ramp = &self.contact_ramp;
            if ramp[i] < 1.0 || ramp[j] < 1.0 || ramp[v] < 1.0 {
                rest = minf(rest, self.flap_angle_rest(v, i, j));
            }
        }
        if len >= rest || len < 1e-12f32 {
            return;
        }
        dx /= len;
        dy /= len;
        let d_l = -(len - rest) / (w + alpha);
        apply_pair(&mut self.px, &mut self.py, i, j, dx, dy, d_l, wi, wj);
    }

    /// `solveBlockBend`: изгиб контура блока (по умолчанию выключен: податливость < 0).
    pub fn solve_block_bend(&mut self, h: f32, block: usize) {
        let compliance = self.bend_compliance();
        if compliance < 0.0 {
            return;
        }
        let alpha = compliance / (h * h);
        let colored = self.cfg.color_order_on;
        if colored {
            // Порядок по цветам (закон с 27.11, color_order.rs).
            self.ensure_colored_bends(block);
        }
        let s = self;
        let list = &s.constraint_blocks.as_ref().unwrap().bends;
        let bend = &s.local_boundary.bend;
        let (px, py, inv) = (&mut s.px, &mut s.py, &s.inv_mass);
        if colored {
            let ids = list.color_items(block);
            for &c in &ids[..list.color_count[block] as usize] {
                bend_one(c as usize, alpha, px, py, inv, &bend.a, &bend.b, &bend.rest);
            }
            return;
        }
        let mut cursor = list.head[block];
        while cursor >= 0 {
            let c = cursor as usize;
            cursor = list.next[c];
            bend_one(c, alpha, px, py, inv, &bend.a, &bend.b, &bend.rest);
        }
    }

    /// `solveBlockAreas`: площади треугольников блока.
    pub fn solve_block_areas(&mut self, h: f32, block: usize) {
        // Порядок по цветам (закон с 27.11, color_order.rs): тот же сегмент набора, номера — из раскраски.
        let colored = self.cfg.color_order_on;
        if colored {
            self.ensure_colored_areas(block);
        }
        let s = self;
        let alpha = s.environment.area_compliance / (h * h);
        let alpha_inverted = AREA_COMPLIANCE_INVERTED / (h * h);
        let max_step = AREA_MAX_STEP * s.link_scale;
        let ts_on = s.cell_scales_on;
        let ks_on = s.softness_on;
        let fibers_on = s.fibers_on;
        let fiber_k = s.fiber_thicken;
        let list = &s.constraint_blocks.as_ref().unwrap().areas;
        let from = list.start[block] as usize;
        let count = if colored { list.color_count[block] } else { list.count[block] };
        let to = from + count as usize;
        let items = if colored { &list.color_items } else { &list.items };
        let (px, py, inv) = (&mut s.px, &mut s.py, &s.inv_mass);
        let (ta, tb, tc) = (&s.tri_a, &s.tri_b, &s.tri_c);
        let (rest2, tm, ms, ts, ks) = (&s.tri_rest_area2, &s.tri_muscle, &s.muscle_scale_of, &s.tri_cell_scale, &s.tri_softness);
        let (alive, tid, fib) = (&s.baked.tri_alive, &s.tri_id, &s.tri_fiber_by_id);
        for slot in from..to {
            let t = items[slot];
            if t < 0 {
                continue;
            }
            let t = t as usize;
            if !alive[tid[t] as usize] {
                continue;
            }
            let i0 = ta[t] as usize;
            let i1 = tb[t] as usize;
            let i2 = tc[t] as usize;
            let (x0, y0) = (px[i0], py[i0]);
            let (x1, y1) = (px[i1], py[i1]);
            let (x2, y2) = (px[i2], py[i2]);
            let g0x = y1 - y2;
            let g0y = x2 - x1;
            let g1x = y2 - y0;
            let g1y = x0 - x2;
            let g2x = y0 - y1;
            let g2y = x1 - x0;
            let w0 = inv[i0];
            let w1 = inv[i1];
            let w2 = inv[i2];
            let denom = w0 * (g0x * g0x + g0y * g0y) + w1 * (g1x * g1x + g1y * g1y) + w2 * (g2x * g2x + g2y * g2y);
            if denom < 1e-18f32 {
                continue;
            }
            let m = tm[t];
            let s0 = if m < 0 { 1.0f32 } else { ms[m as usize] };
            let sc = if !ts_on { s0 } else { s0 * ts[t] };
            let active = minf(1.0, sc);
            let fiber_area = sc < 1.0 && fibers_on && (tid[t] as usize) < fib.len() && fib[tid[t] as usize];
            let rest_area2 = if fiber_area { rest2[t] * fiber_area_factor(sc, fiber_k) } else { rest2[t] * active * active };
            let area2 = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
            let material = if !ks_on { alpha } else { alpha * ks[t] };
            let alpha_t = if sc > 1.0 && area2 > rest_area2 { tensile_compliance(material, sc) } else { material };
            let a = if AREA_SMOOTH_RAMP {
                let q = coerce_inf(area2 / rest_area2, 0.0, 1.0);
                alpha_inverted + (alpha_t - alpha_inverted) * q * q
            } else if area2 < 0.0 {
                alpha_inverted
            } else {
                alpha_t
            };
            let mut excess = area2 - rest_area2;
            let pulling = if fiber_area { fiber_area_factor(sc, fiber_k) < 1.0 } else { active < 1.0 };
            if pulling {
                let cap = 2.0f32 * MUSCLE_MAX_PULL * rest2[t];
                if excess > cap {
                    excess = cap;
                }
            }
            let mut d_l = -excess / (denom + a);
            if max_step > 0.0 {
                d_l = cap_area_step(d_l, denom, max_step);
            }
            px[i0] += w0 * d_l * g0x;
            py[i0] += w0 * d_l * g0y;
            px[i1] += w1 * d_l * g1x;
            py[i1] += w1 * d_l * g1y;
            px[i2] += w2 * d_l * g2x;
            py[i2] += w2 * d_l * g2y;
        }
    }

    /// Нарушение предела у связи [c] при длине² [len2]: (crushed, over, scale). Без записей.
    #[inline(always)]
    pub(crate) fn limit_state(&self, c: usize, len2: f32) -> (bool, bool, f32, f32, f32) {
        let mus = self.con_muscle[c];
        let base = if mus < 0 { 1.0f32 } else { self.muscle_scale_of[mus as usize] };
        let scale = if !self.cell_scales_on { base } else { base * self.con_cell_scale[c] };
        let rest = self.con_rest[c];
        let rest_scale = rest * self.limit_shrink(c, scale);
        let crush_lim = LINK_CRUSH_RATIO * rest_scale;
        let max = link_max_length(self.limit_rest(c, rest, scale), scale);
        let hi2 = max * max;
        let lo2 = crush_lim * crush_lim;
        let crushed = self.tearing_on && len2 < lo2;
        let over = len2 > hi2;
        (crushed, over, scale, hi2, lo2)
    }

    /// `solveBlockLimits`: ранний предел длины по контуру блока; собирает связи у порога (nearItems).
    pub fn solve_block_limits(&mut self, block: usize, from: usize) {
        self.constraint_blocks.as_mut().unwrap().begin_limits(block);
        if from == 0 {
            self.constraint_blocks.as_mut().unwrap().near_count[block] = 0;
        }
        let mut cursor = from;
        loop {
            let c = {
                let layout = self.constraint_blocks.as_ref().unwrap();
                if cursor >= layout.limit_count() {
                    break;
                }
                layout.limit_at(cursor)
            };
            cursor += 1;
            if c < 0 {
                continue;
            }
            let c = c as usize;
            if self.con_dead[c] {
                continue;
            }
            let i = self.con_a[c] as usize;
            let j = self.con_b[c] as usize;
            let dx = self.px[i] - self.px[j];
            let dy = self.py[i] - self.py[j];
            let len2 = dx * dx + dy * dy;
            let (crushed, over, scale, hi2, lo2) = self.limit_state(c, len2);
            let is_near = len2 > hi2 * LIMIT_NEAR_HI2 || (self.tearing_on && len2 < lo2 * LIMIT_NEAR_LO2);
            if is_near {
                self.constraint_blocks.as_mut().unwrap().add_near(block, c as i32);
            }
            if !crushed && !over {
                continue;
            }
            if self.inv_mass[i] + self.inv_mass[j] == 0.0 {
                continue;
            }
            self.solve_link_limit_violation(c, len2, crushed, scale);
        }
        self.constraint_blocks.as_mut().unwrap().end_limits();
    }

    /// `solveBlockNearLimits`: поздний предел блока — только по связям у порога.
    pub fn solve_block_near_limits(&mut self, block: usize, from: usize) {
        let mut x = from;
        loop {
            let c = {
                let layout = self.constraint_blocks.as_ref().unwrap();
                if x >= layout.near_count[block] as usize {
                    break;
                }
                layout.near_items[block][x]
            };
            x += 1;
            self.solve_limit_item_serial(c);
        }
    }

    /// `solveLimitItemSerial`: одна связь общего прохода предела в одном потоке.
    pub fn solve_limit_item_serial(&mut self, c: i32) {
        if c < 0 || self.con_dead[c as usize] {
            return;
        }
        let c = c as usize;
        let i = self.con_a[c] as usize;
        let j = self.con_b[c] as usize;
        let dx = self.px[i] - self.px[j];
        let dy = self.py[i] - self.py[j];
        let len2 = dx * dx + dy * dy;
        let (crushed, over, scale, _, _) = self.limit_state(c, len2);
        if !crushed && !over {
            return;
        }
        if self.inv_mass[i] + self.inv_mass[j] == 0.0 {
            return;
        }
        self.solve_link_limit_violation(c, len2, crushed, scale);
    }

    /// `solveLinkMaxLength`: общий проход предела длины — по наборам блоков, затем хвост прохода.
    pub fn solve_link_max_length(&mut self) {
        if self.constraint_blocks.is_none() {
            self.solve_link_max_length_flat();
            return;
        }
        // С воркерами — наборы блоков по воркерам (тот же порядок, что ниже: блоки, швы, хвост; solver/parallel.rs).
        if let Some(w) = self.active_workers() {
            self.solve_global_limits_parallel(&w);
            return;
        }
        self.constraint_blocks.as_mut().unwrap().begin_limit_pass();
        let buckets = self.constraint_blocks.as_ref().unwrap().buckets;
        // Наборы текущей области (`forActive`): у вида острова — только его.
        let mut walk = self.constraint_blocks.as_ref().unwrap().walk(0, buckets);
        while let Some(bucket) = self.constraint_blocks.as_ref().unwrap().next_active(&mut walk) {
            self.solve_limit_bucket_serial(bucket, -1);
        }
        self.solve_limit_pass_tail();
        self.constraint_blocks.as_mut().unwrap().end_limit_pass();
    }

    /// Проход по общему списку контура — мир без блоков (до первой сборки решателя).
    fn solve_link_max_length_flat(&mut self) {
        self.boundary_link_limits.begin_pass();
        let mut cursor = 0usize;
        while cursor < self.boundary_link_limits.count {
            let c = self.boundary_link_limits.ids[cursor];
            cursor += 1;
            if c < 0 {
                continue;
            }
            let c = c as usize;
            if self.con_dead[c] {
                continue;
            }
            let i = self.con_a[c] as usize;
            let j = self.con_b[c] as usize;
            let dx = self.px[i] - self.px[j];
            let dy = self.py[i] - self.py[j];
            let len2 = dx * dx + dy * dy;
            let (crushed, over, scale, _, _) = self.limit_state(c, len2);
            if !crushed && !over {
                continue;
            }
            if self.inv_mass[i] + self.inv_mass[j] == 0.0 {
                continue;
            }
            self.solve_link_limit_violation(c, len2, crushed, scale);
        }
        self.boundary_link_limits.end_pass();
    }

    /// `solveLimitBucketSerial`: набор [bucket] общего прохода пределов с связи [from] (-1 — с начала).
    pub fn solve_limit_bucket_serial(&mut self, bucket: usize, from: i32) {
        let (items, n) = {
            let layout = self.constraint_blocks.as_mut().unwrap();
            let n = layout.limits.dense(bucket).len();
            (layout.limits.take_dense(bucket), n)
        };
        let mut x = 0usize;
        if from >= 0 {
            while x < n && items[x] != from {
                x += 1;
            }
            if x == n {
                x = 0;
            }
        }
        while x < n {
            let c = items[x];
            x += 1;
            {
                let layout = self.constraint_blocks.as_ref().unwrap();
                if layout.pass_added_n > 0 && layout.added_in_pass(c) {
                    continue;
                }
            }
            self.solve_limit_item_serial(c);
        }
        self.constraint_blocks.as_mut().unwrap().limits.put_dense(bucket, items);
    }

    /// `solveLimitPassTail`: края, открытые разрывами прохода, — в порядке событий.
    pub fn solve_limit_pass_tail(&mut self) {
        let mut k = 0usize;
        loop {
            let c = {
                let layout = self.constraint_blocks.as_ref().unwrap();
                if k >= layout.pass_added_n {
                    break;
                }
                layout.pass_added[k]
            };
            k += 1;
            self.solve_limit_item_serial(c);
        }
    }

    /// `isGrowingLink`: дорастающая связь вне предела и разрыва.
    #[inline]
    pub fn is_growing_link(&self, c: usize) -> bool {
        c < self.con_grow_target.len() && self.con_grow_target[c] > 0.0
    }

    /// `solveLinkLimitViolation`: раздавленная или перерастянутая связь — разрыв и/или поправка до предела.
    pub fn solve_link_limit_violation(&mut self, c: usize, len2: f32, crushed: bool, scale: f32) {
        if self.is_growing_link(c) {
            return;
        }
        // Тень острова событий не исполняет: перед разрывом остров откатывается и идёт с миром (island_run.rs).
        if self.isl.shadow && self.limit_violation_is_event(c, len2, crushed, scale) {
            self.isl.abort = true;
            return;
        }
        let i = self.con_a[c] as usize;
        let j = self.con_b[c] as usize;
        let rest_l = self.limit_rest(c, self.con_rest[c], scale);
        let max = link_max_length(rest_l, scale);
        let len = sqrtf(len2);
        if crushed {
            self.link_torn[c] = true;
            self.con_dead[c] = true;
            self.tears_pending = true;
            self.kill_edge_now(self.con_edge[c] as usize);
            self.crushed_cell[i] = true;
            self.crushed_cell[j] = true;
            return;
        }
        if self.tearing_on && self.membrane_pass_tear && self.con_boundary[c] {
            let (ri, rj, min_r) = match self.contacts.as_ref() {
                Some(ct) => (ct.contact_radius[i], ct.contact_radius[j], ct.min_contact_radius()),
                None => (0.0, 0.0, 0.0),
            };
            if len > ri + rj + 2.0f32 * min_r {
                self.link_torn[c] = true;
                self.con_dead[c] = true;
                self.tears_pending = true;
                self.kill_edge_now(self.con_edge[c] as usize);
                self.membrane_pass_count += 1;
                return;
            }
        }
        if len < 1e-12f32 {
            return;
        }
        let tear = if self.con_tendon[c] { self.link_tear_strain * self.tendon_tear } else { self.link_tear_strain };
        if len - LINK_MAX_STRETCH * rest_l > tear * rest_l {
            self.link_torn[c] = true;
            if self.tearing_on {
                self.con_dead[c] = true;
                self.tears_pending = true;
                self.kill_edge_now(self.con_edge[c] as usize);
            }
        }
        if self.tearing_on && self.membrane_tear_strain >= 0.0 && self.con_boundary[c] && !self.con_dead[c] && len - max > self.membrane_tear_strain * rest_l {
            self.link_torn[c] = true;
            self.con_dead[c] = true;
            self.tears_pending = true;
            self.kill_edge_now(self.con_edge[c] as usize);
            self.membrane_tear_count += 1;
        }
        self.apply_link_limit_correction(i, j, len, max);
        self.link_cap_hits += 1;
    }

    /// `applyLinkLimitCorrection`: поправка перерастянутой связи до предела; костный конец её не двигает.
    pub fn apply_link_limit_correction(&mut self, i: usize, j: usize, len: f32, max: f32) {
        let mut wi = self.inv_mass[i];
        let mut wj = self.inv_mass[j];
        if self.bones_rigid {
            let bone_i = self.bone_of[i] >= 0;
            let bone_j = self.bone_of[j] >= 0;
            if bone_i && !bone_j && wj > 0.0 {
                wi = 0.0;
            } else if bone_j && !bone_i && wi > 0.0 {
                wj = 0.0;
            }
        }
        let w = wi + wj;
        let dx = (self.px[i] - self.px[j]) / len;
        let dy = (self.py[i] - self.py[j]) / len;
        let mut d_l = -(len - max) / w;
        let cap_l = self.cfg.link_max_step * self.link_scale / w;
        if d_l < -cap_l {
            d_l = -cap_l;
        }
        apply_pair(&mut self.px, &mut self.py, i, j, dx, dy, d_l, wi, wj);
    }
}
