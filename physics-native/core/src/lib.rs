//! Ядро физики тканей Genomeia на Rust — дословный перенос модуля `:physics` (Kotlin) с побитовой сверкой.
//! Kotlin — эталон: те же порядок операций с плавающей точкой, те же типы (float, а где в Kotlin double — f64),
//! без FMA и fast-math. Статус переноса по файлам — RUST_PORT.md в корне репозитория.

pub mod config;
pub mod contacts;
pub mod damage;
pub mod definition;
pub mod digest;
pub mod environment;
pub mod jmath;
pub mod params;
pub mod snapshot;
pub mod snapshot_core;
pub mod solver;
pub mod state;
pub mod topology;
pub mod world;

/// Версия двоичного договора с обвязкой Kotlin (`RustPhysics.ABI_VERSION`).
pub const ABI_VERSION: i32 = 8;
