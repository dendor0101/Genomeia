//! Отпечаток состояния для побитовой сверки с Kotlin — тот же формат, что `rust/RustDigest.kt`: число разделов, затем
//! разделы «имя (writeUTF), длина, байты DataOutput». Состав и порядок разделов — ровно как там; поменял один —
//! поменяй другой (тень сразу покажет расхождение списка разделов).

use crate::contacts::BoundaryContacts;
use crate::snapshot::Out;
use crate::state::BodyState;

struct Digest {
    body: Out,
    sections: i32,
}

impl Digest {
    fn sec(&mut self, name: &str, f: impl FnOnce(&mut Out)) {
        let mut o = Out::new();
        f(&mut o);
        self.sections += 1;
        self.body.utf(name);
        self.body.int(o.buf.len() as i32);
        self.body.buf.extend_from_slice(&o.buf);
    }
}

#[inline]
fn take<T>(a: &[T], n: usize) -> &[T] {
    &a[..n.min(a.len())]
}

/// Отпечаток мира: байты в формате `RustDigest.of` Kotlin.
pub fn digest(st: &BodyState) -> Vec<u8> {
    let mut d = Digest { body: Out::new(), sections: 0 };
    let n = st.n;
    let oc = st.organism_count;
    d.sec("counts", |o| {
        o.int(n as i32);
        o.int(oc as i32);
        o.int(st.lnk_count as i32);
        o.int(st.con_count as i32);
        o.int(st.tri_count as i32);
        o.int(st.grow_edge_n as i32);
        o.int(st.grow_tri_n as i32);
        o.int(st.dead_count);
        o.int(st.torn_total);
        o.int(st.structure_version);
    });
    d.sec("px", |o| o.floats_as_doubles(take(&st.px, n)));
    d.sec("py", |o| o.floats_as_doubles(take(&st.py, n)));
    d.sec("prevX", |o| o.floats_as_doubles(take(&st.prev_x, n)));
    d.sec("prevY", |o| o.floats_as_doubles(take(&st.prev_y, n)));
    d.sec("vx", |o| o.floats_as_doubles(take(&st.vx, n)));
    d.sec("vy", |o| o.floats_as_doubles(take(&st.vy, n)));
    d.sec("invMass", |o| o.floats_as_doubles(take(&st.inv_mass, n)));
    d.sec("restInvMass", |o| o.floats_as_doubles(take(&st.rest_inv_mass, n)));
    d.sec("matchWeight", |o| o.floats_as_doubles(take(&st.match_weight, n)));
    d.sec("radius", |o| o.floats_as_doubles(take(&st.radius, n)));
    d.sec("cellDead", |o| o.booleans(take(&st.cell_dead, n)));
    d.sec("cellUnborn", |o| o.booleans(take(&st.cell_unborn, n)));
    d.sec("inContact", |o| o.booleans(take(&st.in_contact, n)));
    d.sec("crushedCell", |o| o.booleans(&st.crushed_cell));
    d.sec("contactPressure", |o| o.floats_as_doubles(&st.contact_pressure));
    d.sec("contactPressureN", |o| o.ints(&st.contact_pressure_n));
    d.sec("sleepAnchorX", |o| o.floats_as_doubles(&st.sleep_anchor_x));
    d.sec("sleepAnchorY", |o| o.floats_as_doubles(&st.sleep_anchor_y));
    d.sec("orgSleepDrift2", |o| o.floats_as_doubles(&st.org_sleep_drift2));
    d.sec("tickJitterX", |o| o.floats_as_doubles(&st.tick_jitter_x));
    d.sec("tickJitterY", |o| o.floats_as_doubles(&st.tick_jitter_y));
    d.sec("cellScale", |o| {
        o.floats(take(&st.cell_scale, n));
        o.floats(take(&st.cell_scale_target, n));
        o.floats(&st.cell_scale_eff);
        o.ints(&st.cell_scale_src);
        o.boolean(st.cell_scales_on);
        o.boolean(st.cell_scales_moving);
        o.boolean(st.cell_scales_dirty);
    });
    d.sec("cellMaterial", |o| {
        o.floats_as_doubles(take(&st.drag_scale, n));
        o.floats(take(&st.cell_softness, n));
        o.boolean(st.softness_on);
        o.booleans(take(&st.cell_no_contact, n));
        o.ints(take(&st.cell_group, n));
        o.floats(take(&st.cell_grip, n));
        o.floats_as_doubles(take(&st.cell_home_compliance, n));
        o.floats_as_doubles(take(&st.cell_home_x, n));
        o.floats_as_doubles(take(&st.cell_home_y, n));
        o.boolean(st.bounds_on);
        o.float(st.bounds_min_x);
        o.float(st.bounds_min_y);
        o.float(st.bounds_max_x);
        o.float(st.bounds_max_y);
    });
    d.sec("fibers", |o| {
        o.floats(take(&st.cell_fiber_x, n));
        o.floats(take(&st.cell_fiber_y, n));
        o.boolean(st.fibers_on);
        o.floats(take(&st.con_fiber_c2, st.con_count));
        o.booleans(&st.tri_fiber_by_id);
    });
    d.sec("boneOf", |o| o.ints(take(&st.bone_of, n)));
    d.sec("boneClusterOf", |o| o.ints(take(&st.bone_cluster_of, n)));
    d.sec("muscleOf", |o| o.ints(take(&st.muscle_of, n)));
    d.sec("organismOf", |o| o.ints(take(&st.organism_of, n)));
    d.sec("organisms", |o| {
        o.ints(take(&st.organism_size, oc));
        o.floats_as_doubles(take(&st.organism_mass, oc));
        o.floats_as_doubles(take(&st.flow_vx, oc));
        o.floats_as_doubles(take(&st.flow_vy, oc));
    });
    d.sec("bones", |o| {
        o.int(st.rigid_bones.len() as i32);
        for b in 0..st.rigid_bones.len() {
            o.ints(&st.rigid_bones[b]);
            o.floats_as_doubles(&st.bone_rest_qx[b]);
            o.floats_as_doubles(&st.bone_rest_qy[b]);
        }
        o.boolean(st.bones_split);
        o.int(st.bone_dirty_n as i32);
    });
    d.sec("muscles", |o| {
        o.floats_as_doubles(&st.muscle_activation);
        o.floats_as_doubles(&st.muscle_activation_prev);
        o.floats_as_doubles(&st.muscle_target);
        o.floats_as_doubles(&st.muscle_scale_of);
    });
    d.sec("links", |o| {
        o.ints(take(&st.lnk_a, st.lnk_count));
        o.ints(take(&st.lnk_b, st.lnk_count));
        o.ints(take(&st.lnk_edge, st.lnk_count));
    });
    d.sec("constraints", |o| {
        let cc = st.con_count;
        o.ints(take(&st.con_a, cc));
        o.ints(take(&st.con_b, cc));
        o.floats_as_doubles(take(&st.con_rest, cc));
        o.ints(take(&st.con_muscle, cc));
        o.ints(take(&st.con_edge, cc));
        o.booleans(take(&st.con_dead, cc));
        o.booleans(take(&st.link_torn, cc));
        o.booleans(take(&st.con_boundary, cc));
        o.booleans(take(&st.con_tendon, cc));
        o.floats(take(&st.con_cell_scale, cc));
        o.floats(take(&st.con_softness, cc));
        o.bytes(take(&st.con_hold, cc));
        o.floats_as_doubles(take(&st.con_grow_target, cc));
        o.bytes(take(&st.con_grow_age, cc));
        o.ints(&st.con_of_edge);
    });
    d.sec("triangles", |o| {
        let tc = st.tri_count;
        o.ints(take(&st.tri_a, tc));
        o.ints(take(&st.tri_b, tc));
        o.ints(take(&st.tri_c, tc));
        o.floats(take(&st.tri_rest_area2, tc));
        o.ints(take(&st.tri_id, tc));
        o.ints(take(&st.tri_muscle, tc));
        o.booleans(take(&st.tri_inverted, tc));
        o.floats(take(&st.tri_cell_scale, tc));
        o.floats(take(&st.tri_softness, tc));
        o.bytes(take(&st.tri_hold, tc));
        o.floats(&st.tri_grow_target);
        o.ints(take(&st.grow_edges, st.grow_edge_n));
        o.ints(take(&st.grow_tris, st.grow_tri_n));
    });
    d.sec("baked", |o| {
        st.baked.write_state(o, &st.body);
        o.int(st.baked.bare_edges);
        o.int(st.baked.live_triangle_count);
        o.int(st.baked.dirty_count as i32);
    });
    d.sec("sleep", |o| st.sleep.digest(o, oc));
    d.sec("misc", |o| {
        o.int(st.drag_id);
        o.float(st.mouse_x);
        o.float(st.mouse_y);
        o.boolean(st.sweep_backwards);
        o.int(st.play_tick);
        o.boolean(st.tears_pending);
        o.ints(take(&st.tear_queue, st.tear_queue_n));
        o.int(st.tear_queue_quiet);
        o.ints(take(&st.edge_death_log, 2 * st.edge_death_n));
        o.ints(take(&st.cell_death_log, st.cell_death_n));
        o.int(st.link_cap_hits);
        o.int(st.bone_cap_hits);
        o.int(st.speed_cap_hits);
        o.float(st.peak_speed2);
        o.int(st.pressure_burst_count);
        o.int(st.crush_burst_count);
        o.int(st.pierced_count);
        o.int(st.contacts_version);
        o.boolean(st.contacts_stale);
    });
    d.sec("boundary", |o| {
        let lb = &st.local_boundary;
        o.ints(&lb.a[..lb.count]);
        o.ints(&lb.b[..lb.count]);
        o.ints(&lb.opposite[..lb.count]);
        o.bytes(&lb.orientation[..lb.count]);
        o.ints(&lb.bend.a[..lb.bend.count]);
        o.ints(&lb.bend.b[..lb.bend.count]);
        o.floats_as_doubles(&lb.bend.rest[..lb.bend.count]);
        o.ints(&lb.flap.a[..lb.flap.count]);
        o.ints(&lb.flap.b[..lb.flap.count]);
        o.floats_as_doubles(&lb.flap.rest[..lb.flap.count]);
        o.ints(&lb.flap.owner[..lb.flap.count]);
    });
    d.sec("surface", |o| {
        let ls = &st.local_surface;
        o.floats_as_doubles(take(&ls.radius, n));
        o.booleans(take(&ls.active, n));
        o.ints(take(&ls.adj_end, n));
        o.ints(take(&ls.boundary_end, n));
        o.ints(&ls.active_changed[..ls.active_changed_n]);
        o.booleans(take(&st.is_free, n));
        o.booleans(take(&st.probe, n));
        o.floats_as_doubles(take(&st.probe_angle, n));
    });
    d.sec("limits", |o| o.ints(&st.boundary_link_limits.ids[..st.boundary_link_limits.count]));
    d.sec("joints", |o| {
        let s = st.joint_slots;
        o.int(s as i32);
        o.int(st.joint_live_count);
        o.ints(take(&st.joint_a, s));
        o.ints(take(&st.joint_b, s));
        o.floats_as_doubles(take(&st.joint_rest, s));
        o.floats_as_doubles(take(&st.joint_compliance, s));
        o.booleans(take(&st.joint_live, s));
        o.ints(take(&st.joint_free, st.joint_free_n));
        o.floats_as_doubles(take(&st.joint_wake_strain, s));
    });
    d.sec("growth", |o| {
        o.floats(&st.contact_ramp);
        o.ints(take(&st.ramp_cells, st.ramp_n));
        o.int(st.grow_stamp_now);
    });
    d.sec("temp", |o| {
        o.ints(&st.temp_retire_start);
        o.ints(&st.temp_retire_edge);
        o.ints(&st.temp_retire_other);
        o.booleans(&st.temp_edge);
        o.ints(take(&st.temp_pending, st.temp_pending_n));
    });
    if let Some(cb) = st.constraint_blocks.as_ref() {
        d.sec("blocks", |o| {
            o.int(cb.block_count as i32);
            o.int(cb.buckets as i32);
            o.ints(&cb.awake);
            o.ints(&cb.links.count);
            o.ints(&cb.links.live_count);
            o.ints(&cb.areas.count);
            o.ints(&cb.areas.live_count);
            o.ints(&cb.near_count);
            o.ints(&cb.triangle_slot);
        });
    }
    if let Some(ct) = st.contacts.as_ref() {
        d.sec("contacts", |o| contacts_digest(ct, o));
        d.sec("contactList", |o| contact_list_digest(ct, o));
        d.sec("broadphase", |o| broadphase_digest(ct, o));
    }
    let mut all = Out::new();
    all.int(d.sections);
    all.buf.extend_from_slice(&d.body.buf);
    all.buf
}

