//! Самоконтакт граничного контура — `contacts/BoundaryContacts.kt` (float, 26.11.2026): хеш-сетка, DDA, CCD, пробой,
//! касание как ограничение с накоплением множителя, упоры в неподвижное, отскок.
//!
//! ОТЛИЧИЯ УСТРОЙСТВА ОТ KOTLIN (биты те же):
//! - Kotlin держит ссылки на массивы хозяина (organismOf, noContact, group, pressure, boneOf, contactRamp,
//!   immuneStamp, lastInvMass). Здесь их передаёт хозяин в каждом вызове: [ContactCtx] и параметры. Хозяин в Kotlin
//!   переставляет ссылки на каждой пересборке массивов (buildContacts, BodyAppend, WorldCompaction), поэтому живой
//!   массив хозяина — то же, что ссылка Kotlin.
//! - `lastInvMass` — признак [inv_mass_seen] («prepare уже был») плюс нынешние обратные массы хозяина.
//! - Пары покоя общие с хозяином через `Arc`: проверка «та же таблица» (`rest === rp`) — `Arc::ptr_eq`.
//! - Части сетки широкой фазы (`shards`) — как в Kotlin: одна, а с воркерами — по части на воркер (перекладка по
//!   воркерам); раскладка по частям на результат не влияет.
//! - Разбор (CT_WATCH, CT_BPCHECK, dbgPair, dbgCcd*, счётчики bp*) не переносится: на мир не влияет.

mod broadphase;
mod ccd;
// Не `aux`: в Windows имя AUX занято устройством, и Git для Windows не берёт файл `aux.rs` (27.11).
pub mod helpers;
pub mod island_fork;
pub mod parallel_broadphase;
pub mod parallel_prepare;
pub mod parallel_solve;
mod solve;

use std::collections::HashMap;
use std::sync::Arc;

use crate::config::ContactConfig;
use crate::jmath::sqrtf;
use crate::params::SPIN_MIN_ARM;
use crate::topology::linked::LinkedPairs;
use crate::topology::surface::LocalSurface;
use helpers::*;

pub(crate) const BIAS: i32 = 1 << 20;
pub const DDA_MAX: usize = 4096;
/// Сдвиг [rest] при отказе: пары покоя (см. `CONTACT_REST_SPACING`).
pub const CONTACT_REST_SPACING: bool = true;
// Касание в Kotlin — всегда XPBD (`XPBD = true`): силовая ветка (CONTACT_DAMPING, CONTACT_DEPTH_CAP,
// CONTACT_SPLIT_LIMIT, CONTACT_OMEGA_H) там мертва и сюда не переносится.
pub(crate) const FIXED_CONTACT_OMEGA: f32 = 8.0;
pub(crate) const FIXED_CONTACT_SWEEPS: i32 = 4;

pub const KIND_SOFT: i8 = 0;
pub const KIND_FIXED: i8 = 1;
pub(crate) const KIND_PUSHED: i8 = 2;
pub(crate) const KIND_OWN: i8 = 3;
pub(crate) const KIND_OWN_PUSHED: i8 = 4;

pub(crate) const REST_NONE: i8 = 0;
pub(crate) const REST_TOUCH: i8 = 1;
pub(crate) const REST_SPACING: i8 = 2;

/// Кандидаты касания в покое, запечённые на тело (`RestPairs`): записи парные, mirror — вторая половина.
#[derive(Clone, Debug)]
pub struct RestPairs {
    pub start: Vec<i32>,
    pub other: Vec<i32>,
    pub mirror: Vec<i32>,
    pub d2: Vec<f32>,
    pub dist: Vec<f32>,
    pub ever: Vec<bool>,
}

impl RestPairs {
    pub fn empty() -> RestPairs {
        RestPairs { start: vec![0; 1], other: Vec::new(), mirror: Vec::new(), d2: Vec::new(), dist: Vec::new(), ever: Vec::new() }
    }
}

/// Массивы хозяина, которые касания в Kotlin держат ссылками (см. шапку).
pub struct ContactCtx<'a> {
    /// Нынешние обратные массы мира — `lastInvMass` Kotlin, если prepare уже был ([BoundaryContacts::inv_mass_seen]).
    pub inv_mass: &'a [f32],
    pub organism_of: &'a [i32],
    pub no_contact: &'a [bool],
    pub group: &'a [i32],
    /// Доли мягкого старта касаний (BodyState.contactRamp); читаются, только если [BoundaryContacts::ramp_on].
    pub contact_ramp: &'a [f32],
    /// Метки «дорастает связь» (BodyState.growStamp) — неуязвимость делящихся, см. [BoundaryContacts::immune_now].
    pub immune_stamp: &'a [i32],
    /// Состав костей: `boneOf` (null, если кости не жёсткие) и `rigidBones` (bindBones).
    pub bone_of: Option<&'a [i32]>,
    pub bone_ids: &'a [Vec<i32>],
    /// Область каждой вершины в этом тике (`vertexScope`, острова); читается, только если
    /// [BoundaryContacts::vertex_scope_on].
    pub vertex_scope: &'a [i32],
}

#[derive(Clone, Debug)]
pub struct BoundaryContacts {
    pub n: usize,
    pub contact_radius: Vec<f32>,
    pub(crate) verts: Vec<i32>,
    pub(crate) adj_start: Vec<i32>,
    pub(crate) adj: Vec<i32>,
    pub(crate) adj_end: Vec<i32>,
    pub(crate) cell_size: f32,
    pub(crate) contact_scale: f32,
    pub(crate) ccd_core: f32,
    pub(crate) restitution: f32,
    pub friction: f32,
    pub(crate) mean_link: f32,
    pub(crate) contact_max_step: f32,
    pub(crate) opt_bp_margin: f32,
    pub(crate) opt_ct_sweep_flip: bool,
    pub(crate) opt_ccd_gate: f32,
    pub(crate) opt_pierce_tear: bool,
    pub(crate) opt_rest_full: bool,
    pub(crate) opt_xpbd_warm_start: bool,
    pub(crate) opt_ab_legacy: bool,
    pub(crate) opt_contact_compliance: f32,
    pub(crate) opt_contact_omega_pair: f32,
    pub(crate) opt_contact_seal: f32,

    // --- пары-кандидаты (nearA/nearB Kotlin — те же pairA/pairB) ---
    pub(crate) pair_a: Vec<i32>,
    pub(crate) pair_b: Vec<i32>,
    pub(crate) pair_n: usize,
    pub(crate) near_rest_slot: Vec<i32>,
    pub(crate) near_n: usize,

    pub(crate) bp_x: Vec<f32>,
    pub(crate) bp_y: Vec<f32>,
    pub(crate) bp_sweep: Vec<f32>,
    pub(crate) bp_valid: bool,
    pub(crate) bp_margin: f32,
    pub(crate) mark: Vec<i32>,
    pub(crate) stamp: i32,

    // --- касания подшага ---
    pub c_i: Vec<i32>,
    pub c_j: Vec<i32>,
    pub(crate) c_nx: Vec<f32>,
    pub(crate) c_ny: Vec<f32>,
    pub(crate) c_vn: Vec<f32>,
    pub(crate) c_lam: Vec<f32>,
    pub(crate) c_lam_tot: Vec<f32>,
    pub(crate) c_lam_init: Vec<f32>,
    pub c_n: usize,

