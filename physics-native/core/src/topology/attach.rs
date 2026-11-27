//! Хватка и пружина к месту (`topology/Grip.kt`), временные связи между телами — липучка (`topology/Joints.kt`),
//! пул свободных частиц (`topology/FreeParticles.kt`). Снимок связей — в snapshot.rs.

use crate::jmath::sqrtf;
use crate::params::LINK_MAX_STRETCH;
use crate::state::BodyState;
use crate::topology::components::LocalComponents;

impl BodyState {
    // --- Grip.kt ---

    /// `applyGrip`: хватка (присоска-чашка) и пружина к своему месту — после снимка скоростей подшага.
    pub fn apply_grip(&mut self, h: f32) {
        if self.grips_dirty {
            self.count_grips();
        }
        if self.grip_n == 0 {
            return;
        }
        self.prepare_awake_spans();
        for k in 0..self.sleep.span_count {
            let (from, to) = (self.sleep.span_start[k] as usize, self.sleep.span_end[k] as usize);
            for i in from..to {
                if self.cell_grip[i] > 0.0 || self.cell_home_compliance[i] > 0.0 {
                    self.ground_one(i, h);
                }
            }
        }
    }

    fn ground_one(&mut self, i: usize, h: f32) {
        let w = self.inv_mass[i];
        if w == 0.0 {
            return;
        }
        let mut nvx = self.vx[i];
        let mut nvy = self.vy[i];
        let hc = self.cell_home_compliance[i];
        if hc > 0.0 {
            let f = w / (w + hc / (h * h));
            let dx = (self.cell_home_x[i] - self.px[i]) * f;
            let dy = (self.cell_home_y[i] - self.py[i]) * f;
            nvx += dx / h;
            nvy += dy / h;
        }
        let keep = 1.0f32 - self.cell_grip[i];
        nvx *= keep;
        nvy *= keep;
        self.vx[i] = nvx;
        self.vy[i] = nvy;
        self.px[i] = self.prev_x[i] + nvx * h;
        self.py[i] = self.prev_y[i] + nvy * h;
    }

    pub fn grip_count_now(&mut self) -> i32 {
        if self.grips_dirty {
            self.count_grips();
        }
        self.grip_n
    }

    fn count_grips(&mut self) {
        let mut c = 0;
        for i in 0..self.n {
            if self.cell_grip[i] > 0.0 || self.cell_home_compliance[i] > 0.0 {
                c += 1;
            }
        }
        self.grip_n = c;
        self.grips_dirty = false;
    }

    // --- Joints.kt ---

    /// `prepareJoints`: связи с подвижным концом — на этот тик; натянутая связь будит спящего соседа.
    pub fn prepare_joints(&mut self) {
        self.joint_active_n = 0;
        if self.joint_live_count == 0 {
            return;
        }
        if self.joint_active.len() < self.joint_slots {
            self.joint_active = vec![0; self.joint_a.len()];
        }
        for k in 0..self.joint_slots {
            if !self.joint_live[k] {
                continue;
            }
            let a = self.joint_a[k] as usize;
            let b = self.joint_b[k] as usize;
            if self.cell_dead[a] || self.cell_dead[b] {
                self.free_joint(k);
                continue;
            }
            if self.inv_mass[a] > 0.0 || self.inv_mass[b] > 0.0 {
                self.joint_active[self.joint_active_n] = k as i32;
                self.joint_active_n += 1;
            }
            let sa = self.sleep.is_asleep(self.organism_of[a]) && !self.is_fixed(a);
            let sb = self.sleep.is_asleep(self.organism_of[b]) && !self.is_fixed(b);
            if sa == sb {
                continue;
            }
            let ws = self.joint_wake_strain[k];
            if ws > 0.0 {
                let dx = self.px[a] - self.px[b];
                let dy = self.py[a] - self.py[b];
                if (sqrtf(dx * dx + dy * dy) / self.joint_rest[k] - 1.0f32).abs() <= ws {
                    continue;
                }
            }
            self.sleep.request_wake(if sa { a as i32 } else { b as i32 });
        }
    }

