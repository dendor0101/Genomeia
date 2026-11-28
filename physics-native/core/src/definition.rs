//! Описание тела из редактора (`model/BodyDefinition.kt`) и постоянные масштаба мира (`model/WorldScale.kt`).
//! Описание неизменно: ткань рвётся в собственных копиях топологии BodyState, а отсюда берутся только позы покоя,
//! радиусы, кластеры и временные связи роста.

use crate::jmath::sqrtf;

#[derive(Clone, Debug, Default)]
pub struct BodyDefinition {
    pub x: Vec<f32>,
    pub y: Vec<f32>,
    pub radius: Vec<f32>,
    pub is_bone: Vec<bool>,
    pub is_muscle: Vec<bool>,
    pub link_a: Vec<i32>,
    pub link_b: Vec<i32>,
    pub tri_a: Vec<i32>,
    pub tri_b: Vec<i32>,
    pub tri_c: Vec<i32>,
    pub tri_rest_area2: Vec<f32>,
    pub bone_clusters: Vec<Vec<i32>>,
    pub muscle_clusters: Vec<Vec<i32>>,
    pub scale: f32,
    /// Временные связи роста (topology/TempLinks.kt): у связи l — пары s из link_retire_start[l]..[l + 1]:
    /// клетка link_retire_cells[2s] и вторая клетка link_retire_cells[2s + 1] (−1 — клетка лежит на связи).
    /// Пусто — временных связей у описания нет.
    pub link_retire_start: Vec<i32>,
    pub link_retire_cells: Vec<i32>,
}

impl BodyDefinition {
    #[inline]
    pub fn count(&self) -> usize {
        self.x.len()
    }
    #[inline]
    pub fn link_count(&self) -> usize {
        self.link_a.len()
    }
    #[inline]
    pub fn tri_count(&self) -> usize {
        self.tri_a.len()
    }

    /// Есть ли у описания временные связи роста.
    #[inline]
    pub fn has_temp_links(&self) -> bool {
        !self.link_retire_start.is_empty()
    }

    /// Временная ли связь [l] (снимается рождением клетки, которая на неё ложится).
    #[inline]
    pub fn is_temp_link(&self, l: usize) -> bool {
        !self.link_retire_start.is_empty() && self.link_retire_start[l + 1] > self.link_retire_start[l]
    }

    /// Описание для мира, где рождены клетки [born] (None — все): временная связь, которую рождённая клетка уже сняла
    /// бы, в мир не идёт вовсе (`BodyDefinition.forBorn`). Без временных связей, которые надо выбросить, — копия.
    pub fn for_born(&self, born: Option<&[bool]>) -> BodyDefinition {
        if !self.has_temp_links() {
            return self.clone();
        }
        let lc = self.link_count();
        let mut keep = vec![false; lc];
        for l in 0..lc {
            let mut blocked = false;
            for s in self.link_retire_start[l]..self.link_retire_start[l + 1] {
                let c = self.link_retire_cells[2 * s as usize];
                let p = self.link_retire_cells[2 * s as usize + 1];
                if born.is_none_or(|b| b[c as usize] && (p < 0 || b[p as usize])) {
                    blocked = true;
                    break;
                }
            }
            keep[l] = !blocked;
        }
        let mut kept = 0usize;
        let mut temp = 0usize;
        for l in 0..lc {
            if keep[l] {
                kept += 1;
                if self.is_temp_link(l) {
                    temp += 1;
                }
            }
        }
        if kept == lc {
            return self.clone();
        }
        let mut la = vec![0i32; kept];
        let mut lb = vec![0i32; kept];
        let mut start = if temp > 0 { vec![0i32; kept + 1] } else { Vec::new() };
        let mut cells: Vec<i32> = Vec::new();
        let mut m = 0usize;
        for l in 0..lc {
            if !keep[l] {
                continue;
            }
            la[m] = self.link_a[l];
            lb[m] = self.link_b[l];
            if temp > 0 {
                for s in self.link_retire_start[l]..self.link_retire_start[l + 1] {
                    cells.push(self.link_retire_cells[2 * s as usize]);
                    cells.push(self.link_retire_cells[2 * s as usize + 1]);
                }
                start[m + 1] = (cells.len() / 2) as i32;
            }
            m += 1;
        }
        BodyDefinition {
            link_a: la,
            link_b: lb,
            link_retire_start: start,
            link_retire_cells: cells,
            ..self.clone()
        }
    }

