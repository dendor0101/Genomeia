//! Копия касаний для воркера островов — `BoundaryContacts.islandFork`, `setIsland`, `prepareIslands`,
//! `mergeIslandFork` (contacts/BoundaryContacts.kt) на Rust.
//!
//! Остров решает свои касания сам, параллельно с другими островами. Копия делит с миром топологию и массивы НА
//! ВЕРШИНУ (привязки широкой фазы, силы, время удара, метки) и массы костей: острова не делят вершин и костей, каждый
//! пишет только свои. Своё у копии — всё, что не по номеру вершины: список вершин контура (вершины острова в порядке
//! списка мира), списки пар и касаний, сетки широкой фазы, буферы гибели и пробоя, черновики костей, CCD и решения.
//! Метки (`stamp`, `moved_stamp`, `pass`) идут по общим массивам на вершину: у копий они продолжаются от метки мира, а
//! после островов мир берёт наибольшую — равенство со старой меткой у вершины невозможно.
//!
//! УСТРОЙСТВО. Копия собирается ПОЛНЫМ литералом структуры: общие поля — побитовой копией поля мира (`ptr::read`),
//! свои — из [ContactsOwn] комплекта воркера. Так компилятор не даст пропустить поле: забытое поле не тихо разделит
//! память с миром, а не соберётся. Копию нельзя освобождать (её общие поля принадлежат миру): после тика свои части
//! возвращаются в комплект ([BoundaryContacts::island_unfork]), а память под структуру — заново используется.

use std::mem::{take, ManuallyDrop};
use std::ptr::read;

use super::helpers::*;
use super::{BoundaryContacts, DDA_MAX};
use crate::params::SPIN_MIN_ARM;

/// Свои части копии касаний острова (живут в комплекте воркера между тиками).
#[derive(Default)]
pub struct ContactsOwn {
    n: usize,
    verts: Vec<i32>,
    pair_a: Vec<i32>,
    pair_b: Vec<i32>,
    near_rest_slot: Vec<i32>,
    c_i: Vec<i32>,
    c_j: Vec<i32>,
    c_nx: Vec<f32>,
    c_ny: Vec<f32>,
    c_vn: Vec<f32>,
    c_lam: Vec<f32>,
    c_lam_tot: Vec<f32>,
    c_lam_init: Vec<f32>,
    c_ax: Vec<f32>,
    c_ay: Vec<f32>,
    c_rr: Vec<f32>,
    c_bone: Vec<bool>,
    c_fixed: Vec<i8>,
    bone_z: Vec<i32>,
    bone_z_stamp: Vec<i32>,
    frc_list: Vec<i32>,
    damage: Option<ContactDamageCandidates>,
    start_deep: Option<ContactDamageCandidates>,
    pierce_a: Vec<i32>,
    pierce_b: Vec<i32>,
    serial_pierce: PierceBuffer,
    lam: Option<ContactLambdaCache>,
    dda_x: Vec<i32>,
    dda_y: Vec<i32>,
    dirty_buf: Vec<i32>,
    movers: Vec<i32>,
    moved: Vec<i32>,
    shards: Vec<AnchoredContactGrid>,
    keys: PairKeyBuffer,
    kept_keys: Vec<i64>,
    kept_slot: Vec<i32>,
    static_grid: Option<SweptContactGrid>,
    in_static: Vec<bool>,
    in_static_any: bool,
    ccd_bone_t: Vec<f32>,
    ccd_bone_rep: Vec<i32>,
    ccd_bone_stamp: Vec<i32>,
    ccd_bone_hit: Vec<i32>,
    ccd_bone_pass: i32,
    pass_cache: Option<ContactPassCache>,
    fixed_list: Vec<i32>,
    bt_keys: Vec<i64>,
    bt_vals: Vec<i32>,
    bt_stamp: Vec<i32>,
    bt_gen: i32,
    rigid_batch: Option<RigidContactBatch>,
    serial_sink: ContactSolveSink,
    island_bones: Vec<i32>,
}

