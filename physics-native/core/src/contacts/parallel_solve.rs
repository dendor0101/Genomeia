//! Решение касаний по воркерам — `contacts/ParallelContactSolve.kt` на Rust.
//!
//! Касания Якоби: каждое считается от состояния на входе в подшаг и пишет только свои поля и черновик куска
//! (`solve_contact_pair`), поэтому куски списка идут по воркерам. Силы на частицу складываются уже не в порядке
//! «кто первым дорешал», а по корзинам частиц (1024 подряд): касания корзины перебираются в исходном порядке обхода
//! (вперёд или назад — `ct_backwards`), сумма на частицу та же, что у обхода в одном потоке. Потолок поправки —
//! по корзинам, урезание множителей — по кускам касаний, применение — по корзинам; кости получают силы своих клеток в
//! порядке первого касания (ранг касания), как список частиц с силами в одном потоке. Итог побитово тот же, что у
//! обхода в одном потоке, при любом числе воркеров (сверка — RustWorkersParityTest).

use crate::contacts::helpers::ContactSolveSink;
use crate::contacts::{BoundaryContacts, ContactCtx};
use crate::jmath::sqrtf;
use crate::solver::pool::{SharedMut, Workers};

const BUCKET_SHIFT: usize = 10;

/// Рабочие массивы параллельного решения (живут в касаниях между подшагами).
#[derive(Clone, Debug, Default)]
pub struct ParallelContactSolve {
    sinks: Vec<ContactSolveSink>,
    solve_from: Vec<usize>,
    chunks: usize,
    wi: Vec<f32>,
    wj: Vec<f32>,
    buckets: usize,
    stride: usize,
    counts: Vec<i32>,
    bucket_start: Vec<i32>,
    entries: Vec<i32>,
    capped: Vec<bool>,
    touched_bones: Vec<Vec<i64>>,
    touched_n: Vec<usize>,
    bone_order: Vec<i64>,
}

impl ParallelContactSolve {
    pub fn new(workers: usize) -> ParallelContactSolve {
        ParallelContactSolve {
            sinks: (0..16 * workers).map(|_| ContactSolveSink::default()).collect(),
            solve_from: vec![0; 16 * workers + 1],
            wi: vec![0.0; 4096],
            wj: vec![0.0; 4096],
            bucket_start: vec![0; 1],
            entries: vec![0; 4096],
            bone_order: vec![0; 64],
            ..Default::default()
        }
    }

    pub fn workers_fit(&self, workers: usize) -> bool {
        self.sinks.len() == 16 * workers
    }
}

