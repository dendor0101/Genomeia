//! Постоянные решателя — копия `physics/config/*Parameters.kt` и постоянных из файлов решателя (значения и типы те
//! же: Float в Kotlin — f32 здесь, Int — i32). Пояснения, замеры и история каждой постоянной — в комментариях
//! Kotlin: этот файл держит только числа, чтобы не разойтись с эталоном при правке. Поменял число там — поменяй и
//! здесь (тень это поймает). Число подшагов и разобранные ключи RB_*/CT_* — в `config.rs`.

#![allow(dead_code)]

// --- MaterialParameters.kt ---
pub const FLAP_COMPLIANCE: f32 = 1.0e-4;
pub const SOFT_COMPLIANCE: f32 = 1.0e-5;
pub const AREA_COMPLIANCE: f32 = 1.0e-6;
pub const AREA_COMPLIANCE_INVERTED: f32 = 0.0;
pub const AREA_MAX_STEP: f32 = 0.2;
pub const SLOW_CONSTRAINT_PERIOD: i32 = 1;
pub const AREA_SMOOTH_RAMP: bool = true;
pub const BONE_MAX_STEP: f32 = 0.5;
pub const LINK_MAX_STRETCH: f32 = 1.05;
pub const LIMIT_NEAR_HI2: f32 = 0.97 * 0.97;
pub const LIMIT_NEAR_LO2: f32 = 1.5 * 1.5;
pub const BEND_LEVELS: [f32; 7] = [-1.0, 1.0e-3, 1.0e-4, 1.0e-5, 1.0e-6, 1.0e-7, 0.0];

// --- MotionParameters.kt ---
pub const DRAG_MATCH_WEIGHT: f32 = 1.0;
pub const DRAG_TAU: f32 = 0.2;
pub const MAX_DRAG_SPEED: f32 = 4.0;
pub const DRAG_COMPLIANCE: f32 = 1e-5;
pub const MUSCLE_CONTRACTION: f32 = 0.50;
pub const MUSCLE_TOUCH: i32 = 1;
pub const GAIT_PERIOD: i32 = 8;
pub const GAIT_DUTY: f32 = 0.45;
pub const MUSCLE_RATE_CONTRACT: f32 = 12.0;
pub const MUSCLE_MAX_PULL: f32 = 0.1;
pub const BIRTH_GROW_TICKS: i32 = 10;
pub const BIRTH_MIN_FRACTION: f32 = 0.1;
pub const BIRTH_MAX_PULL: f32 = 0.1;
pub const DIVISION_START_FRACTION: f32 = 0.1;
pub const MUSCLE_RATE_RELAX: f32 = 8.546;

// --- MediumParameters.kt ---
pub const GRAVITY: f32 = 0.0;
pub const GROUND_Y: f32 = -100.0;
pub const GROUND_FRICTION: f32 = 0.0;
pub const GROUND_RESTITUTION: f32 = 0.0;
pub const MEDIUM_DRAG: f32 = 0.0921;
pub const NORMAL_DRAG: f32 = 43.33;
pub const NORMAL_DRAG_QUADRATIC: f32 = 71.8;
pub const TANGENT_DRAG: f32 = 0.0;
pub const FLOW_ENTRAIN: f32 = 2.0;
pub const FLOW_MASS: f32 = 0.050;
pub const FLOW_DECAY: f32 = 1.168;
pub const VISCOSITY: f32 = 10.0;

