//! Склеенные поштучные проходы по скоростям — `solver/FusedPasses.kt`: восстановление скоростей со снимком для учёта
//! импульса, отскок от пола, изотропная среда раз в тик, учёт внешнего импульса, потолок скорости; лёгкий проход
//! подшагов без контактов и доделка частиц контактов.
//!
//! У каждого прохода есть ядро `*_with`, которому учёт импульса и счётчики потолка передаются явно: последовательный
//! путь отдаёт свои, воркер (solver/parallel.rs) — общий учёт импульса (частицы у кусков свои) и свой черновик
//! счётчиков (`acc` в Kotlin).

use crate::jmath::sqrtf;
use crate::params::{DT, GROUND_RESTITUTION, MAX_SPEED_CELLS_PER_TICK};
use crate::solver::tick_momentum::{momentum_external_at, TickMomentum};
use crate::state::BodyState;

/// `clampOne`: потолок скорости одной частицы.
#[inline(always)]
pub(crate) fn clamp_one(i: usize, max_v: f32, max_v2: f32, vx: &mut [f32], vy: &mut [f32], peak: &mut f32, hits: &mut i32) {
    let v2 = vx[i] * vx[i] + vy[i] * vy[i];
    if v2 > *peak {
        *peak = v2;
    }
    if v2 <= max_v2 {
        return;
    }
    let sc = max_v / sqrtf(v2);
    vx[i] *= sc;
    vy[i] *= sc;
    *hits += 1;
}

impl BodyState {
    /// Учёт импульса для склеенных проходов: заводится, если нужен (как геттер `tickMomentum` Kotlin).
    pub(crate) fn ensure_tick_momentum(&mut self) {
        if self.cancel_spin_on {
            self.tick_momentum();
        }
    }

    /// `velocityPassBefore`: восстановление скоростей со снимком для учёта импульса.
    pub fn velocity_pass_before(&mut self, h: f32) {
        self.prepare_awake_spans();
        self.ensure_tick_momentum();
        if let Some(w) = self.active_workers().filter(|w| w.count > 1) {
            self.velocity_before_parallel(&w, h);
            return;
        }
        for k in 0..self.sleep.span_count {
            let (from, to) = (self.sleep.span_start[k] as usize, self.sleep.span_end[k] as usize);
            self.velocity_before_range(from, to, h);
        }
    }

    /// `velocityBeforeRange`: восстановление скоростей частиц [from, to).
    pub fn velocity_before_range(&mut self, from: usize, to: usize, h: f32) {
        let mut tm = if self.cancel_spin_on { Some(self.take_tick_momentum()) } else { None };
        self.before_range_with(from, to, h, tm.as_mut());
        if let Some(t) = tm {
            self.put_tick_momentum(t);
        }
    }

    /// Ядро [velocity_before_range]; учёт импульса передан явно.
    pub(crate) fn before_range_with(&mut self, from: usize, to: usize, h: f32, mut tm: Option<&mut TickMomentum>) {
        let (px, py, vx, vy, prev_x, prev_y, inv) = (&self.px, &self.py, &mut self.vx, &mut self.vy, &self.prev_x, &self.prev_y, &self.inv_mass);
        let inv_h = 1.0f32 / h;
        for i in from..to {
            if inv[i] == 0.0 {
                vx[i] = 0.0;
                vy[i] = 0.0;
            } else {
                let nvx = (px[i] - prev_x[i]) * inv_h;
                let nvy = (py[i] - prev_y[i]) * inv_h;
                vx[i] = nvx;
                vy[i] = nvy;
            }
            if let Some(t) = tm.as_mut() {
                t.before_x[i] = vx[i];
                t.before_y[i] = vy[i];
            }
        }
    }

