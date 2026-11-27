//! JNI-обёртка ядра физики: нативные методы класса `io.github.some_example_name.physics.rust.RustPhysics`.
//!
//! ЕДИНСТВЕННОЕ место с unsafe-вызовами JVM. Правила:
//! - паника ядра не должна пересечь границу FFI (это неопределённое поведение): каждый вход оборачивается
//!   [`guard`], который превращает панику в `IllegalStateException`;
//! - число вызовов за тик минимально: шаг мира — один вызов (`worldStep`), остальное — редкие правки между тиками
//!   (`worldCall` с кодом операции) и чтение результатов;
//! - числа физики — float (как в Kotlin с 22.11); double на границе — только у `worldCall`, где float в double и
//!   обратно переходит без потерь.
//!
//! Мир живёт в куче Rust; Kotlin держит его адрес (`jlong`) и обязан звать `worldDestroy`. Один мир — один поток за
//! раз (поток симуляции), как и мир на Kotlin.

use std::panic::{AssertUnwindSafe, catch_unwind};

use genomeia_physics::config::Config;
use genomeia_physics::definition::{BodyDefinition, WorldScale};
use genomeia_physics::world::PhysicsWorld;
use jni::JNIEnv;
use jni::objects::{JBooleanArray, JByteArray, JClass, JDoubleArray, JFloatArray, JIntArray, JString};
use jni::sys::{jboolean, jbyteArray, jdouble, jfloat, jint, jlong};

/// Выполнить `body`, а панику превратить в исключение Java и вернуть `fallback`.
fn guard<T>(env: &mut JNIEnv, fallback: T, body: impl FnOnce(&mut JNIEnv) -> T) -> T {
    match catch_unwind(AssertUnwindSafe(|| body(env))) {
        Ok(v) => v,
        Err(panic) => {
            let msg = if let Some(s) = panic.downcast_ref::<&str>() {
                (*s).to_string()
            } else if let Some(s) = panic.downcast_ref::<String>() {
                s.clone()
            } else {
                "паника в ядре физики Rust".to_string()
            };
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new("java/lang/IllegalStateException", msg);
            }
            fallback
        }
    }
}

/// Мир по адресу из Kotlin.
///
/// # Safety
/// `handle` выдан `worldCreate` и ещё не освобождён `worldDestroy`; один поток за раз.
unsafe fn world<'a>(handle: jlong) -> &'a mut PhysicsWorld {
    assert!(handle != 0, "мир физики Rust уже закрыт");
    // SAFETY: договор функции — адрес живого Box<PhysicsWorld>, единственный пользователь.
    unsafe { &mut *(handle as *mut PhysicsWorld) }
}

fn ints(env: &mut JNIEnv, a: &JIntArray) -> Vec<i32> {
    let n = env.get_array_length(a).expect("длина int[]") as usize;
    let mut v = vec![0i32; n];
    env.get_int_array_region(a, 0, &mut v).expect("int[]");
    v
}

fn floats(env: &mut JNIEnv, a: &JFloatArray) -> Vec<f32> {
    let n = env.get_array_length(a).expect("длина float[]") as usize;
    let mut v = vec![0f32; n];
    env.get_float_array_region(a, 0, &mut v).expect("float[]");
    v
}

fn doubles(env: &mut JNIEnv, a: &JDoubleArray) -> Vec<f64> {
    let n = env.get_array_length(a).expect("длина double[]") as usize;
    let mut v = vec![0f64; n];
    env.get_double_array_region(a, 0, &mut v).expect("double[]");
    v
}

fn booleans(env: &mut JNIEnv, a: &JBooleanArray) -> Option<Vec<bool>> {
    if a.is_null() {
        return None;
    }
    let n = env.get_array_length(a).expect("длина boolean[]") as usize;
    let mut v = vec![0 as jboolean; n];
    env.get_boolean_array_region(a, 0, &mut v).expect("boolean[]");
    Some(v.into_iter().map(|b| b != 0).collect())
}

