//! Математика с ПОБИТОВО теми же ответами, что у JVM.
//!
//! Решатель Kotlin пользуется только `sqrt`, `abs`, `floor`, `ceil`, `Math.min/max`,
//! `coerce*` и `StrictMath.sin/cos/acos` (fdlibm). Корень, модуль, floor и ceil в Rust
//! и в Java дают одно и то же по IEEE 754 — их берём из std. А вот:
//!
//! - `f64::min/max` в Rust возвращают не-NaN операнд и не различают ±0, тогда как
//!   `Math.min/max` отдают NaN и считают -0.0 меньше +0.0. Знак нуля потом виден в
//!   делении и в битах хэша мира, поэтому здесь свои [`min`]/[`max`] по коду JDK;
//! - `sin/cos/acos` из libm платформы (MSVC CRT, bionic на Android) расходятся с fdlibm
//!   на последний бит. Здесь — перенос fdlibm 5.3 (тот же, что `java.lang.FdLibm`),
//!   сверяемый с `StrictMath` тестом Kotlin на миллионе случайных аргументов.
//!
//! FMA не используется нигде: JVM не сливает умножение со сложением, а Rust по умолчанию
//! тоже не сливает — `mul_add` в этом крейте запрещён.
//!
//! Код намеренно повторяет JDK и fdlibm буква в букву: `a != a` (проверка NaN, как в JDK),
//! `x - x` (NaN из бесконечности, как в C) и константы, записанные ровно как в исходнике
//! fdlibm, — так перенос сверяется с оригиналом глазами. Отсюда разрешения clippy ниже.
#![allow(clippy::eq_op, clippy::approx_constant)]
#![allow(clippy::excessive_precision, clippy::unreadable_literal)]

// ---------------------------------------------------------------------------------------
//  Math.min / Math.max (JDK 21, java.lang.Math)
// ---------------------------------------------------------------------------------------

/// `Math.min(double, double)`: NaN заразен, -0.0 < +0.0.
#[inline(always)]
pub fn min(a: f64, b: f64) -> f64 {
    if a != a {
        return a;
    }
    if a == 0.0 && b == 0.0 && b.to_bits() == (-0.0f64).to_bits() {
        return b;
    }
    if a <= b { a } else { b }
}

/// `Math.max(double, double)`: NaN заразен, +0.0 > -0.0.
#[inline(always)]
pub fn max(a: f64, b: f64) -> f64 {
    if a != a {
        return a;
    }
    if a == 0.0 && b == 0.0 && a.to_bits() == (-0.0f64).to_bits() {
        return b;
    }
    if a >= b { a } else { b }
}

/// `Math.min(float, float)`.
#[inline(always)]
pub fn minf(a: f32, b: f32) -> f32 {
    if a != a {
        return a;
    }
    if a == 0.0 && b == 0.0 && b.to_bits() == (-0.0f32).to_bits() {
        return b;
    }
    if a <= b { a } else { b }
}

/// `Math.max(float, float)`.
#[inline(always)]
pub fn maxf(a: f32, b: f32) -> f32 {
    if a != a {
        return a;
    }
    if a == 0.0 && b == 0.0 && a.to_bits() == (-0.0f32).to_bits() {
        return b;
    }
    if a >= b { a } else { b }
}

/// Kotlin `Double.coerceIn(lo, hi)`: сначала «меньше нижней», потом «больше верхней».
#[inline(always)]
pub fn coerce_in(x: f64, lo: f64, hi: f64) -> f64 {
    debug_assert!(!(lo > hi), "coerceIn: пустой отрезок");
    if x < lo {
        lo
    } else if x > hi {
        hi
    } else {
        x
    }
}

/// Kotlin `Float.coerceIn(lo, hi)`.
#[inline(always)]
pub fn coerce_inf(x: f32, lo: f32, hi: f32) -> f32 {
    debug_assert!(!(lo > hi), "coerceIn: пустой отрезок");
    if x < lo {
        lo
    } else if x > hi {
        hi
    } else {
        x
    }
}