impl BoundaryContacts {
    /// `ParallelContactSolve.run`: решение, слияние черновиков, силы по корзинам, потолок, применение, кости.
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn solve_contacts_parallel(
        &mut self,
        w: &Workers,
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
        let mut p = match self.parallel_solve.take() {
            Some(p) if p.workers_fit(w.count) => p,
            _ => Box::new(ParallelContactSolve::new(w.count)),
        };
        let backwards = self.ct_backwards;
        let rigid = !self.to_velocity && ctx.bone_of.is_some();
        let cn = self.c_n;
        if p.wi.len() < cn {
            let cap = cn.max(p.wi.len() * 2);
            p.wi.resize(cap, 0.0);
            p.wj.resize(cap, 0.0);
        }
        p.chunks = p.sinks.len().min((cn / 128).max(1));
        let chunks = p.chunks;
        for k in 0..=chunks {
            p.solve_from[k] = (cn as i64 * k as i64 / chunks as i64) as usize;
        }
        p.buckets = (self.n >> BUCKET_SHIFT) + 1;
        p.stride = p.buckets + 16;
        if p.counts.len() < chunks * p.stride {
            p.counts.resize(chunks * p.stride, 0);
        }

        // 1. Решение кусками: черновик куска, wi/wj касания, счёт записей по корзинам.
        {
            let ct = SharedMut(self as *mut BoundaryContacts);
            let pp = SharedMut(&mut *p as *mut ParallelContactSolve);
            let (pxp, pyp, vxp, vyp) = (SharedMut(px.as_mut_ptr()), SharedMut(py.as_mut_ptr()), SharedMut(vx.as_mut_ptr()), SharedMut(vy.as_mut_ptr()));
            let n = px.len();
            w.run(chunks, &|k, _| {
                // SAFETY: кусок k пишет только поля своих касаний, свой черновик, свои wi/wj и свою строку счёта;
                // положения и скорости только читает (решение — от состояния на входе в подшаг).
                unsafe {
                    let ct = ct.get();
                    let p = pp.get();
                    let px = std::slice::from_raw_parts_mut(pxp.at(0), n);
                    let py = std::slice::from_raw_parts_mut(pyp.at(0), n);
                    let vx = std::slice::from_raw_parts_mut(vxp.at(0), n);
                    let vy = std::slice::from_raw_parts_mut(vyp.at(0), n);
                    let sink = &mut *p.sinks.as_mut_ptr().add(k);
                    sink.reset();
                    let (from, to) = (p.solve_from[k], p.solve_from[k + 1]);
                    let wi = p.wi.as_mut_ptr();
                    let wj = p.wj.as_mut_ptr();
                    let mut solve = |c: usize| {
                        if ct.solve_contact_pair(c, px, py, vx, vy, inv_mass, h, sink, ctx) {
                            *wi.add(c) = sink.wi;
                            *wj.add(c) = sink.wj;
                        }
                    };
                    if backwards {
                        let mut c = to;
                        while c > from {
                            c -= 1;
                            solve(c);
                        }
                    } else {
                        for c in from..to {
                            solve(c);
                        }
                    }
                    count_chunk(ct, p, k);
                }
            });
        }
        if backwards {
            for k in (0..chunks).rev() {
                let s = std::mem::take(&mut p.sinks[k]);
                self.add_solved(&s);
                p.sinks[k] = s;
            }
        } else {
            for k in 0..chunks {
                let s = std::mem::take(&mut p.sinks[k]);
                self.add_solved(&s);
                p.sinks[k] = s;
            }
        }

        // 2. Списки касаний по корзинам частиц.
        let buckets = p.buckets;
        if p.bucket_start.len() < buckets + 1 {
            p.bucket_start.resize(buckets + 1, 0);
        }
        if p.capped.len() < buckets {
            p.capped.resize(buckets, false);
        }
        if p.touched_bones.len() < buckets {
            p.touched_bones.resize_with(buckets, || vec![0; 16]);
            p.touched_n.resize(buckets, 0);
        }
        let mut total = 0i32;
        for b in 0..buckets {
            p.bucket_start[b] = total;
            for q in 0..chunks {
                let slot = q * p.stride + b;
                let c = p.counts[slot];
                p.counts[slot] = total;
                total += c;
            }
        }
        p.bucket_start[buckets] = total;
        if p.entries.len() < total as usize {
            let cap = (total as usize).max(p.entries.len() * 2);
            p.entries.resize(cap, 0);
        }
        {
            let ct = SharedMut(self as *mut BoundaryContacts);
            let pp = SharedMut(&mut *p as *mut ParallelContactSolve);
            w.run(chunks, &|q, _| {
                // SAFETY: часть q пишет свои места списков (начала — её счёт по корзинам).
                unsafe { fill_part(ct.get(), pp.get(), q) }
            });
        }

        // 3. Силы на частицу по корзинам, в исходном порядке обхода; потолок корзины.
        {
            let ct = SharedMut(self as *mut BoundaryContacts);
            let pp = SharedMut(&mut *p as *mut ParallelContactSolve);
            w.run(buckets, &|b, _| {
                // SAFETY: корзина b пишет только силы своих частиц и свои списки костей.
                unsafe { accumulate_bucket(ct.get(), pp.get(), b, backwards, rigid, ctx) }
            });
        }
        let any_capped = p.capped[..buckets].iter().any(|&c| c);
        if any_capped {
            let ct = SharedMut(self as *mut BoundaryContacts);
            let pp = SharedMut(&mut *p as *mut ParallelContactSolve);
            w.run(chunks, &|k, _| {
                // SAFETY: кусок k урезает множители только своих касаний.
                unsafe {
                    let p = pp.get();
                    cap_contacts(ct.get(), p.solve_from[k], p.solve_from[k + 1]);
                }
            });
        }

        // 4. Применение по корзинам.
        {
            let ct = SharedMut(self as *mut BoundaryContacts);
            let pp = SharedMut(&mut *p as *mut ParallelContactSolve);
            let (pxp, pyp, vxp, vyp) = (SharedMut(px.as_mut_ptr()), SharedMut(py.as_mut_ptr()), SharedMut(vx.as_mut_ptr()), SharedMut(vy.as_mut_ptr()));
            let (prp, pnp) = (SharedMut(pressure.as_mut_ptr()), SharedMut(pressure_n.as_mut_ptr()));
            let (n, np) = (px.len(), pressure.len());
            w.run(buckets, &|b, _| {
                // SAFETY: корзина b пишет положения, скорости и давление только своих частиц.
                unsafe {
                    apply_bucket(
                        ct.get(),
                        pp.get(),
                        b,
                        rigid,
                        std::slice::from_raw_parts_mut(pxp.at(0), n),
                        std::slice::from_raw_parts_mut(pyp.at(0), n),
                        std::slice::from_raw_parts_mut(vxp.at(0), n),
                        std::slice::from_raw_parts_mut(vyp.at(0), n),
                        inv_mass,
                        std::slice::from_raw_parts_mut(prp.at(0), np),
                        std::slice::from_raw_parts_mut(pnp.at(0), np),
                        ctx,
                    )
                }
            });
        }
        if rigid {
            apply_rigid(self, &mut p, px, py, inv_mass, ctx);
        }
        self.parallel_solve = Some(p);
    }
}