fn clusters(flat: &[i32], sizes: &[i32]) -> Vec<Vec<i32>> {
    let mut out = Vec::with_capacity(sizes.len());
    let mut at = 0usize;
    for &s in sizes {
        let s = s as usize;
        out.push(flat[at..at + s].to_vec());
        at += s;
    }
    out
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_some_1example_1name_physics_rust_RustPhysics_abiVersion(_env: JNIEnv, _class: JClass) -> jint {
    genomeia_physics::ABI_VERSION
}

/// Проверка переноса fdlibm (jmath): `op` 0 — sin, 1 — cos, 2 — acos по каждому `input[k]` в `out[k]`; сверяется со
/// StrictMath в RustSmokeTest. Не для тика.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_some_1example_1name_physics_rust_RustPhysics_strictBatch(
    mut env: JNIEnv,
    _class: JClass,
    op: jint,
    input: JDoubleArray,
    out: JDoubleArray,
) {
    guard(&mut env, (), |env| {
        use genomeia_physics::jmath::{strict_acos, strict_cos, strict_sin};
        let f: fn(f64) -> f64 = match op {
            0 => strict_sin,
            1 => strict_cos,
            2 => strict_acos,
            _ => panic!("strictBatch: нет операции {op}"),
        };
        let v: Vec<f64> = doubles(env, &input).into_iter().map(f).collect();
        env.set_double_array_region(&out, 0, &v).expect("double[] out");
    })
}

/// Новый мир: разобранные параметры (`RustWorld.configLayout`) и масштаб мира (`hasScale` = false — из первого тела).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_some_1example_1name_physics_rust_RustPhysics_worldCreate(
    mut env: JNIEnv,
    _class: JClass,
    config: JDoubleArray,
    has_scale: jboolean,
    mean_link: jfloat,
    mean_area: jfloat,
) -> jlong {
    guard(&mut env, 0, |env| {
        let cfg = Config::from_slice(&doubles(env, &config));
        let scale = if has_scale != 0 { Some(WorldScale { mean_link_length: mean_link, mean_cell_area: mean_area }) } else { None };
        Box::into_raw(Box::new(PhysicsWorld::new(cfg, scale))) as jlong
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_some_1example_1name_physics_rust_RustPhysics_worldDestroy(mut env: JNIEnv, _class: JClass, handle: jlong) {
    guard(&mut env, (), |_| {
        if handle != 0 {
            // SAFETY: адрес выдан worldCreate (Box::into_raw) и освобождается один раз.
            drop(unsafe { Box::from_raw(handle as *mut PhysicsWorld) });
        }
    })
}

/// Загрузить (`mode` 0 — `load`) или добавить (`mode` 1 — `addBody`) тело. Кластеры — плоским массивом и размерами.
/// Возвращает номер тела; -1 и исключение — тело не принято.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_some_1example_1name_physics_rust_RustPhysics_worldAddBody<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    mode: jint,
    x: JFloatArray<'l>,
    y: JFloatArray<'l>,
    radius: JFloatArray<'l>,
    is_bone: JBooleanArray<'l>,
    is_muscle: JBooleanArray<'l>,
    link_a: JIntArray<'l>,
    link_b: JIntArray<'l>,
    tri_a: JIntArray<'l>,
    tri_b: JIntArray<'l>,
    tri_c: JIntArray<'l>,
    tri_rest_area2: JFloatArray<'l>,
    bone_flat: JIntArray<'l>,
    bone_sizes: JIntArray<'l>,
    muscle_flat: JIntArray<'l>,
    muscle_sizes: JIntArray<'l>,
    scale: jfloat,
    retire_start: JIntArray<'l>,
    retire_cells: JIntArray<'l>,
    born: JBooleanArray<'l>,
    bone_on: JBooleanArray<'l>,
) -> jint {
    guard(&mut env, -1, |env| {
        let def = BodyDefinition {
            x: floats(env, &x),
            y: floats(env, &y),
            radius: floats(env, &radius),
            is_bone: booleans(env, &is_bone).expect("isBone"),
            is_muscle: booleans(env, &is_muscle).expect("isMuscle"),
            link_a: ints(env, &link_a),
            link_b: ints(env, &link_b),
            tri_a: ints(env, &tri_a),
            tri_b: ints(env, &tri_b),
            tri_c: ints(env, &tri_c),
            tri_rest_area2: floats(env, &tri_rest_area2),
            bone_clusters: clusters(&ints(env, &bone_flat), &ints(env, &bone_sizes)),
            muscle_clusters: clusters(&ints(env, &muscle_flat), &ints(env, &muscle_sizes)),
            scale,
            link_retire_start: ints(env, &retire_start),
            link_retire_cells: ints(env, &retire_cells),
        };
        let born = booleans(env, &born);
        let bone_on = booleans(env, &bone_on);
        // SAFETY: handle — живой мир (договор RustWorld).
        let w = unsafe { world(handle) };
        match mode {
            0 => {
                w.load(&def);
                0
            }
            _ => w.add_body(&def, born.as_deref(), bone_on.as_deref()),
        }
    })
}

/// Снимок мира (`PhysicsWorld.write`): [material] — отпечаток материала Kotlin; байты — те же, что пишет Kotlin.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_some_1example_1name_physics_rust_RustPhysics_worldSnapshotWrite<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    material: JString<'l>,
) -> jbyteArray {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let key: String = env.get_string(&material).expect("материал").into();
        // SAFETY: handle — живой мир (договор RustWorld).
        let w = unsafe { world(handle) };
        let bytes = w.write(&key);
        env.byte_array_from_slice(&bytes).expect("byte[]").into_raw()
    })
}