    /// `velocityPassAfter`: отскок от пола, среда (раз в тик), учёт внешнего импульса, потолок скорости.
    pub fn velocity_pass_after(&mut self, last_step: bool, h_tick: f32) {
        self.prepare_awake_spans();
        self.ensure_tick_momentum();
        if let Some(w) = self.active_workers().filter(|w| w.count > 1) {
            self.velocity_after_parallel(&w, last_step, h_tick);
            return;
        }
        for k in 0..self.sleep.span_count {
            let (from, to) = (self.sleep.span_start[k] as usize, self.sleep.span_end[k] as usize);
            self.velocity_after_range(from, to, last_step, h_tick);
        }
    }

    pub fn velocity_after_range(&mut self, from: usize, to: usize, last_step: bool, h_tick: f32) {
        let mut tm = if self.cancel_spin_on { Some(self.take_tick_momentum()) } else { None };
        let (mut peak, mut hits) = (self.peak_speed2, self.speed_cap_hits);
        self.after_range_with(from, to, last_step, h_tick, tm.as_mut(), &mut peak, &mut hits);
        self.peak_speed2 = peak;
        self.speed_cap_hits = hits;
        if let Some(t) = tm {
            self.put_tick_momentum(t);
        }
    }

    /// Ядро [velocity_after_range]; учёт импульса и счётчики потолка переданы явно.
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn after_range_with(
        &mut self,
        from: usize,
        to: usize,
        last_step: bool,
        h_tick: f32,
        mut tm: Option<&mut TickMomentum>,
        peak: &mut f32,
        hits: &mut i32,
    ) {
        let medium = last_step && !self.dbg_iso_drag_off;
        let mut keep = 1.0f32 - self.environment.medium_drag * h_tick;
        if keep < 0.0 {
            keep = 0.0;
        }
        let max_v = MAX_SPEED_CELLS_PER_TICK * self.link_scale / DT;
        let clamp = !self.dbg_no_clamp;
        let max_v2 = max_v * max_v;
        let medium_drag = self.environment.medium_drag;
        let (px, py, vx, vy, inv, in_contact, drag_scale) =
            (&self.px, &self.py, &mut self.vx, &mut self.vy, &self.inv_mass, &self.in_contact, &self.drag_scale);
        for i in from..to {
            if in_contact[i] && vy[i] > 0.0 {
                vy[i] *= GROUND_RESTITUTION;
            }
            if medium {
                let d = drag_scale[i];
                let k = if d == 1.0 { keep } else { medium_keep(medium_drag, h_tick, d) };
                vx[i] *= k;
                vy[i] *= k;
            }
            if let Some(t) = tm.as_mut() {
                momentum_external_at(i, px, py, vx, vy, inv, in_contact[i], &t.before_x, &t.before_y, &mut t.target_x, &mut t.target_y, &mut t.target_l, &mut t.blocked);
            }
            if clamp {
                clamp_one(i, max_v, max_v2, vx, vy, peak, hits);
            }
        }
    }

    /// `velocityFastAllowed`: лёгкий проход — без мыши, хватки и гравитации мира.
    pub fn velocity_fast_allowed(&mut self) -> bool {
        self.drag_id < 0 && self.grip_count_now() == 0 && self.environment.gravity_y == 0.0
    }

    /// `markVelocityTouched`: частицы, чью скорость изменит отскок этого подшага, — контакты и их кости целиком.
    pub fn mark_velocity_touched(&mut self) {
        if self.vel_touch_stamp.len() < self.n {
            self.vel_touch_stamp = vec![0; self.n];
            self.vel_touch_list = vec![0; self.n];
        }
        if self.vel_bone_stamp.len() < self.rigid_bones.len() {
            self.vel_bone_stamp = vec![0; self.rigid_bones.len()];
        }
        self.vel_touch_pass = self.vel_touch_pass.wrapping_add(1);
        self.vel_touch_n = 0;
        if !self.contacts_on || self.contacts_stale {
            return;
        }
        let ct = match self.contacts.take() {
            Some(ct) => ct,
            None => return,
        };
        for k in 0..ct.contact_count() {
            self.mark_touched(ct.contact_i(k) as usize);
            self.mark_touched(ct.contact_j(k) as usize);
        }
        self.contacts = Some(ct);
    }