    pub(crate) toi: Vec<f32>,
    pub(crate) ccd_parent: Vec<i32>,
    pub(crate) isl_m: Vec<f32>,
    pub(crate) isl_x: Vec<f32>,
    pub(crate) isl_y: Vec<f32>,

    pub(crate) frc_x: Vec<f32>,
    pub(crate) frc_y: Vec<f32>,
    pub(crate) frc_w: Vec<f32>,
    pub(crate) frc_s: Vec<f32>,
    pub(crate) c_ax: Vec<f32>,
    pub(crate) c_ay: Vec<f32>,
    pub(crate) frc_z: Vec<i32>,
    pub(crate) bone_z: Vec<i32>,
    pub(crate) bone_z_stamp: Vec<i32>,
    pub(crate) frc_stamp: Vec<i32>,
    pub(crate) frc_list: Vec<i32>,
    pub(crate) frc_n: usize,
    pub(crate) pass: i32,

    pub(crate) damage: ContactDamageCandidates,
    pub(crate) start_deep: ContactDamageCandidates,

    pub pierce_n: usize,
    pub pierce_a: Vec<i32>,
    pub pierce_b: Vec<i32>,
    pub(crate) bnd_start: Vec<i32>,
    pub(crate) bnd_other: Vec<i32>,
    pub(crate) bnd_opp: Vec<i32>,
    pub(crate) bnd_end: Vec<i32>,
    /// Смежность и контур — копия местной поверхности (attachSurface); reconfigure строит свои.
    pub(crate) surface_attached: bool,
    pub(crate) pierce_reach: Vec<f32>,
    pub(crate) pierce_move: Vec<f32>,
    pub(crate) serial_pierce: PierceBuffer,

    pub attract_n: i32,
    pub kill_on_deep: bool,
    pub kill_depth: f32,
    /// Метка «неуязвим» этого шага (`immuneNow`); метки по клетке — [ContactCtx::immune_stamp].
    pub immune_now: i32,
    /// `contactRamp != null` в Kotlin: мягкий старт касаний включён, доли — [ContactCtx::contact_ramp].
    pub ramp_on: bool,

    pub(crate) to_velocity: bool,
    pub(crate) vel_h: f32,
    pub(crate) lam: ContactLambdaCache,

    pub(crate) rest: Arc<RestPairs>,
    pub(crate) rest_state: Vec<i8>,
    pub(crate) rest_touch_n: i32,
    pub(crate) rest_spacing_n: i32,
    pub(crate) on_bound: Vec<bool>,
    pub(crate) vert_count: usize,
    pub(crate) vert_slot: Vec<i32>,
    pub(crate) max_radius: f32,
    pub(crate) radius_minimum: ContactRadiusMinimum,
    pub(crate) c_rr: Vec<f32>,

    pub(crate) bone_m: Vec<f32>,
    pub(crate) bone_cx: Vec<f32>,
    pub(crate) bone_cy: Vec<f32>,
    pub(crate) bone_i: Vec<f32>,

    pub(crate) dda_x: Vec<i32>,
    pub(crate) dda_y: Vec<i32>,
    pub last_contacts: usize,
    pub last_toi_clamps: i32,
    pub impulse_accum: f32,

    pub(crate) prev_radius: Vec<f32>,
    pub(crate) prev_bound: Vec<bool>,
    pub(crate) prev_deg: Vec<i32>,
    pub(crate) rest_valid: bool,
    pub(crate) dirty_buf: Vec<i32>,
    pub(crate) cfg_deg: Vec<i32>,

    pub(crate) movers: Vec<i32>,
    pub(crate) mover_count: usize,
    pub(crate) moved: Vec<i32>,
    pub(crate) moved_count: usize,
    pub(crate) moved_mark: Vec<i32>,
    pub(crate) moved_stamp: i32,
    /// Подвижная сетка по частям (`shards`): с воркерами — часть на воркер по ячейкам ([shard_of]), каждую часть
    /// перекладывает свой воркер (`ParallelReinsert`). На список пар раскладка не влияет: поиск соседей помечает уже
    /// увиденных и сортирует пары вершины, порядок записей в ячейке ему безразличен.
    pub(crate) shards: Vec<AnchoredContactGrid>,
    pub(crate) keys: PairKeyBuffer,
    pub(crate) kept_keys: Vec<i64>,
    pub(crate) kept_slot: Vec<i32>,
    pub(crate) static_grid: SweptContactGrid,
    pub(crate) static_valid: bool,
    pub(crate) static_count: usize,
    pub(crate) in_static: Vec<bool>,
    pub(crate) in_static_any: bool,
    /// `lastInvMass != null`: prepare уже был.
    pub inv_mass_seen: bool,
    /// Длина `lastInvMass` — массива обратных масс, который видел последний prepare. Мир, дописав тело, пересоздаёт
    /// массив, а касания до следующего prepare смотрят в старый: новые частицы для них «вне массива» (touchesStatic).
    /// Значения старых частиц в старом и новом массиве до prepare одни (copyOf), поэтому хватает длины.
    pub inv_mass_len_seen: usize,

    pub skip_candidates_now: bool,
    pub(crate) ct_backwards: bool,

    pub(crate) ccd_bone_t: Vec<f32>,
    pub(crate) ccd_bone_rep: Vec<i32>,
    pub(crate) ccd_bone_stamp: Vec<i32>,
    pub(crate) ccd_bone_hit: Vec<i32>,
    pub(crate) ccd_bone_pass: i32,
    pub(crate) pass_cache: ContactPassCache,

    pub(crate) c_bone: Vec<bool>,
    pub(crate) c_fixed: Vec<i8>,
    pub(crate) fixed_list: Vec<i32>,
    pub(crate) fixed_n: usize,
    pub(crate) solving_fixed: bool,
    pub(crate) fixed_sweep_now: i32,

    pub(crate) bt_keys: Vec<i64>,
    pub(crate) bt_vals: Vec<i32>,
    pub(crate) bt_stamp: Vec<i32>,
    pub(crate) bt_gen: i32,
    pub(crate) bt_used: usize,

    pub(crate) rigid_batch: RigidContactBatch,
    pub(crate) min_bone_arm2: f32,
    pub(crate) serial_sink: ContactSolveSink,
    /// Решение по воркерам (`parallelSolve`) и воркеры этого тика (`workers`, ставит тик мира).
    pub(crate) parallel_solve: Option<Box<parallel_solve::ParallelContactSolve>>,
    /// Подготовка по воркерам (`parallelPrepare`, `parallelCcd`, `parallelPierce`, `parallelBuild`): одни списки кусков.
    pub(crate) parallel_prepare: Option<Box<parallel_prepare::ParallelPrepare>>,
    /// Поиск пар широкой фазы по воркерам (`parallelCandidates`).
    pub(crate) parallel_candidates: Option<Box<parallel_broadphase::ParallelCandidates>>,
    pub workers: Option<std::sync::Arc<crate::solver::pool::Workers>>,
    /// ОБЛАСТЬ ТИКА (острова): есть ли области вершин в этом тике (массив — [ContactCtx::vertex_scope]) и область,
    /// которую сейчас собирает широкая фаза. Вершины чужой области в сетку не попадают вовсе — ни подвижными, ни
    /// неподвижными: остров по построению не может их коснуться. Смена области пересобирает широкую фазу с нуля — биты
    /// от этого те же (опыт Kotlin 25.11: полная пересборка каждый подшаг даёт ту же сумму мира).
    pub vertex_scope_on: bool,
    pub scope: i32,
    /// Копия касаний острова: только свои кости (их массы и центры пишет остров; чужие — их остров или мир).
    pub(crate) island_bones_on: bool,
    pub(crate) island_bones: Vec<i32>,
}