/// Прочитать снимок в новый мир (`PhysicsWorld.read`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_some_1example_1name_physics_rust_RustPhysics_worldSnapshotRead<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    data: JByteArray<'l>,
    material: JString<'l>,
) {
    guard(&mut env, (), |env| {
        let key: String = env.get_string(&material).expect("материал").into();
        let bytes = env.convert_byte_array(&data).expect("byte[]");
        // SAFETY: handle — живой мир (договор RustWorld).
        let w = unsafe { world(handle) };
        w.read(&bytes, &key);
    })
}

/// Свободная частица (`addFree`; `addFixed` — масса +∞, скорость 0, сопротивление 1). Возвращает номер частицы.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_io_github_some_1example_1name_physics_rust_RustPhysics_worldAddFree(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    x: jfloat,
    y: jfloat,
    radius: jfloat,
    vx: jfloat,
    vy: jfloat,
    mass: jfloat,
    drag: jfloat,
) -> jint {
    guard(&mut env, -1, |_| {
        // SAFETY: handle — живой мир (договор RustWorld).
        let w = unsafe { world(handle) };
        w.add_free(x, y, radius, vx, vy, mass, drag)
    })
}

/// Изменяемые параметры решателя из BodyState Kotlin (тень): переключатели и параметры, которые стенды крутят прямо в
/// состоянии; мышь и номер тика.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_some_1example_1name_physics_rust_RustPhysics_worldSyncParams(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    params: JDoubleArray,
    mouse_x: jfloat,
    mouse_y: jfloat,
    play_tick: jint,
) {
    guard(&mut env, (), |env| {
        let p = doubles(env, &params);
        let c = Config::from_slice(&p);
        // SAFETY: handle — живой мир.
        let st = &mut unsafe { world(handle) }.state;
        st.probe_on = c.probe_on;
        st.muscle_contraction = c.muscle_contraction;
        st.muscle_rate_contract = c.muscle_rate_contract;
        st.muscle_rate_relax = c.muscle_rate_relax;
        st.gait_period = c.gait_period;
        st.gait_duty = c.gait_duty;
        st.muscle_touch = c.muscle_touch;
        st.sweep_flip = c.sweep_flip;
        st.tendon_tear = c.tendon_tear;
        st.link_tear_strain = c.link_tear_strain;
        st.tangent_drag = c.tangent_drag;
        st.areas_on = c.areas_on;
        st.pressure_burst = c.pressure_burst;
        st.membrane_tear_strain = c.membrane_tear_strain;
        st.crush_burst_on = c.crush_burst_on;
        st.trap_kill_folds = c.trap_kill_folds;
        st.cancel_spin_on = c.cancel_spin_on;
        st.tearing_on = c.tearing_on;
        st.bones_rigid = c.bones_rigid;
        st.contacts_on = c.contacts_on;
        if st.deep_kill_on != c.deep_kill_on {
            st.deep_kill_on = c.deep_kill_on;
            if let Some(ct) = st.contacts.as_mut() {
                ct.kill_on_deep = c.deep_kill_on;
            }
        }
        st.sleep.on = c.sleep_on;
        st.dbg_drag_off = c.dbg_drag_off;
        st.dbg_iso_drag_off = c.dbg_iso_drag_off;
        st.dbg_no_clamp = c.dbg_no_clamp;
        st.environment = c.environment;
        st.mouse_x = mouse_x;
        st.mouse_y = mouse_y;
        st.play_tick = play_tick;
    })
}

