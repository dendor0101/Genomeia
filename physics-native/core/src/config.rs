//! Параметры запуска, которые Kotlin разбирает из `PhysicsConfig` (ключи RB_*, CT_*, FIBER_THICKEN) и
//! `genomeia.substeps`. Rust не читает строковые ключи: Kotlin отдаёт уже РАЗОБРАННЫЕ значения массивом
//! double в фиксированном порядке ([`Config::LAYOUT_LEN`] чисел, порядок — `RustWorld.configLayout`). Так оба
//! решателя гарантированно стартуют с одних чисел. Физика во float (с 21–22.11): float → double → float точен.

/// Параметры контактного решателя (`config/ContactConfig.kt`).
#[derive(Clone, Debug)]
pub struct ContactConfig {
    pub contact_omega_h: f32,
    pub bp_margin: f32,
    pub ct_sweep_flip: bool,
    pub ccd_always: bool,
    pub ccd_gate: f32,
    pub pierce_tear: bool,
    pub pierce_depth: f32,
    pub rest_full: bool,
    pub bp_check: bool,
    pub xpbd_warm_start: bool,
    pub ab_legacy: bool,
    pub contact_compliance: f32,
    pub contact_omega_pair: f32,
    pub contact_seal: f32,
}

/// Разобранные параметры одного мира.
#[derive(Clone, Debug)]
pub struct Config {
    pub substeps: i32,
    pub workers: i32,
    pub parallel_min: i32,
    pub contact_max_step: f32,
    pub link_max_step: f32,
    pub maxlen_early: bool,
    pub drag_accel: f32,
    pub drag_speed_limit: f32,
    pub kill_on_deep_overlap: bool,
    // Начальные значения изменяемых полей BodyState (часть из них входит в снимок).
    pub probe_on: bool,
    pub sleep_on: bool,
    pub pierce_each_iter: bool,
    pub muscle_contraction: f32,
    pub muscle_rate_contract: f32,
    pub muscle_rate_relax: f32,
    pub gait_period: i32,
    pub gait_duty: f32,
    pub muscle_touch: i32,
    pub dbg_seal_on: bool,
    pub sweep_flip: i32,
    pub tendon_tear: f32,
    pub link_tear_strain: f32,
    pub tangent_drag: f32,
    pub areas_on: bool,
    pub pressure_burst: f32,
    pub membrane_tear_strain: f32,
    pub crush_burst_on: bool,
    pub trap_kill_ticks: i32,
    pub membrane_pass_tear: bool,
    pub trap_kill_folds: bool,
    pub trap_crash: i32,
    pub watch_cell: i32,
    pub contacts: ContactConfig,
    pub cancel_spin_on: bool,
    pub tearing_on: bool,
    pub bones_rigid: bool,
    pub contacts_on: bool,
    pub deep_kill_on: bool,
    /// Показатель утолщения мышцы поперёк волокна k (`FIBER_THICKEN`, config/FiberParameters.kt).
    pub fiber_thicken: f32,
    /// Отладочные ключи Kotlin, меняющие физику (BodyState.dbgDragOff, dbgIsoDragOff, dbgNoClamp).
    pub dbg_drag_off: bool,
    pub dbg_iso_drag_off: bool,
    pub dbg_no_clamp: bool,
    /// Среда мира (BodyState.environment): стенды пишут её и прямо в состояние.
    pub environment: crate::environment::WorldEnvironment,
    /// Режим островов (`ISLANDS`: 0 выкл., 1 всегда, 2 по замеру) и откат каждого острова (`ISLANDS_ROLLBACK_ALL`,
    /// проверка снимка). Исполнение: биты от них не зависят.
    pub islands_mode: i32,
    pub islands_rollback_all: bool,
    /// Порядок по цветам (`COLOR_ORDER`, `BodyState.colorOrderOn`, закон с 27.11): связи, площади, изгибы и створки
    /// набора решаются цвет за цветом (solver/color_order.rs). Меняет биты: false — прежний порядок по номерам.
    pub color_order_on: bool,
}

