//! Решатель: стадии подшага, блоки и швы, куски проходов, мышцы, среда, учёт импульса, сон
//! (`physics/.../solver/*.kt`).

pub mod blocks;
pub mod bones;
pub mod color_order;
pub mod fiber;
pub mod fused;
pub mod island_plan;
pub mod island_run;
pub mod islands;
pub mod iteration;
pub mod kernels;
pub mod medium;
pub mod muscle_rings;
pub mod muscles;
pub mod orientation;
pub mod parallel;
pub mod pass_chunks;
pub mod pool;
pub mod simulation;
pub mod sleep;
pub mod sleep_fns;
pub mod tick_momentum;
pub mod tick_motion;