// --- DamageParameters.kt ---
pub const LINK_TEAR_STRAIN: f32 = 0.45;
pub const LINK_CRUSH_RATIO: f32 = 0.35;
pub const TEAR_IMMEDIATE: bool = true;
pub const PRESSURE_BURST_LINKS: f32 = 12.0;
pub const CRUSH_BURST: bool = true;
pub const TRAP_KILL_TICKS: i32 = -1;
pub const PROBE_UNCOVERED: bool = true;
pub const PROBE_ANGLE_EPS: f32 = 0.05;
pub const MEMBRANE_PASS_TEAR: bool = false;
pub const TENDON_TEAR_FACTOR: f32 = 1.5;
pub const TRAP_KILL_FOLDS: bool = true;
pub const MEMBRANE_TEAR_STRAIN: f32 = -1.0;
pub const PLASTIC_RATE: f32 = 1.0;
pub const PLASTIC_YIELD: f32 = 0.15;
pub const PLASTIC_HOLD: i32 = 90;
pub const TEARING_DEFAULT: bool = true;
pub const INVERTED_TRIANGLE_HOLD_TICKS: i32 = 10;
pub const KILL_DEPTH: f32 = 1.0;
pub const KILL_ON_DEEP_OVERLAP: bool = true;

// --- ContactParameters.kt (CONTACT_MAX_STEP зависит от подшагов — в config.rs) ---
pub const CONTACTS_ON: bool = true;
pub const CONTACT_SCALE: f32 = 0.6;
pub const CCD_CORE: f32 = 0.4;
pub const CONTACT_RESTITUTION: f32 = 0.1;
pub const CONTACT_FRICTION: f32 = 0.3;
pub const MAX_SPEED_CELLS_PER_TICK: f32 = 8.0;
pub const CONTACT_BEFORE_INTEGRATE: bool = false;

// --- SleepParameters.kt ---
pub const SLEEP_ON: bool = true;
pub const SLEEP_TICKS: i32 = 60;
pub const SLEEP_DRIFT_CELLS: f32 = 0.06;
pub const WAKE_DRIFT_CELLS: f32 = 0.02;
pub const SLEEP_MUSCLE_EPS: f32 = 1e-4;
pub const GROUND_WAKE_DRIFT_CELLS: f32 = 0.25;

// --- SolverParameters.kt (SUBSTEPS — в config.rs) ---
pub const DBG_STAGES: usize = 13;
pub const SPIN_MIN_ARM: f32 = 1e-3;
pub const DT: f32 = 1.0 / 30.0;
pub const CANCEL_INTERNAL_DRIFT: bool = false;
pub const CANCEL_INTERNAL_SPIN: bool = true;
pub const SOLVER_ITERS: i32 = 1;
pub const SWEEP_FLIP: i32 = 2;

// --- FiberParameters.kt ---
pub const FIBER_THICKEN: f32 = 0.0;

// --- прочие места ---
pub const COMPACT_DEAD_SHARE: f32 = 0.25; // PhysicsWorld.kt
pub const FREE_POOL_BLOCK: i32 = 256; // PhysicsWorld.kt
pub const CONSTRAINT_BLOCK_SIZE: i32 = 500; // BlockedIteration.kt
pub const BLOCK_STAGE_START: i32 = 0;
pub const BLOCK_STAGE_LIMITS_EARLY: i32 = 1;
pub const BLOCK_STAGE_LIMITS_LATE: i32 = 2;
pub const RELAXED_MAX_STRETCH: f32 = 1.25; // ConstraintSolver.kt
pub const LERP_PARALLEL_MIN: i32 = 4096; // MuscleDriver.kt
pub const CELL_SCALE_SNAP: f32 = 1e-4; // MuscleDriver.kt
pub const PASS_CHUNK: i32 = 1024; // PassChunks.kt
pub const PARALLEL_MIN_PARTICLES: i32 = 4000; // PhysicsWorkers.kt
pub const WAVE_WINDOW: i32 = 1024; // WaveOrder.kt
pub const TEMP_BRIDGE_REACH: i32 = 1024; // TempLinks.kt
pub const BIRTH_SEARCH_RINGS: i32 = 3; // BodyGrowth.kt
pub const BIRTH_PUSH_ITERATIONS: i32 = 8;
pub const BIRTH_CLEAR: f32 = 1.1;
pub const SNAPSHOT_MAGIC: i32 = 0x50485753; // WorldSnapshot.kt
pub const SNAPSHOT_VERSION: i32 = 8;
pub const SNAPSHOT_OLDEST_READABLE: i32 = 3;
