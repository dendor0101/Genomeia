//! Снимок мира — `topology/WorldSnapshot.kt` (writeCore/readCore, описание тела), `topology/Joints.kt`
//! (writeJoints/readJoints) и `PhysicsWorld.write/read`: байты те же, что у Kotlin (DataOutput, big-endian), поэтому
//! снимок Kotlin читается миром Rust и наоборот. Массивы float, которые Kotlin пишет `writeDoubles`, идут как double.

use std::sync::Arc;

use crate::contacts::RestPairs;
use crate::definition::{BodyDefinition, WorldScale};
use crate::snapshot::{In, Out};
use crate::state::BodyState;
use crate::world::PhysicsWorld;

pub const SNAPSHOT_MAGIC: i32 = 0x50485753; // "PHWS"
pub const SNAPSHOT_VERSION: i32 = 8;
pub const SNAPSHOT_OLDEST_READABLE: i32 = 3;

/// `writeDefinition`.
pub fn write_definition(out: &mut Out, d: &BodyDefinition) {
    out.floats(&d.x);
    out.floats(&d.y);
    out.floats(&d.radius);
    out.booleans(&d.is_bone);
    out.booleans(&d.is_muscle);
    out.ints(&d.link_a);
    out.ints(&d.link_b);
    out.ints(&d.tri_a);
    out.ints(&d.tri_b);
    out.ints(&d.tri_c);
    out.floats(&d.tri_rest_area2);
    for clusters in [&d.bone_clusters, &d.muscle_clusters] {
        out.int(clusters.len() as i32);
        for c in clusters.iter() {
            out.ints(c);
        }
    }
    out.float(d.scale);
    out.ints(&d.link_retire_start);
    out.ints(&d.link_retire_cells);
}

/// `readDefinition(version)`: временные связи роста — с версии 7.
pub fn read_definition(input: &mut In, version: i32) -> BodyDefinition {
    let x = input.floats(0);
    let y = input.floats(0);
    let radius = input.floats(0);
    let is_bone = input.booleans(0);
    let is_muscle = input.booleans(0);
    let link_a = input.ints(0);
    let link_b = input.ints(0);
    let tri_a = input.ints(0);
    let tri_b = input.ints(0);
    let tri_c = input.ints(0);
    let tri_rest_area2 = input.floats(0);
    let bc = input.int() as usize;
    let bone_clusters: Vec<Vec<i32>> = (0..bc).map(|_| input.ints(0)).collect();
    let mc = input.int() as usize;
    let muscle_clusters: Vec<Vec<i32>> = (0..mc).map(|_| input.ints(0)).collect();
    let scale = input.float();
    let (link_retire_start, link_retire_cells) = if version >= 7 { (input.ints(0), input.ints(0)) } else { (Vec::new(), Vec::new()) };
    BodyDefinition {
        x,
        y,
        radius,
        is_bone,
        is_muscle,
        link_a,
        link_b,
        tri_a,
        tri_b,
        tri_c,
        tri_rest_area2,
        bone_clusters,
        muscle_clusters,
        scale,
        link_retire_start,
        link_retire_cells,
    }
}

/// `readFloats/readDoubles(0).let { if (it.isEmpty()) it else it.copyOf(n) }`.
fn sized<T: Copy + Default>(mut v: Vec<T>, n: usize) -> Vec<T> {
    if !v.is_empty() {
        v.resize(n, T::default());
    }
    v
}