/// Kotlin `Double.coerceAtLeast(lo)`.
#[inline(always)]
pub fn coerce_at_least(x: f64, lo: f64) -> f64 {
    if x < lo { lo } else { x }
}

/// Kotlin `Double.coerceAtMost(hi)`.
#[inline(always)]
pub fn coerce_at_most(x: f64, hi: f64) -> f64 {
    if x > hi { hi } else { x }
}

/// Kotlin `Float.coerceAtLeast(lo)`.
#[inline(always)]
pub fn coerce_at_leastf(x: f32, lo: f32) -> f32 {
    if x < lo { lo } else { x }
}

/// Kotlin `Float.coerceAtMost(hi)`.
#[inline(always)]
pub fn coerce_at_mostf(x: f32, hi: f32) -> f32 {
    if x > hi { hi } else { x }
}

/// Kotlin `sqrt(Float)` = `Math.sqrt(x.toDouble()).toFloat()`. Двойное округление здесь
/// безвредно (53 ≥ 2·24 + 2), то есть это то же, что корень во float, но пишем как в Kotlin.
#[inline(always)]
pub fn sqrtf(x: f32) -> f32 {
    (x as f64).sqrt() as f32
}

// ---------------------------------------------------------------------------------------
//  fdlibm 5.3: sin, cos, acos — как java.lang.StrictMath
// ---------------------------------------------------------------------------------------

#[inline(always)]
fn hi(x: f64) -> i32 {
    (x.to_bits() >> 32) as u32 as i32
}

#[inline(always)]
fn lo(x: f64) -> i32 {
    x.to_bits() as u32 as i32
}

#[inline(always)]
fn with_hi(x: f64, h: i32) -> f64 {
    f64::from_bits(((h as u32 as u64) << 32) | (x.to_bits() & 0xffff_ffff))
}

#[inline(always)]
fn with_lo(x: f64, l: i32) -> f64 {
    f64::from_bits((x.to_bits() & 0xffff_ffff_0000_0000) | (l as u32 as u64))
}

fn kernel_sin(x: f64, y: f64, iy: i32) -> f64 {
    const HALF: f64 = 5.00000000000000000000e-01;
    const S1: f64 = -1.66666666666666324348e-01;
    const S2: f64 = 8.33333333332248946124e-03;
    const S3: f64 = -1.98412698298579493134e-04;
    const S4: f64 = 2.75573137070700676789e-06;
    const S5: f64 = -2.50507602534068634195e-08;
    const S6: f64 = 1.58969099521155010221e-10;
    let ix = hi(x) & 0x7fffffff;
    if ix < 0x3e400000 && (x as i32) == 0 {
        return x;
    }
    let z = x * x;
    let v = z * x;
    let r = S2 + z * (S3 + z * (S4 + z * (S5 + z * S6)));
    if iy == 0 { x + v * (S1 + z * r) } else { x - ((z * (HALF * y - v * r) - y) - v * S1) }
}

fn kernel_cos(x: f64, y: f64) -> f64 {
    const ONE: f64 = 1.00000000000000000000e+00;
    const C1: f64 = 4.16666666666666019037e-02;
    const C2: f64 = -1.38888888888741095749e-03;
    const C3: f64 = 2.48015872894767294178e-05;
    const C4: f64 = -2.75573143513906633035e-07;
    const C5: f64 = 2.08757232129817482790e-09;
    const C6: f64 = -1.13596475577881948265e-11;
    let ix = hi(x) & 0x7fffffff;
    if ix < 0x3e400000 && (x as i32) == 0 {
        return ONE;
    }
    let z = x * x;
    let r = z * (C1 + z * (C2 + z * (C3 + z * (C4 + z * (C5 + z * C6)))));
    if ix < 0x3FD33333 {
        ONE - (0.5 * z - (z * r - x * y))
    } else {
        let qx = if ix > 0x3fe90000 { 0.28125 } else { with_lo(with_hi(0.0, ix - 0x00200000), 0) };
        let hz = 0.5 * z - qx;
        let a = ONE - qx;
        a - (hz - (z * r - x * y))
    }
}