/// Входы `build`/`reconfigure`: рабочие связи, контур, поза покоя и признаки клеток.
pub struct ContactTopology<'a> {
    pub con_a: &'a [i32],
    pub con_b: &'a [i32],
    pub con_count: usize,
    pub bound_a: &'a [i32],
    pub bound_b: &'a [i32],
    pub bound_count: usize,
    pub bound_opp: &'a [i32],
    pub rest_x: &'a [f32],
    pub rest_y: &'a [f32],
    /// Клетки без единой связи в полном графе (probeCells хозяина).
    pub isolated: Option<&'a [bool]>,
    pub dead: Option<&'a [bool]>,
    /// Радиус клетки (рисовальный) — у отломанной клетки касание по нему.
    pub radius: &'a [f32],
}

impl BoundaryContacts {
    #[allow(clippy::too_many_arguments)]
    fn new_empty(
        n: usize,
        contact_scale: f32,
        ccd_core: f32,
        restitution: f32,
        friction: f32,
        mean_link: f32,
        contact_max_step: f32,
        config: &ContactConfig,
    ) -> BoundaryContacts {
        let min_arm = SPIN_MIN_ARM * mean_link;
        BoundaryContacts {
            n,
            contact_radius: vec![0.0; n],
            verts: Vec::new(),
            adj_start: vec![0; n + 1],
            adj: Vec::new(),
            adj_end: (0..n).map(|_| 0).collect(),
            cell_size: 1e-6,
            contact_scale,
            ccd_core,
            restitution,
            friction,
            mean_link,
            contact_max_step,
            opt_bp_margin: config.bp_margin,
            opt_ct_sweep_flip: config.ct_sweep_flip,
            opt_ccd_gate: config.ccd_gate,
            opt_pierce_tear: config.pierce_tear,
            opt_rest_full: config.rest_full,
            opt_xpbd_warm_start: config.xpbd_warm_start,
            opt_ab_legacy: config.ab_legacy,
            opt_contact_compliance: config.contact_compliance,
            opt_contact_omega_pair: config.contact_omega_pair,
            opt_contact_seal: config.contact_seal,
            pair_a: vec![0; 4096],
            pair_b: vec![0; 4096],
            pair_n: 0,
            near_rest_slot: vec![0; 4096],
            near_n: 0,
            bp_x: vec![0.0; n],
            bp_y: vec![0.0; n],
            bp_sweep: vec![0.0; n],
            bp_valid: false,
            bp_margin: 0.0,
            mark: vec![0; n],
            stamp: 0,
            c_i: vec![0; 4096],
            c_j: vec![0; 4096],
            c_nx: vec![0.0; 4096],
            c_ny: vec![0.0; 4096],
            c_vn: vec![0.0; 4096],
            c_lam: vec![0.0; 4096],
            c_lam_tot: vec![0.0; 4096],
            c_lam_init: vec![0.0; 4096],
            c_n: 0,
            toi: vec![0.0; n],
            ccd_parent: vec![0; n],
            isl_m: vec![0.0; n],
            isl_x: vec![0.0; n],
            isl_y: vec![0.0; n],
            frc_x: vec![0.0; n],
            frc_y: vec![0.0; n],
            frc_w: vec![0.0; n],
            frc_s: vec![0.0; n],
            c_ax: vec![0.0; 4096],
            c_ay: vec![0.0; 4096],
            frc_z: vec![0; n],
            bone_z: Vec::new(),
            bone_z_stamp: Vec::new(),
            frc_stamp: vec![-1; n],
            frc_list: vec![0; 256],
            frc_n: 0,
            pass: 0,
            damage: ContactDamageCandidates::new(n),
            start_deep: ContactDamageCandidates::new(n),
            pierce_n: 0,
            pierce_a: vec![0; 16],
            pierce_b: vec![0; 16],
            bnd_start: Vec::new(),
            bnd_other: Vec::new(),
            bnd_opp: Vec::new(),
            bnd_end: vec![0; n],
            surface_attached: false,
            pierce_reach: Vec::new(),
            pierce_move: Vec::new(),
            serial_pierce: PierceBuffer::default(),
            attract_n: 0,
            kill_on_deep: false,
            kill_depth: 1.0,
            immune_now: -1,
            ramp_on: false,
            to_velocity: false,
            vel_h: 1.0,
            lam: ContactLambdaCache::default(),
            rest: Arc::new(RestPairs::empty()),
            rest_state: Vec::new(),
            rest_touch_n: 0,
            rest_spacing_n: 0,
            on_bound: vec![false; n],
            vert_count: 0,
            vert_slot: vec![-1; n],
            max_radius: 0.0,
            radius_minimum: ContactRadiusMinimum::new(n),
            c_rr: vec![0.0; 4096],
            bone_m: Vec::new(),
            bone_cx: Vec::new(),
            bone_cy: Vec::new(),
            bone_i: Vec::new(),
            dda_x: vec![0; DDA_MAX],
            dda_y: vec![0; DDA_MAX],
            last_contacts: 0,
            last_toi_clamps: 0,
            impulse_accum: 0.0,
            prev_radius: vec![0.0; n],
            prev_bound: vec![false; n],
            prev_deg: vec![0; n],
            rest_valid: false,
            dirty_buf: vec![0; 64],
            cfg_deg: vec![0; n + 1],
            movers: Vec::new(),
            mover_count: 0,
            moved: Vec::new(),
            moved_count: 0,
            moved_mark: vec![0; n],
            moved_stamp: 0,
            shards: vec![AnchoredContactGrid::new(n)],
            keys: PairKeyBuffer::default(),
            kept_keys: vec![0; 256],
            kept_slot: vec![0; 256],
            static_grid: SweptContactGrid::default(),
            static_valid: false,
            static_count: 0,
            in_static: Vec::new(),
            in_static_any: false,
            inv_mass_seen: false,
            inv_mass_len_seen: 0,
            skip_candidates_now: false,
            ct_backwards: false,
            ccd_bone_t: Vec::new(),
            ccd_bone_rep: Vec::new(),
            ccd_bone_stamp: Vec::new(),
            ccd_bone_hit: Vec::new(),
            ccd_bone_pass: 0,
            pass_cache: ContactPassCache::default(),
            c_bone: vec![false; 4096],
            c_fixed: vec![0; 4096],
            fixed_list: vec![0; 256],
            fixed_n: 0,
            solving_fixed: false,
            fixed_sweep_now: 0,
            bt_keys: vec![0; 256],
            bt_vals: vec![0; 256],
            bt_stamp: vec![0; 256],
            bt_gen: 0,
            bt_used: 0,
            rigid_batch: RigidContactBatch::new(min_arm),
            min_bone_arm2: min_arm * min_arm,
            serial_sink: ContactSolveSink::default(),
            parallel_solve: None,
            parallel_prepare: None,
            parallel_candidates: None,
            workers: None,
            vertex_scope_on: false,
            scope: crate::solver::blocks::SCOPE_WORLD,
            island_bones_on: false,
            island_bones: Vec::new(),
        }
    }