/// Один тик мира. ЕДИНСТВЕННЫЙ вызов JNI на тик в установившемся режиме.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_some_1example_1name_physics_rust_RustPhysics_worldStep(mut env: JNIEnv, _class: JClass, handle: jlong, dt: jfloat) {
    guard(&mut env, (), |_| {
        // SAFETY: handle — живой мир.
        unsafe { world(handle) }.step(dt);
    })
}

/// Отпечаток состояния для сверки с Kotlin (тень и тесты).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_some_1example_1name_physics_rust_RustPhysics_worldDigest(mut env: JNIEnv, _class: JClass, handle: jlong) -> jbyteArray {
    guard(&mut env, std::ptr::null_mut(), |env| {
        // SAFETY: handle — живой мир.
        let bytes = genomeia_physics::digest::digest(&unsafe { world(handle) }.state);
        let arr: JByteArray = env.byte_array_from_slice(&bytes).expect("byte[]");
        arr.into_raw()
    })
}

/// Скопировать массив частиц мира в `out`: 0 — x, 1 — y, 2 — vx, 3 — vy.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_some_1example_1name_physics_rust_RustPhysics_worldRead(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    which: jint,
    out: JFloatArray,
) {
    guard(&mut env, (), |env| {
        // SAFETY: handle — живой мир.
        let st = &unsafe { world(handle) }.state;
        let src = match which {
            0 => &st.px,
            1 => &st.py,
            2 => &st.vx,
            3 => &st.vy,
            // Зеркало для игры (PhysicsWorld с физикой на Rust): то, что мост читает каждый тик.
            4 => &st.cell_scale,
            5 => &st.cell_scale_target,
            6 => &st.cell_softness,
            7 => &st.cell_grip,
            8 => &st.radius,
            _ => panic!("worldRead: неизвестный массив {which}"),
        };
        env.set_float_array_region(&out, 0, &src[..st.n]).expect("set float[]");
    })
}

/// Зеркало для игры: признаки частиц — 0 cellDead, 1 cellUnborn, 2 cellNoContact, 3 «организм частицы спит»
/// (PhysicsWorld.isAsleep; игра спрашивает его из потока рисования — зеркало, а не вызов в мир) (первые n).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_some_1example_1name_physics_rust_RustPhysics_worldReadBools(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    which: jint,
    out: JBooleanArray,
) {
    guard(&mut env, (), |env| {
        // SAFETY: handle — живой мир.
        let st = &unsafe { world(handle) }.state;
        let bytes: Vec<jboolean> = match which {
            0 => st.cell_dead[..st.n].iter().map(|&v| v as jboolean).collect(),
            1 => st.cell_unborn[..st.n].iter().map(|&v| v as jboolean).collect(),
            2 => st.cell_no_contact[..st.n].iter().map(|&v| v as jboolean).collect(),
            3 => (0..st.n).map(|i| st.sleep.is_asleep(st.organism_of[i]) as jboolean).collect(),
            _ => panic!("worldReadBools: неизвестный массив {which}"),
        };
        env.set_boolean_array_region(&out, 0, &bytes).expect("set boolean[]");
    })
}

/// Зеркало для игры: 0 boneOf (первые n).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_some_1example_1name_physics_rust_RustPhysics_worldReadInts(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    which: jint,
    out: JIntArray,
) {
    guard(&mut env, (), |env| {
        // SAFETY: handle — живой мир.
        let st = &unsafe { world(handle) }.state;
        let src = match which {
            0 => &st.bone_of,
            _ => panic!("worldReadInts: неизвестный массив {which}"),
        };
        env.set_int_array_region(&out, 0, &src[..st.n]).expect("set int[]");
    })
}

/// Зеркало для игры, одна частица [i] после правки между тиками: в outF — px, py, vx, vy, cellScale,
/// cellScaleTarget, cellSoftness, cellGrip, radius; в outI — cellDead, cellUnborn, cellNoContact, boneOf,
/// cellScalesOn, cellScalesMoving.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_some_1example_1name_physics_rust_RustPhysics_worldReadCell(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    i: jint,
    out_f: JFloatArray,
    out_i: JIntArray,
) {
    guard(&mut env, (), |env| {
        // SAFETY: handle — живой мир.
        let st = &unsafe { world(handle) }.state;
        let k = i as usize;
        let f = [
            st.px[k], st.py[k], st.vx[k], st.vy[k], st.cell_scale[k], st.cell_scale_target[k], st.cell_softness[k],
            st.cell_grip[k], st.radius[k],
        ];
        let n = [
            st.cell_dead[k] as i32, st.cell_unborn[k] as i32, st.cell_no_contact[k] as i32, st.bone_of[k],
            st.cell_scales_on as i32, st.cell_scales_moving as i32,
        ];
        env.set_float_array_region(&out_f, 0, &f).expect("set float[]");
        env.set_int_array_region(&out_i, 0, &n).expect("set int[]");
    })
}