/// `countChunk`: записей куска [k] по корзинам частиц (касание с поправкой — в корзины обоих концов).
fn count_chunk(ct: &BoundaryContacts, p: &mut ParallelContactSolve, k: usize) {
    let row = k * p.stride;
    p.counts[row..row + p.buckets].fill(0);
    for c in p.solve_from[k]..p.solve_from[k + 1] {
        if ct.c_ax[c] == 0.0 && ct.c_ay[c] == 0.0 {
            continue;
        }
        let bi = ct.c_i[c] as usize >> BUCKET_SHIFT;
        let bj = ct.c_j[c] as usize >> BUCKET_SHIFT;
        p.counts[row + bi] += 1;
        if bj != bi {
            p.counts[row + bj] += 1;
        }
    }
}

/// `fillPart`: касания части [q] — в списки корзин (их места — по счёту части).
unsafe fn fill_part(ct: &BoundaryContacts, p: &mut ParallelContactSolve, q: usize) {
    let row = q * p.stride;
    let counts = p.counts.as_mut_ptr();
    let entries = p.entries.as_mut_ptr();
    for c in p.solve_from[q]..p.solve_from[q + 1] {
        if ct.c_ax[c] == 0.0 && ct.c_ay[c] == 0.0 {
            continue;
        }
        let bi = ct.c_i[c] as usize >> BUCKET_SHIFT;
        let bj = ct.c_j[c] as usize >> BUCKET_SHIFT;
        unsafe {
            let at = counts.add(row + bi);
            *entries.add(*at as usize) = c as i32;
            *at += 1;
            if bj != bi {
                let at = counts.add(row + bj);
                *entries.add(*at as usize) = c as i32;
                *at += 1;
            }
        }
    }
}