    /// `BoundaryContacts.build`: пустой объект и reconfigure по топологии.
    #[allow(clippy::too_many_arguments)]
    pub fn build(
        n: usize,
        topo: &ContactTopology,
        contact_scale: f32,
        ccd_core: f32,
        restitution: f32,
        friction: f32,
        mean_link: f32,
        contact_max_step: f32,
        rest_pairs: &Arc<RestPairs>,
        config: &ContactConfig,
    ) -> BoundaryContacts {
        let mut bc =
            BoundaryContacts::new_empty(n, contact_scale, ccd_core, restitution, friction, mean_link, contact_max_step, config);
        bc.reconfigure(topo, rest_pairs);
        bc
    }

    #[inline]
    pub fn contact_count(&self) -> usize {
        self.c_n
    }
    #[inline]
    pub fn contact_i(&self, c: usize) -> i32 {
        self.c_i[c]
    }
    #[inline]
    pub fn contact_j(&self, c: usize) -> i32 {
        self.c_j[c]
    }
    #[inline]
    pub fn kill_n(&self) -> usize {
        self.damage.count
    }
    #[inline]
    pub fn kill_list(&self) -> &[i32] {
        &self.damage.ids
    }
    #[inline]
    pub fn min_contact_radius(&self) -> f32 {
        self.radius_minimum.value()
    }
    #[inline]
    pub fn max_contact_radius_now(&self) -> f32 {
        self.max_radius
    }
    #[inline]
    pub fn rest_pairs(&self) -> &Arc<RestPairs> {
        &self.rest
    }
    #[inline]
    pub fn in_contact_set(&self, i: usize) -> bool {
        self.on_bound[i]
    }

    pub fn clear_pierce(&mut self) {
        self.pierce_n = 0;
    }

    /// Клетка умерла посреди тика: из столкновений — без перестройки топологии (`dropFromContacts`).
    pub fn drop_from_contacts(&mut self, i: usize) {
        self.contact_radius[i] = 0.0;
        self.radius_minimum.update(i, 0.0);
        self.static_valid = false;
    }

    /// `invalidateBroadphase`: кто-то уснул или проснулся.
    pub fn invalidate_broadphase(&mut self) {
        self.bp_valid = false;
        self.static_valid = false;
    }

    /// `invalidateStatic`: частица стала неподвижной (гибель).
    pub fn invalidate_static(&mut self) {
        self.static_valid = false;
    }

    #[inline]
    pub(crate) fn key(ix: i32, iy: i32) -> i64 {
        (((ix.wrapping_add(BIAS)) as i64) << 32) | ((iy.wrapping_add(BIAS)) as i64 & 0xFFFF_FFFF)
    }

    #[inline]
    pub(crate) fn pair_key(i: i32, j: i32) -> i64 {
        let (lo, hi) = if i < j { (i, j) } else { (j, i) };
        (lo as i64).wrapping_mul(1_000_003).wrapping_add(hi as i64)
    }

    /// `ddaInto`: растеризация отрезка в ячейки, координаты — в [dda_x]/[dda_y]; возвращает число ячеек.
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn dda_into(cell_size: f32, x0: f32, y0: f32, x1: f32, y1: f32, dda_x: &mut [i32], dda_y: &mut [i32]) -> usize {
        let mut ix = (x0 / cell_size).floor() as i32;
        let mut iy = (y0 / cell_size).floor() as i32;
        let ex = (x1 / cell_size).floor() as i32;
        let ey = (y1 / cell_size).floor() as i32;
        let dx = x1 - x0;
        let dy = y1 - y0;
        let step_x = if dx > 0.0 {
            1
        } else if dx < 0.0 {
            -1
        } else {
            0
        };
        let step_y = if dy > 0.0 {
            1
        } else if dy < 0.0 {
            -1
        } else {
            0
        };
        let adx = dx.abs();
        let ady = dy.abs();
        let t_dx = if adx > 1e-12f32 { cell_size / adx } else { f32::MAX };
        let t_dy = if ady > 1e-12f32 { cell_size / ady } else { f32::MAX };
        let mut t_mx = if adx > 1e-12f32 {
            (if dx > 0.0 { (ix.wrapping_add(1)) as f32 * cell_size - x0 } else { x0 - ix as f32 * cell_size }) / adx
        } else {
            f32::MAX
        };
        let mut t_my = if ady > 1e-12f32 {
            (if dy > 0.0 { (iy.wrapping_add(1)) as f32 * cell_size - y0 } else { y0 - iy as f32 * cell_size }) / ady
        } else {
            f32::MAX
        };
        let mut c = 0usize;
        dda_x[c] = ix;
        dda_y[c] = iy;
        c += 1;
        while (ix != ex || iy != ey) && c < DDA_MAX {
            if t_mx < t_my {
                t_mx += t_dx;
                ix = ix.wrapping_add(step_x);
            } else {
                t_my += t_dy;
                iy = iy.wrapping_add(step_y);
            }
            dda_x[c] = ix;
            dda_y[c] = iy;
            c += 1;
        }
        c
    }

    #[inline]
    pub(crate) fn rest_state_of(&self, i: i32, j: i32) -> i8 {
        let rp = &self.rest;
        for s in rp.start[i as usize]..rp.start[i as usize + 1] {
            if rp.other[s as usize] == j {
                return self.rest_state[s as usize];
            }
        }
        REST_NONE
    }

    /// `bonded`: пара не сталкивается — связана, в одной группе касаний или перекрыта уже в позе покоя.
    #[inline]
    pub(crate) fn bonded(&self, i: i32, j: i32, group: &[i32]) -> bool {
        let iu = i as usize;
        for k in self.adj_start[iu]..self.adj_end[iu] {
            if self.adj[k as usize] == j {
                return true;
            }
        }
        let ju = j as usize;
        if iu < group.len() && ju < group.len() && group[iu] != 0 && group[iu] == group[ju] {
            return true;
        }
        self.rest_touch_n > 0 && self.rest_state_of(i, j) == REST_TOUCH
    }