const TWO_OVER_PI: [i32; 66] = [
    0xA2F983, 0x6E4E44, 0x1529FC, 0x2757D1, 0xF534DD, 0xC0DB62, 0x95993C, 0x439041, 0xFE5163, 0xABDEBB, 0xC561B7,
    0x246E3A, 0x424DD2, 0xE00649, 0x2EEA09, 0xD1921C, 0xFE1DEB, 0x1CB129, 0xA73EE8, 0x8235F5, 0x2EBB44, 0x84E99C,
    0x7026B4, 0x5F7E41, 0x3991D6, 0x398353, 0x39F49C, 0x845F8B, 0xBDF928, 0x3B1FF8, 0x97FFDE, 0x05980F, 0xEF2F11,
    0x8B5A0A, 0x6D1F6D, 0x367ECF, 0x27CB09, 0xB74F46, 0x3F669E, 0x5FEA2D, 0x7527BA, 0xC7EBE5, 0xF17B3D, 0x0739F7,
    0x8A5292, 0xEA6BFB, 0x5FB11F, 0x8D5D08, 0x560330, 0x46FC7B, 0x6BABF0, 0xCFBC20, 0x9AF436, 0x1DA9E3, 0x91615E,
    0xE61B08, 0x659985, 0x5F14A0, 0x68408D, 0xFFD880, 0x4D7327, 0x310606, 0x1556CA, 0x73A8C9, 0x60E27B, 0xC08C6B,
];

const NPIO2_HW: [i32; 32] = [
    0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C, 0x4025FDBB, 0x402921FB, 0x402C463A,
    0x402F6A7A, 0x4031475C, 0x4032D97C, 0x40346B9C, 0x4035FDBB, 0x40378FDB, 0x403921FB, 0x403AB41B, 0x403C463A,
    0x403DD85A, 0x403F6A7A, 0x40407E4C, 0x4041475C, 0x4042106C, 0x4042D97C, 0x4043A28C, 0x40446B9C, 0x404534AC,
    0x4045FDBB, 0x4046C6CB, 0x40478FDB, 0x404858EB, 0x404921FB,
];

/// 2^n точно (n в пределах нормальных чисел — так его зовёт kernel_rem_pio2).
fn scalbn(x: f64, n: i32) -> f64 {
    // fdlibm scalbn, тот же код: масштаб степенью двойки точен, пока нет переполнения.
    const TWO54: f64 = 1.80143985094819840000e+16;
    const TWOM54: f64 = 5.55111512312578270212e-17;
    const HUGE: f64 = 1.0e+300;
    const TINY: f64 = 1.0e-300;
    let mut x = x;
    let mut hx = hi(x);
    let lx = lo(x);
    let mut k = (hx & 0x7ff00000) >> 20;
    if k == 0 {
        if (lx | (hx & 0x7fffffff)) == 0 {
            return x;
        }
        x *= TWO54;
        hx = hi(x);
        k = ((hx & 0x7ff00000) >> 20) - 54;
        if n < -50000 {
            return TINY * x;
        }
    }
    if k == 0x7ff {
        return x + x;
    }
    k += n;
    if k > 0x7fe {
        return HUGE * HUGE.copysign(x);
    }
    if k > 0 {
        return with_hi(x, (hx & 0x800fffff_u32 as i32) | (k << 20));
    }
    if k <= -54 {
        if n > 50000 {
            return HUGE * HUGE.copysign(x);
        }
        return TINY * TINY.copysign(x);
    }
    k += 54;
    with_hi(x, (hx & 0x800fffff_u32 as i32) | (k << 20)) * TWOM54
}

