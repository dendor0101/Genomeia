//! «Волны» — `topology/WaveOrder.kt`: связи и треугольники описания переставляются так, чтобы соседние в памяти не
//! делили частиц (уровни в окне WAVE_WINDOW). Биты решателя те же, что у мира, загруженного тем же описанием.

use crate::definition::BodyDefinition;
use crate::params::WAVE_WINDOW;

/// `waveOrder`: перестановка элементов (a[k], b[k][, c[k]]) по уровням.
pub fn wave_order(n: usize, a: &[i32], b: &[i32], c: Option<&[i32]>, window: usize) -> Vec<i32> {
    let count = a.len();
    let mut last = vec![0i32; n];
    let mut level = vec![0i32; count];
    let mut sizes = vec![0i32; count + 1];
    let mut height = 0i32;
    let mut base = 0i32;
    for k in 0..count {
        if k % window == 0 {
            base = height;
        }
        // Kotlin maxOf(base, x, y) = max(base, max(x, y)) — для целых порядок не важен.
        let mut l = base.max(last[a[k] as usize].max(last[b[k] as usize]));
        if let Some(c) = c {
            if last[c[k] as usize] > l {
                l = last[c[k] as usize];
            }
        }
        level[k] = l;
        last[a[k] as usize] = l + 1;
        last[b[k] as usize] = l + 1;
        if let Some(c) = c {
            last[c[k] as usize] = l + 1;
        }
        sizes[l as usize] += 1;
        if l + 1 > height {
            height = l + 1;
        }
    }
    let mut cursor = vec![0i32; height as usize + 1];
    for l in 0..height as usize {
        cursor[l + 1] = cursor[l] + sizes[l];
    }
    let mut order = vec![0i32; count];
    for k in 0..count {
        let lv = level[k] as usize;
        order[cursor[lv] as usize] = k as i32;
        cursor[lv] += 1;
    }
    order
}

impl BodyDefinition {
    /// `inWaveOrder`: описание со связями и треугольниками в порядке «волн».
    pub fn in_wave_order(&self) -> BodyDefinition {
        let n = self.count();
        let lo = wave_order(n, &self.link_a, &self.link_b, None, WAVE_WINDOW as usize);
        let to = wave_order(n, &self.tri_a, &self.tri_b, Some(&self.tri_c), WAVE_WINDOW as usize);
        let mut retire_start = self.link_retire_start.clone();
        let mut retire_cells = self.link_retire_cells.clone();
        if self.has_temp_links() {
            let lc = self.link_count();
            retire_start = vec![0; lc + 1];
            retire_cells = vec![0; self.link_retire_cells.len()];
            let mut w = 0usize;
            for k in 0..lc {
                let l = lo[k] as usize;
                retire_start[k] = w as i32;
                for s in self.link_retire_start[l]..self.link_retire_start[l + 1] {
                    let s = s as usize;
                    retire_cells[2 * w] = self.link_retire_cells[2 * s];
                    retire_cells[2 * w + 1] = self.link_retire_cells[2 * s + 1];
                    w += 1;
                }
            }
            retire_start[lc] = w as i32;
        }
        BodyDefinition {
            x: self.x.clone(),
            y: self.y.clone(),
            radius: self.radius.clone(),
            is_bone: self.is_bone.clone(),
            is_muscle: self.is_muscle.clone(),
            link_a: lo.iter().map(|&k| self.link_a[k as usize]).collect(),
            link_b: lo.iter().map(|&k| self.link_b[k as usize]).collect(),
            tri_a: to.iter().map(|&k| self.tri_a[k as usize]).collect(),
            tri_b: to.iter().map(|&k| self.tri_b[k as usize]).collect(),
            tri_c: to.iter().map(|&k| self.tri_c[k as usize]).collect(),
            tri_rest_area2: to.iter().map(|&k| self.tri_rest_area2[k as usize]).collect(),
            bone_clusters: self.bone_clusters.clone(),
            muscle_clusters: self.muscle_clusters.clone(),
            scale: self.scale,
            link_retire_start: retire_start,
            link_retire_cells: retire_cells,
        }
    }
}