    /// Последние обратные массы (`lastInvMass`): None, пока не было prepare.
    #[inline]
    pub(crate) fn last_inv<'a>(&self, ctx: &ContactCtx<'a>) -> Option<&'a [f32]> {
        if self.inv_mass_seen {
            Some(ctx.inv_mass)
        } else {
            None
        }
    }

    /// `ramped`: мягкий старт касаний новорождённой — только внутри своего организма и не с неподвижным.
    #[inline]
    pub(crate) fn ramped(&self, i: i32, j: i32, rr: f32, ctx: &ContactCtx) -> f32 {
        if !self.ramp_on {
            return rr;
        }
        let (iu, ju) = (i as usize, j as usize);
        if let Some(w) = self.last_inv(ctx) {
            if w[iu] <= 0.0 || w[ju] <= 0.0 {
                return rr;
            }
        }
        if ctx.organism_of[iu] != ctx.organism_of[ju] {
            return rr;
        }
        let ramp = ctx.contact_ramp;
        let f = crate::jmath::minf(ramp[iu], ramp[ju]);
        if f < 1.0 {
            rr * f
        } else {
            rr
        }
    }

    #[inline]
    pub(crate) fn kill_immune(&self, i: i32, j: i32, ctx: &ContactCtx) -> bool {
        let s = ctx.immune_stamp;
        let (iu, ju) = (i as usize, j as usize);
        (iu < s.len() && s[iu] == self.immune_now) || (ju < s.len() && s[ju] == self.immune_now)
    }

    #[inline]
    pub(crate) fn pair_contact_distance(&mut self, p: usize, rr: f32, ctx: &ContactCtx) -> f32 {
        let d = self.pair_rest_distance(p, rr);
        self.ramped(self.pair_a[p], self.pair_b[p], d, ctx)
    }

    pub(crate) fn pair_rest_distance(&mut self, p: usize, rr: f32) -> f32 {
        if !CONTACT_REST_SPACING || self.opt_ab_legacy || self.rest_spacing_n == 0 {
            return rr;
        }
        let mut s = self.near_rest_slot[p];
        if s == -2 {
            s = -1;
            let i = self.pair_a[p] as usize;
            let j = self.pair_b[p];
            let rp = &self.rest;
            for k in rp.start[i]..rp.start[i + 1] {
                if rp.other[k as usize] == j {
                    s = k;
                    break;
                }
            }
            self.near_rest_slot[p] = s;
        }
        if s < 0 || self.rest_state[s as usize] != REST_SPACING {
            return rr;
        }
        let d = self.rest.dist[s as usize];
        if d < rr {
            d
        } else {
            rr
        }
    }

    /// `contactKind`: упор в неподвижное, касание своего организма или обычное. Растущая клетка — обычное.
    pub(crate) fn contact_kind(&self, i: i32, j: i32, ctx: &ContactCtx) -> i8 {
        let w = match self.last_inv(ctx) {
            Some(w) => w,
            None => return KIND_SOFT,
        };
        if self.kill_immune(i, j, ctx) {
            return KIND_SOFT;
        }
        if w[i as usize] <= 0.0 || w[j as usize] <= 0.0 {
            return KIND_FIXED;
        }
        if ctx.organism_of[i as usize] == ctx.organism_of[j as usize] {
            KIND_OWN
        } else {
            KIND_SOFT
        }
    }

    /// `applyRestPairs`: какие пары покоя касаются на этой топологии — у задетых клеток или целиком.
    fn apply_rest_pairs(&mut self, rp: &Arc<RestPairs>) {
        let same_rp = Arc::ptr_eq(&self.rest, rp) && self.rest_state.len() == rp.other.len();
        self.rest = rp.clone();
        if !same_rp {
            self.bp_valid = false;
        }
        if self.rest_state.len() != rp.other.len() {
            self.rest_state = vec![0; rp.other.len()];
            self.rest_valid = false;
        }
        let n = self.n;
        let mut max_r = 0.0f32;
        for vi in 0..self.vert_count {
            let v = self.verts[vi] as usize;
            if self.contact_radius[v] > max_r {
                max_r = self.contact_radius[v];
            }
        }
        let mut dirty_n = 0usize;
        let mut too_many = false;
        if self.rest_valid && same_rp {
            for i in 0..n {
                let d = self.adj_end[i] - self.adj_start[i];
                if self.contact_radius[i] == self.prev_radius[i] && self.on_bound[i] == self.prev_bound[i] && d == self.prev_deg[i] {
                    continue;
                }
                if dirty_n == self.dirty_buf.len() {
                    if self.dirty_buf.len() >= n / 4 {
                        too_many = true;
                        break;
                    }
                    let cap = self.dirty_buf.len() * 2;
                    self.dirty_buf.resize(cap, 0);
                }
                self.dirty_buf[dirty_n] = i as i32;
                dirty_n += 1;
            }
        }
        let local = self.rest_valid && same_rp && !too_many && !self.opt_rest_full;
        if !local {
            self.rest_state.fill(0);
            self.rest_touch_n = 0;
            self.rest_spacing_n = 0;
        } else {
            for k in 0..dirty_n {
                let i = self.dirty_buf[k] as usize;
                for s_idx in rp.start[i]..rp.start[i + 1] {
                    let s_idx = s_idx as usize;
                    let st = self.rest_state[s_idx];
                    if st == REST_NONE {
                        continue;
                    }
                    if st == REST_SPACING {
                        self.rest_spacing_n -= 1;
                    } else {
                        self.rest_touch_n -= 1;
                    }
                    self.rest_state[s_idx] = 0;
                    self.rest_state[rp.mirror[s_idx] as usize] = 0;
                }
            }
        }
        for i in 0..n {
            self.prev_radius[i] = self.contact_radius[i];
            self.prev_bound[i] = self.on_bound[i];
            self.prev_deg[i] = self.adj_end[i] - self.adj_start[i];
        }
        self.rest_valid = true;
        if max_r <= 0.0 {
            return;
        }
        let scan_n = if local { dirty_n } else { self.vert_count };
        for kk in 0..scan_n {
            let i = if local { self.dirty_buf[kk] } else { self.verts[kk] } as usize;
            if !self.on_bound[i] {
                continue;
            }
            for s in rp.start[i]..rp.start[i + 1] {
                let s = s as usize;
                let j = rp.other[s];
                if !local && j <= i as i32 {
                    continue;
                }
                let ju = j as usize;
                if !self.on_bound[ju] {
                    continue;
                }
                let rr = self.contact_radius[i] + self.contact_radius[ju];
                if rp.d2[s] >= rr * rr {
                    continue;
                }
                let mut linked = false;
                for k in self.adj_start[i]..self.adj_end[i] {
                    if self.adj[k as usize] == j {
                        linked = true;
                        break;
                    }
                }
                if linked {
                    continue;
                }
                let st = if rp.ever[s] { REST_SPACING } else { REST_TOUCH };
                if self.rest_state[s] == st {
                    continue;
                }
                self.rest_state[s] = st;
                self.rest_state[rp.mirror[s] as usize] = st;
                if st == REST_SPACING {
                    self.rest_spacing_n += 1;
                } else {
                    self.rest_touch_n += 1;
                }
            }
        }
    }

    /// `reconfigure`: пересобрать под новую топологию на месте.
    pub fn reconfigure(&mut self, t: &ContactTopology, rest_pairs: &Arc<RestPairs>) {
        let n = self.n;
        self.surface_attached = false;
        self.adj_start = vec![0; n + 1];
        self.adj_end = vec![0; n];
        self.bnd_start = vec![0; n + 1];
        self.bnd_end = vec![0; n];
        let deg = &mut self.cfg_deg;
        deg.fill(0);
        for c in 0..t.con_count {
            deg[t.con_a[c] as usize] += 1;
            deg[t.con_b[c] as usize] += 1;
        }
        self.adj_start[0] = 0;
        for i in 0..n {
            self.adj_start[i + 1] = self.adj_start[i] + deg[i];
        }
        if self.adj.len() < self.adj_start[n] as usize {
            self.adj = vec![0; self.adj_start[n] as usize * 2];
        }
        deg[..n].copy_from_slice(&self.adj_start[..n]);
        for c in 0..t.con_count {
            let a = t.con_a[c] as usize;
            let b = t.con_b[c] as usize;
            let da = deg[a] as usize;
            self.adj[da] = t.con_b[c];
            deg[a] += 1;
            let db = deg[b] as usize;
            self.adj[db] = t.con_a[c];
            deg[b] += 1;
        }
        for i in 0..n {
            self.adj_end[i] = self.adj_start[i + 1];
        }

        self.on_bound.fill(false);
        for e in 0..t.bound_count {
            self.on_bound[t.bound_a[e] as usize] = true;
            self.on_bound[t.bound_b[e] as usize] = true;
        }
        if let Some(iso) = t.isolated {
            for i in 0..n {
                if iso[i] {
                    self.on_bound[i] = true;
                }
            }
        }
        if let Some(dead) = t.dead {
            for i in 0..n {
                if dead[i] {
                    self.on_bound[i] = false;
                }
            }
        }
        let mut nv = 0usize;
        for i in 0..n {
            if self.on_bound[i] {
                nv += 1;
            }
        }
        if self.verts.len() < n {
            self.verts = vec![0; n];
        }
        self.vert_count = nv;
        self.vert_slot.fill(-1);
        {
            let mut k = 0usize;
            for i in 0..n {
                if self.on_bound[i] {
                    self.vert_slot[i] = k as i32;
                    self.verts[k] = i as i32;
                    k += 1;
                }
            }
        }

        if self.bnd_start.len() != n + 1 {
            self.bnd_start = vec![0; n + 1];
        }
        self.bnd_start.fill(0);
        for e in 0..t.bound_count {
            self.bnd_start[t.bound_a[e] as usize] += 1;
            self.bnd_start[t.bound_b[e] as usize] += 1;
        }
        {
            let mut acc = 0i32;
            for i in 0..n {
                let d = self.bnd_start[i];
                self.bnd_start[i] = acc;
                acc += d;
            }
            self.bnd_start[n] = acc;
            let acc = acc as usize;
            if self.bnd_other.len() < acc {
                self.bnd_other = vec![0; acc];
            }
            if self.bnd_opp.len() < acc {
                self.bnd_opp = vec![0; acc];
            }
            let mut fill = self.bnd_start.clone();
            for e in 0..t.bound_count {
                let a = t.bound_a[e] as usize;
                let b = t.bound_b[e] as usize;
                let fa = fill[a] as usize;
                self.bnd_opp[fa] = t.bound_opp[e];
                self.bnd_other[fa] = t.bound_b[e];
                fill[a] += 1;
                let fb = fill[b] as usize;
                self.bnd_opp[fb] = t.bound_opp[e];
                self.bnd_other[fb] = t.bound_a[e];
                fill[b] += 1;
            }
        }
        for i in 0..n {
            self.bnd_end[i] = self.bnd_start[i + 1];
        }
        self.contact_radius.fill(0.0);
        for e in 0..t.bound_count {
            let a = t.bound_a[e] as usize;
            let b = t.bound_b[e] as usize;
            let dx = t.rest_x[a] - t.rest_x[b];
            let dy = t.rest_y[a] - t.rest_y[b];
            let half = 0.5f32 * sqrtf(dx * dx + dy * dy) * self.opt_contact_seal;
            if half > self.contact_radius[a] {
                self.contact_radius[a] = half;
            }
            if half > self.contact_radius[b] {
                self.contact_radius[b] = half;
            }
        }
        for i in 0..n {
            if self.contact_radius[i] > 0.0 {
                continue;
            }
            let lone = self.adj_end[i] == self.adj_start[i];
            if lone || t.isolated.is_some_and(|iso| iso[i]) {
                self.contact_radius[i] = t.radius[i] * self.contact_scale;
            }
        }
        if let Some(dead) = t.dead {
            for i in 0..n {
                if dead[i] {
                    self.contact_radius[i] = 0.0;
                }
            }
        }

        let mut max_r = 0.0f32;
        for vi in 0..self.vert_count {
            let v = self.verts[vi] as usize;
            if self.contact_radius[v] > max_r {
                max_r = self.contact_radius[v];
            }
        }
        self.max_radius = max_r;
        self.radius_minimum.reset(&self.contact_radius);
        self.bp_margin = self.opt_bp_margin.abs() * max_r;
        self.cell_size = crate::jmath::maxf(2.0f32 * max_r + self.bp_margin, 1e-6f32);
        self.bp_valid = false;
        self.static_valid = false;

        self.c_n = 0;
        self.pair_n = 0;
        self.attract_n = 0;
        self.pierce_n = 0;
        self.damage.clear();
        self.start_deep.clear();
        self.last_contacts = 0;
        self.last_toi_clamps = 0;
        self.impulse_accum = 0.0;

        self.apply_rest_pairs(rest_pairs);
    }

    /// `grow`: мир дописал тело — массивы на частицу растут с копированием.
    pub fn grow(&mut self, new_n: usize) {
        let old = self.n;
        self.n = new_n;
        self.contact_radius.resize(new_n, 0.0);
        self.bp_x.resize(new_n, 0.0);
        self.bp_y.resize(new_n, 0.0);
        self.bp_sweep.resize(new_n, 0.0);
        self.mark.resize(new_n, 0);
        self.toi.resize(new_n, 0.0);
        self.ccd_parent.resize(new_n, 0);
        self.isl_m.resize(new_n, 0.0);
        self.isl_x.resize(new_n, 0.0);
        self.isl_y.resize(new_n, 0.0);
        self.frc_x.resize(new_n, 0.0);
        self.frc_y.resize(new_n, 0.0);
        self.frc_w.resize(new_n, 0.0);
        self.frc_s.resize(new_n, 0.0);
        self.frc_z.resize(new_n, 0);
        self.frc_stamp.resize(new_n, -1);
        self.damage = ContactDamageCandidates::new(new_n);
        self.start_deep = ContactDamageCandidates::new(new_n);
        self.on_bound.resize(new_n, false);
        self.vert_slot.resize(new_n, -1);
        if self.verts.len() < new_n {
            self.verts.resize(new_n, 0);
        }
        self.prev_radius.resize(new_n, 0.0);
        self.prev_bound.resize(new_n, false);
        self.prev_deg.resize(new_n, 0);
        self.cfg_deg = vec![0; new_n + 1];
        self.moved_mark.resize(new_n, 0);
        self.radius_minimum = ContactRadiusMinimum::new(new_n);
        self.radius_minimum.reset(&self.contact_radius);
        self.shards = (0..self.shards.len()).map(|_| AnchoredContactGrid::new(new_n)).collect();
        self.bp_valid = false;
        self.static_valid = false;
        let _ = old;
    }

    /// `resize`: мир уплотнён или загружен из снимка — массивы на частицу заново.
    pub fn resize(&mut self, new_n: usize) {
        self.n = new_n;
        self.contact_radius = vec![0.0; new_n];
        self.bp_x = vec![0.0; new_n];
        self.bp_y = vec![0.0; new_n];
        self.bp_sweep = vec![0.0; new_n];
        self.mark = vec![0; new_n];
        self.toi = vec![0.0; new_n];
        self.ccd_parent = vec![0; new_n];
        self.isl_m = vec![0.0; new_n];
        self.isl_x = vec![0.0; new_n];
        self.isl_y = vec![0.0; new_n];
        self.frc_x = vec![0.0; new_n];
        self.frc_y = vec![0.0; new_n];
        self.frc_w = vec![0.0; new_n];
        self.frc_s = vec![0.0; new_n];
        self.frc_z = vec![0; new_n];
        self.frc_stamp = vec![-1; new_n];
        self.damage = ContactDamageCandidates::new(new_n);
        self.start_deep = ContactDamageCandidates::new(new_n);
        self.on_bound = vec![false; new_n];
        self.vert_slot = vec![-1; new_n];
        self.verts = vec![0; new_n];
        self.prev_radius = vec![0.0; new_n];
        self.prev_bound = vec![false; new_n];
        self.prev_deg = vec![0; new_n];
        self.cfg_deg = vec![0; new_n + 1];
        self.moved_mark = vec![0; new_n];
        self.radius_minimum = ContactRadiusMinimum::new(new_n);
        self.shards = (0..self.shards.len()).map(|_| AnchoredContactGrid::new(new_n)).collect();
        self.lam.clear();
        self.bp_valid = false;
        self.static_valid = false;
    }

    /// `appendRestPairs`: пары покоя нового тела ([add] — в номерах тела, первая частица [n0]) — к прежним.
    pub fn append_rest_pairs(&mut self, add: &RestPairs, n0: usize) -> Arc<RestPairs> {
        let rp = self.rest.clone();
        let m0 = rp.other.len();
        let add_n = add.start.len() - 1;
        let mut start = vec![0i32; n0 + add_n + 1];
        let copy = rp.start.len().min(n0 + 1);
        start[..copy].copy_from_slice(&rp.start[..copy]);
        for i in rp.start.len()..=n0 {
            start[i] = m0 as i32;
        }
        for i in 1..=add_n {
            start[n0 + i] = m0 as i32 + add.start[i];
        }
        let total = m0 + add.other.len();
        let mut other = rp.other.clone();
        other.resize(total, 0);
        let mut mirror = rp.mirror.clone();
        mirror.resize(total, 0);
        let mut d2 = rp.d2.clone();
        d2.resize(total, 0.0);
        let mut dist = rp.dist.clone();
        dist.resize(total, 0.0);
        let mut ever = rp.ever.clone();
        ever.resize(total, false);
        for s in 0..add.other.len() {
            other[m0 + s] = add.other[s] + n0 as i32;
            mirror[m0 + s] = add.mirror[s] + m0 as i32;
            d2[m0 + s] = add.d2[s];
            dist[m0 + s] = add.dist[s];
            ever[m0 + s] = add.ever[s];
        }
        self.rest = Arc::new(RestPairs { start, other, mirror, d2, dist, ever });
        self.rest_state.resize(total, 0);
        self.rest.clone()
    }

    /// `attachSurface`: смежность и контур — из местной поверхности (строки постоянной ёмкости). В Kotlin это ссылки на
    /// массивы LocalSurface; здесь копия, и [update_surface] переносит строки задетых вершин (поверхность пишет только
    /// строку своей вершины, и все её пересчёты идут вместе с updateSurface). [start] — vertStart запечённой топологии.
    pub fn attach_surface(&mut self, surface: &LocalSurface, start: &[i32]) {
        self.adj_start = start.to_vec();
        self.adj = surface.adj.clone();
        self.adj_end = surface.adj_end.clone();
        self.bnd_start = start.to_vec();
        self.bnd_other = surface.boundary.clone();
        self.bnd_opp = surface.opposite.clone();
        self.bnd_end = surface.boundary_end.clone();
        self.surface_attached = true;
    }

    /// Строка вершины [v] — из поверхности (см. [attach_surface]).
    fn sync_surface_row(&mut self, surface: &LocalSurface, v: usize) {
        let from = self.adj_start[v] as usize;
        let to = surface.adj_end[v] as usize;
        self.adj[from..to].copy_from_slice(&surface.adj[from..to]);
        self.adj_end[v] = surface.adj_end[v];
        let bto = surface.boundary_end[v] as usize;
        self.bnd_other[from..bto].copy_from_slice(&surface.boundary[from..bto]);
        self.bnd_opp[from..bto].copy_from_slice(&surface.opposite[from..bto]);
        self.bnd_end[v] = surface.boundary_end[v];
    }

    /// `updateSurface`: контактная геометрия только у задетых клеток, множители не сбрасываются.
    pub fn update_surface(&mut self, surface: &LocalSurface, changed: &[i32], count: usize, ctx: &ContactCtx) {
        if self.surface_attached {
            for k in 0..count {
                self.sync_surface_row(surface, changed[k] as usize);
            }
        }
        for k in 0..count {
            let v = changed[k] as usize;
            let nc = ctx.no_contact;
            let active = surface.active[v] && !(v < nc.len() && nc[v]);
            if active != self.on_bound[v] {
                if active {
                    self.vert_slot[v] = self.vert_count as i32;
                    self.verts[self.vert_count] = v as i32;
                    self.vert_count += 1;
                } else {
                    let slot = self.vert_slot[v] as usize;
                    self.vert_count -= 1;
                    let last = self.verts[self.vert_count];
                    self.verts[slot] = last;
                    self.vert_slot[last as usize] = slot as i32;
                    self.vert_slot[v] = -1;
                }
                self.on_bound[v] = active;
            }
            self.contact_radius[v] = surface.radius[v];
            self.radius_minimum.update(v, self.contact_radius[v]);
            self.max_radius = crate::jmath::maxf(self.max_radius, self.contact_radius[v]);
            self.prev_radius[v] = self.contact_radius[v];
            self.prev_bound[v] = self.on_bound[v];
            self.prev_deg[v] = self.adj_end[v] - self.adj_start[v];
        }
        self.bp_margin = self.opt_bp_margin.abs() * self.max_radius;
        let old_cell = self.cell_size;
        self.cell_size = crate::jmath::maxf(2.0f32 * self.max_radius + self.bp_margin, 1e-6f32);
        self.bp_valid = false;
        if self.cell_size != old_cell || self.touches_static(changed, count, ctx) {
            self.static_valid = false;
        }
        let rp = self.rest.clone();
        for k in 0..count {
            let v = changed[k] as usize;
            for s in rp.start[v]..rp.start[v + 1] {
                let s = s as usize;
                let old = self.rest_state[s];
                if old == REST_NONE {
                    continue;
                }
                if old == REST_SPACING {
                    self.rest_spacing_n -= 1;
                } else {
                    self.rest_touch_n -= 1;
                }
                self.rest_state[s] = 0;
                self.rest_state[rp.mirror[s] as usize] = 0;
            }
        }
        for k in 0..count {
            let v = changed[k] as usize;
            if !self.on_bound[v] {
                continue;
            }
            for s in rp.start[v]..rp.start[v + 1] {
                let s = s as usize;
                let j = rp.other[s];
                let ju = j as usize;
                if !self.on_bound[ju] {
                    continue;
                }
                let rr = self.contact_radius[v] + self.contact_radius[ju];
                if rp.d2[s] >= rr * rr {
                    continue;
                }
                let mut linked = false;
                for a in self.adj_start[v]..self.adj_end[v] {
                    if self.adj[a as usize] == j {
                        linked = true;
                        break;
                    }
                }
                if linked || self.rest_state[s] != REST_NONE {
                    continue;
                }
                let state = if rp.ever[s] { REST_SPACING } else { REST_TOUCH };
                self.rest_state[s] = state;
                self.rest_state[rp.mirror[s] as usize] = state;
                if state == REST_SPACING {
                    self.rest_spacing_n += 1;
                } else {
                    self.rest_touch_n += 1;
                }
            }
        }
    }

    /// `touchesStatic`: задето ли обновление поверхности неподвижной сеткой.
    fn touches_static(&self, changed: &[i32], count: usize, ctx: &ContactCtx) -> bool {
        let m = match self.last_inv(ctx) {
            Some(m) => m,
            None => return true,
        };
        for k in 0..count {
            let v = changed[k] as usize;
            if v < self.in_static.len() && self.in_static[v] {
                return true;
            }
            if self.on_bound[v] && (v >= m.len().min(self.inv_mass_len_seen) || m[v] <= 0.0) {
                return true;
            }
        }
        false
    }
}