fn kernel_rem_pio2(x: &[f64; 3], y: &mut [f64; 2], e0: i32, nx: i32, prec: i32) -> i32 {
    const INIT_JK: [i32; 4] = [2, 3, 4, 6];
    const PIO2: [f64; 8] = [
        1.57079625129699707031e+00,
        7.54978941586159635335e-08,
        5.39030252995776476554e-15,
        3.28200341580791294123e-22,
        1.27065575308067607349e-29,
        1.22933308981111328932e-36,
        2.73370053816464559624e-44,
        2.16741683877804819444e-51,
    ];
    const ZERO: f64 = 0.0;
    const ONE: f64 = 1.0;
    const TWO24: f64 = 1.67772160000000000000e+07;
    const TWON24: f64 = 5.96046447753906250000e-08;

    let mut iq = [0i32; 20];
    let mut f = [0.0f64; 20];
    let mut fq = [0.0f64; 20];
    let mut q = [0.0f64; 20];

    let jk = INIT_JK[prec as usize];
    let jp = jk;
    let jx = nx - 1;
    let mut jv = (e0 - 3) / 24;
    if jv < 0 {
        jv = 0;
    }
    let mut q0 = e0 - 24 * (jv + 1);

    let mut j = jv - jx;
    let m = jx + jk;
    for i in 0..=m {
        f[i as usize] = if j < 0 { ZERO } else { TWO_OVER_PI[j as usize] as f64 };
        j += 1;
    }
    for i in 0..=jk {
        let mut fw = 0.0;
        for j in 0..=jx {
            fw += x[j as usize] * f[(jx + i - j) as usize];
        }
        q[i as usize] = fw;
    }

    let mut jz = jk;
    let mut z;
    let mut n;
    let mut ih;
    loop {
        // distill q[] into iq[] reversingly
        let mut i = 0i32;
        let mut j = jz;
        z = q[jz as usize];
        while j > 0 {
            let fw = ((TWON24 * z) as i32) as f64;
            iq[i as usize] = (z - TWO24 * fw) as i32;
            z = q[(j - 1) as usize] + fw;
            i += 1;
            j -= 1;
        }
        z = scalbn(z, q0);
        z -= 8.0 * (z * 0.125).floor();
        n = z as i32;
        z -= n as f64;
        ih = 0;
        if q0 > 0 {
            let i = iq[(jz - 1) as usize] >> (24 - q0);
            n += i;
            iq[(jz - 1) as usize] -= i << (24 - q0);
            ih = iq[(jz - 1) as usize] >> (23 - q0);
        } else if q0 == 0 {
            ih = iq[(jz - 1) as usize] >> 23;
        } else if z >= 0.5 {
            ih = 2;
        }

        if ih > 0 {
            n += 1;
            let mut carry = 0;
            for i in 0..jz {
                let j = iq[i as usize];
                if carry == 0 {
                    if j != 0 {
                        carry = 1;
                        iq[i as usize] = 0x1000000 - j;
                    }
                } else {
                    iq[i as usize] = 0xffffff - j;
                }
            }
            if q0 > 0 {
                match q0 {
                    1 => iq[(jz - 1) as usize] &= 0x7fffff,
                    2 => iq[(jz - 1) as usize] &= 0x3fffff,
                    _ => {}
                }
            }
            if ih == 2 {
                z = ONE - z;
                if carry != 0 {
                    z -= scalbn(ONE, q0);
                }
            }
        }

        if z == ZERO {
            let mut j = 0;
            let mut i = jz - 1;
            while i >= jk {
                j |= iq[i as usize];
                i -= 1;
            }
            if j == 0 {
                let mut k = 1;
                while iq[(jk - k) as usize] == 0 {
                    k += 1;
                }
                for i in (jz + 1)..=(jz + k) {
                    f[(jx + i) as usize] = TWO_OVER_PI[(jv + i) as usize] as f64;
                    let mut fw = 0.0;
                    for j in 0..=jx {
                        fw += x[j as usize] * f[(jx + i - j) as usize];
                    }
                    q[i as usize] = fw;
                }
                jz += k;
                continue;
            }
        }
        break;
    }

    if z == 0.0 {
        jz -= 1;
        q0 -= 24;
        while iq[jz as usize] == 0 {
            jz -= 1;
            q0 -= 24;
        }
    } else {
        z = scalbn(z, -q0);
        if z >= TWO24 {
            let fw = ((TWON24 * z) as i32) as f64;
            iq[jz as usize] = (z - TWO24 * fw) as i32;
            jz += 1;
            q0 += 24;
            iq[jz as usize] = fw as i32;
        } else {
            iq[jz as usize] = z as i32;
        }
    }

    let mut fw = scalbn(ONE, q0);
    let mut i = jz;
    while i >= 0 {
        q[i as usize] = fw * (iq[i as usize] as f64);
        fw *= TWON24;
        i -= 1;
    }

    let mut i = jz;
    while i >= 0 {
        let mut fw = 0.0;
        let mut k = 0;
        while k <= jp && k <= jz - i {
            fw += PIO2[k as usize] * q[(i + k) as usize];
            k += 1;
        }
        fq[(jz - i) as usize] = fw;
        i -= 1;
    }

    // prec == 2 (двойная точность) — единственный путь, которым зовёт rem_pio2.
    debug_assert!(prec == 2);
    let mut fw = 0.0;
    let mut i = jz;
    while i >= 0 {
        fw += fq[i as usize];
        i -= 1;
    }
    y[0] = if ih == 0 { fw } else { -fw };
    fw = fq[0] - fw;
    for i in 1..=jz {
        fw += fq[i as usize];
    }
    y[1] = if ih == 0 { fw } else { -fw };
    n & 7
}

