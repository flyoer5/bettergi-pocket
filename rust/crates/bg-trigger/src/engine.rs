//! 触发引擎：把设置、前台门控、状态机串成一拍。
//!
//! 平台层（Android Service）负责提供一帧识别结果并执行返回的动作；
//! 引擎本身不碰线程、不碰时钟，`now_ms` 由调用方注入，因此整条链路可测。

use bg_core::{Action, Size, TriggerSettings};

use crate::autoskip::{AutoSkip, AutoskipEvent, AutoskipInput, State};
use crate::keywords::OptionKeywords;

/// 有动作后切到快节奏的窗口。
pub const ACTIVE_WINDOW_MS: u64 = 1_500;
/// 快节奏 / 慢节奏间隔。
pub const FAST_TICK_MS: u32 = 200;
pub const SLOW_TICK_MS: u32 = 600;
/// 前台未知时的重试间隔。
pub const FG_PAUSED_TICK_MS: u32 = 500;
/// 前台告警日志节流。
pub const FG_WARN_INTERVAL_MS: u64 = 30_000;

/// 一拍的结果。
#[derive(Debug, Clone, Default, PartialEq)]
pub struct TickResult {
    pub actions: Vec<Action>,
    pub events: Vec<AutoskipEvent>,
    pub state: State,
    /// 下一拍建议的延迟（毫秒）。
    pub next_tick_ms: u32,
}

/// 一次 tick 的输入。
#[derive(Debug, Clone, PartialEq)]
pub struct Observation {
    pub now_ms: u64,
    pub screen: Size,
    pub autoskip: AutoskipInput,
}

#[derive(Debug, Clone)]
pub struct TriggerEngine {
    settings: TriggerSettings,
    autoskip: AutoSkip,
    keywords: OptionKeywords,
    last_action_at_ms: Option<u64>,
    last_fg_warn_at_ms: Option<u64>,
}

impl TriggerEngine {
    pub fn new(settings: TriggerSettings) -> Self {
        TriggerEngine {
            settings,
            autoskip: AutoSkip::new(),
            keywords: OptionKeywords::default(),
            last_action_at_ms: None,
            last_fg_warn_at_ms: None,
        }
    }

    pub fn with_keywords(mut self, keywords: OptionKeywords) -> Self {
        self.keywords = keywords;
        self
    }

    pub fn settings(&self) -> &TriggerSettings {
        &self.settings
    }

    pub fn set_settings(&mut self, settings: TriggerSettings) {
        self.settings = settings;
    }

    pub fn state(&self) -> State {
        self.autoskip.state()
    }

    pub fn keywords(&self) -> &OptionKeywords {
        &self.keywords
    }

    pub fn set_keywords(&mut self, keywords: OptionKeywords) {
        self.keywords = keywords;
    }

    /// 停止任务时调用，避免残留状态影响下一次启动。
    pub fn reset(&mut self) {
        self.autoskip.reset();
        self.last_action_at_ms = None;
        self.last_fg_warn_at_ms = None;
    }

    /// 推进一拍。
    ///
    /// * `foreground`：`Some(true)` 确认原神在前台；`Some(false)`/`None` 时一律不产生动作。
    ///   识别是在屏幕流上做的，桌面图标同样可能误命中模板，所以这层门控不能省。
    pub fn tick(&mut self, observation: &Observation, foreground: Option<bool>) -> TickResult {
        if !self.settings.pipeline_active() {
            return TickResult {
                next_tick_ms: SLOW_TICK_MS,
                state: self.autoskip.state(),
                ..Default::default()
            };
        }

        if foreground != Some(true) {
            let mut events = Vec::new();
            if foreground.is_none()
                && elapsed_since(self.last_fg_warn_at_ms, observation.now_ms) >= FG_WARN_INTERVAL_MS
            {
                self.last_fg_warn_at_ms = Some(observation.now_ms);
                events.push(AutoskipEvent::AutoSkipLog(
                    "无法确认前台应用，已暂停自动点击".to_string(),
                ));
            }
            return TickResult {
                actions: Vec::new(),
                events,
                state: self.autoskip.state(),
                next_tick_ms: FG_PAUSED_TICK_MS,
            };
        }

        let output = self.autoskip.tick(
            observation.now_ms,
            &observation.autoskip,
            &self.settings,
            &self.keywords,
        );

        if !output.actions.is_empty() {
            self.last_action_at_ms = Some(observation.now_ms);
        }
        let recently_active = match self.last_action_at_ms {
            Some(last) => observation.now_ms.saturating_sub(last) < ACTIVE_WINDOW_MS,
            None => false,
        };

        TickResult {
            actions: output.actions,
            events: output.events,
            state: self.autoskip.state(),
            next_tick_ms: if recently_active {
                FAST_TICK_MS
            } else {
                SLOW_TICK_MS
            },
        }
    }
}