impl BodyState {
    /// `writeCore`: всё, что влияет на следующие тики, кроме описания тела и раскладки мира.
    pub fn write_core(&self, out: &mut Out) {
        assert!(!self.tears_pending && self.tear_queue_n == 0, "снимок — между тиками, после уплотнения");
        out.int(self.n as i32);
        out.int(self.organism_count as i32);
        for a in [
            &self.px,
            &self.py,
            &self.prev_x,
            &self.prev_y,
            &self.vx,
            &self.vy,
            &self.inv_mass,
            &self.rest_inv_mass,
            &self.match_weight,
            &self.radius,
            &self.sleep_anchor_x,
            &self.sleep_anchor_y,
            &self.tick_jitter_x,
            &self.tick_jitter_y,
            &self.contact_pressure,
        ] {
            out.floats_as_doubles(a);
        }
        for a in [&self.cell_dead, &self.cell_unborn, &self.in_contact, &self.crushed_cell] {
            out.booleans(a);
        }
        out.floats(&self.cell_scale);
        out.boolean(self.cell_scales_on);
        out.floats(&self.cell_scale_target);
        out.boolean(self.cell_scales_moving);
        out.floats_as_doubles(&self.drag_scale);
        out.floats(&self.cell_softness);
        out.boolean(self.softness_on);
        out.floats(&self.cell_fiber_x);
        out.floats(&self.cell_fiber_y);
        out.boolean(self.fibers_on);
        out.booleans(&self.cell_no_contact);
        out.ints(&self.cell_group);
        out.floats(&self.cell_grip);
        for a in [&self.cell_home_compliance, &self.cell_home_x, &self.cell_home_y] {
            out.floats_as_doubles(a);
        }
        out.boolean(self.bounds_on);
        for b in [self.bounds_min_x, self.bounds_min_y, self.bounds_max_x, self.bounds_max_y] {
            out.double(b as f64);
        }
        for a in [&self.trap_ticks, &self.trap_org, &self.fold_ticks, &self.contact_pressure_n, &self.bone_of, &self.muscle_of, &self.organism_of] {
            out.ints(a);
        }
        let oc = self.organism_count;
        out.ints(&self.organism_size[..oc]);
        for a in [&self.organism_mass, &self.flow_vx, &self.flow_vy] {
            out.floats_as_doubles(&a[..oc]);
        }
        out.floats_as_doubles(&self.org_sleep_drift2[..self.org_sleep_drift2.len().min(oc)]);
        out.int(self.rigid_bones.len() as i32);
        for b in &self.rigid_bones {
            out.ints(b);
        }
        out.ints(&self.bone_cluster_of);
        for b in 0..self.rigid_bones.len() {
            out.floats_as_doubles(&self.bone_rest_qx[b]);
            out.floats_as_doubles(&self.bone_rest_qy[b]);
        }
        out.boolean(self.bones_split);
        for a in [&self.muscle_activation, &self.muscle_activation_prev, &self.muscle_target, &self.muscle_scale_of] {
            out.floats_as_doubles(a);
        }
        out.int(self.lnk_count as i32);
        out.int(self.con_count as i32);
        out.int(self.tri_count as i32);
        out.int(self.grow_edge_n as i32);
        out.int(self.grow_tri_n as i32);
        let (lc, cc, tc) = (self.lnk_count, self.con_count, self.tri_count);
        out.ints(&self.lnk_a[..lc]);
        out.ints(&self.lnk_b[..lc]);
        out.ints(&self.lnk_edge[..lc]);
        out.ints(&self.con_a[..cc]);
        out.ints(&self.con_b[..cc]);
        out.floats_as_doubles(&self.con_rest[..cc]);
        out.ints(&self.con_muscle[..cc]);
        out.ints(&self.con_edge[..cc]);
        out.booleans(&self.con_dead[..cc]);
        out.booleans(&self.link_torn[..cc]);
        out.bytes(&self.con_hold[..self.con_hold.len().min(cc)]);
        out.floats_as_doubles(&self.con_grow_target[..self.con_grow_target.len().min(cc)]);
        out.bytes(&self.con_grow_age[..self.con_grow_age.len().min(cc)]);
        out.ints(&self.tri_a[..tc]);
        out.ints(&self.tri_b[..tc]);
        out.ints(&self.tri_c[..tc]);
        out.floats(&self.tri_rest_area2[..tc]);
        out.ints(&self.tri_id[..tc]);
        out.ints(&self.tri_muscle[..tc]);
        out.booleans(&self.tri_inverted[..tc]);
        out.bytes(&self.tri_hold[..self.tri_hold.len().min(tc)]);
        out.floats(&self.tri_grow_target);
        out.ints(&self.grow_edges[..self.grow_edge_n]);
        out.ints(&self.grow_tris[..self.grow_tri_n]);
        self.baked.write_state(out, &self.body);
        self.sleep.write(out, oc);
        let empty = RestPairs::empty();
        let rp: &RestPairs = match self.rest_pairs.as_ref() {
            Some(r) => r,
            None => &empty,
        };
        out.ints(&rp.start);
        out.ints(&rp.other);
        out.ints(&rp.mirror);
        out.floats_as_doubles(&rp.d2);
        out.floats_as_doubles(&rp.dist);
        out.booleans(&rp.ever);
        out.int(self.drag_id);
        out.double(self.mouse_x as f64);
        out.double(self.mouse_y as f64);
        out.double(self.drag_shift_x as f64);
        out.double(self.drag_shift_y as f64);
        out.int(self.torn_total);
        out.boolean(self.sweep_backwards);
        out.int(self.play_tick);
        for b in [
            self.contacts_on,
            self.probe_on,
            self.bones_rigid,
            self.tearing_on,
            self.areas_on,
            self.crush_burst_on,
            self.membrane_pass_tear,
            self.trap_kill_folds,
            self.cancel_spin_on,
        ] {
            out.boolean(b);
        }
        for d in [
            self.muscle_contraction,
            self.muscle_rate_contract,
            self.muscle_rate_relax,
            self.gait_duty,
            self.tendon_tear,
            self.link_tear_strain,
            self.tangent_drag,
            self.pressure_burst,
            self.membrane_tear_strain,
        ] {
            out.double(d as f64);
        }
        for i in [self.gait_period, self.muscle_touch, self.sweep_flip, self.trap_kill_ticks, self.bend_level] {
            out.int(i);
        }
        self.write_joints(out);
    }

