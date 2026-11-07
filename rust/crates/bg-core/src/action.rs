//! 点击 / 滑动等自动化动作。
//!
//! 引擎只产出 [`Action`]，真正注入由平台层（Android 的 `AutomationController`）负责，
//! 这样状态机可以在没有设备的情况下单测。

use crate::geometry::Point;

/// 固定点与附加点的最小间距：小于此值视为同一位置，只点一次，
/// 避免同位置连点被判成双击。
pub const MIN_POINT_SEPARATION_PX: i32 = 32;

/// 默认点击时长（毫秒）。与 Android `input tap` 的行为一致。
pub const DEFAULT_TAP_DURATION_MS: u32 = 50;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Action {
    Tap {
        point: Point,
        duration_ms: u32,
    },
    Swipe {
        from: Point,
        to: Point,
        duration_ms: u32,
    },
    Back,
}

impl Action {
    pub fn tap(point: Point) -> Self {
        Action::Tap {
            point,
            duration_ms: DEFAULT_TAP_DURATION_MS,
        }
    }

    pub fn tap_at(x: i32, y: i32) -> Self {
        Action::tap(Point::new(x, y))
    }

    pub fn long_press(point: Point, duration_ms: u32) -> Self {
        Action::Tap { point, duration_ms }
    }

    pub fn swipe(from: Point, to: Point, duration_ms: u32) -> Self {
        Action::Swipe {
            from,
            to,
            duration_ms,
        }
    }

    pub fn point(&self) -> Option<Point> {
        match self {
            Action::Tap { point, .. } => Some(*point),
            _ => None,
        }
    }

    /// 两个动作是否落在同一位置（用于附加点去重）。
    pub fn is_same_position(&self, other: &Action) -> bool {
        match (self.point(), other.point()) {
            (Some(a), Some(b)) => a.distance_sq(b) < (MIN_POINT_SEPARATION_PX as i64).pow(2),
            _ => false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tap_dedup_by_distance() {
        let a = Action::tap_at(100, 100);
        let near = Action::tap_at(120, 100); // 20px < 32px
        let far = Action::tap_at(140, 100); // 40px
        assert!(a.is_same_position(&near));
        assert!(!a.is_same_position(&far));
        assert_eq!(a.point(), Some(Point::new(100, 100)));
    }

    #[test]
    fn back_has_no_point() {
        assert_eq!(Action::Back.point(), None);
        assert!(!Action::Back.is_same_position(&Action::Back));
    }
}