/// Раздел «broadphase» — кэш широкой фазы (`broadphaseDigest` в RustDigest.kt). Отдельно от «contacts»: острова
/// Kotlin сбрасывают его на смене области (полная пересборка вместо дописывания, касания те же), поэтому сверка его
/// пропускает, когда в Kotlin острова могут работать.
fn broadphase_digest(ct: &BoundaryContacts, o: &mut Out) {
    o.boolean(ct.bp_valid);
    o.boolean(ct.static_valid);
    o.ints(&ct.pair_a[..ct.pair_n]);
    o.ints(&ct.pair_b[..ct.pair_n]);
    o.int(ct.mover_count as i32);
}

/// Раздел «contactList» — касания последнего подшага (`contactListDigest` в RustDigest.kt): список, множители, гибель и
/// пробой. Живёт подшаг; острова Kotlin решают свои касания в копиях, и к концу тика в объекте мира остаются только
/// касания области мира — поэтому раздел отдельный и при возможных островах не сверяется.
fn contact_list_digest(ct: &BoundaryContacts, o: &mut Out) {
    o.int(ct.c_n as i32);
    o.ints(&ct.c_i[..ct.c_n]);
    o.ints(&ct.c_j[..ct.c_n]);
    o.floats_as_doubles(&ct.c_lam_tot[..ct.c_n]);
    o.floats_as_doubles(&ct.c_lam_init[..ct.c_n]);
    o.floats_as_doubles(&ct.c_vn[..ct.c_n]);
    o.ints(&ct.damage.ids[..ct.damage.count]);
    o.ints(&ct.pierce_a[..ct.pierce_n]);
    o.ints(&ct.pierce_b[..ct.pierce_n]);
}

/// Раздел «contacts» — тот же порядок, что `contactsDigest` в RustDigest.kt.
fn contacts_digest(ct: &BoundaryContacts, o: &mut Out) {
    o.int(ct.vert_count as i32);
    o.ints(&ct.verts[..ct.vert_count]);
    o.booleans(&ct.on_bound);
    o.floats_as_doubles(&ct.contact_radius);
    o.float(ct.cell_size);
    o.float(ct.bp_margin);
    o.bytes(&ct.rest_state);
    o.int(ct.rest_touch_n);
    o.int(ct.rest_spacing_n);
    o.boolean(ct.ct_backwards);
    o.float(ct.friction);
    o.int(ct.immune_now);
    o.boolean(ct.ramp_on);
}
