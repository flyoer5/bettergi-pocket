//! JNI 边界上交换的 JSON 结构。
//!
//! 为什么用 JSON 而不是二进制：这条链路每拍只有几十字节，JSON 的开销可以忽略；
//! 换来的是 Kotlin 侧不需要手写布局、加字段不用同步改两边、出问题能直接打日志看原文。

use bg_core::{Action, Point, Rect, Size, TriggerSettings};
use bg_trigger::autoskip::{AutoskipEvent, AutoskipInput, CaptureMapping, OcrLine, State};
use bg_trigger::engine::TickResult;
use bg_trigger::keywords::OptionKeywords;
use bg_trigger::Observation;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, Deserialize)]
pub struct SizePayload {
    pub w: i32,
    pub h: i32,
}

impl From<SizePayload> for Size {
    fn from(value: SizePayload) -> Self {
        Size::new(value.w, value.h)
    }
}

#[derive(Debug, Clone, Copy, Deserialize)]
pub struct RectPayload {
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
}

impl From<RectPayload> for Rect {
    fn from(value: RectPayload) -> Self {
        Rect::new(value.x, value.y, value.w, value.h)
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct OcrLinePayload {
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
    pub text: String,
}

impl From<OcrLinePayload> for OcrLine {
    fn from(value: OcrLinePayload) -> Self {
        OcrLine {
            rect: Rect::new(value.x, value.y, value.w, value.h),
            text: value.text,
        }
    }
}

/// 一拍的全部输入。Kotlin 侧只需要填它已经算出来的东西。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ObservationPayload {
    pub now_ms: u64,
    /// 原生屏幕尺寸（点击坐标用）。
    pub screen: SizePayload,
    /// 识别图尺寸（命中坐标所在坐标系）。
    pub capture: SizePayload,
    /// 前台是否原神：`true` / `false` / 省略表示未知。
    #[serde(default)]
    pub foreground: Option<bool>,
    #[serde(default)]
    pub dialogue: bool,
    #[serde(default)]
    pub exclamation: Vec<RectPayload>,
    #[serde(default)]
    pub chat_icons: Vec<RectPayload>,
    #[serde(default)]
    pub ocr_lines: Vec<OcrLinePayload>,
    #[serde(default)]
    pub black_rate: Option<f64>,
}

impl ObservationPayload {
    pub fn into_observation(self) -> Observation {
        let screen: Size = self.screen.into();
        let capture: Size = self.capture.into();
        let mut input = AutoskipInput::new(screen, CaptureMapping::new(capture, screen));
        input.dialogue = self.dialogue;
        input.exclamation_hits = self.exclamation.into_iter().map(Rect::from).collect();
        input.chat_hits = self.chat_icons.into_iter().map(Rect::from).collect();
        input.ocr_lines = self.ocr_lines.into_iter().map(OcrLine::from).collect();
        input.black_rate = self.black_rate;
        Observation {
            now_ms: self.now_ms,
            screen,
            autoskip: input,
        }
    }

