//! Волокно мышцы — `solver/MuscleFiber.kt`, закон — `config/FiberParameters.kt`. Показатель утолщения k
//! (FIBER_THICKEN) в Rust только 0, 0.5 или 1 (config.rs отвергает другие): общая ветка Kotlin через
//! `StrictMath.pow` тогда не нужна.

use crate::jmath::{maxf, minf, sqrtf};
use crate::params::MUSCLE_MAX_PULL;
use crate::solver::muscles::stronger_scale;
use crate::state::BodyState;

#[inline(always)]
pub fn fiber_across2(s: f32, k: f32) -> f32 {
    if k == 0.0 {
        1.0
    } else if k == 1.0 {
        1.0f32 / (s * s)
    } else {
        // k == 0.5 (config.rs не пропускает других)
        1.0f32 / s
    }
}

#[inline(always)]
pub fn fiber_link_factor(s: f32, c2: f32, k: f32) -> f32 {
    sqrtf(s * s * c2 + fiber_across2(s, k) * (1.0f32 - c2))
}

#[inline]
pub fn fiber_area_factor(s: f32, k: f32) -> f32 {
    if k == 0.0 {
        s
    } else if k == 1.0 {
        1.0
    } else {
        sqrtf(s)
    }
}

/// `fiberLinkCorrection`: поправка связи мышцы, сжатой по волокну (тот же потолок тяги, что у обычной мышцы).
#[inline]
pub fn fiber_link_correction(len: f32, rest: f32, s: f32, w: f32, alpha: f32, c2: f32, k: f32) -> f32 {
    let target = rest * fiber_link_factor(s, c2, k);
    let cap = MUSCLE_MAX_PULL * rest;
    let mut over = len - target;
    if over > cap {
        over = cap;
    } else if over < 0.0 && target > rest {
        let tissue = if len < rest { len - rest } else { 0.0 };
        let muscle = maxf(len, rest) - target;
        over = tissue + (if muscle < -cap { -cap } else { muscle });
    }
    -over / (w + alpha)
}

/// `fiberRelaxedSoftness`: мягкость расслабленной мышцы вдоль волокна.
#[inline(always)]
pub fn fiber_relaxed_softness(s: f32, c2: f32) -> f32 {
    sqrtf(1.0f32 + (s * s - 1.0f32) * c2)
}

#[inline(always)]
fn driving_fiber_index_at(v: usize, src_of: &[i32], fx_len: usize) -> i32 {
    let src = if v < src_of.len() { src_of[v] } else { v as i32 };
    if src >= 0 && (src as usize) < fx_len {
        src
    } else {
        -1
    }
}

#[inline(always)]
fn driving_end_at(a: usize, b: usize, eff: &[f32]) -> usize {
    if a >= eff.len() || b >= eff.len() {
        return a;
    }
    let ea = eff[a];
    let eb = eff[b];
    if stronger_scale(ea, eb) == ea && ea != eb {
        b
    } else {
        a
    }
}

/// `linkFiberC2At`: cos² угла связи в позе покоя к волокну клетки, задающей ей множитель; 1 — без волокна.
#[allow(clippy::too_many_arguments)]
#[inline(always)]
pub fn link_fiber_c2_at(c: usize, ca: &[i32], cb: &[i32], eff: &[f32], src_of: &[i32], fx: &[f32], fy: &[f32], bx: &[f32], by: &[f32]) -> f32 {
    let i = ca[c] as usize;
    let j = cb[c] as usize;
    let f = driving_fiber_index_at(driving_end_at(i, j, eff), src_of, fx.len());
    if f < 0 {
        return 1.0;
    }
    let f = f as usize;
    let x = fx[f];
    let y = fy[f];
    if x == 0.0 && y == 0.0 {
        return 1.0;
    }
    let dx = bx[j] - bx[i];
    let dy = by[j] - by[i];
    let l2 = dx * dx + dy * dy;
    if l2 < 1e-24f32 {
        return 1.0;
    }
    let a = x * dx + y * dy;
    a * a / l2
}