    fn mark_touched(&mut self, i: usize) {
        let pass = self.vel_touch_pass;
        if self.vel_touch_stamp[i] != pass {
            self.vel_touch_stamp[i] = pass;
            self.vel_touch_list[self.vel_touch_n] = i as i32;
            self.vel_touch_n += 1;
        }
        if !self.bones_rigid {
            return;
        }
        let b = self.bone_of[i];
        if b < 0 || self.vel_bone_stamp[b as usize] == pass {
            return;
        }
        self.vel_bone_stamp[b as usize] = pass;
        for q in 0..self.rigid_bones[b as usize].len() {
            let k = self.rigid_bones[b as usize][q] as usize;
            if self.vel_touch_stamp[k] != pass {
                self.vel_touch_stamp[k] = pass;
                self.vel_touch_list[self.vel_touch_n] = k as i32;
                self.vel_touch_n += 1;
            }
        }
    }

    /// `velocityPassFast`: лёгкий проход подшага без среды.
    pub fn velocity_pass_fast(&mut self, h: f32) {
        self.prepare_awake_spans();
        self.ensure_tick_momentum();
        if let Some(w) = self.active_workers().filter(|w| w.count > 1) {
            self.velocity_fast_parallel(&w, h);
            return;
        }
        for k in 0..self.sleep.span_count {
            let (from, to) = (self.sleep.span_start[k] as usize, self.sleep.span_end[k] as usize);
            self.velocity_fast_range(from, to, h);
        }
    }

    pub fn velocity_fast_range(&mut self, from: usize, to: usize, h: f32) {
        let mut tm = if self.cancel_spin_on { Some(self.take_tick_momentum()) } else { None };
        let (mut peak, mut hits) = (self.peak_speed2, self.speed_cap_hits);
        self.fast_range_with(from, to, h, tm.as_mut(), &mut peak, &mut hits);
        self.peak_speed2 = peak;
        self.speed_cap_hits = hits;
        if let Some(t) = tm {
            self.put_tick_momentum(t);
        }
    }

    /// Ядро [velocity_fast_range]; учёт импульса и счётчики потолка переданы явно.
    pub(crate) fn fast_range_with(&mut self, from: usize, to: usize, h: f32, mut tm: Option<&mut TickMomentum>, peak: &mut f32, hits: &mut i32) {
        let max_v = MAX_SPEED_CELLS_PER_TICK * self.link_scale / DT;
        let clamp = !self.dbg_no_clamp;
        let max_v2 = max_v * max_v;
        let pass = self.vel_touch_pass;
        let (px, py, vx, vy, prev_x, prev_y, inv, in_contact, stamp) = (
            &self.px,
            &self.py,
            &mut self.vx,
            &mut self.vy,
            &self.prev_x,
            &self.prev_y,
            &self.inv_mass,
            &self.in_contact,
            &self.vel_touch_stamp,
        );
        let inv_h = 1.0f32 / h;
        for i in from..to {
            if inv[i] == 0.0 {
                vx[i] = 0.0;
                vy[i] = 0.0;
            } else {
                vx[i] = (px[i] - prev_x[i]) * inv_h;
                vy[i] = (py[i] - prev_y[i]) * inv_h;
            }
            if stamp[i] == pass {
                if let Some(t) = tm.as_mut() {
                    t.before_x[i] = vx[i];
                    t.before_y[i] = vy[i];
                }
                continue;
            }
            if in_contact[i] {
                if let Some(t) = tm.as_mut() {
                    t.before_x[i] = vx[i];
                    t.before_y[i] = vy[i];
                }
                if vy[i] > 0.0 {
                    vy[i] *= GROUND_RESTITUTION;
                }
                if let Some(t) = tm.as_mut() {
                    momentum_external_at(i, px, py, vx, vy, inv, in_contact[i], &t.before_x, &t.before_y, &mut t.target_x, &mut t.target_y, &mut t.target_l, &mut t.blocked);
                }
            }
            if clamp {
                clamp_one(i, max_v, max_v2, vx, vy, peak, hits);
            }
        }
    }

