//! Кости: проекция кости на позу покоя (`solver/BoneSolver.kt`, shape matching) и рамка мира для костей
//! (`solver/BoneWorldBounds.kt`). Всё во float, как в Kotlin; порядок сумм — по списку клеток кости.

use crate::jmath::{maxf, minf, sqrtf};
use crate::params::BONE_MAX_STEP;
use crate::state::BodyState;

/// Бит на кость, список непустых слов (`BoneWallHits`). Обход и очистка — только по задетым словам.
#[derive(Clone, Debug)]
pub struct BoneWallHits {
    pub capacity: usize,
    bits: Vec<u64>,
    used: Vec<i32>,
    pub size: usize,
}

impl BoneWallHits {
    pub fn new(capacity: usize) -> BoneWallHits {
        let words = (capacity + 63) >> 6;
        BoneWallHits { capacity, bits: vec![0; words], used: vec![0; words], size: 0 }
    }

    #[inline]
    pub fn add(&mut self, bone: usize) {
        let word = bone >> 6;
        if self.bits[word] == 0 {
            self.used[self.size] = word as i32;
            self.size += 1;
        }
        self.bits[word] |= 1u64 << (bone & 63);
    }

    /// `drain`: кости по возрастанию номера внутри слова, слова — в порядке первого попадания; всё очищается.
    pub fn drain(&mut self, mut visit: impl FnMut(usize)) {
        for k in 0..self.size {
            let word = self.used[k] as usize;
            let mut mask = self.bits[word];
            while mask != 0 {
                visit((word << 6) + mask.trailing_zeros() as usize);
                mask &= mask - 1;
            }
            self.bits[word] = 0;
            self.used[k] = 0;
        }
        self.size = 0;
    }
}

/// Если кость шире рамки, жёсткого переноса по оси нет: избыток делится поровну.
#[inline]
fn wall_shift(low: f32, high: f32) -> f32 {
    if low > high {
        (low + high) * 0.5
    } else {
        // 0.0f.coerceIn(low, high): low <= high здесь.
        let x = 0.0f32;
        if x < low {
            low
        } else if x > high {
            high
        } else {
            x
        }
    }
}

#[inline]
fn wall_coordinate(x: f32, min: f32, max: f32, r: f32) -> f32 {
    if min + r > max - r {
        (min + max) * 0.5
    } else {
        let lo = min + r;
        let hi = max - r;
        if x < lo {
            lo
        } else if x > hi {
            hi
        } else {
            x
        }
    }
}

impl BodyState {
    /// `resolveBoneBounds`: кости, задевшие рамку, возвращаются внутрь жёстким сдвигом.
    pub fn resolve_bone_bounds(&mut self, final_pass: bool) {
        let mut hits = std::mem::replace(&mut self.wall_hits, BoneWallHits::new(0));
        hits.drain(|bone| self.fit_bone_to_bounds(bone, final_pass));
        self.wall_hits = hits;
    }

    #[inline]
    fn movable_bone_member(&self, i: usize, bone: usize) -> bool {
        self.bone_of[i] == bone as i32 && !self.cell_unborn[i] && !self.cell_dead[i] && !self.is_fixed(i)
    }

    fn fit_bone_to_bounds(&mut self, bone: usize, final_pass: bool) {
        let ids = std::mem::take(&mut self.rigid_bones[bone]);
        let mut low_x = f32::NEG_INFINITY;
        let mut low_y = f32::NEG_INFINITY;
        let mut high_x = f32::INFINITY;
        let mut high_y = f32::INFINITY;
        let mut mass = 0.0f32;
        let mut momentum_x = 0.0f32;
        let mut momentum_y = 0.0f32;
        for &i in &ids {
            let i = i as usize;
            if !self.movable_bone_member(i, bone) {
                continue;
            }
            let r = self.radius[i];
            low_x = maxf(low_x, self.bounds_min_x + r - self.px[i]);
            high_x = minf(high_x, self.bounds_max_x - r - self.px[i]);
            low_y = maxf(low_y, self.bounds_min_y + r - self.py[i]);
            high_y = minf(high_y, self.bounds_max_y - r - self.py[i]);
            if final_pass {
                let m = 1.0f32 / self.rest_inv_mass[i];
                mass += m;
                momentum_x += m * self.vx[i];
                momentum_y += m * self.vy[i];
            }
        }
        let dx = wall_shift(low_x, high_x);
        let dy = wall_shift(low_y, high_y);
        if dx == 0.0 && dy == 0.0 && low_x <= high_x && low_y <= high_y {
            self.rigid_bones[bone] = ids;
            return;
        }
        let stop_x = if final_pass && mass > 0.0 && dx * momentum_x < 0.0 { momentum_x / mass } else { 0.0 };
        let stop_y = if final_pass && mass > 0.0 && dy * momentum_y < 0.0 { momentum_y / mass } else { 0.0 };
        // Рамка — опора и для кости: её группа в этом тике из учёта импульса выходит.
        let cancel = self.cancel_spin_on;
        for &i in &ids {
            let i = i as usize;
            if !self.movable_bone_member(i, bone) {
                continue;
            }
            if cancel {
                self.tick_momentum().block(i);
            }
            let r = self.radius[i];
            self.px[i] = wall_coordinate(self.px[i] + dx, self.bounds_min_x, self.bounds_max_x, r);
            self.py[i] = wall_coordinate(self.py[i] + dy, self.bounds_min_y, self.bounds_max_y, r);
            if final_pass {
                self.vx[i] -= stop_x;
                self.vy[i] -= stop_y;
                if self.inv_mass[i] == 0.0 {
                    self.prev_x[i] = self.px[i];
                    self.prev_y[i] = self.py[i];
                }
            }
        }
        self.rigid_bones[bone] = ids;
    }