/// Тень: перенести массив, поправленный стендом прямо в состоянии Kotlin.
/// 0 px, 1 py, 2 prevX, 3 prevY, 4 vx, 5 vy, 6 invMass, 7 matchWeight, 8 muscleActivation, 9 muscleActivationPrev,
/// 10 muscleTarget, 11 muscleScaleOf, 12 flowVX, 13 flowVY.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_some_1example_1name_physics_rust_RustPhysics_worldAdopt(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    which: jint,
    values: JFloatArray,
) {
    guard(&mut env, (), |env| {
        let v = floats(env, &values);
        // SAFETY: handle — живой мир.
        let st = &mut unsafe { world(handle) }.state;
        let dst: &mut Vec<f32> = match which {
            0 => &mut st.px,
            1 => &mut st.py,
            2 => &mut st.prev_x,
            3 => &mut st.prev_y,
            4 => &mut st.vx,
            5 => &mut st.vy,
            6 => &mut st.inv_mass,
            7 => &mut st.match_weight,
            8 => &mut st.muscle_activation,
            9 => &mut st.muscle_activation_prev,
            10 => &mut st.muscle_target,
            11 => &mut st.muscle_scale_of,
            12 => &mut st.flow_vx,
            13 => &mut st.flow_vy,
            14 => &mut st.rest_inv_mass,
            // Физика на Rust в игре: цели мышц клеток, которые мост пишет прямо в зеркало (WorldPhysics.syncOrganismFast).
            15 => &mut st.cell_scale_target,
            _ => panic!("worldAdopt: неизвестный массив {which}"),
        };
        if dst.len() < v.len() {
            dst.resize(v.len(), 0.0);
        }
        dst[..v.len()].copy_from_slice(&v);
    })
}

/// Правки и вопросы между тиками: один вход с кодом операции (`RustWorld`), чтобы граница JNI оставалась узкой.
/// Ответ — число (bool — 0/1). Числа float приходят в double и обратно без потерь.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_io_github_some_1example_1name_physics_rust_RustPhysics_worldCall(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    op: jint,
    i: jint,
    j: jint,
    a: jdouble,
    b: jdouble,
    c: jdouble,
    d: jdouble,
) -> jdouble {
    guard(&mut env, f64::NAN, |_| {
        // SAFETY: handle — живой мир.
        let w = unsafe { world(handle) };
        call(w, op, i, j, a, b, c, d)
    })
}

