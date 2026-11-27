//! Сон организмов: функции BodyState из `solver/BodySleep.kt` (засыпание, пробуждение, касание будит, стоянка
//! граничных связей). Структура сна — `sleep.rs`.

use crate::params::{GROUND_WAKE_DRIFT_CELLS, SLEEP_DRIFT_CELLS, SLEEP_MUSCLE_EPS, SLEEP_TICKS, WAKE_DRIFT_CELLS};
use crate::state::BodyState;

impl BodyState {
    /// `prepareAwakeSpans`: диапазоны бодрствующих частиц актуальны.
    #[inline]
    pub fn prepare_awake_spans(&mut self) {
        self.sleep.rebuild_spans_if_needed(self.n, &self.organism_of, &self.isl.org_scope);
    }

    /// `resetSleep`: после полной разметки организмов все бодрствуют.
    pub fn reset_sleep(&mut self) {
        self.sleep.reset(self.n, &self.organism_of, self.organism_count);
    }

    /// `wakeByInput`: мышь и мышцы будят свои организмы до учёта импульса за тик.
    pub fn wake_by_input(&mut self) {
        if !self.sleep.on {
            return;
        }
        if self.drag_id >= 0 {
            self.sleep.request_wake(self.drag_id);
        }
        for m in 0..self.muscle_activation.len() {
            let a = self.muscle_activation[m];
            let prev = if m < self.muscle_activation_prev.len() { self.muscle_activation_prev[m] } else { a };
            if (self.muscle_target[m] - a).abs() <= SLEEP_MUSCLE_EPS && (a - prev).abs() <= SLEEP_MUSCLE_EPS {
                continue;
            }
            for k in 0..self.body.muscle_clusters[m].len() {
                let c = self.body.muscle_clusters[m][k];
                self.sleep.request_wake(c);
            }
        }
        self.apply_wake_requests();
    }

    /// `applyWakeRequests`: исполнить запросы пробуждения.
    pub fn apply_wake_requests(&mut self) {
        if !self.sleep.has_wake_requests() {
            return;
        }
        // Тень острова очередь мира не исполняет: спящих в острове нет, заявки (и пометку «занят») исполнит мир в первом
        // своём подшаге, до которого их никто не читает. Очищается только счётчик в копии сна тени.
        if self.isl.shadow {
            self.sleep.clear_wake_requests();
            return;
        }
        for k in 0..self.sleep.wake_request_count() {
            let o = self.organism_of[self.sleep.wake_request(k) as usize] as usize;
            self.sleep.mark_busy(o);
            if self.sleep.asleep[o] {
                self.wake_organism(o);
            }
        }
        self.sleep.clear_wake_requests();
    }

    /// `noteSleepContacts`: касание ушедшей с начала тика клетки будит спящего соседа; true — заказано пробуждение.
    pub fn note_sleep_contacts(&mut self) -> bool {
        if self.sleep.asleep_count == 0 || self.tick_jitter_x.len() != self.n {
            return false;
        }
        let ct = match self.contacts.as_ref() {
            Some(c) => c,
            None => return false,
        };
        let wake = WAKE_DRIFT_CELLS * self.link_scale;
        let wake2 = wake * wake;
        let ground = GROUND_WAKE_DRIFT_CELLS * self.link_scale;
        let ground2 = ground * ground;
        let mut any = false;
        for k in 0..ct.contact_count() {
            let i = ct.contact_i(k) as usize;
            let j = ct.contact_j(k) as usize;
            let si = self.sleep.asleep[self.organism_of[i] as usize];
            let sj = self.sleep.asleep[self.organism_of[j] as usize];
            if si == sj {
                continue;
            }
            let mover = if si { j } else { i };
            let sleeper = if si { i } else { j };
            if self.is_fixed(sleeper) {
                continue;
            }
            let dx = self.px[mover] - self.tick_jitter_x[mover];
            let dy = self.py[mover] - self.tick_jitter_y[mover];
            let limit2 =
                if self.cell_home_compliance.len() > sleeper && self.cell_home_compliance[sleeper] > 0.0 { ground2 } else { wake2 };
            if dx * dx + dy * dy > limit2 {
                // Пробуждение соседа — событие: тень острова откатывается и идёт с миром (island_run.rs).
                if self.isl.shadow {
                    self.isl.abort = true;
                    return false;
                }
                self.sleep.request_wake(sleeper as i32);
                any = true;
            }
        }
        any
    }

