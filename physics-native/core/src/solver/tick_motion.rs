//! Средняя скорость за тик для стадий раз в тик — `solver/TickMotion.kt`: снимок положений начала тика, дрожь скорости
//! на последнем подшаге, удаление клеток от места начала серии покоя (засыпание); начало и снятие учёта импульса.

use crate::params::DT;
use crate::state::BodyState;

impl BodyState {
    /// `beginTickMotion`: положения бодрствующих частиц в начале тика.
    pub fn begin_tick_motion(&mut self) {
        if self.tick_jitter_x.len() != self.n {
            self.tick_jitter_x = vec![0.0; self.n];
            self.tick_jitter_y = vec![0.0; self.n];
        }
        self.prepare_awake_spans();
        for k in 0..self.sleep.span_count {
            let from = self.sleep.span_start[k] as usize;
            let to = self.sleep.span_end[k] as usize;
            self.tick_jitter_x[from..to].copy_from_slice(&self.px[from..to]);
            self.tick_jitter_y[from..to].copy_from_slice(&self.py[from..to]);
        }
    }

    /// `removeTickJitter`: скорость — средняя за тик, дрожь — в tick_jitter; удаление от места покоя организма.
    pub fn remove_tick_jitter(&mut self) {
        if self.org_sleep_drift2.len() < self.n {
            self.org_sleep_drift2 = vec![0.0; self.n];
            self.sleep_anchor_x = vec![0.0; self.n];
            self.sleep_anchor_y = vec![0.0; self.n];
        }
        // Только организмы текущей области (острова): удаление чужой области уже посчитано или посчитается.
        if !self.sleep.org_scope_on {
            let oc = self.organism_count;
            self.org_sleep_drift2[..oc].fill(0.0);
        } else {
            for x in 0..self.scope_org_slots() {
                if let Some(o) = self.scope_org_at(x) {
                    self.org_sleep_drift2[o] = 0.0;
                }
            }
        }
        self.prepare_awake_spans();
        if let Some(w) = self.active_workers().filter(|w| w.count > 1) {
            self.span_parts_run(&w, crate::solver::parallel::SpanPass::RemoveJitter);
        } else {
            for k in 0..self.sleep.span_count {
                let (from, to) = (self.sleep.span_start[k] as usize, self.sleep.span_end[k] as usize);
                self.remove_jitter_range(from, to);
            }
        }
        if self.sleep.calm_ticks.is_empty() {
            return;
        }
        for k in 0..self.sleep.span_count {
            let (from, to) = (self.sleep.span_start[k] as usize, self.sleep.span_end[k] as usize);
            for i in from..to {
                if self.inv_mass[i] == 0.0 {
                    continue;
                }
                let x = self.px[i];
                let y = self.py[i];
                let o = self.organism_of[i] as usize;
                if self.sleep.calm_ticks[o] == 0 {
                    self.sleep_anchor_x[i] = x;
                    self.sleep_anchor_y[i] = y;
                    continue;
                }
                let dx = x - self.sleep_anchor_x[i];
                let dy = y - self.sleep_anchor_y[i];
                let d2 = dx * dx + dy * dy;
                if d2 > self.org_sleep_drift2[o] {
                    self.org_sleep_drift2[o] = d2;
                }
            }
        }
    }

    /// `removeJitterRange`: частицы [from, to) — скорость средняя за тик, дрожь отложена.
    pub fn remove_jitter_range(&mut self, from: usize, to: usize) {
        let inv = 1.0f32 / DT;
        let (px, py, vx, vy, im, jx, jy) =
            (&self.px, &self.py, &mut self.vx, &mut self.vy, &self.inv_mass, &mut self.tick_jitter_x, &mut self.tick_jitter_y);
        for i in from..to {
            if im[i] == 0.0 {
                jx[i] = 0.0;
                jy[i] = 0.0;
                continue;
            }
            let mx = (px[i] - jx[i]) * inv;
            let my = (py[i] - jy[i]) * inv;
            jx[i] = vx[i] - mx;
            jy[i] = vy[i] - my;
            vx[i] = mx;
            vy[i] = my;
        }
    }

    /// `restoreTickJitter`: после сопротивления дрожь возвращается в скорость.
    pub fn restore_tick_jitter(&mut self) {
        if let Some(w) = self.active_workers().filter(|w| w.count > 1) {
            self.span_parts_run(&w, crate::solver::parallel::SpanPass::RestoreJitter);
            return;
        }
        for k in 0..self.sleep.span_count {
            let (from, to) = (self.sleep.span_start[k] as usize, self.sleep.span_end[k] as usize);
            self.restore_jitter_range(from, to);
        }
    }

    pub fn restore_jitter_range(&mut self, from: usize, to: usize) {
        let (vx, vy, jx, jy) = (&mut self.vx, &mut self.vy, &self.tick_jitter_x, &self.tick_jitter_y);
        for i in from..to {
            vx[i] += jx[i];
            vy[i] += jy[i];
        }
    }

    /// `momentumBegin`: начало учёта импульса за тик (группы в одном потоке, импульс частиц).
    pub fn momentum_begin(&mut self) {
        if let Some(w) = self.active_workers().filter(|w| w.count > 1) {
            self.momentum_begin_parallel(&w);
            return;
        }
        let mut tm = self.take_tick_momentum();
        tm.begin(&self.px, &self.py, &self.vx, &self.vy, &self.inv_mass, &self.organism_of, Some(&self.sleep), Some(&self.cell_dead));
        self.put_tick_momentum(tm);
    }

    /// `momentumCancel`: снять импульс и момент, внесённые внутренними стадиями за тик.
    pub fn momentum_cancel(&mut self) {
        self.prepare_awake_spans();
        if let Some(w) = self.active_workers().filter(|w| w.count > 1) {
            self.momentum_cancel_parallel(&w);
            return;
        }
        self.ensure_pass_chunks();
        let mut tm = self.take_tick_momentum();
        let chunks = self.take_active_chunks();
        tm.cancel(&self.px, &self.py, &mut self.vx, &mut self.vy, &self.inv_mass, Some(&self.sleep), &chunks);
        self.put_active_chunks(chunks);
        self.put_tick_momentum(tm);
    }
}