    /// `solveJoints`: липучка — после внутренних ограничений; перерастянутая рвётся.
    pub fn solve_joints(&mut self, h: f32) {
        if self.joint_active_n == 0 {
            return;
        }
        let inv = 1.0f32 / (h * h);
        let tear = LINK_MAX_STRETCH + self.link_tear_strain;
        for q in 0..self.joint_active_n {
            let k = self.joint_active[q] as usize;
            if !self.joint_live[k] {
                continue;
            }
            let i = self.joint_a[k] as usize;
            let j = self.joint_b[k] as usize;
            let wi = self.inv_mass[i];
            let wj = self.inv_mass[j];
            let w = wi + wj;
            if w == 0.0 {
                continue;
            }
            let mut dx = self.px[i] - self.px[j];
            let mut dy = self.py[i] - self.py[j];
            let len = sqrtf(dx * dx + dy * dy);
            if len > tear * self.joint_rest[k] {
                self.free_joint(k);
                continue;
            }
            if len < 1e-12f32 {
                continue;
            }
            dx /= len;
            dy /= len;
            let d_l = -(len - self.joint_rest[k]) / (w + self.joint_compliance[k] * inv);
            self.px[i] += dx * d_l * wi;
            self.py[i] += dy * d_l * wi;
            self.px[j] -= dx * d_l * wj;
            self.py[j] -= dy * d_l * wj;
        }
    }

    /// `noteJointsMomentum`: липучка объединяет группы учёта импульса.
    pub fn note_joints_momentum(&mut self) {
        if self.joint_active_n == 0 {
            return;
        }
        let mut tm = self.take_tick_momentum();
        for q in 0..self.joint_active_n {
            let k = self.joint_active[q] as usize;
            if self.joint_live[k] {
                tm.note_joint(self.joint_a[k] as usize, self.joint_b[k] as usize, &self.inv_mass, &self.organism_of);
            }
        }
        self.put_tick_momentum(tm);
    }

    pub fn alloc_joint(&mut self, a: i32, b: i32, rest: f32, compliance: f32, wake_strain: f32) -> usize {
        let k = if self.joint_free_n > 0 {
            self.joint_free_n -= 1;
            self.joint_free[self.joint_free_n] as usize
        } else {
            if self.joint_slots == self.joint_a.len() {
                let cap = 8usize.max(self.joint_slots * 2);
                self.joint_a.resize(cap, 0);
                self.joint_b.resize(cap, 0);
                self.joint_rest.resize(cap, 0.0);
                self.joint_compliance.resize(cap, 0.0);
                self.joint_live.resize(cap, false);
                self.joint_free.resize(cap, 0);
                self.joint_wake_strain.resize(cap, 0.0);
            }
            let k = self.joint_slots;
            self.joint_slots += 1;
            k
        };
        self.joint_a[k] = a;
        self.joint_b[k] = b;
        self.joint_rest[k] = rest;
        self.joint_compliance[k] = compliance;
        self.joint_wake_strain[k] = wake_strain;
        self.joint_live[k] = true;
        self.joint_live_count += 1;
        k
    }

    pub fn free_joint(&mut self, k: usize) {
        if !self.joint_live[k] {
            return;
        }
        self.joint_live[k] = false;
        self.joint_live_count -= 1;
        self.joint_free[self.joint_free_n] = k as i32;
        self.joint_free_n += 1;
    }

    pub fn drop_joints_of(&mut self, i: i32) {
        if self.joint_live_count == 0 {
            return;
        }
        for k in 0..self.joint_slots {
            if self.joint_live[k] && (self.joint_a[k] == i || self.joint_b[k] == i) {
                self.free_joint(k);
            }
        }
    }