    /// `projectBone`: проекция кости с общими черновиками поправок.
    pub fn project_bone(&mut self, b: usize) {
        let mut dx = std::mem::take(&mut self.bone_dx);
        let mut dy = std::mem::take(&mut self.bone_dy);
        if project_bone_into(self, b, &mut dx, &mut dy) {
            self.bone_cap_hits += 1;
        }
        self.bone_dx = dx;
        self.bone_dy = dy;
    }
}

/// `projectBoneInto`: проекция кости [b] с черновиками поправок снаружи (у каждого воркера свои). Возвращает,
/// сработал ли потолок смещения. Пишет только px/py клеток кости.
pub fn project_bone_into(s: &mut BodyState, b: usize, bone_dx: &mut [f32], bone_dy: &mut [f32]) -> bool {
    let link_scale = s.link_scale;
    let BodyState { rigid_bones, bone_rest_qx, bone_rest_qy, px, py, match_weight, .. } = s;
    project_bone_arrays(&rigid_bones[b], &bone_rest_qx[b], &bone_rest_qy[b], px, py, match_weight, link_scale, bone_dx, bone_dy)
}

/// Ядро проекции кости над массивами: годится и воркерам (кости не делят вершин).
#[allow(clippy::too_many_arguments)]
pub fn project_bone_arrays(
    ids: &[i32],
    q0x: &[f32],
    q0y: &[f32],
    px: &mut [f32],
    py: &mut [f32],
    match_weight: &[f32],
    link_scale: f32,
    bone_dx: &mut [f32],
    bone_dy: &mut [f32],
) -> bool {
    // Начало отсчёта — первая вершина кости: числа порядка размера кости.
    let ox = px[ids[0] as usize];
    let oy = py[ids[0] as usize];

    let mut cx = 0.0f32;
    let mut cy = 0.0f32;
    let mut wsum = 0.0f32;
    for &i in ids {
        let i = i as usize;
        let w = match_weight[i];
        cx += w * (px[i] - ox);
        cy += w * (py[i] - oy);
        wsum += w;
    }
    // Кость без рождённых клеток не проецируется.
    if wsum <= 0.0 {
        return false;
    }
    cx /= wsum;
    cy /= wsum;

    let mut s = 0.0f32;
    let mut t = 0.0f32;
    for k in 0..ids.len() {
        let i = ids[k] as usize;
        let w = match_weight[i];
        let ppx = (px[i] - ox) - cx;
        let ppy = (py[i] - oy) - cy;
        s += w * (q0x[k] * ppx + q0y[k] * ppy);
        t += w * (q0x[k] * ppy - q0y[k] * ppx);
    }
    let norm = sqrtf(s * s + t * t);
    if norm < 1e-12f32 {
        return false;
    }
    let cos = s / norm;
    let sin = t / norm;

    let mut sdx = 0.0f32;
    let mut sdy = 0.0f32;
    for k in 0..ids.len() {
        let i = ids[k] as usize;
        let tx = ox + cx + cos * q0x[k] - sin * q0y[k];
        let ty = oy + cy + sin * q0x[k] + cos * q0y[k];
        bone_dx[k] = tx - px[i];
        bone_dy[k] = ty - py[i];
        let w = match_weight[i];
        sdx += w * bone_dx[k];
        sdy += w * bone_dy[k];
    }
    let mdx = sdx / wsum;
    let mdy = sdy / wsum;

    // Потолок на смещение за подшаг — одним множителем на весь набор (сумма поправок уже ноль).
    let mut max_d2 = 0.0f32;
    for k in 0..ids.len() {
        if match_weight[ids[k] as usize] == 0.0 {
            continue;
        }
        let dx = bone_dx[k] - mdx;
        let dy = bone_dy[k] - mdy;
        let d2 = dx * dx + dy * dy;
        if d2 > max_d2 {
            max_d2 = d2;
        }
    }
    let mut scale = 1.0f32;
    let cap = BONE_MAX_STEP * link_scale;
    let capped = max_d2 > cap * cap;
    if capped {
        scale = cap / sqrtf(max_d2);
    }

    for k in 0..ids.len() {
        let i = ids[k] as usize;
        if match_weight[i] == 0.0 {
            continue;
        }
        px[i] += (bone_dx[k] - mdx) * scale;
        py[i] += (bone_dy[k] - mdy) * scale;
    }
    capped
}