fn elapsed_since(last: Option<u64>, now: u64) -> u64 {
    match last {
        Some(last) => now.saturating_sub(last),
        None => u64::MAX,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::autoskip::CaptureMapping;
    use bg_core::{Point, Rect};

    fn engine() -> TriggerEngine {
        TriggerEngine::new(TriggerSettings {
            quick_skip_dialogue_enabled: true,
            ..Default::default()
        })
    }

    fn obs(now_ms: u64) -> Observation {
        let screen = Size::new(1920, 1080);
        Observation {
            now_ms,
            screen,
            autoskip: AutoskipInput::new(screen, CaptureMapping::new(screen, screen)),
        }
    }

    #[test]
    fn does_nothing_when_screen_share_disabled() {
        let mut engine = TriggerEngine::new(TriggerSettings {
            screen_share_enabled: false,
            ..Default::default()
        });
        let mut observation = obs(1_000);
        observation.autoskip.dialogue = true;
        let result = engine.tick(&observation, Some(true));
        assert!(result.actions.is_empty());
        assert_eq!(result.next_tick_ms, SLOW_TICK_MS);
    }

    #[test]
    fn pauses_when_foreground_is_not_the_game() {
        let mut engine = engine();
        let mut observation = obs(1_000);
        observation.autoskip.dialogue = true;
        let result = engine.tick(&observation, Some(false));
        assert!(result.actions.is_empty());
        assert_eq!(result.next_tick_ms, FG_PAUSED_TICK_MS);
        assert_eq!(result.state, State::Idle);
    }

    #[test]
    fn warns_when_foreground_unknown_but_throttled() {
        let mut engine = engine();
        let observation = obs(1_000);
        let first = engine.tick(&observation, None);
        assert!(first.events.iter().any(
            |event| matches!(event, AutoskipEvent::AutoSkipLog(msg) if msg.contains("无法确认前台"))
        ));
        let second = engine.tick(&obs(2_000), None);
        assert!(second.events.is_empty(), "30 秒内不应重复告警");
    }

    #[test]
    fn enters_dialog_then_clicks_and_switches_to_fast_tick() {
        let mut engine = engine();
        let mut observation = obs(1_000);
        observation.autoskip.dialogue = true;

        // 第一拍只做「进入对话」的状态迁移，不产生点击。
        let entered = engine.tick(&observation, Some(true));
        assert!(entered.actions.is_empty());
        assert_eq!(entered.state, State::InDialog);

        // 第二拍按快速跳过点底部中央，并切到快节奏。
        let mut observation = obs(1_200);
        observation.autoskip.dialogue = true;
        let result = engine.tick(&observation, Some(true));
        assert_eq!(result.actions, vec![Action::tap(Point::new(960, 1060))]);
        assert_eq!(result.next_tick_ms, FAST_TICK_MS);
        assert_eq!(result.state, State::InDialog);
    }

    #[test]
    fn slow_tick_when_idle() {
        let mut engine = engine();
        let observation = obs(1_000);
        let result = engine.tick(&observation, Some(true));
        assert!(result.actions.is_empty());
        assert_eq!(result.next_tick_ms, SLOW_TICK_MS);
    }

    #[test]
    fn reset_clears_state() {
        let mut engine = engine();
        let mut observation = obs(1_000);
        observation.autoskip.dialogue = true;
        engine.tick(&observation, Some(true));
        assert_eq!(engine.state(), State::InDialog);
        engine.reset();
        assert_eq!(engine.state(), State::Idle);
    }

    #[test]
    fn mapping_does_not_change_identity_when_sizes_match() {
        let mapping = CaptureMapping::new(Size::new(1920, 1080), Size::new(1920, 1080));
        assert!((mapping.scale() - 1.0).abs() < 1e-12);
        assert_eq!(mapping.to_native(Point::new(10, 20)), Point::new(10, 20));
        assert_eq!(mapping.capture_center_to_native(), Point::new(960, 540));
        assert_eq!(
            mapping.rect_center_to_native(Rect::new(0, 0, 10, 10)),
            Point::new(5, 5)
        );
    }

    #[test]
    fn mapping_scales_capture_coordinates_back_to_native() {
        // 1080P 识别图，原生屏幕 2400x1080（宽屏）
        let mapping = CaptureMapping::new(Size::new(1920, 1080), Size::new(2400, 1350));
        assert!((mapping.scale() - 1.25).abs() < 1e-9);
        assert_eq!(
            mapping.to_native(Point::new(100, 200)),
            Point::new(125, 250)
        );
    }

    #[test]
    fn mapping_handles_empty_sizes() {
        let mapping = CaptureMapping::new(Size::ZERO, Size::new(1920, 1080));
        assert!((mapping.scale() - 1.0).abs() < 1e-12);
    }
}