impl ContactsOwn {
    /// Свои части под мир из [n] частиц: прошлые переходят, если мир не вырос (`islandFork`, `prev.n == n`).
    fn fit(&mut self, n: usize, mean_link: f32) {
        if self.n != n || self.damage.is_none() {
            *self = ContactsOwn { n, ..Default::default() };
            self.verts = vec![0; 64];
            self.c_i = vec![0; 4096];
            self.c_j = vec![0; 4096];
            self.c_nx = vec![0.0; 4096];
            self.c_ny = vec![0.0; 4096];
            self.c_vn = vec![0.0; 4096];
            self.c_lam = vec![0.0; 4096];
            self.c_lam_tot = vec![0.0; 4096];
            self.c_lam_init = vec![0.0; 4096];
            self.c_ax = vec![0.0; 4096];
            self.c_ay = vec![0.0; 4096];
            self.c_rr = vec![0.0; 4096];
            self.c_bone = vec![false; 4096];
            self.c_fixed = vec![0; 4096];
            self.frc_list = vec![0; 256];
            self.damage = Some(ContactDamageCandidates::new(n));
            self.start_deep = Some(ContactDamageCandidates::new(n));
            self.pierce_a = vec![0; 16];
            self.pierce_b = vec![0; 16];
            self.lam = Some(ContactLambdaCache::default());
            self.dda_x = vec![0; DDA_MAX];
            self.dda_y = vec![0; DDA_MAX];
            self.dirty_buf = vec![0; 64];
            self.pair_a = vec![0; 64];
            self.pair_b = vec![0; 64];
            self.near_rest_slot = vec![0; 64];
            self.shards = vec![AnchoredContactGrid::new(n)];
            self.kept_keys = vec![0; 256];
            self.kept_slot = vec![0; 256];
            self.static_grid = Some(SweptContactGrid::default());
            self.pass_cache = Some(ContactPassCache::default());
            self.fixed_list = vec![0; 256];
            self.bt_keys = vec![0; 256];
            self.bt_vals = vec![0; 256];
            self.bt_stamp = vec![0; 256];
            self.rigid_batch = Some(RigidContactBatch::new(SPIN_MIN_ARM * mean_link));
        }
    }
}

impl BoundaryContacts {
    /// `prepareIslands`: перед островами, в главном потоке — общие массивы на вершину и на кость, которые подшаг
    /// дозаказывает сам: в копии острова их нельзя пересоздать (копия делит их с миром).
    pub fn prepare_islands(&mut self, bones: usize) {
        if self.pierce_reach.len() < self.n {
            self.pierce_reach = vec![0.0; self.n];
            self.pierce_move = vec![0.0; self.n];
        }
        if bones > 0 && self.bone_m.len() != bones {
            self.bone_m = vec![0.0; bones];
            self.bone_cx = vec![0.0; bones];
            self.bone_cy = vec![0.0; bones];
            self.bone_i = vec![0.0; bones];
        }
    }