fn rem_pio2(x: f64, y: &mut [f64; 2]) -> i32 {
    const ZERO: f64 = 0.00000000000000000000e+00;
    const HALF: f64 = 5.00000000000000000000e-01;
    const TWO24: f64 = 1.67772160000000000000e+07;
    const INVPIO2: f64 = 6.36619772367581382433e-01;
    const PIO2_1: f64 = 1.57079632673412561417e+00;
    const PIO2_1T: f64 = 6.07710050650619224932e-11;
    const PIO2_2: f64 = 6.07710050630396597660e-11;
    const PIO2_2T: f64 = 2.02226624879595063154e-21;
    const PIO2_3: f64 = 2.02226624871116645580e-21;
    const PIO2_3T: f64 = 8.47842766036889956997e-32;

    let hx = hi(x);
    let ix = hx & 0x7fffffff;
    if ix <= 0x3fe921fb {
        y[0] = x;
        y[1] = 0.0;
        return 0;
    }
    if ix < 0x4002d97c {
        if hx > 0 {
            let mut z = x - PIO2_1;
            if ix != 0x3ff921fb {
                y[0] = z - PIO2_1T;
                y[1] = (z - y[0]) - PIO2_1T;
            } else {
                z -= PIO2_2;
                y[0] = z - PIO2_2T;
                y[1] = (z - y[0]) - PIO2_2T;
            }
            return 1;
        } else {
            let mut z = x + PIO2_1;
            if ix != 0x3ff921fb {
                y[0] = z + PIO2_1T;
                y[1] = (z - y[0]) + PIO2_1T;
            } else {
                z += PIO2_2;
                y[0] = z + PIO2_2T;
                y[1] = (z - y[0]) + PIO2_2T;
            }
            return -1;
        }
    }
    if ix <= 0x413921fb {
        let mut t = x.abs();
        let n = (t * INVPIO2 + HALF) as i32;
        let fnn = n as f64;
        let mut r = t - fnn * PIO2_1;
        let mut w = fnn * PIO2_1T;
        if n < 32 && ix != NPIO2_HW[(n - 1) as usize] {
            y[0] = r - w;
        } else {
            let j = ix >> 20;
            y[0] = r - w;
            let mut i = j - ((hi(y[0]) >> 20) & 0x7ff);
            if i > 16 {
                t = r;
                w = fnn * PIO2_2;
                r = t - w;
                w = fnn * PIO2_2T - ((t - r) - w);
                y[0] = r - w;
                i = j - ((hi(y[0]) >> 20) & 0x7ff);
                if i > 49 {
                    t = r;
                    w = fnn * PIO2_3;
                    r = t - w;
                    w = fnn * PIO2_3T - ((t - r) - w);
                    y[0] = r - w;
                }
            }
        }
        y[1] = (r - y[0]) - w;
        if hx < 0 {
            y[0] = -y[0];
            y[1] = -y[1];
            return -n;
        }
        return n;
    }
    if ix >= 0x7ff00000 {
        y[0] = x - x;
        y[1] = y[0];
        return 0;
    }
    let mut z = with_lo(0.0, lo(x));
    let e0 = (ix >> 20) - 1046;
    z = with_hi(z, ix - (e0 << 20));
    let mut tx = [0.0f64; 3];
    for t in tx.iter_mut().take(2) {
        *t = (z as i32) as f64;
        z = (z - *t) * TWO24;
    }
    tx[2] = z;
    let mut nx = 3;
    while tx[(nx - 1) as usize] == ZERO {
        nx -= 1;
    }
    let n = kernel_rem_pio2(&tx, y, e0, nx, 2);
    if hx < 0 {
        y[0] = -y[0];
        y[1] = -y[1];
        return -n;
    }
    n
}

