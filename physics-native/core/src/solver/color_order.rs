//! ПОРЯДОК ПО ЦВЕТАМ — `solver/ColorOrder.kt` (закон с 27.11.2026) на Rust.
//!
//! Ограничения набора (блока решателя или швов тела) решались по порядку списка, а у тел игроков они выстроены в
//! цепочки: ограничение ждёт прежних ограничений своих частиц. Порядок по цветам: ограничения набора жадно красятся по
//! порядку списка (цвет — наименьший, которого ещё нет у прежних ограничений с общей частицей), затем решаются цвет за
//! цветом, внутри цвета — по порядку списка. Назад (связи при `sweep_backwards`) — весь раскрашенный список с конца.
//! Это другой порядок Гаусса—Зейделя, то есть другие биты, чем до 27.11; `COLOR_ORDER=0` — прежний порядок.
//!
//! Раскраска — производное списка набора: пересобирается при первом обходе после любой правки набора (разрыв, упаковка,
//! перенос номера) и в состояние мира не входит (отпечаток и снимок её не видят). Её место — в индексах блоков
//! (`BlockIndex`/`DenseBlockIndex`, blocks.rs), маска цветов на частицу — `ConstraintBlocks::color_mask`.
//!
//! Воркеры и острова: набор обходит один воркер; наборы, которые раскрашиваются одновременно (блоки одной чётности, швы
//! разных тел, острова), частиц не делят, поэтому общая маска и записи в разные наборы гонок не дают — тот же довод,
//! что у записей частиц в ядрах. Тень острова пишет раскраску в общие массивы мира (свой набор острова).

use crate::state::BodyState;

/// Цветов не больше, чем бит в u64: у частицы тела игрока ограничений одного рода около шести, жадной раскраске хватает
/// 2·6 − 1 (`MAX_COLORS`).
pub const MAX_COLORS: usize = 64;

/// `colorOrder`: раскраска [m] номеров из [src] с [from] (пустые места −1 пропускаются) в [dst] с [dst_from]: по цветам,
/// внутри цвета — в прежнем порядке. [particles] кладёт частицы ограничения в out (до четырёх) и возвращает их число;
/// [mask] — занятые цвета частиц, на входе и на выходе нули. Возвращает число живых номеров. Редкий путь (после правок
/// набора): временные массивы здесь допустимы.
#[inline]
#[allow(clippy::too_many_arguments)]
pub fn color_order(
    src: &[i32],
    from: usize,
    m: usize,
    dst: &mut [i32],
    dst_from: usize,
    mask: &mut [u64],
    particles: impl Fn(i32, &mut [i32; 4]) -> usize,
) -> i32 {
    let mut parts = [0i32; 4];
    let mut color_of = vec![0i32; m];
    let mut start = [0i32; MAX_COLORS + 1];
    let mut colors = 0usize;
    let mut live = 0i32;
    for k in 0..m {
        let id = src[from + k];
        if id < 0 {
            color_of[k] = -1;
            continue;
        }
        let p = particles(id, &mut parts);
        let mut busy = 0u64;
        for &q in &parts[..p] {
            busy |= mask[q as usize];
        }
        let free = !busy;
        if free == 0 {
            color_overflow(id);
        }
        let bit = free & free.wrapping_neg();
        for &q in &parts[..p] {
            mask[q as usize] |= bit;
        }
        let color = bit.trailing_zeros() as usize;
        color_of[k] = color as i32;
        start[color + 1] += 1;
        live += 1;
        if color >= colors {
            colors = color + 1;
        }
    }
    for c in 0..colors {
        start[c + 1] += start[c];
    }
    for k in 0..m {
        let color = color_of[k];
        if color < 0 {
            continue;
        }
        let id = src[from + k];
        let c = color as usize;
        dst[dst_from + start[c] as usize] = id;
        start[c] += 1;
        let p = particles(id, &mut parts);
        for &q in &parts[..p] {
            mask[q as usize] = 0;
        }
    }
    live
}

#[cold]
#[inline(never)]
fn color_overflow(id: i32) -> ! {
    panic!("порядок по цветам: у частиц ограничения {id} больше {MAX_COLORS} цветов")
}

impl BodyState {
    /// `coloredLinks`: связи набора [block] по цветам — `links.color_items` с `links.start[block]`, их число —
    /// `links.color_count[block]`. Раскраска — только если набор правили после прошлой.
    #[inline]
    pub fn ensure_colored_links(&mut self, block: usize) {
        let layout = self.constraint_blocks.as_mut().unwrap();
        if !layout.links.color_stale(block) {
            return;
        }
        let (a, b) = (&self.con_a, &self.con_b);
        layout.links.color_block(block, &mut layout.color_mask, |c, out| {
            out[0] = a[c as usize];
            out[1] = b[c as usize];
            2
        });
    }

    /// `coloredAreas`: площади (треугольники) набора [block] по цветам — как [ensure_colored_links].
    #[inline]
    pub fn ensure_colored_areas(&mut self, block: usize) {
        let layout = self.constraint_blocks.as_mut().unwrap();
        if !layout.areas.color_stale(block) {
            return;
        }
        let (ta, tb, tc) = (&self.tri_a, &self.tri_b, &self.tri_c);
        layout.areas.color_block(block, &mut layout.color_mask, |t, out| {
            out[0] = ta[t as usize];
            out[1] = tb[t as usize];
            out[2] = tc[t as usize];
            3
        });
    }

    /// `coloredBends`: изгибы набора [block] по цветам — `bends.color_items(block)` с нуля, их число —
    /// `bends.color_count[block]`.
    #[inline]
    pub fn ensure_colored_bends(&mut self, block: usize) {
        let layout = self.constraint_blocks.as_mut().unwrap();
        if !layout.bends.color_stale(block) {
            return;
        }
        let bend = &self.local_boundary.bend;
        layout.bends.color_block(block, &mut layout.color_mask, |c, out| {
            out[0] = bend.a[c as usize];
            out[1] = bend.b[c as usize];
            2
        });
    }

    /// `coloredFlaps`: створки набора [block] по цветам. Вершина — тоже частица створки: ядро читает её положение
    /// (`flap_angle_rest`).
    #[inline]
    pub fn ensure_colored_flaps(&mut self, block: usize) {
        let layout = self.constraint_blocks.as_mut().unwrap();
        if !layout.flaps.color_stale(block) {
            return;
        }
        let flap = &self.local_boundary.flap;
        layout.flaps.color_block(block, &mut layout.color_mask, |c, out| {
            out[0] = flap.a[c as usize];
            out[1] = flap.b[c as usize];
            out[2] = flap.owner[c as usize];
            3
        });
    }
}