    /// `readCore`: состояние поверх тела, загруженного тем же описанием; производное — заново (`rebuildDerived`).
    pub fn read_core(&mut self, input: &mut In, version: i32) {
        assert!(input.int() as usize == self.n, "снимок не от этого описания");
        let n = self.n;
        self.organism_count = input.int() as usize;
        self.px = input.doubles_as_floats(0);
        self.py = input.doubles_as_floats(0);
        self.prev_x = input.doubles_as_floats(0);
        self.prev_y = input.doubles_as_floats(0);
        self.vx = input.doubles_as_floats(0);
        self.vy = input.doubles_as_floats(0);
        self.inv_mass = input.doubles_as_floats(0);
        self.rest_inv_mass = input.doubles_as_floats(0);
        self.match_weight = input.doubles_as_floats(0);
        self.radius = input.doubles_as_floats(0);
        self.sleep_anchor_x = input.doubles_as_floats(0);
        self.sleep_anchor_y = input.doubles_as_floats(0);
        self.tick_jitter_x = input.doubles_as_floats(0);
        self.tick_jitter_y = input.doubles_as_floats(0);
        self.contact_pressure = input.doubles_as_floats(0);
        if (6..=7).contains(&version) {
            input.doubles_as_floats(0);
        }
        self.cell_dead = input.booleans(0);
        self.cell_unborn = input.booleans(0);
        self.in_contact = input.booleans(0);
        self.crushed_cell = input.booleans(0);
        self.cell_scale = input.floats(0);
        self.cell_scales_on = input.boolean();
        self.cell_scale_target = input.floats(0);
        self.cell_scales_moving = input.boolean();
        self.drag_scale = input.doubles_as_floats(0);
        self.cell_softness = input.floats(0);
        self.softness_on = input.boolean();
        if version >= 4 {
            self.cell_fiber_x = input.floats(0);
            self.cell_fiber_y = input.floats(0);
            self.fibers_on = input.boolean();
        }
        self.cell_no_contact = input.booleans(0);
        self.cell_group = input.ints(0);
        self.cell_grip = input.floats(0);
        self.grips_dirty = true;
        self.cell_home_compliance = input.doubles_as_floats(0);
        self.cell_home_x = input.doubles_as_floats(0);
        self.cell_home_y = input.doubles_as_floats(0);
        self.bounds_on = input.boolean();
        self.bounds_min_x = input.double() as f32;
        self.bounds_min_y = input.double() as f32;
        self.bounds_max_x = input.double() as f32;
        self.bounds_max_y = input.double() as f32;
        self.trap_ticks = input.ints(0);
        self.trap_org = input.ints(0);
        self.fold_ticks = input.ints(0);
        self.contact_pressure_n = input.ints(0);
        self.bone_of = input.ints(0);
        self.muscle_of = input.ints(0);
        self.organism_of = input.ints(0);
        self.organism_size = input.ints(n);
        self.organism_mass = input.doubles_as_floats(n);
        self.flow_vx = input.doubles_as_floats(n);
        self.flow_vy = input.doubles_as_floats(n);
        self.org_sleep_drift2 = sized(input.doubles_as_floats(0), n);
        let bone_count = input.int() as usize;
        if version >= 5 {
            self.rigid_bones = (0..bone_count).map(|_| input.ints(0)).collect();
            self.bone_cluster_of = input.ints(0);
            self.bone_rest_qx = vec![Vec::new(); bone_count];
            self.bone_rest_qy = vec![Vec::new(); bone_count];
        } else {
            assert!(bone_count == self.rigid_bones.len(), "кости снимка не от этого описания");
        }
        for b in 0..self.rigid_bones.len() {
            self.bone_rest_qx[b] = input.doubles_as_floats(0);
            self.bone_rest_qy[b] = input.doubles_as_floats(0);
        }
        if version >= 5 {
            self.bones_split = input.boolean();
        }
        self.bone_dirty_n = 0;
        self.bone_dirty_mark = Vec::new();
        self.muscle_activation = input.doubles_as_floats(0);
        self.muscle_activation_prev = input.doubles_as_floats(0);
        self.muscle_target = input.doubles_as_floats(0);
        self.muscle_scale_of = input.doubles_as_floats(0);
        let lc = self.body.link_count();
        let tc = self.body.tri_count();
        self.lnk_count = input.int() as usize;
        self.con_count = input.int() as usize;
        self.tri_count = input.int() as usize;
        self.grow_edge_n = input.int() as usize;
        self.grow_tri_n = input.int() as usize;
        self.lnk_a = input.ints(lc);
        self.lnk_b = input.ints(lc);
        self.lnk_edge = input.ints(lc);
        self.con_a = input.ints(lc);
        self.con_b = input.ints(lc);
        self.con_rest = input.doubles_as_floats(lc);
        self.con_muscle = input.ints(lc);
        self.con_edge = input.ints(lc);
        self.con_dead = input.booleans(lc);
        self.link_torn = input.booleans(lc);
        self.con_hold = sized(input.bytes(0), lc);
        self.con_grow_target = sized(input.doubles_as_floats(0), lc);
        self.con_grow_age = sized(input.bytes(0), lc);
        self.tri_a = input.ints(tc);
        self.tri_b = input.ints(tc);
        self.tri_c = input.ints(tc);
        self.tri_rest_area2 = input.floats(tc);
        self.tri_id = input.ints(tc);
        self.tri_muscle = input.ints(tc);
        self.tri_inverted = input.booleans(tc);
        self.tri_hold = sized(input.bytes(0), tc);
        self.tri_grow_target = input.floats(0);
        self.grow_edges = input.ints(16);
        self.grow_tris = input.ints(16);
        self.con_boundary = vec![false; lc];
        self.con_tendon = vec![false; lc];
        self.tears_pending = false;
        self.tear_queue_n = 0;
        let body = std::mem::take(&mut self.body);
        self.baked.read_state(input, &body);
        self.body = body;
        self.sleep.read(input, n);
        let rp = RestPairs {
            start: input.ints(0),
            other: input.ints(0),
            mirror: input.ints(0),
            d2: input.doubles_as_floats(0),
            dist: input.doubles_as_floats(0),
            ever: input.booleans(0),
        };
        if self.rest_pairs.is_some() {
            self.rest_pairs = Some(Arc::new(rp));
        }
        self.drag_id = input.int();
        self.mouse_x = input.double() as f32;
        self.mouse_y = input.double() as f32;
        self.drag_shift_x = input.double() as f32;
        self.drag_shift_y = input.double() as f32;
        self.torn_total = input.int();
        self.sweep_backwards = input.boolean();
        self.play_tick = input.int();
        self.contacts_on = input.boolean();
        self.probe_on = input.boolean();
        self.bones_rigid = input.boolean();
        self.tearing_on = input.boolean();
        self.areas_on = input.boolean();
        self.crush_burst_on = input.boolean();
        self.membrane_pass_tear = input.boolean();
        self.trap_kill_folds = input.boolean();
        self.cancel_spin_on = input.boolean();
        self.muscle_contraction = input.double() as f32;
        self.muscle_rate_contract = input.double() as f32;
        self.muscle_rate_relax = input.double() as f32;
        self.gait_duty = input.double() as f32;
        self.tendon_tear = input.double() as f32;
        self.link_tear_strain = input.double() as f32;
        self.tangent_drag = input.double() as f32;
        self.pressure_burst = input.double() as f32;
        self.membrane_tear_strain = input.double() as f32;
        self.gait_period = input.int();
        self.muscle_touch = input.int();
        self.sweep_flip = input.int();
        self.trap_kill_ticks = input.int();
        self.bend_level = input.int();
        self.read_joints(input);
        self.rebuild_derived(None);
    }