/// `accumulateBucket`: силы частиц корзины [b] в исходном порядке обхода, затем потолок корзины.
unsafe fn accumulate_bucket(ct: &mut BoundaryContacts, p: &mut ParallelContactSolve, b: usize, backwards: bool, rigid: bool, ctx: &ContactCtx) {
    let from = p.bucket_start[b] as usize;
    let to = p.bucket_start[b + 1] as usize;
    p.capped[b] = false;
    p.touched_n[b] = 0;
    if from == to {
        return;
    }
    let cn = ct.c_n;
    let mut add = |c: usize, p: &mut ParallelContactSolve| {
        let i = ct.c_i[c] as usize;
        let j = ct.c_j[c] as usize;
        let ax = ct.c_ax[c];
        let ay = ct.c_ay[c];
        let rank = (if backwards { cn - 1 - c } else { c }) as i64 * 2;
        if i >> BUCKET_SHIFT == b {
            touch(ct, p, i, ax, ay, p.wi[c], rank, b, rigid, ctx);
        }
        if j >> BUCKET_SHIFT == b {
            touch(ct, p, j, -ax, -ay, p.wj[c], rank + 1, b, rigid, ctx);
        }
    };
    if backwards {
        let mut q = to;
        while q > from {
            q -= 1;
            let c = p.entries[q] as usize;
            add(c, p);
        }
    } else {
        for q in from..to {
            let c = p.entries[q] as usize;
            add(c, p);
        }
    }
    cap_bucket(ct, p, b);
}

#[allow(clippy::too_many_arguments)]
fn touch(ct: &mut BoundaryContacts, p: &mut ParallelContactSolve, k: usize, ax: f32, ay: f32, w: f32, rank: i64, b: usize, rigid: bool, ctx: &ContactCtx) {
    if ct.frc_stamp[k] != ct.pass {
        ct.frc_stamp[k] = ct.pass;
        ct.frc_x[k] = 0.0;
        ct.frc_y[k] = 0.0;
        ct.frc_w[k] = 0.0;
        if rigid && ct.bone_idx(k, ctx.bone_of) >= 0 {
            let n = p.touched_n[b];
            let list = &mut p.touched_bones[b];
            if n == list.len() {
                list.resize((n * 2).max(16), 0);
            }
            list[n] = (rank << 32) | k as i64;
            p.touched_n[b] = n + 1;
        }
    }
    ct.frc_x[k] += ax;
    ct.frc_y[k] += ay;
    if w > ct.frc_w[k] {
        ct.frc_w[k] = w;
    }
}

/// `capBucket`: доля поправки частиц корзины под потолком; силы — к нулю (их сложит применение).
fn cap_bucket(ct: &mut BoundaryContacts, p: &mut ParallelContactSolve, b: usize) {
    let cap = ct.contact_max_step * ct.mean_link;
    let pass = ct.pass;
    let mut any = false;
    let to = ct.n.min((b + 1) << BUCKET_SHIFT);
    for i in (b << BUCKET_SHIFT)..to {
        if ct.frc_stamp[i] != pass {
            continue;
        }
        let w = ct.frc_w[i];
        let mut s = 1.0f32;
        if w > 0.0 {
            let d2 = (ct.frc_x[i] * ct.frc_x[i] + ct.frc_y[i] * ct.frc_y[i]) * w * w;
            if d2 > cap * cap {
                s = cap / sqrtf(d2);
                any = true;
            }
        }
        ct.frc_s[i] = s;
        ct.frc_x[i] = 0.0;
        ct.frc_y[i] = 0.0;
    }
    p.capped[b] = any;
}

/// `capContacts`: множители касаний [from, to), чья поправка урезана потолком.
fn cap_contacts(ct: &mut BoundaryContacts, from: usize, to: usize) {
    for c in from..to {
        let ax = ct.c_ax[c];
        let ay = ct.c_ay[c];
        if ax == 0.0 && ay == 0.0 {
            continue;
        }
        let si = ct.frc_s[ct.c_i[c] as usize];
        let sj = ct.frc_s[ct.c_j[c] as usize];
        let s = if si < sj { si } else { sj };
        if s < 1.0 {
            ct.c_lam_tot[c] -= (1.0f32 - s) * (ax * ct.c_nx[c] + ay * ct.c_ny[c]);
            ct.c_lam[c] *= s;
        }
    }
}