    pub fn remap_joints(&mut self, p_new: &[i32]) {
        for k in 0..self.joint_slots {
            if !self.joint_live[k] {
                continue;
            }
            let a = p_new[self.joint_a[k] as usize];
            let b = p_new[self.joint_b[k] as usize];
            if a < 0 || b < 0 {
                self.free_joint(k);
                continue;
            }
            self.joint_a[k] = a;
            self.joint_b[k] = b;
        }
    }

    // --- FreeParticles.kt ---

    /// `activateFree`: свободная частица из пула оживает с радиусом [r], массой [mass] (NaN — по радиусу).
    #[allow(clippy::too_many_arguments)]
    pub fn activate_free(&mut self, i: usize, x: f32, y: f32, vx0: f32, vy0: f32, r: f32, mass: f32, drag: f32) {
        assert!(self.baked.vert_start[i] == self.baked.vert_start[i + 1], "слот {i} — не одиночная клетка");
        assert!(r > 0.0, "радиус свободной частицы должен быть больше нуля");
        self.radius[i] = r;
        let m = if !mass.is_nan() {
            mass
        } else {
            let mean_area = self.world_scale.expect("масштаб мира").mean_cell_area;
            if mean_area > 0.0 {
                (r * r) / mean_area
            } else {
                1.0
            }
        };
        self.rest_inv_mass[i] = if m > 1e-12f32 { 1.0f32 / m } else { 1.0 };
        self.drag_scale[i] = drag;
        self.activate_cell(i, x, y, vx0, vy0);
    }

    /// `deactivateFree`: свободная частица — обратно в пул.
    pub fn deactivate_free(&mut self, i: usize, refresh: bool) {
        assert!(!self.cell_unborn[i], "свободной частицы {i} нет");
        assert!(self.baked.vert_start[i] == self.baked.vert_start[i + 1], "слот {i} — не одиночная клетка");
        if self.drag_id == i as i32 {
            self.match_weight[i] = 1.0;
            self.drag_id = -1;
        }
        if self.cell_dead[i] {
            self.dead_count -= 1;
        } else {
            LocalComponents::remove_cell(i, &self.organism_of, &mut self.organism_size, &mut self.organism_mass, &self.rest_inv_mass);
        }
        self.cell_dead[i] = true;
        self.cell_unborn[i] = true;
        self.inv_mass[i] = 0.0;
        self.match_weight[i] = 0.0;
        self.vx[i] = 0.0;
        self.vy[i] = 0.0;
        self.prev_x[i] = self.px[i];
        self.prev_y[i] = self.py[i];
        self.in_contact[i] = false;
        self.drag_scale[i] = 1.0;
        if self.cell_grip.len() > i && self.cell_grip[i] != 0.0 {
            self.cell_grip[i] = 0.0;
            self.grips_dirty = true;
        }
        if self.cell_home_compliance.len() > i && self.cell_home_compliance[i] != 0.0 {
            self.cell_home_compliance[i] = 0.0;
            self.grips_dirty = true;
        }
        self.cell_no_contact[i] = false;
        if self.cell_group.len() > i {
            self.cell_group[i] = 0;
        }
        if self.crushed_cell.len() > i {
            self.crushed_cell[i] = false;
        }
        if self.trap_ticks.len() > i {
            self.trap_ticks[i] = 0;
        }
        if self.trap_org.len() > i {
            self.trap_org[i] = -1;
        }
        if self.fold_ticks.len() > i {
            self.fold_ticks[i] = 0;
        }
        if self.contact_pressure.len() > i {
            self.contact_pressure[i] = 0.0;
        }
        if self.contact_pressure_n.len() > i {
            self.contact_pressure_n[i] = 0;
        }
        self.baked.touch(i as i32);
        if refresh {
            self.refresh_surface();
        }
        self.structure_version = self.structure_version.wrapping_add(1);
    }
}