    /// `updateSleep`: конец тика — счёт покоя и засыпание бодрствующих организмов.
    pub fn update_sleep(&mut self) {
        if !self.sleep.on {
            return;
        }
        let drift = SLEEP_DRIFT_CELLS * self.link_scale;
        let drift2 = drift * drift;
        for o in 0..self.organism_count {
            if self.sleep.asleep[o] {
                continue;
            }
            if self.sleep.take_busy(o) || self.organism_size[o] == 0 {
                self.sleep.reset_calm(o);
                continue;
            }
            if self.sleep.calm_ticks[o] > 0 && (self.org_sleep_drift2.len() <= o || self.org_sleep_drift2[o] > drift2) {
                self.sleep.reset_calm(o);
                continue;
            }
            if self.sleep.count_calm(o) >= SLEEP_TICKS {
                self.sleep_organism(o);
            }
        }
    }

    fn sleep_organism(&mut self, o: usize) {
        let (first, last) = (self.sleep.first[o], self.sleep.last[o]);
        if first <= last {
            for i in first as usize..=last as usize {
                if self.organism_of[i] != o as i32 {
                    continue;
                }
                self.inv_mass[i] = 0.0;
                self.vx[i] = 0.0;
                self.vy[i] = 0.0;
                self.prev_x[i] = self.px[i];
                self.prev_y[i] = self.py[i];
                self.in_contact[i] = false;
                if let Some(cb) = self.constraint_blocks.as_mut() {
                    let b = cb.particle_block[i] as usize;
                    cb.awake[b] -= 1;
                }
            }
        }
        self.flow_vx[o] = 0.0;
        self.flow_vy[o] = 0.0;
        self.sleep.set_asleep(o, true);
        self.park_boundary_limits(o, true);
        if let Some(ct) = self.contacts.as_mut() {
            ct.invalidate_broadphase();
        }
    }

    fn wake_organism(&mut self, o: usize) {
        let (first, last) = (self.sleep.first[o], self.sleep.last[o]);
        let cancel = self.cancel_spin_on;
        if first <= last {
            for i in first as usize..=last as usize {
                if self.organism_of[i] != o as i32 {
                    continue;
                }
                self.inv_mass[i] = if self.cell_dead[i] { 0.0 } else { self.rest_inv_mass[i] };
                if self.tick_jitter_x.len() == self.n {
                    self.tick_jitter_x[i] = self.px[i];
                    self.tick_jitter_y[i] = self.py[i];
                }
                if cancel {
                    self.tick_momentum().admit_frozen(i);
                }
                if let Some(cb) = self.constraint_blocks.as_mut() {
                    let b = cb.particle_block[i] as usize;
                    cb.awake[b] += 1;
                }
            }
        }
        self.sleep.set_asleep(o, false);
        self.park_boundary_limits(o, false);
        if let Some(ct) = self.contacts.as_mut() {
            ct.invalidate_broadphase();
        }
    }

    /// `parkBoundaryLimits`: граничные связи спящего — вон из общего прохода предела длины и обратно.
    fn park_boundary_limits(&mut self, o: usize, park: bool) {
        let (first, last) = (self.sleep.first[o], self.sleep.last[o]);
        if first > last {
            return;
        }
        for i in first as usize..=last as usize {
            if self.organism_of[i] != o as i32 {
                continue;
            }
            for s in self.baked.vert_start[i]..self.baked.vert_start[i + 1] {
                let mut c = self.con_of_edge[self.baked.vert_edge[s as usize] as usize];
                while c >= 0 {
                    let cu = c as usize;
                    if park {
                        self.boundary_link_limits.remove(c);
                    } else if !self.con_dead[cu] && self.con_boundary[cu] {
                        self.boundary_link_limits.add(c);
                    }
                    c = self.con_next_for_edge[cu];
                }
            }
        }
    }

    /// `setSleepOn`: выключить сон — все просыпаются сразу.
    pub fn set_sleep_on(&mut self, value: bool) {
        if !value {
            for o in 0..self.organism_count {
                if self.sleep.asleep.len() > o && self.sleep.asleep[o] {
                    self.wake_organism(o);
                }
            }
        }
        self.sleep.on = value;
    }

    /// `sleepNow`: усыпить организм частицы [i] сейчас (между тиками; при выключенном сне — ничего).
    pub fn sleep_now(&mut self, i: usize) {
        if !self.sleep.on || self.cell_dead[i] {
            return;
        }
        let o = self.organism_of[i];
        if o < 0 || self.sleep.is_asleep(o) {
            return;
        }
        self.sleep_organism(o as usize);
    }
}