    pub fn foreground(&self) -> Option<bool> {
        self.foreground
    }
}

/// 设置覆盖：只带需要改的字段，其余沿用默认值。
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SettingsPayload {
    pub screen_share_enabled: Option<bool>,
    pub auto_skip_enabled: Option<bool>,
    pub quick_skip_dialogue_enabled: Option<bool>,
    pub auto_launch_genshin_enabled: Option<bool>,
    pub smart_option_enabled: Option<bool>,
    pub black_screen_click_enabled: Option<bool>,
    pub show_tap_indicator: Option<bool>,
    pub exclamation_click_enabled: Option<bool>,
    pub quick_skip_custom_position: Option<bool>,
    pub quick_skip_position_x: Option<f32>,
    pub quick_skip_position_y: Option<f32>,
    pub black_screen_custom_position: Option<bool>,
    pub black_screen_position_x: Option<f32>,
    pub black_screen_position_y: Option<f32>,
    pub skip_click_interval_ms: Option<u32>,
    pub black_click_interval_ms: Option<u32>,
}

impl SettingsPayload {
    pub fn apply_to(self, base: TriggerSettings) -> TriggerSettings {
        let mut settings = base;
        macro_rules! apply {
            ($field:ident) => {
                if let Some(value) = self.$field {
                    settings.$field = value;
                }
            };
        }
        apply!(screen_share_enabled);
        apply!(auto_skip_enabled);
        apply!(quick_skip_dialogue_enabled);
        apply!(auto_launch_genshin_enabled);
        apply!(smart_option_enabled);
        apply!(black_screen_click_enabled);
        apply!(show_tap_indicator);
        apply!(exclamation_click_enabled);
        apply!(quick_skip_custom_position);
        apply!(quick_skip_position_x);
        apply!(quick_skip_position_y);
        apply!(black_screen_custom_position);
        apply!(black_screen_position_x);
        apply!(black_screen_position_y);
        apply!(skip_click_interval_ms);
        apply!(black_click_interval_ms);
        settings
    }
}

/// 三张关键词表。
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct KeywordsPayload {
    #[serde(default)]
    pub select: Vec<String>,
    #[serde(default)]
    pub pause: Vec<String>,
    #[serde(default)]
    pub default_pause: Vec<String>,
}

impl KeywordsPayload {
    pub fn into_keywords(self) -> OptionKeywords {
        OptionKeywords::new(self.select, self.pause, self.default_pause)
    }
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum ActionPayload {
    Tap {
        x: i32,
        y: i32,
        #[serde(rename = "durationMs")]
        duration_ms: u32,
    },
    Swipe {
        #[serde(rename = "fromX")]
        from_x: i32,
        #[serde(rename = "fromY")]
        from_y: i32,
        #[serde(rename = "toX")]
        to_x: i32,
        #[serde(rename = "toY")]
        to_y: i32,
        #[serde(rename = "durationMs")]
        duration_ms: u32,
    },
    Back,
}

impl From<&Action> for ActionPayload {
    fn from(action: &Action) -> Self {
        match *action {
            Action::Tap { point, duration_ms } => ActionPayload::Tap {
                x: point.x,
                y: point.y,
                duration_ms,
            },
            Action::Swipe {
                from,
                to,
                duration_ms,
            } => ActionPayload::Swipe {
                from_x: from.x,
                from_y: from.y,
                to_x: to.x,
                to_y: to.y,
                duration_ms,
            },
            Action::Back => ActionPayload::Back,
        }
    }
}

/// 一拍的结果。
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TickPayload {
    pub actions: Vec<ActionPayload>,
    /// 可读的日志事件（直接展示在悬浮窗里）。
    pub events: Vec<String>,
    /// `idle` / `inDialog` / `confirming`
    pub state: &'static str,
    pub next_tick_ms: u32,
}

/// 把引擎结果编码成 JSON。
pub fn encode_tick(result: &TickResult) -> String {
    let payload = TickPayload {
        actions: result.actions.iter().map(ActionPayload::from).collect(),
        events: result.events.iter().map(render_event).collect(),
        state: state_name(result.state),
        next_tick_ms: result.next_tick_ms,
    };
    serde_json::to_string(&payload).unwrap_or_else(|_| {
        "{\"actions\":[],\"events\":[],\"state\":\"idle\",\"nextTickMs\":600}".to_string()
    })
}

pub fn state_name(state: State) -> &'static str {
    match state {
        State::Idle => "idle",
        State::InDialog => "inDialog",
        State::Confirming => "confirming",
    }
}

/// 日志事件转成给人看的字符串。
pub fn render_event(event: &AutoskipEvent) -> String {
    match event {
        AutoskipEvent::TalkHistoryMatched => "对话图标命中".to_string(),
        AutoskipEvent::IdleScan => "空闲扫描".to_string(),
        AutoskipEvent::OptionTexts(texts) => format!("选项文字：{}", texts.join(" | ")),
        AutoskipEvent::ChatIconsRecognized { count, x, y } => {
            format!("识别到 {count} 个对话图标，目标 ({x}, {y})")
        }
        AutoskipEvent::ChatIconClicked { x, y } => format!("点击对话图标 ({x}, {y})"),
        AutoskipEvent::BlackScreenClicked { x, y } => format!("黑屏转场点击 ({x}, {y})"),
        AutoskipEvent::AutoSkipLog(message) => message.clone(),
    }
}

/// 解析一拍输入。
pub fn parse_observation(json: &str) -> Result<ObservationPayload, serde_json::Error> {
    serde_json::from_str(json)
}

/// 解析设置覆盖。
pub fn parse_settings(json: &str) -> Result<SettingsPayload, serde_json::Error> {
    serde_json::from_str(json)
}

/// 解析关键词表。
pub fn parse_keywords(json: &str) -> Result<KeywordsPayload, serde_json::Error> {
    serde_json::from_str(json)
}

/// 点工具：给 Kotlin 侧做坐标换算时用，避免两边各写一份。
pub fn scale_capture_point(x: i32, y: i32, capture: Size, native: Size) -> Point {
    CaptureMapping::new(capture, native).to_native(Point::new(x, y))
}

#[cfg(test)]
mod tests {
    use super::*;

    const OBSERVATION: &str = r#"{
      "nowMs": 12345,
      "screen": { "w": 1920, "h": 1080 },
      "capture": { "w": 1920, "h": 1080 },
      "foreground": true,
      "dialogue": true,
      "exclamation": [{ "x": 10, "y": 20, "w": 30, "h": 40 }],
      "chatIcons": [{ "x": 100, "y": 500, "w": 20, "h": 20 }],
      "ocrLines": [{ "x": 200, "y": 300, "w": 200, "h": 40, "text": "确定" }],
      "blackRate": 0.62
    }"#;