    /// `islandFork`: копия для воркера островов на этот тик — общие поля мира и свои части из [own] (они уходят в
    /// копию до [island_unfork]). Копия без воркеров: остров целиком идёт в одном потоке.
    ///
    /// # Safety
    /// Копию нельзя освобождать и нельзя держать дольше тика мира: её общие поля принадлежат миру.
    pub unsafe fn island_fork(&self, own: &mut ContactsOwn) -> ManuallyDrop<BoundaryContacts> {
        own.fit(self.n, self.mean_link);
        let o = own;
        // SAFETY (весь литерал): общие поля — побитовые копии полей мира; копия живёт внутри тика мира и никогда не
        // освобождается (ManuallyDrop), свои части возвращает island_unfork.
        unsafe {
            ManuallyDrop::new(BoundaryContacts {
                // --- общие: топология, параметры, массивы на вершину и на кость ---
                n: self.n,
                contact_radius: read(&self.contact_radius),
                adj_start: read(&self.adj_start),
                adj: read(&self.adj),
                adj_end: read(&self.adj_end),
                cell_size: self.cell_size,
                contact_scale: self.contact_scale,
                ccd_core: self.ccd_core,
                restitution: self.restitution,
                friction: self.friction,
                mean_link: self.mean_link,
                contact_max_step: self.contact_max_step,
                opt_bp_margin: self.opt_bp_margin,
                opt_ct_sweep_flip: self.opt_ct_sweep_flip,
                opt_ccd_gate: self.opt_ccd_gate,
                opt_pierce_tear: self.opt_pierce_tear,
                opt_rest_full: self.opt_rest_full,
                opt_xpbd_warm_start: self.opt_xpbd_warm_start,
                opt_ab_legacy: self.opt_ab_legacy,
                opt_contact_compliance: self.opt_contact_compliance,
                opt_contact_omega_pair: self.opt_contact_omega_pair,
                opt_contact_seal: self.opt_contact_seal,
                bp_x: read(&self.bp_x),
                bp_y: read(&self.bp_y),
                bp_sweep: read(&self.bp_sweep),
                bp_margin: self.bp_margin,
                mark: read(&self.mark),
                stamp: self.stamp,
                toi: read(&self.toi),
                ccd_parent: read(&self.ccd_parent),
                isl_m: read(&self.isl_m),
                isl_x: read(&self.isl_x),
                isl_y: read(&self.isl_y),
                frc_x: read(&self.frc_x),
                frc_y: read(&self.frc_y),
                frc_w: read(&self.frc_w),
                frc_s: read(&self.frc_s),
                frc_z: read(&self.frc_z),
                frc_stamp: read(&self.frc_stamp),
                pass: self.pass,
                bnd_start: read(&self.bnd_start),
                bnd_other: read(&self.bnd_other),
                bnd_opp: read(&self.bnd_opp),
                bnd_end: read(&self.bnd_end),
                surface_attached: self.surface_attached,
                pierce_reach: read(&self.pierce_reach),
                pierce_move: read(&self.pierce_move),
                attract_n: self.attract_n,
                kill_on_deep: self.kill_on_deep,
                kill_depth: self.kill_depth,
                immune_now: self.immune_now,
                ramp_on: self.ramp_on,
                to_velocity: self.to_velocity,
                vel_h: self.vel_h,
                rest: read(&self.rest),
                rest_state: read(&self.rest_state),
                rest_touch_n: self.rest_touch_n,
                rest_spacing_n: self.rest_spacing_n,
                on_bound: read(&self.on_bound),
                vert_slot: read(&self.vert_slot),
                max_radius: self.max_radius,
                radius_minimum: read(&self.radius_minimum),
                bone_m: read(&self.bone_m),
                bone_cx: read(&self.bone_cx),
                bone_cy: read(&self.bone_cy),
                bone_i: read(&self.bone_i),
                last_contacts: self.last_contacts,
                last_toi_clamps: self.last_toi_clamps,
                impulse_accum: self.impulse_accum,
                prev_radius: read(&self.prev_radius),
                prev_bound: read(&self.prev_bound),
                prev_deg: read(&self.prev_deg),
                rest_valid: self.rest_valid,
                cfg_deg: read(&self.cfg_deg),
                moved_mark: read(&self.moved_mark),
                moved_stamp: self.moved_stamp,
                inv_mass_seen: false,
                inv_mass_len_seen: 0,
                skip_candidates_now: self.skip_candidates_now,
                ct_backwards: self.ct_backwards,
                min_bone_arm2: self.min_bone_arm2,
                vertex_scope_on: self.vertex_scope_on,
                scope: self.scope,
                // --- свои: вершины, пары, касания, сетки, буферы ---
                verts: take(&mut o.verts),
                vert_count: 0,
                pair_a: take(&mut o.pair_a),
                pair_b: take(&mut o.pair_b),
                pair_n: 0,
                near_rest_slot: take(&mut o.near_rest_slot),
                near_n: 0,
                bp_valid: false,
                c_i: take(&mut o.c_i),
                c_j: take(&mut o.c_j),
                c_nx: take(&mut o.c_nx),
                c_ny: take(&mut o.c_ny),
                c_vn: take(&mut o.c_vn),
                c_lam: take(&mut o.c_lam),
                c_lam_tot: take(&mut o.c_lam_tot),
                c_lam_init: take(&mut o.c_lam_init),
                c_n: 0,
                c_ax: take(&mut o.c_ax),
                c_ay: take(&mut o.c_ay),
                c_rr: take(&mut o.c_rr),
                c_bone: take(&mut o.c_bone),
                c_fixed: take(&mut o.c_fixed),
                bone_z: take(&mut o.bone_z),
                bone_z_stamp: take(&mut o.bone_z_stamp),
                frc_list: take(&mut o.frc_list),
                frc_n: 0,
                damage: o.damage.take().unwrap(),
                start_deep: o.start_deep.take().unwrap(),
                pierce_n: 0,
                pierce_a: take(&mut o.pierce_a),
                pierce_b: take(&mut o.pierce_b),
                serial_pierce: take(&mut o.serial_pierce),
                lam: o.lam.take().unwrap(),
                dda_x: take(&mut o.dda_x),
                dda_y: take(&mut o.dda_y),
                dirty_buf: take(&mut o.dirty_buf),
                movers: take(&mut o.movers),
                mover_count: 0,
                moved: take(&mut o.moved),
                moved_count: 0,
                shards: take(&mut o.shards),
                keys: take(&mut o.keys),
                kept_keys: take(&mut o.kept_keys),
                kept_slot: take(&mut o.kept_slot),
                static_grid: o.static_grid.take().unwrap(),
                static_valid: false,
                static_count: 0,
                in_static: take(&mut o.in_static),
                in_static_any: o.in_static_any,
                ccd_bone_t: take(&mut o.ccd_bone_t),
                ccd_bone_rep: take(&mut o.ccd_bone_rep),
                ccd_bone_stamp: take(&mut o.ccd_bone_stamp),
                ccd_bone_hit: take(&mut o.ccd_bone_hit),
                ccd_bone_pass: o.ccd_bone_pass,
                pass_cache: o.pass_cache.take().unwrap(),
                fixed_list: take(&mut o.fixed_list),
                fixed_n: 0,
                solving_fixed: false,
                fixed_sweep_now: 0,
                bt_keys: take(&mut o.bt_keys),
                bt_vals: take(&mut o.bt_vals),
                bt_stamp: take(&mut o.bt_stamp),
                bt_gen: o.bt_gen,
                bt_used: 0,
                rigid_batch: o.rigid_batch.take().unwrap(),
                serial_sink: take(&mut o.serial_sink),
                parallel_solve: None,
                parallel_prepare: None,
                parallel_candidates: None,
                workers: None,
                island_bones_on: true,
                island_bones: take(&mut o.island_bones),
            })
        }
    }

