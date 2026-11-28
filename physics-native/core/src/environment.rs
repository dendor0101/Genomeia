//! Среда мира, которую клиент меняет на ходу — `config/WorldEnvironment.kt`. Ставится раз в тик до шага
//! (`setEnvironment`), в снимок не входит. По умолчанию — ровно константы материала: константа × 1 — те же биты.

use crate::params::{AREA_COMPLIANCE, CONTACT_FRICTION, MEDIUM_DRAG, NORMAL_DRAG, NORMAL_DRAG_QUADRATIC, SOFT_COMPLIANCE};

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct WorldEnvironment {
    /// Гравитация по y, единиц мира в секунду в квадрате; 0 — мир сверху.
    pub gravity_y: f32,
    /// Изотропное сопротивление среды, 1/сек.
    pub medium_drag: f32,
    /// Сопротивление рёбер контура — линейная и квадратичная части.
    pub normal_drag: f32,
    pub normal_drag_quadratic: f32,
    /// Податливость связей и площадей ткани.
    pub soft_compliance: f32,
    pub area_compliance: f32,
    /// Кулоново трение касания тел (и стенки), доля нормальной поправки.
    pub contact_friction: f32,
}

impl Default for WorldEnvironment {
    fn default() -> Self {
        WorldEnvironment {
            gravity_y: 0.0,
            medium_drag: MEDIUM_DRAG,
            normal_drag: NORMAL_DRAG,
            normal_drag_quadratic: NORMAL_DRAG_QUADRATIC,
            soft_compliance: SOFT_COMPLIANCE,
            area_compliance: AREA_COMPLIANCE,
            contact_friction: CONTACT_FRICTION,
        }
    }
}