    /// `writeJoints`.
    pub fn write_joints(&self, out: &mut Out) {
        let s = self.joint_slots;
        out.int(s as i32);
        out.int(self.joint_live_count);
        out.ints(&self.joint_a[..s]);
        out.ints(&self.joint_b[..s]);
        out.floats_as_doubles(&self.joint_rest[..s]);
        out.floats_as_doubles(&self.joint_compliance[..s]);
        out.booleans(&self.joint_live[..s]);
        out.ints(&self.joint_free[..self.joint_free_n]);
        out.floats_as_doubles(&self.joint_wake_strain[..s]);
    }

    /// `readJoints`.
    pub fn read_joints(&mut self, input: &mut In) {
        self.joint_slots = input.int() as usize;
        self.joint_live_count = input.int();
        self.joint_a = input.ints(0);
        self.joint_b = input.ints(0);
        self.joint_rest = input.doubles_as_floats(0);
        self.joint_compliance = input.doubles_as_floats(0);
        self.joint_live = input.booleans(0);
        let mut free = input.ints(0);
        self.joint_free_n = free.len();
        let cap = self.joint_a.len().max(free.len());
        free.resize(cap, 0);
        self.joint_free = free;
        self.joint_wake_strain = input.doubles_as_floats(0);
    }
}

