//! 引擎实例的状态管理与 JSON 出入口。
//!
//! JNI 层只做类型搬运，真正的状态在 [`EngineState`] 里，可以在 host 上直接单测。

use bg_core::TriggerSettings;
use bg_trigger::engine::TriggerEngine;

use crate::payload::{self, ObservationPayload};

/// 被 Kotlin 持有的引擎状态。
pub struct EngineState {
    engine: TriggerEngine,
}

impl EngineState {
    pub fn new(settings: TriggerSettings) -> Self {
        EngineState {
            engine: TriggerEngine::new(settings),
        }
    }

    /// 用 JSON 覆盖默认设置创建实例；JSON 为空或不合法时返回错误。
    pub fn from_json(settings_json: &str) -> Result<Self, String> {
        let settings = if settings_json.trim().is_empty() {
            TriggerSettings::default()
        } else {
            payload::parse_settings(settings_json)
                .map_err(|err| format!("设置 JSON 解析失败: {err}"))?
                .apply_to(TriggerSettings::default())
        };
        Ok(EngineState::new(settings))
    }

    /// 推进一拍。入参是一帧观测的 JSON，出参是动作 + 日志的 JSON。
    pub fn tick_json(&mut self, observation_json: &str) -> Result<String, String> {
        let payload: ObservationPayload = payload::parse_observation(observation_json)
            .map_err(|err| format!("观测 JSON 解析失败: {err}"))?;
        let foreground = payload.foreground();
        let observation = payload.into_observation();
        let result = self.engine.tick(&observation, foreground);
        Ok(payload::encode_tick(&result))
    }

    /// 停止任务时清空状态，避免残留 `Confirming` 影响下一次启动。
    pub fn reset(&mut self) {
        self.engine.reset();
    }

    pub fn set_settings_json(&mut self, settings_json: &str) -> Result<(), String> {
        let payload = payload::parse_settings(settings_json)
            .map_err(|err| format!("设置 JSON 解析失败: {err}"))?;
        let settings = payload.apply_to(*self.engine.settings());
        self.engine.set_settings(settings);
        Ok(())
    }

    pub fn set_keywords_json(&mut self, keywords_json: &str) -> Result<(), String> {
        let payload = payload::parse_keywords(keywords_json)
            .map_err(|err| format!("词表 JSON 解析失败: {err}"))?;
        self.engine.set_keywords(payload.into_keywords());
        Ok(())
    }

    pub fn settings(&self) -> &TriggerSettings {
        self.engine.settings()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn observation(now_ms: u64, dialogue: bool) -> String {
        format!(
            r#"{{
              "nowMs": {now_ms},
              "screen": {{ "w": 1920, "h": 1080 }},
              "capture": {{ "w": 1920, "h": 1080 }},
              "foreground": true,
              "dialogue": {dialogue}
            }}"#
        )
    }

    #[test]
    fn empty_settings_use_defaults() {
        let state = EngineState::from_json("").unwrap();
        assert!(state.settings().screen_share_enabled);
        assert_eq!(state.settings().skip_click_interval_ms, 500);
    }

    #[test]
    fn settings_json_overrides_defaults() {
        let state = EngineState::from_json(r#"{ "skipClickIntervalMs": 250 }"#).unwrap();
        assert_eq!(state.settings().skip_click_interval_ms, 250);
        assert!(
            state.settings().screen_share_enabled,
            "未提供的字段沿用默认值"
        );
    }

    #[test]
    fn invalid_settings_json_is_an_error() {
        assert!(EngineState::from_json("{ oops").is_err());
        assert!(EngineState::from_json(r#"{ "skipClickIntervalMs": "快" }"#).is_err());
    }

    #[test]
    fn tick_enters_dialog_then_clicks() {
        let mut state = EngineState::from_json(
            r#"{ "quickSkipDialogueEnabled": true, "blackScreenClickEnabled": false }"#,
        )
        .unwrap();

        let first = state.tick_json(&observation(1_000, true)).unwrap();
        assert!(first.contains("\"state\":\"inDialog\""), "{first}");
        assert!(first.contains("\"actions\":[]"), "{first}");

        let second = state.tick_json(&observation(1_200, true)).unwrap();
        assert!(second.contains("\"type\":\"tap\""), "{second}");
        assert!(second.contains("\"x\":960"), "{second}");
    }

    #[test]
    fn tick_rejects_broken_json() {
        let mut state = EngineState::from_json("").unwrap();
        let error = state.tick_json("{ oops").unwrap_err();
        assert!(error.contains("观测 JSON"), "{error}");
    }

    #[test]
    fn reset_clears_dialog_state() {
        let mut state = EngineState::from_json(
            r#"{ "quickSkipDialogueEnabled": true, "blackScreenClickEnabled": false }"#,
        )
        .unwrap();
        state.tick_json(&observation(1_000, true)).unwrap();
        assert!(state
            .tick_json(&observation(1_200, true))
            .unwrap()
            .contains("\"state\":\"inDialog\""));
        state.reset();
        // reset 后重新从 idle 开始：即使画面还在对话里，第一拍只做状态迁移
        let after = state.tick_json(&observation(2_000, true)).unwrap();
        assert!(after.contains("\"actions\":[]"), "{after}");
        assert!(after.contains("\"state\":\"inDialog\""), "{after}");
    }

    #[test]
    fn keyword_update_changes_decision() {
        let mut state = EngineState::from_json(
            r#"{ "smartOptionEnabled": true, "blackScreenClickEnabled": false }"#,
        )
        .unwrap();
        state
            .set_keywords_json(r#"{ "select": ["确定"] }"#)
            .unwrap();
        state.tick_json(&observation(1_000, true)).unwrap();

        // 选项文字命中 select → 点该行中心 (300, 320)
        let observation = r#"{
          "nowMs": 1200,
          "screen": { "w": 1920, "h": 1080 },
          "capture": { "w": 1920, "h": 1080 },
          "foreground": true,
          "dialogue": true,
          "chatIcons": [{ "x": 100, "y": 500, "w": 20, "h": 20 }],
          "ocrLines": [{ "x": 200, "y": 300, "w": 200, "h": 40, "text": "确定" }]
        }"#;
        let result = state.tick_json(observation).unwrap();
        assert!(result.contains("\"x\":300"), "{result}");
        assert!(result.contains("\"y\":320"), "{result}");
    }

    #[test]
    fn invalid_keywords_json_is_an_error() {
        let mut state = EngineState::from_json("").unwrap();
        assert!(state.set_keywords_json("{ oops").is_err());
    }
}