    /// Свои части копии — обратно в [own] (`islandFork` следующего тика возьмёт их без выделения). Копию после этого не
    /// трогать.
    ///
    /// # Safety
    /// `fork` — копия из [island_fork] этого тика.
    pub unsafe fn island_unfork(fork: &mut ManuallyDrop<BoundaryContacts>, own: &mut ContactsOwn) {
        let f: &mut BoundaryContacts = fork;
        unsafe {
            own.verts = read(&f.verts);
            own.pair_a = read(&f.pair_a);
            own.pair_b = read(&f.pair_b);
            own.near_rest_slot = read(&f.near_rest_slot);
            own.c_i = read(&f.c_i);
            own.c_j = read(&f.c_j);
            own.c_nx = read(&f.c_nx);
            own.c_ny = read(&f.c_ny);
            own.c_vn = read(&f.c_vn);
            own.c_lam = read(&f.c_lam);
            own.c_lam_tot = read(&f.c_lam_tot);
            own.c_lam_init = read(&f.c_lam_init);
            own.c_ax = read(&f.c_ax);
            own.c_ay = read(&f.c_ay);
            own.c_rr = read(&f.c_rr);
            own.c_bone = read(&f.c_bone);
            own.c_fixed = read(&f.c_fixed);
            own.bone_z = read(&f.bone_z);
            own.bone_z_stamp = read(&f.bone_z_stamp);
            own.frc_list = read(&f.frc_list);
            own.damage = Some(read(&f.damage));
            own.start_deep = Some(read(&f.start_deep));
            own.pierce_a = read(&f.pierce_a);
            own.pierce_b = read(&f.pierce_b);
            own.serial_pierce = read(&f.serial_pierce);
            own.lam = Some(read(&f.lam));
            own.dda_x = read(&f.dda_x);
            own.dda_y = read(&f.dda_y);
            own.dirty_buf = read(&f.dirty_buf);
            own.movers = read(&f.movers);
            own.moved = read(&f.moved);
            own.shards = read(&f.shards);
            own.keys = read(&f.keys);
            own.kept_keys = read(&f.kept_keys);
            own.kept_slot = read(&f.kept_slot);
            own.static_grid = Some(read(&f.static_grid));
            own.in_static = read(&f.in_static);
            own.in_static_any = f.in_static_any;
            own.ccd_bone_t = read(&f.ccd_bone_t);
            own.ccd_bone_rep = read(&f.ccd_bone_rep);
            own.ccd_bone_stamp = read(&f.ccd_bone_stamp);
            own.ccd_bone_hit = read(&f.ccd_bone_hit);
            own.ccd_bone_pass = f.ccd_bone_pass;
            own.pass_cache = Some(read(&f.pass_cache));
            own.fixed_list = read(&f.fixed_list);
            own.bt_keys = read(&f.bt_keys);
            own.bt_vals = read(&f.bt_vals);
            own.bt_stamp = read(&f.bt_stamp);
            own.bt_gen = f.bt_gen;
            own.rigid_batch = Some(read(&f.rigid_batch));
            own.serial_sink = read(&f.serial_sink);
            own.island_bones = read(&f.island_bones);
        }
    }