/// `StrictMath.sin`.
pub fn strict_sin(x: f64) -> f64 {
    let ix = hi(x) & 0x7fffffff;
    if ix <= 0x3fe921fb {
        return kernel_sin(x, 0.0, 0);
    }
    if ix >= 0x7ff00000 {
        return x - x;
    }
    let mut y = [0.0f64; 2];
    let n = rem_pio2(x, &mut y);
    match n & 3 {
        0 => kernel_sin(y[0], y[1], 1),
        1 => kernel_cos(y[0], y[1]),
        2 => -kernel_sin(y[0], y[1], 1),
        _ => -kernel_cos(y[0], y[1]),
    }
}

/// `StrictMath.cos`.
pub fn strict_cos(x: f64) -> f64 {
    let ix = hi(x) & 0x7fffffff;
    if ix <= 0x3fe921fb {
        return kernel_cos(x, 0.0);
    }
    if ix >= 0x7ff00000 {
        return x - x;
    }
    let mut y = [0.0f64; 2];
    let n = rem_pio2(x, &mut y);
    match n & 3 {
        0 => kernel_cos(y[0], y[1]),
        1 => -kernel_sin(y[0], y[1], 1),
        2 => -kernel_cos(y[0], y[1]),
        _ => kernel_sin(y[0], y[1], 1),
    }
}