/// `triangleFiberAt`: треугольник сокращается по волокну.
#[allow(clippy::too_many_arguments)]
#[inline(always)]
pub fn triangle_fiber_at(t: usize, ta: &[i32], tb: &[i32], tc: &[i32], bone: &[i32], eff: &[f32], src_of: &[i32], fx: &[f32], fy: &[f32]) -> bool {
    let i0 = ta[t] as usize;
    let i1 = tb[t] as usize;
    let i2 = tc[t] as usize;
    if bone[i0] >= 0 || bone[i1] >= 0 || bone[i2] >= 0 {
        return false;
    }
    if i0 >= eff.len() || i1 >= eff.len() || i2 >= eff.len() {
        return false;
    }
    let mut d = if stronger_scale(eff[i0], eff[i1]) == eff[i0] && eff[i0] != eff[i1] { i1 } else { i0 };
    if stronger_scale(eff[d], eff[i2]) == eff[d] && eff[d] != eff[i2] {
        d = i2;
    }
    let f = driving_fiber_index_at(d, src_of, fx.len());
    f >= 0 && (fx[f as usize] != 0.0 || fy[f as usize] != 0.0)
}

impl BodyState {
    #[inline]
    fn link_along_fiber(&self, c: usize, scale: f32) -> bool {
        self.fibers_on && scale < 1.0 && c < self.con_fiber_c2.len() && self.con_fiber_c2[c] < 1.0
    }

    /// `limitShrink`: доля сжатия связи для предела длины.
    pub fn limit_shrink(&self, c: usize, scale: f32) -> f32 {
        if self.link_along_fiber(c, scale) {
            minf(1.0, fiber_link_factor(scale, self.con_fiber_c2[c], self.fiber_thicken))
        } else {
            minf(1.0, scale)
        }
    }

    /// `limitRest`: длина покоя для предела длины.
    pub fn limit_rest(&self, c: usize, rest: f32, scale: f32) -> f32 {
        if self.link_along_fiber(c, scale) {
            rest * maxf(1.0, fiber_link_factor(scale, self.con_fiber_c2[c], self.fiber_thicken))
        } else {
            rest
        }
    }

    fn link_fiber_c2(&self, c: usize) -> f32 {
        link_fiber_c2_at(c, &self.con_a, &self.con_b, &self.cell_scale_eff, &self.cell_scale_src, &self.cell_fiber_x, &self.cell_fiber_y, &self.body.x, &self.body.y)
    }

    pub fn triangle_fiber(&self, t: usize) -> bool {
        triangle_fiber_at(t, &self.tri_a, &self.tri_b, &self.tri_c, &self.bone_of, &self.cell_scale_eff, &self.cell_scale_src, &self.cell_fiber_x, &self.cell_fiber_y)
    }

    pub fn refresh_triangle_fiber(&mut self, t: usize) {
        if !self.fibers_on {
            return;
        }
        let id = self.tri_id[t] as usize;
        if self.tri_fiber_by_id.len() <= id {
            let cap = self.body.tri_count().max(id + 1);
            self.tri_fiber_by_id.resize(cap, false);
        }
        self.tri_fiber_by_id[id] = self.triangle_fiber(t);
    }

    pub fn ensure_fiber_size(&mut self) {
        if !self.fibers_on {
            return;
        }
        if self.con_fiber_c2.len() < self.con_a.len() {
            let n = self.con_a.len();
            self.con_fiber_c2.resize(n, 1.0);
        }
        if self.tri_fiber_by_id.len() < self.body.tri_count() {
            let n = self.body.tri_count();
            self.tri_fiber_by_id.resize(n, false);
        }
    }

    pub fn refresh_link_fiber(&mut self, c: usize) {
        if !self.fibers_on {
            return;
        }
        if self.con_fiber_c2.len() < self.con_a.len() {
            self.ensure_fiber_size();
        }
        self.con_fiber_c2[c] = self.link_fiber_c2(c);
    }

    /// `muscleLengthFactor`: доля длины покоя связи мышцы.
    pub fn muscle_length_factor(&mut self, c: usize, scale: f32) -> f32 {
        if !self.fibers_on || scale >= 1.0 {
            return minf(1.0, scale);
        }
        self.refresh_link_fiber(c);
        let c2 = self.con_fiber_c2[c];
        if c2 >= 1.0 {
            return minf(1.0, scale);
        }
        fiber_link_factor(scale, c2, self.fiber_thicken)
    }

    /// `triangleAreaFactor`: доля площади покоя треугольника мышцы.
    pub fn triangle_area_factor(&mut self, w: usize, scale: f32) -> f32 {
        if self.fibers_on && scale < 1.0 {
            self.refresh_triangle_fiber(w);
            if self.tri_fiber_by_id[self.tri_id[w] as usize] {
                return fiber_area_factor(scale, self.fiber_thicken);
            }
        }
        let a = minf(1.0, scale);
        a * a
    }
}