/// `maxContactRadius`: наибольший радиус касания клетки при любом контуре.
#[allow(clippy::too_many_arguments)]
pub fn max_contact_radius(
    n: usize,
    link_a: &[i32],
    link_b: &[i32],
    link_count: usize,
    radius: &[f32],
    contact_scale: f32,
    rest_x: &[f32],
    rest_y: &[f32],
    contact_seal: f32,
) -> Vec<f32> {
    let mut r: Vec<f32> = (0..n).map(|i| radius[i] * contact_scale).collect();
    for k in 0..link_count {
        let a = link_a[k] as usize;
        let b = link_b[k] as usize;
        let dx = rest_x[a] - rest_x[b];
        let dy = rest_y[a] - rest_y[b];
        let half = 0.5f32 * sqrtf(dx * dx + dy * dy) * contact_seal;
        if half > r[a] {
            r[a] = half;
        }
        if half > r[b] {
            r[b] = half;
        }
    }
    r
}

/// `bakeRestPairs`: все пары, перекрытые в позе покоя при наибольших радиусах [max_r]; порядок канонический.
pub fn bake_rest_pairs(n: usize, max_r: &[f32], rest_x: &[f32], rest_y: &[f32], ever_linked: &LinkedPairs) -> RestPairs {
    let mut gmax = 0.0f32;
    for i in 0..n {
        if max_r[i] > gmax {
            gmax = max_r[i];
        }
    }
    if gmax <= 0.0 {
        return RestPairs { start: vec![0; n + 1], other: Vec::new(), mirror: Vec::new(), d2: Vec::new(), dist: Vec::new(), ever: Vec::new() };
    }
    let cs = 2.0f32 * gmax;
    let ck = |x: i32, y: i32| -> i64 { ((x as i64) << 32) ^ (y as i64 & 0xffff_ffff) };
    let mut cells: HashMap<i64, Vec<i32>> = HashMap::with_capacity(n * 2);
    let mut cell_x = vec![0i32; n];
    let mut cell_y = vec![0i32; n];
    for i in 0..n {
        if max_r[i] <= 0.0 {
            continue;
        }
        cell_x[i] = (rest_x[i] / cs).floor() as i32;
        cell_y[i] = (rest_y[i] / cs).floor() as i32;
        cells.entry(ck(cell_x[i], cell_y[i])).or_default().push(i as i32);
    }
    let mut pa: Vec<i32> = Vec::with_capacity(1024);
    let mut pb: Vec<i32> = Vec::with_capacity(1024);
    for i in 0..n {
        if max_r[i] <= 0.0 {
            continue;
        }
        for ox in -1..=1 {
            for oy in -1..=1 {
                let bucket = match cells.get(&ck(cell_x[i].wrapping_add(ox), cell_y[i].wrapping_add(oy))) {
                    Some(b) => b,
                    None => continue,
                };
                for &j in bucket {
                    if j <= i as i32 {
                        continue;
                    }
                    let ju = j as usize;
                    let dx = rest_x[i] - rest_x[ju];
                    let dy = rest_y[i] - rest_y[ju];
                    let lim = (max_r[i] + max_r[ju]) * (1.0f32 + 1e-9f32);
                    if dx * dx + dy * dy >= lim * lim {
                        continue;
                    }
                    pa.push(i as i32);
                    pb.push(j);
                }
            }
        }
    }
    let pn = pa.len();
    {
        let mut keys: Vec<i64> = (0..pn).map(|p| ((pa[p] as i64) << 32) | pb[p] as i64).collect();
        keys.sort_unstable();
        for p in 0..pn {
            pa[p] = (keys[p] as u64 >> 32) as i32;
            pb[p] = keys[p] as i32;
        }
    }
    let mut start = vec![0i32; n + 1];
    for p in 0..pn {
        start[pa[p] as usize + 1] += 1;
        start[pb[p] as usize + 1] += 1;
    }
    for i in 0..n {
        start[i + 1] += start[i];
    }
    let mut fill = start.clone();
    let mut other = vec![0i32; 2 * pn];
    let mut mirror = vec![0i32; 2 * pn];
    let mut d2 = vec![0.0f32; 2 * pn];
    let mut dist = vec![0.0f32; 2 * pn];
    let mut ever = vec![false; 2 * pn];
    for p in 0..pn {
        let i = pa[p] as usize;
        let j = pb[p] as usize;
        let dx = rest_x[i] - rest_x[j];
        let dy = rest_y[i] - rest_y[j];
        let q = dx * dx + dy * dy;
        let e = ever_linked.contains((i as i64) * 1_000_003 + j as i64);
        let si = fill[i] as usize;
        fill[i] += 1;
        let sj = fill[j] as usize;
        fill[j] += 1;
        other[si] = j as i32;
        other[sj] = i as i32;
        mirror[si] = sj as i32;
        mirror[sj] = si as i32;
        d2[si] = q;
        d2[sj] = q;
        dist[si] = sqrtf(q);
        dist[sj] = dist[si];
        ever[si] = e;
        ever[sj] = e;
    }
    RestPairs { start, other, mirror, d2, dist, ever }
}