    /// `setIsland`: копия обходит остров [k] — его вершины контура [verts] (в порядке списка мира) и кости [bones];
    /// направление обхода касаний — как у мира в начале тика ([ct_backwards0]). Широкая фаза — с нуля.
    pub fn set_island(&mut self, k: i32, verts: &[i32], bones: &[i32], ct_backwards0: bool) {
        self.verts.clear();
        self.verts.extend_from_slice(verts);
        self.vert_count = verts.len();
        self.island_bones.clear();
        self.island_bones.extend_from_slice(bones);
        self.scope = k;
        self.ct_backwards = ct_backwards0;
        self.c_n = 0;
        self.frc_n = 0;
        self.pierce_n = 0;
        self.fixed_n = 0;
        self.solving_fixed = false;
        self.fixed_sweep_now = 0;
        self.damage.clear();
        self.start_deep.clear();
        self.pair_n = 0;
        self.near_n = 0;
        self.mover_count = 0;
        self.moved_count = 0;
        // У острова неподвижных вершин нет.
        self.bp_valid = false;
        self.static_valid = false;
        self.static_count = 0;
    }

    /// `mergeIslandFork`: после островов — метки на общих массивах вершин наибольшие из копий.
    pub fn merge_island_fork(&mut self, f: &BoundaryContacts) {
        if f.stamp > self.stamp {
            self.stamp = f.stamp;
        }
        if f.moved_stamp > self.moved_stamp {
            self.moved_stamp = f.moved_stamp;
        }
        if f.pass > self.pass {
            self.pass = f.pass;
        }
    }
}