/// `StrictMath.acos`.
pub fn strict_acos(x: f64) -> f64 {
    const ONE: f64 = 1.00000000000000000000e+00;
    const PI: f64 = 3.14159265358979311600e+00;
    const PIO2_HI: f64 = 1.57079632679489655800e+00;
    const PIO2_LO: f64 = 6.12323399573676603587e-17;
    const PS0: f64 = 1.66666666666666657415e-01;
    const PS1: f64 = -3.25565818622400915405e-01;
    const PS2: f64 = 2.01212532134862925881e-01;
    const PS3: f64 = -4.00555345006794114027e-02;
    const PS4: f64 = 7.91534994289814532176e-04;
    const PS5: f64 = 3.47933107596021167570e-05;
    const QS1: f64 = -2.40339491173441421878e+00;
    const QS2: f64 = 2.02094576023350569471e+00;
    const QS3: f64 = -6.88283971605453293030e-01;
    const QS4: f64 = 7.70381505559019352791e-02;

    let hx = hi(x);
    let ix = hx & 0x7fffffff;
    if ix >= 0x3ff00000 {
        if ((ix - 0x3ff00000) | lo(x)) == 0 {
            if hx > 0 {
                return 0.0;
            }
            return PI + 2.0 * PIO2_LO;
        }
        return (x - x) / (x - x);
    }
    if ix < 0x3fe00000 {
        if ix <= 0x3c600000 {
            return PIO2_HI + PIO2_LO;
        }
        let z = x * x;
        let p = z * (PS0 + z * (PS1 + z * (PS2 + z * (PS3 + z * (PS4 + z * PS5)))));
        let q = ONE + z * (QS1 + z * (QS2 + z * (QS3 + z * QS4)));
        let r = p / q;
        PIO2_HI - (x - (PIO2_LO - x * r))
    } else if hx < 0 {
        let z = (ONE + x) * 0.5;
        let p = z * (PS0 + z * (PS1 + z * (PS2 + z * (PS3 + z * (PS4 + z * PS5)))));
        let q = ONE + z * (QS1 + z * (QS2 + z * (QS3 + z * QS4)));
        let s = z.sqrt();
        let r = p / q;
        let w = r * s - PIO2_LO;
        PI - 2.0 * (s + w)
    } else {
        let z = (ONE - x) * 0.5;
        let s = z.sqrt();
        let df = with_lo(s, 0);
        let c = (z - df * df) / (s + df);
        let p = z * (PS0 + z * (PS1 + z * (PS2 + z * (PS3 + z * (PS4 + z * PS5)))));
        let q = ONE + z * (QS1 + z * (QS2 + z * (QS3 + z * QS4)));
        let r = p / q;
        let w = r * s + c;
        2.0 * (df + w)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn min_max_follow_java() {
        assert_eq!(min(0.0, -0.0).to_bits(), (-0.0f64).to_bits());
        assert_eq!(min(-0.0, 0.0).to_bits(), (-0.0f64).to_bits());
        assert_eq!(max(-0.0, 0.0).to_bits(), 0.0f64.to_bits());
        assert_eq!(max(0.0, -0.0).to_bits(), 0.0f64.to_bits());
        assert!(min(f64::NAN, 1.0).is_nan());
        assert!(min(1.0, f64::NAN).is_nan());
        assert!(max(1.0, f64::NAN).is_nan());
        assert_eq!(min(1.0, 2.0), 1.0);
        assert_eq!(max(1.0, 2.0), 2.0);
    }

    #[test]
    fn float_min_max_follow_java() {
        assert_eq!(minf(0.0, -0.0).to_bits(), (-0.0f32).to_bits());
        assert_eq!(maxf(-0.0, 0.0).to_bits(), 0.0f32.to_bits());
        assert!(minf(f32::NAN, 1.0).is_nan());
        assert!(maxf(1.0, f32::NAN).is_nan());
        // f32::min из std здесь ответил бы 1.0 — именно этим он и не годится.
        assert_eq!(f32::NAN.min(1.0), 1.0);
    }

    #[test]
    fn coerce_follows_kotlin() {
        // NaN проходит насквозь: в Kotlin оба сравнения ложны.
        assert!(coerce_in(f64::NAN, 0.0, 1.0).is_nan());
        assert!(coerce_inf(f32::NAN, 0.0, 1.0).is_nan());
        assert_eq!(coerce_in(-2.0, -1.0, 1.0), -1.0);
        assert_eq!(coerce_in(2.0, -1.0, 1.0), 1.0);
        // -0.0 не меньше 0.0: нижняя граница его не заменяет, знак нуля сохраняется.
        assert_eq!(coerce_at_least(-0.0, 0.0).to_bits(), (-0.0f64).to_bits());
        assert_eq!(coerce_at_most(0.0, -0.0).to_bits(), 0.0f64.to_bits());
        assert_eq!(sqrtf(2.0), 2.0f32.sqrt());
    }

    #[test]
    fn known_strict_values() {
        // Значения StrictMath из JDK 21 (печать Double.toHexString).
        assert_eq!(strict_sin(0.5), 0.479425538604203);
        assert_eq!(strict_cos(0.5), 0.8775825618903728);
        assert_eq!(strict_acos(0.5), 1.0471975511965979);
        assert_eq!(strict_acos(-1.0), std::f64::consts::PI);
        assert_eq!(strict_sin(3.0), 0.1411200080598672);
        assert_eq!(strict_cos(1e6), 0.9367521275331447);
    }
}