    #[test]
    fn parses_full_observation() {
        let payload = parse_observation(OBSERVATION).unwrap();
        assert_eq!(payload.now_ms, 12345);
        assert_eq!(payload.foreground(), Some(true));
        let observation = payload.into_observation();
        assert_eq!(observation.now_ms, 12345);
        assert_eq!(observation.screen, Size::new(1920, 1080));
        assert!(observation.autoskip.dialogue);
        assert_eq!(
            observation.autoskip.chat_hits,
            vec![Rect::new(100, 500, 20, 20)]
        );
        assert_eq!(observation.autoskip.ocr_lines.len(), 1);
        assert_eq!(observation.autoskip.ocr_lines[0].text, "确定");
        assert_eq!(observation.autoskip.black_rate, Some(0.62));
    }

    #[test]
    fn optional_fields_have_defaults() {
        let payload = parse_observation(
            r#"{ "nowMs": 1, "screen": {"w":1920,"h":1080}, "capture": {"w":1920,"h":1080} }"#,
        )
        .unwrap();
        assert_eq!(payload.foreground(), None);
        assert!(!payload.dialogue);
        assert!(payload.exclamation.is_empty());
        assert!(payload.chat_icons.is_empty());
        assert!(payload.ocr_lines.is_empty());
        assert!(payload.black_rate.is_none());
    }

    #[test]
    fn rejects_broken_json() {
        assert!(parse_observation("{ oops").is_err());
        assert!(
            parse_observation(r#"{"nowMs":1}"#).is_err(),
            "screen/capture 必填"
        );
    }

    #[test]
    fn settings_payload_only_overrides_present_fields() {
        let base = TriggerSettings::default();
        let payload =
            parse_settings(r#"{ "quickSkipDialogueEnabled": true, "skipClickIntervalMs": 300 }"#)
                .unwrap();
        let settings = payload.apply_to(base);
        assert!(settings.quick_skip_dialogue_enabled);
        assert_eq!(settings.skip_click_interval_ms, 300);
        // 其余字段保持默认
        assert_eq!(
            settings.black_click_interval_ms,
            base.black_click_interval_ms
        );
        assert_eq!(settings.quick_skip_position_y, base.quick_skip_position_y);
    }

    #[test]
    fn keywords_payload_uses_camel_case() {
        let payload = parse_keywords(
            r#"{ "select": ["确定"], "pause": ["暂停"], "defaultPause": ["离开"] }"#,
        )
        .unwrap();
        let keywords = payload.into_keywords();
        assert_eq!(keywords.select, vec!["确定".to_string()]);
        assert_eq!(keywords.default_pause, vec!["离开".to_string()]);
    }

    #[test]
    fn encodes_actions_and_events() {
        let mut engine = bg_trigger::TriggerEngine::new(TriggerSettings {
            quick_skip_dialogue_enabled: true,
            ..Default::default()
        });
        let screen = Size::new(1920, 1080);
        let mut input = AutoskipInput::new(screen, CaptureMapping::new(screen, screen));
        input.dialogue = true;
        let observation = Observation {
            now_ms: 1_000,
            screen,
            autoskip: input,
        };
        // 第一拍只进对话
        let first = engine.tick(&observation, Some(true));
        let encoded = encode_tick(&first);
        assert!(encoded.contains("\"state\":\"inDialog\""), "{encoded}");

        // 第二拍点底部中央
        let mut input = AutoskipInput::new(screen, CaptureMapping::new(screen, screen));
        input.dialogue = true;
        let observation = Observation {
            now_ms: 1_200,
            screen,
            autoskip: input,
        };
        let second = engine.tick(&observation, Some(true));
        let encoded = encode_tick(&second);
        assert!(encoded.contains("\"type\":\"tap\""), "{encoded}");
        assert!(encoded.contains("\"x\":960"), "{encoded}");
        assert!(encoded.contains("\"nextTickMs\":200"), "{encoded}");
    }

    #[test]
    fn encodes_back_action() {
        let payload = ActionPayload::from(&Action::Back);
        assert_eq!(
            serde_json::to_string(&payload).unwrap(),
            "{\"type\":\"back\"}"
        );
    }

    #[test]
    fn renders_events_readably() {
        assert_eq!(
            render_event(&AutoskipEvent::ChatIconClicked { x: 1, y: 2 }),
            "点击对话图标 (1, 2)"
        );
        assert_eq!(
            render_event(&AutoskipEvent::OptionTexts(vec!["A".into(), "B".into()])),
            "选项文字：A | B"
        );
        assert_eq!(
            render_event(&AutoskipEvent::AutoSkipLog("原样输出".into())),
            "原样输出"
        );
    }

    #[test]
    fn scale_point_matches_engine_mapping() {
        assert_eq!(
            scale_capture_point(100, 200, Size::new(1920, 1080), Size::new(2400, 1350)),
            Point::new(125, 250)
        );
    }
}