    /// `finishVelocityTouched`: частицы контактов после отскока — как velocityAfterRange без среды.
    pub fn finish_velocity_touched(&mut self) {
        if self.cancel_spin_on {
            self.tick_momentum();
        }
        if self.vel_touch_n >= 1024 {
            if let Some(w) = self.active_workers().filter(|w| w.count > 1) {
                self.finish_touched_parallel(&w);
                return;
            }
        }
        let mut tm = if self.cancel_spin_on { Some(self.take_tick_momentum()) } else { None };
        let (mut peak, mut hits) = (self.peak_speed2, self.speed_cap_hits);
        let n = self.vel_touch_n;
        self.finish_touched_with(0, n, tm.as_mut(), &mut peak, &mut hits);
        self.peak_speed2 = peak;
        self.speed_cap_hits = hits;
        if let Some(t) = tm {
            self.put_tick_momentum(t);
        }
    }

    /// `finishVelocityTouchedRange`: записи [from, to) списка частиц контактов; всё передано явно.
    pub(crate) fn finish_touched_with(&mut self, from: usize, to: usize, mut tm: Option<&mut TickMomentum>, peak: &mut f32, hits: &mut i32) {
        let max_v = MAX_SPEED_CELLS_PER_TICK * self.link_scale / DT;
        let clamp = !self.dbg_no_clamp;
        let max_v2 = max_v * max_v;
        let (px, py, vx, vy, inv, in_contact, list) =
            (&self.px, &self.py, &mut self.vx, &mut self.vy, &self.inv_mass, &self.in_contact, &self.vel_touch_list);
        for k in from..to {
            let i = list[k] as usize;
            if in_contact[i] && vy[i] > 0.0 {
                vy[i] *= GROUND_RESTITUTION;
            }
            if let Some(t) = tm.as_mut() {
                momentum_external_at(i, px, py, vx, vy, inv, in_contact[i], &t.before_x, &t.before_y, &mut t.target_x, &mut t.target_y, &mut t.target_l, &mut t.blocked);
            }
            if clamp {
                clamp_one(i, max_v, max_v2, vx, vy, peak, hits);
            }
        }
    }

    /// `noteContactsMomentum`: слияние групп импульса по касаниям подшага (в их порядке).
    pub fn note_contacts_momentum(&mut self) {
        let ct = match self.contacts.take() {
            Some(ct) => ct,
            None => return,
        };
        let mut tm = self.take_tick_momentum();
        match self.active_workers() {
            // Много касаний — ключи пар организмов по воркерам (solver/parallel.rs, `ParallelPhysics.noteContacts`).
            Some(w) if ct.contact_count() >= NOTE_PARALLEL_MIN => self.note_contacts_parallel(&w, &ct, &mut tm),
            _ => {
                for k in 0..ct.contact_count() {
                    tm.note_contact(ct.contact_i(k) as usize, ct.contact_j(k) as usize, &self.inv_mass, &self.organism_of);
                }
            }
        }
        self.put_tick_momentum(tm);
        self.contacts = Some(ct);
    }
}

/// Касаний подшага, с которых слияние групп импульса идёт по воркерам (`noteContactsMomentum`: 1024).
const NOTE_PARALLEL_MIN: usize = 1024;

/// `mediumKeep`: доля скорости, что остаётся за тик у частицы со своим множителем сопротивления [d].
#[inline(always)]
fn medium_keep(medium_drag: f32, h_tick: f32, d: f32) -> f32 {
    let k = 1.0f32 - medium_drag * h_tick * d;
    if k < 0.0 {
        0.0
    } else {
        k
    }
}