/// `applyBucket`: урезанные силы и давление частиц корзины, затем их сдвиг (или скорость).
#[allow(clippy::too_many_arguments)]
fn apply_bucket(
    ct: &mut BoundaryContacts,
    p: &mut ParallelContactSolve,
    b: usize,
    rigid: bool,
    px: &mut [f32],
    py: &mut [f32],
    vx: &mut [f32],
    vy: &mut [f32],
    inv_mass: &[f32],
    pressure: &mut [f32],
    pressure_n: &mut [i32],
    ctx: &ContactCtx,
) {
    let from = p.bucket_start[b] as usize;
    let to = p.bucket_start[b + 1] as usize;
    if from == to {
        return;
    }
    for q in from..to {
        let c = p.entries[q] as usize;
        let i = ct.c_i[c] as usize;
        let j = ct.c_j[c] as usize;
        let ax = ct.c_ax[c];
        let ay = ct.c_ay[c];
        let si = ct.frc_s[i];
        let sj = ct.frc_s[j];
        let s = if si < sj { si } else { sj };
        if s <= 0.0 {
            continue;
        }
        let push = sqrtf(ax * ax + ay * ay) * s;
        if i >> BUCKET_SHIFT == b {
            ct.frc_x[i] += ax * s;
            ct.frc_y[i] += ay * s;
            pressure[i] += push * inv_mass[i];
            pressure_n[i] += 1;
        }
        if j >> BUCKET_SHIFT == b {
            ct.frc_x[j] -= ax * s;
            ct.frc_y[j] -= ay * s;
            pressure[j] += push * inv_mass[j];
            pressure_n[j] += 1;
        }
    }
    let pass = ct.pass;
    let to_velocity = ct.to_velocity;
    let vel_h = ct.vel_h;
    let end = ct.n.min((b + 1) << BUCKET_SHIFT);
    for i in (b << BUCKET_SHIFT)..end {
        if ct.frc_stamp[i] != pass {
            continue;
        }
        let ax = ct.frc_x[i];
        let ay = ct.frc_y[i];
        if ax == 0.0 && ay == 0.0 {
            continue;
        }
        if to_velocity {
            vx[i] += inv_mass[i] * ax / vel_h;
            vy[i] += inv_mass[i] * ay / vel_h;
        } else if !rigid || ct.bone_idx(i, ctx.bone_of) < 0 {
            px[i] += inv_mass[i] * ax;
            py[i] += inv_mass[i] * ay;
        }
    }
}

/// `applyRigid`: силы клеток костей — в общий кластер в порядке первого касания (как список частиц в одном потоке).
fn apply_rigid(ct: &mut BoundaryContacts, p: &mut ParallelContactSolve, px: &mut [f32], py: &mut [f32], inv_mass: &[f32], ctx: &ContactCtx) {
    let mut total = 0usize;
    for b in 0..p.buckets {
        total += p.touched_n[b];
    }
    if p.bone_order.len() < total {
        let cap = total.max(p.bone_order.len() * 2);
        p.bone_order.resize(cap, 0);
    }
    let mut at = 0usize;
    for b in 0..p.buckets {
        let n = p.touched_n[b];
        p.bone_order[at..at + n].copy_from_slice(&p.touched_bones[b][..n]);
        at += n;
    }
    p.bone_order[..total].sort_unstable();
    ct.rigid_batch.begin(ct.bone_m.len());
    for k in 0..total {
        let i = p.bone_order[k] as i32 as usize;
        let ax = ct.frc_x[i];
        let ay = ct.frc_y[i];
        if ax == 0.0 && ay == 0.0 {
            continue;
        }
        let b = ct.bone_idx(i, ctx.bone_of) as usize;
        ct.rigid_batch.add(b, px[i] - ct.bone_cx[b], py[i] - ct.bone_cy[b], ax, ay);
    }
    ct.rigid_batch.apply_positions(ctx.bone_ids, &ct.bone_m, &ct.bone_i, &mut ct.bone_cx, &mut ct.bone_cy, px, py, inv_mass);
}