impl Config {
    pub const LAYOUT_LEN: usize = 65;

    /// Разбор массива из `RustWorld.configLayout`. Ветки, которых перенос не умеет (отладочные замеры,
    /// охота за проникновением), отвергаются сразу — молча идти другим путём нельзя.
    pub fn from_slice(v: &[f64]) -> Config {
        assert_eq!(v.len(), Self::LAYOUT_LEN, "длина массива параметров не та: {}", v.len());
        let b = |k: usize| v[k] != 0.0;
        let i = |k: usize| v[k] as i32;
        let f = |k: usize| v[k] as f32;
        let c = Config {
            substeps: i(0),
            workers: i(1),
            parallel_min: i(2),
            contact_max_step: f(3),
            link_max_step: f(4),
            maxlen_early: b(5),
            drag_accel: f(6),
            drag_speed_limit: f(7),
            kill_on_deep_overlap: b(8),
            probe_on: b(9),
            sleep_on: b(10),
            pierce_each_iter: b(11),
            muscle_contraction: f(12),
            muscle_rate_contract: f(13),
            muscle_rate_relax: f(14),
            gait_period: i(15),
            gait_duty: f(16),
            muscle_touch: i(17),
            dbg_seal_on: b(18),
            sweep_flip: i(19),
            tendon_tear: f(20),
            link_tear_strain: f(21),
            tangent_drag: f(22),
            areas_on: b(23),
            pressure_burst: f(24),
            membrane_tear_strain: f(25),
            crush_burst_on: b(26),
            trap_kill_ticks: i(27),
            membrane_pass_tear: b(28),
            trap_kill_folds: b(29),
            trap_crash: i(30),
            watch_cell: i(31),
            contacts: ContactConfig {
                contact_omega_h: f(32),
                bp_margin: f(33),
                ct_sweep_flip: b(34),
                ccd_always: b(35),
                ccd_gate: f(36),
                pierce_tear: b(37),
                pierce_depth: f(38),
                rest_full: b(39),
                bp_check: b(40),
                xpbd_warm_start: b(41),
                ab_legacy: b(42),
                contact_compliance: f(43),
                contact_omega_pair: f(44),
                contact_seal: f(45),
            },
            cancel_spin_on: b(46),
            tearing_on: b(47),
            bones_rigid: b(48),
            contacts_on: b(49),
            deep_kill_on: b(50),
            fiber_thicken: f(51),
            dbg_drag_off: b(52),
            dbg_iso_drag_off: b(53),
            dbg_no_clamp: b(54),
            environment: crate::environment::WorldEnvironment {
                gravity_y: f(55),
                medium_drag: f(56),
                normal_drag: f(57),
                normal_drag_quadratic: f(58),
                soft_compliance: f(59),
                area_compliance: f(60),
                contact_friction: f(61),
            },
            islands_mode: i(62),
            islands_rollback_all: b(63),
            color_order_on: b(64),
        };
        assert!(c.substeps >= 1, "подшагов должно быть не меньше одного");
        assert!(!c.dbg_seal_on, "RB_SEAL (замер щелей) в переносе на Rust не поддержан");
        assert!(c.trap_kill_ticks < 0, "RB_TRAP (отлов застрявших) в переносе на Rust не поддержан");
        assert!(c.trap_crash == 0, "RB_TRAP_CRASH в переносе на Rust не поддержан");
        assert!(c.watch_cell < 0, "RB_WATCH в переносе на Rust не поддержан");
        assert!(!c.membrane_pass_tear, "RB_PASS в переносе на Rust не поддержан");
        assert!(!c.pierce_each_iter, "RB_PIERCE_ITER в переносе на Rust не поддержан");
        assert!(
            c.fiber_thicken == 0.0 || c.fiber_thicken == 0.5 || c.fiber_thicken == 1.0,
            "FIBER_THICKEN вне 0, 0.5 и 1 (StrictMath.pow) в переносе на Rust пока не поддержан"
        );
        c
    }
}