#[allow(clippy::too_many_arguments)]
fn call(w: &mut PhysicsWorld, op: i32, i: i32, j: i32, a: f64, b: f64, c: f64, d: f64) -> f64 {
    let iu = i as usize;
    let ju = j as usize;
    let (af, bf, cf, df) = (a as f32, b as f32, c as f32, d as f32);
    let yes = |v: bool| if v { 1.0 } else { 0.0 };
    match op {
        1 => w.set_muscle_target(iu, af),
        2 => w.set_all_muscle_targets(af),
        3 => w.set_cell_scale(iu, af),
        4 => w.set_softness(iu, af),
        5 => w.set_contactless(iu, a != 0.0),
        6 => w.set_grip(iu, af),
        7 => w.set_home(iu, af, bf, cf),
        8 => w.set_contact_group(iu, j),
        9 => return w.add_joint(i, j, af, bf, cf, d != 0.0) as f64,
        10 => w.remove_joint(iu),
        11 => w.set_joint_compliance(iu, af),
        12 => w.set_mass(iu, af),
        13 => w.set_drag(iu, af),
        14 => w.set_deep_kill_on(a != 0.0),
        15 => w.set_bounds(af, bf, cf, df),
        16 => w.clear_bounds(),
        17 => w.kill(iu),
        18 => w.grab(i),
        19 => w.drag_to(af, bf),
        20 => w.release(),
        21 => w.set_velocity(iu, af, bf),
        22 => w.place(iu, af, bf),
        23 => w.wake(i),
        24 => w.sleep_now(iu),
        25 => w.set_sleep_on(a != 0.0),
        26 => return yes(w.set_bone(iu)),
        27 => w.activate(iu, af, bf, cf, df),
        28 => w.activate_placed(iu),
        29 => w.activate_from_parent(iu, ju),
        30 => w.reset(),
        31 => w.state.solve_tick(),
        32 => w.state.play_tick = i,
        33 => w.remove_body(i),
        34 => w.compact(),
        36 => w.remove_free(i),
        38 => w.state.restore_topology(),
        // То, чем PhysicsWorld.cellScaleMismatches оставляет мир: полный пересчёт множителей клеток.
        39 => {
            let st = &mut w.state;
            if (st.cell_scales_on || st.softness_on) && st.cell_scale_eff.len() == st.n {
                st.recompute_cell_scales();
            }
        }
        40 => w.set_cell_fiber(iu, af, bf),
        // Среда мира: a gravityY, b mediumScale, c edgeDragScale, d softness, j — сырые биты frictionScale (float).
        41 => w.set_environment(af, bf, cf, df, f32::from_bits(j as u32)),
        43 => w.pull_toward(iu, af, bf),
        44 => w.wake_all(),
        // Физика на Rust в игре: признаки множителей клеток, которые мост ставит прямо в зеркале.
        45 => {
            w.state.cell_scales_on = a != 0.0;
            w.state.cell_scales_moving = b != 0.0;
        }
        100 => return w.state.n as f64,
        101 => return w.state.organism_count as f64,
        102 => return w.body_first(i) as f64,
        103 => return w.body_count(i) as f64,
        104 => return w.body_muscle_first(i) as f64,
        105 => return w.body_muscle_count(i) as f64,
        106 => return yes(w.is_body_alive(i)),
        107 => return w.state.edge_death_n as f64,
        108 => return w.state.edge_death_log[2 * iu] as f64,
        109 => return w.state.edge_death_log[2 * iu + 1] as f64,
        110 => return w.state.cell_death_n as f64,
        111 => return w.state.cell_death_log[iu] as f64,
        112 => return w.state.local_boundary.count as f64,
        113 => return w.state.local_boundary.a[iu] as f64,
        114 => return w.state.local_boundary.b[iu] as f64,
        115 => return yes(w.state.local_surface.active[iu]),
        116 => return w.state.local_surface.active_changed_n as f64,
        117 => return w.state.local_surface.active_changed[iu] as f64,
        118 => w.state.local_surface.clear_active_changed(),
        119 => return yes(w.state.sleep.is_asleep(w.state.organism_of[iu])),
        120 => return yes(w.state.bone_of[iu] >= 0),
        121 => return yes(w.state.cell_unborn[iu]),
        123 => return w.state.link_scale as f64,
        124 => return yes(w.state.cell_dead[iu]),
        130 => return w.body_ids as f64,
        131 => return w.free_particle(i) as f64,
        132 => return yes(w.is_free_in_use(i)),
        133 => return yes(w.is_free_alive(i)),
        134 => return w.compactions as f64,
        135 => return w.free_count as f64,
        136 => return w.state.isl.last_tick as f64,
        137 => return w.state.isl.aborts as f64,
        // Физика на Rust в игре: признаки множителей клеток и вопросы моста к топологии (PhysicsWorld.liveDegree,
        // neighborCount, neighbor) и к временным связям (isJointLive).
        138 => return yes(w.state.cell_scales_on),
        139 => return yes(w.state.cell_scales_moving),
        140 => {
            let b = &w.state.baked;
            let mut d = 0;
            for q in b.vert_start[iu]..b.vert_start[iu + 1] {
                if !b.edge_dead[b.vert_edge[q as usize] as usize] {
                    d += 1;
                }
            }
            return d as f64;
        }
        141 => return (w.state.baked.vert_start[iu + 1] - w.state.baked.vert_start[iu]) as f64,
        142 => return w.state.baked.vert_other[(w.state.baked.vert_start[iu] + j) as usize] as f64,
        143 => return yes(i >= 0 && iu < w.state.joint_slots && w.state.joint_live[iu]),
        _ => panic!("worldCall: неизвестная операция {op}"),
    }
    0.0
}