    /// Средняя длина связи позы покоя: сумма во float, как в Kotlin (`var s = 0f`).
    pub fn mean_link_length(&self) -> f32 {
        let lc = self.link_count();
        if lc == 0 {
            return 1.0;
        }
        let mut s = 0.0f32;
        for i in 0..lc {
            let dx = self.x[self.link_a[i] as usize] - self.x[self.link_b[i] as usize];
            let dy = self.y[self.link_a[i] as usize] - self.y[self.link_b[i] as usize];
            s += sqrtf(dx * dx + dy * dy);
        }
        s / lc as f32
    }

    /// Склейка тел (`BodyDefinition.concat`): номера второго сдвигаются на число клеток первого, временные связи
    /// роста — промежутки обоих описаний подряд.
    pub fn concat(&self, other: &BodyDefinition) -> BodyDefinition {
        let off = self.count() as i32;
        fn cat<T: Clone>(a: &[T], b: &[T]) -> Vec<T> {
            let mut v = Vec::with_capacity(a.len() + b.len());
            v.extend_from_slice(a);
            v.extend_from_slice(b);
            v
        }
        fn cat_shift(a: &[i32], b: &[i32], off: i32) -> Vec<i32> {
            let mut v = Vec::with_capacity(a.len() + b.len());
            v.extend_from_slice(a);
            v.extend(b.iter().map(|&k| k + off));
            v
        }
        fn clusters(a: &[Vec<i32>], b: &[Vec<i32>], off: i32) -> Vec<Vec<i32>> {
            let mut v: Vec<Vec<i32>> = a.to_vec();
            v.extend(b.iter().map(|c| c.iter().map(|&i| i + off).collect()));
            v
        }
        let (lc, olc) = (self.link_count(), other.link_count());
        let mut retire_start = Vec::new();
        let mut retire_cells = Vec::new();
        if self.has_temp_links() || other.has_temp_links() {
            retire_start = vec![0i32; lc + olc + 1];
            retire_cells = vec![0i32; self.link_retire_cells.len() + other.link_retire_cells.len()];
            let mut m = 0usize;
            for l in 0..lc {
                if self.has_temp_links() {
                    for s in self.link_retire_start[l]..self.link_retire_start[l + 1] {
                        retire_cells[m] = self.link_retire_cells[2 * s as usize];
                        m += 1;
                        retire_cells[m] = self.link_retire_cells[2 * s as usize + 1];
                        m += 1;
                    }
                }
                retire_start[l + 1] = (m / 2) as i32;
            }
            for l in 0..olc {
                if other.has_temp_links() {
                    for s in other.link_retire_start[l]..other.link_retire_start[l + 1] {
                        let p = other.link_retire_cells[2 * s as usize + 1];
                        retire_cells[m] = other.link_retire_cells[2 * s as usize] + off;
                        m += 1;
                        retire_cells[m] = if p < 0 { p } else { p + off };
                        m += 1;
                    }
                }
                retire_start[lc + l + 1] = (m / 2) as i32;
            }
        }
        BodyDefinition {
            x: cat(&self.x, &other.x),
            y: cat(&self.y, &other.y),
            radius: cat(&self.radius, &other.radius),
            is_bone: cat(&self.is_bone, &other.is_bone),
            is_muscle: cat(&self.is_muscle, &other.is_muscle),
            link_a: cat_shift(&self.link_a, &other.link_a, off),
            link_b: cat_shift(&self.link_b, &other.link_b, off),
            tri_a: cat_shift(&self.tri_a, &other.tri_a, off),
            tri_b: cat_shift(&self.tri_b, &other.tri_b, off),
            tri_c: cat_shift(&self.tri_c, &other.tri_c, off),
            tri_rest_area2: cat(&self.tri_rest_area2, &other.tri_rest_area2),
            bone_clusters: clusters(&self.bone_clusters, &other.bone_clusters, off),
            muscle_clusters: clusters(&self.muscle_clusters, &other.muscle_clusters, off),
            scale: self.scale,
            link_retire_start: retire_start,
            link_retire_cells: retire_cells,
        }
    }
}

/// Постоянные масштаба мира (`WorldScale.kt`): средняя длина связи и средний квадрат радиуса клетки — во float.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct WorldScale {
    pub mean_link_length: f32,
    pub mean_cell_area: f32,
}

impl WorldScale {
    /// Средние по набору тел (`WorldScale.of`): сумма r² во float, деление на число клеток.
    pub fn of(d: &BodyDefinition) -> WorldScale {
        let mut mean_area = 0.0f32;
        for i in 0..d.count() {
            let r = d.radius[i];
            mean_area += r * r;
        }
        mean_area /= d.count() as f32;
        WorldScale { mean_link_length: d.mean_link_length(), mean_cell_area: mean_area }
    }
}