impl PhysicsWorld {
    /// `write`: полный снимок мира; сначала мир уплотняется (производное — каноническое, как у прочитанного).
    /// [material] — отпечаток материала Kotlin (`PhysicsConfig.snapshot`).
    pub fn write(&mut self, material: &str) -> Vec<u8> {
        assert!(self.state.n > 0, "пустой мир не пишется");
        self.compact();
        let mut out = Out::new();
        out.int(SNAPSHOT_MAGIC);
        out.int(SNAPSHOT_VERSION);
        out.utf(material);
        let scale = self.state.world_scale.expect("масштаб мира");
        out.float(scale.mean_link_length);
        out.double(scale.mean_cell_area as f64);
        write_definition(&mut out, &self.state.body);
        let ids = self.body_ids as usize;
        out.int(self.body_ids);
        out.ints(&self.body_first_of[..ids]);
        out.ints(&self.body_count_of[..ids]);
        out.ints(&self.body_muscle_first_of[..ids]);
        out.ints(&self.body_muscle_count_of[..ids]);
        out.bytes(&self.body_state_of[..ids]);
        out.int(self.free_block_n as i32);
        out.ints(&self.free_block_body[..self.free_block_n]);
        out.ints(&self.free_block_start[..self.free_block_n]);
        out.int(self.free_slots as i32);
        out.int(self.free_count);
        out.ints(&self.free_stack[..self.free_stack_n]);
        out.booleans(&self.free_live[..self.free_slots]);
        self.state.write_core(&mut out);
        out.buf
    }

    /// `read`: снимок в НОВЫЙ мир с тем же материалом.
    pub fn read(&mut self, data: &[u8], material: &str) {
        assert!(self.state.n == 0, "снимок читается в новый мир");
        let mut input = In::new(data);
        assert!(input.int() == SNAPSHOT_MAGIC, "это не снимок мира физики");
        let version = input.int();
        assert!(
            (SNAPSHOT_OLDEST_READABLE..=SNAPSHOT_VERSION).contains(&version),
            "снимок версии {version}, мир читает {SNAPSHOT_OLDEST_READABLE}..{SNAPSHOT_VERSION}"
        );
        assert!(input.utf() == material, "снимок записан с другим материалом мира");
        let link = input.float();
        let area = input.double() as f32;
        self.state.world_scale = Some(WorldScale { mean_link_length: link, mean_cell_area: area });
        let def = read_definition(&mut input, version);
        self.state.initialize(def);
        self.body_ids = input.int();
        self.body_first_of = input.ints(8);
        self.body_count_of = input.ints(8);
        self.body_muscle_first_of = input.ints(8);
        self.body_muscle_count_of = input.ints(8);
        self.body_state_of = input.bytes(8);
        self.removed_particles = 0;
        self.free_block_n = input.int() as usize;
        self.free_block_body = input.ints(4);
        self.free_block_start = input.ints(4);
        self.free_slots = input.int() as usize;
        self.free_count = input.int();
        let mut stack = input.ints(0);
        self.free_stack_n = stack.len();
        stack.resize(self.free_slots, 0);
        self.free_stack = stack;
        self.free_live = input.booleans(self.free_slots);
        self.state.read_core(&mut input, version);
    }
}
