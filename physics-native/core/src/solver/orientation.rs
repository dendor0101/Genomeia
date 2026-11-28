//! Ориентация контура — `solver/BoundaryOrientation.kt`: вывернутый наружу треугольник контура (знак площади против
//! знака покоя) возвращается одной проекцией площади в ноль. Клетку включённой кости проекция не двигает, пока в
//! треугольнике есть подвижная мягкая клетка (16.11.2026). Стадия целиком (обход по блокам, затем швы — при любом
//! числе организмов, находка 42) — в `solver/stages.rs`.

use crate::jmath::{coerce_at_leastf, sqrtf};

/// `orientEdge`: ребро контура [k]; [bone] — номера костей клеток (`orientationBones`), None — кости не берегутся.
#[inline]
pub fn orient_edge(
    k: usize,
    a: &[i32],
    b: &[i32],
    opposite: &[i32],
    orientation: &[i8],
    px: &mut [f32],
    py: &mut [f32],
    inv_mass: &[f32],
    bone: Option<&[i32]>,
) {
    let c = opposite[k];
    if c < 0 {
        return;
    }
    let c = c as usize;
    let i = a[k] as usize;
    let j = b[k] as usize;
    let area = (px[j] - px[i]) * (py[c] - py[i]) - (py[j] - py[i]) * (px[c] - px[i]);
    // Kotlin: Float * Byte — байт расширяется до float.
    if area * (orientation[k] as f32) >= 0.0 {
        return;
    }
    project_boundary_orientation(i, j, c, area, px, py, inv_mass, bone);
}

/// `solveBoundaryOrientation`: рёбра контура подряд.
pub fn solve_boundary_orientation(
    count: usize,
    a: &[i32],
    b: &[i32],
    opposite: &[i32],
    orientation: &[i8],
    px: &mut [f32],
    py: &mut [f32],
    inv_mass: &[f32],
    bone: Option<&[i32]>,
) {
    for k in 0..count {
        orient_edge(k, a, b, opposite, orientation, px, py, inv_mass, bone);
    }
}

/// `projectBoundaryOrientation`: площадь треугольника (a, b, c) в ноль вдоль взвешенного градиента — ближайший корень
/// квадратного уравнения в форме без вычитания близких чисел. Всё во float.
pub fn project_boundary_orientation(
    a: usize,
    b: usize,
    c: usize,
    area: f32,
    x: &mut [f32],
    y: &mut [f32],
    w: &[f32],
    bone: Option<&[i32]>,
) {
    let gax = y[b] - y[c];
    let gay = x[c] - x[b];
    let gbx = y[c] - y[a];
    let gby = x[a] - x[c];
    let gcx = y[a] - y[b];
    let gcy = x[b] - x[a];
    let mut wa = w[a];
    let mut wb = w[b];
    let mut wc = w[c];
    if let Some(bone) = bone {
        let ba = bone[a] >= 0;
        let bb = bone[b] >= 0;
        let bc = bone[c] >= 0;
        if (ba || bb || bc) && ((!ba && wa > 0.0) || (!bb && wb > 0.0) || (!bc && wc > 0.0)) {
            if ba {
                wa = 0.0;
            }
            if bb {
                wb = 0.0;
            }
            if bc {
                wc = 0.0;
            }
        }
    }
    let den = wa * (gax * gax + gay * gay) + wb * (gbx * gbx + gby * gby) + wc * (gcx * gcx + gcy * gcy);
    if den < 1e-18f32 {
        return;
    }
    let q = (wa * wb + wb * wc + wc * wa) * area;
    let discriminant = coerce_at_leastf(den * den - 4.0f32 * q * area, 0.0);
    let lambda = -2.0f32 * area / (den + sqrtf(discriminant));
    x[a] += wa * lambda * gax;
    y[a] += wa * lambda * gay;
    x[b] += wb * lambda * gbx;
    y[b] += wb * lambda * gby;
    x[c] += wc * lambda * gcx;
    y[c] += wc * lambda * gcy;
}
