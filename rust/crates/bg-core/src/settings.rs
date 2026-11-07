//! 触发器设置：与 Android 侧 `TriggerSettings` 逐字段对应。
//!
//! Rust 侧把它当作纯数据，读写仍由 Android 的 SharedPreferences 负责；
//! 这里只提供默认值与归一化后的坐标换算，方便单测。

/// 自动对话 / 自动拾取等触发器开关与参数。
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TriggerSettings {
    /// 屏幕共享（投屏）总开关；关掉后引擎不产生任何动作。
    pub screen_share_enabled: bool,
    pub auto_skip_enabled: bool,
    pub quick_skip_dialogue_enabled: bool,
    pub auto_launch_genshin_enabled: bool,
    pub smart_option_enabled: bool,
    pub black_screen_click_enabled: bool,
    pub show_tap_indicator: bool,
    pub exclamation_click_enabled: bool,

    /// 对话跳过的附加点（固定点 = 屏幕底部中央，始终生效）。
    pub quick_skip_custom_position: bool,
    /// 附加点 X，按屏幕宽比例 0.0~1.0。
    pub quick_skip_position_x: f32,
    /// 附加点 Y，按屏幕高比例 0.0~1.0。
    pub quick_skip_position_y: f32,

    /// 黑屏转场的附加点（固定点 = 画面中央，始终生效）。
    pub black_screen_custom_position: bool,
    pub black_screen_position_x: f32,
    pub black_screen_position_y: f32,

    /// 对话推进点击间隔（毫秒）。
    pub skip_click_interval_ms: u32,
    /// 黑屏转场点击间隔（毫秒）。
    pub black_click_interval_ms: u32,
}

impl Default for TriggerSettings {
    fn default() -> Self {
        TriggerSettings {
            screen_share_enabled: true,
            auto_skip_enabled: true,
            quick_skip_dialogue_enabled: false,
            auto_launch_genshin_enabled: false,
            smart_option_enabled: true,
            black_screen_click_enabled: true,
            show_tap_indicator: false,
            exclamation_click_enabled: true,
            quick_skip_custom_position: false,
            quick_skip_position_x: 0.5,
            quick_skip_position_y: 0.99,
            black_screen_custom_position: false,
            black_screen_position_x: 0.5,
            black_screen_position_y: 0.5,
            skip_click_interval_ms: 500,
            black_click_interval_ms: 1200,
        }
    }
}

impl TriggerSettings {
    /// 整条识别流水线是否应该跑。
    pub fn pipeline_active(&self) -> bool {
        self.screen_share_enabled && self.auto_skip_enabled
    }

    /// 比例坐标 → 屏幕像素，夹到 `[0, edge]`。
    pub fn resolve_position(
        &self,
        x_ratio: f32,
        y_ratio: f32,
        width: i32,
        height: i32,
    ) -> (i32, i32) {
        let x = (width as f32 * x_ratio).round() as i32;
        let y = (height as f32 * y_ratio).round() as i32;
        (x.clamp(0, width), y.clamp(0, height))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_match_android_side() {
        let s = TriggerSettings::default();
        assert!(s.screen_share_enabled);
        assert!(s.auto_skip_enabled);
        assert!(!s.quick_skip_dialogue_enabled);
        assert_eq!(s.skip_click_interval_ms, 500);
        assert_eq!(s.black_click_interval_ms, 1200);
        assert_eq!(s.quick_skip_position_y, 0.99);
        assert!(s.pipeline_active());
    }

    #[test]
    fn position_is_clamped_to_screen() {
        let s = TriggerSettings::default();
        assert_eq!(s.resolve_position(0.5, 0.99, 1920, 1080), (960, 1069));
        assert_eq!(s.resolve_position(2.0, -1.0, 1920, 1080), (1920, 0));
    }

    #[test]
    fn pipeline_inactive_when_share_disabled() {
        let s = TriggerSettings {
            screen_share_enabled: false,
            ..Default::default()
        };
        assert!(!s.pipeline_active());
    }
}
