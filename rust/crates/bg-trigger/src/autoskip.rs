//! 自动对话状态机。
//!
//! 三个状态：`Idle`（找对话）→ `InDialog`（推进对话 / 选选项）→ `Confirming`（确认点击生效）。
//!
//! 这个模块只做决策：输入是「一帧识别结果」，输出是「要点的坐标 + 日志事件」。
//! 时间从外部注入（毫秒），因此整条状态机可以用假时钟在 CI 上跑。

use bg_core::{Action, Point, Rect, Size, TriggerSettings};
use bg_recognition::ocr;

use crate::keywords::OptionKeywords;

/// 一帧 OCR 出来的一行文字（坐标在识别图坐标系）。
#[derive(Debug, Clone, PartialEq)]
pub struct OcrLine {
    pub rect: Rect,
    pub text: String,
}

/// 识别图坐标 → 原生屏幕坐标的换算。
///
/// 识别前会把宽 > 1920 的截图缩到 1920 宽，命中坐标必须乘回去才能点击。
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CaptureMapping {
    pub capture: Size,
    pub native: Size,
}

impl CaptureMapping {
    pub fn new(capture: Size, native: Size) -> Self {
        CaptureMapping { capture, native }
    }

    /// 缩放比；识别图无效时退化为 1.0。
    pub fn scale(&self) -> f64 {
        if self.capture.is_empty() || self.native.is_empty() {
            return 1.0;
        }
        self.native.width as f64 / self.capture.width as f64
    }

    pub fn to_native(&self, point: Point) -> Point {
        let scale = self.scale();
        Point::new(
            (point.x as f64 * scale).round() as i32,
            (point.y as f64 * scale).round() as i32,
        )
    }

    pub fn rect_center_to_native(&self, rect: Rect) -> Point {
        self.to_native(rect.center())
    }

    pub fn capture_center_to_native(&self) -> Point {
        self.rect_center_to_native(Rect::new(0, 0, self.capture.width, self.capture.height))
    }
}

/// 一帧里自动对话需要的全部识别结果。
#[derive(Debug, Clone, PartialEq)]
pub struct AutoskipInput {
    pub screen: Size,
    pub mapping: CaptureMapping,
    /// 是否处于对话中（TalkHistory 模板命中，或左侧状态文字 OCR 命中）。
    pub dialogue: bool,
    /// 感叹号选项命中（识别图坐标）。
    pub exclamation_hits: Vec<Rect>,
    /// 对话选项图标命中（识别图坐标）。
    pub chat_hits: Vec<Rect>,
    /// 选项文字 OCR 结果（识别图坐标）。
    pub ocr_lines: Vec<OcrLine>,
    /// 画面中部黑屏比例，`None` 表示本拍没算。
    pub black_rate: Option<f64>,
}

impl AutoskipInput {
    pub fn new(screen: Size, mapping: CaptureMapping) -> Self {
        AutoskipInput {
            screen,
            mapping,
            dialogue: false,
            exclamation_hits: Vec::new(),
            chat_hits: Vec::new(),
            ocr_lines: Vec::new(),
            black_rate: None,
        }
    }
}

/// 一次 tick 产生的日志事件（给悬浮窗 / 日志面板展示）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AutoskipEvent {
    TalkHistoryMatched,
    IdleScan,
    OptionTexts(Vec<String>),
    ChatIconsRecognized { count: usize, x: i32, y: i32 },
    ChatIconClicked { x: i32, y: i32 },
    BlackScreenClicked { x: i32, y: i32 },
    AutoSkipLog(String),
}

/// tick 的输出。
#[derive(Debug, Clone, Default, PartialEq)]
pub struct AutoskipOutput {
    pub actions: Vec<Action>,
    pub events: Vec<AutoskipEvent>,
}

impl AutoskipOutput {
    fn tap(&mut self, point: Point) {
        self.actions.push(Action::tap(point));
    }

    fn log(&mut self, message: impl Into<String>) {
        self.events.push(AutoskipEvent::AutoSkipLog(message.into()));
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum State {
    #[default]
    Idle,
    InDialog,
    Confirming,
}

// ---- 与 Android 侧一致的常量 ----

/// 点击选项后的静默窗口：这段时间内不再做新的选项决策。
pub const CONFIRM_WINDOW_MS: u64 = 600;
/// 确认阶段的超时：选项一直没变化就允许重试。
pub const CONFIRM_TIMEOUT_MS: u64 = 1_200;
/// 选项 OCR 决策节流。
pub const OPTION_DECISION_INTERVAL_MS: u64 = 1_000;
/// 锚点容差（识别图像素，基准 1920 宽）。
pub const OPTION_ANCHOR_TOLERANCE_PX: i32 = 32;
/// 相邻选项行 Y 间距上限（1080P 基准，对齐桌面版 150）。
pub const OPTION_MAX_Y_GAP: i32 = 150;
/// 黑屏比例下限 / 上限（上限排除纯黑遮挡接口的情况）。
pub const BLACK_RATE_MIN: f64 = 0.5;
pub const BLACK_RATE_MAX: f64 = 0.98999;
/// 空闲 / 跳过日志节流。
pub const IDLE_LOG_INTERVAL_MS: u64 = 5_000;
pub const SKIP_LOG_INTERVAL_MS: u64 = 5_000;
/// 计算选项 OCR 框时用的基准宽度。
pub const BASELINE_WIDTH: f64 = 1920.0;

/// 自动对话状态机。
#[derive(Debug, Clone, Default)]
pub struct AutoSkip {
    state: State,
    clicked_option: Option<Point>,
    clicked_at_ms: Option<u64>,
    last_option_decision_at_ms: Option<u64>,
    last_black_click_ms: Option<u64>,
    last_idle_log_ms: Option<u64>,
    last_skip_log_ms: Option<u64>,
    last_skip_click_ms: Option<u64>,
}

impl AutoSkip {
    pub fn new() -> Self {
        AutoSkip::default()
    }

    pub fn state(&self) -> State {
        self.state
    }

    /// 停止任务时调用：状态机必须回到 Idle，否则下次启动会卡在 Confirming。
    pub fn reset(&mut self) {
        *self = AutoSkip::default();
    }

    /// 推进一拍。
    pub fn tick(
        &mut self,
        now_ms: u64,
        input: &AutoskipInput,
        settings: &TriggerSettings,
        keywords: &OptionKeywords,
    ) -> AutoskipOutput {
        let mut output = AutoskipOutput::default();
        match self.state {
            State::Idle => self.tick_idle(now_ms, input, settings, &mut output),
            State::InDialog => self.tick_in_dialog(now_ms, input, settings, keywords, &mut output),
            State::Confirming => self.tick_confirming(now_ms, input, &mut output),
        }
        output
    }

    fn tick_idle(
        &mut self,
        now_ms: u64,
        input: &AutoskipInput,
        settings: &TriggerSettings,
        output: &mut AutoskipOutput,
    ) {
        if input.dialogue {
            output.events.push(AutoskipEvent::TalkHistoryMatched);
            self.state = State::InDialog;
            return;
        }
        if settings.black_screen_click_enabled {
            self.click_black_screen_if_needed(now_ms, input, settings, output);
        }
        if elapsed_since(self.last_idle_log_ms, now_ms) >= IDLE_LOG_INTERVAL_MS {
            self.last_idle_log_ms = Some(now_ms);
            output.events.push(AutoskipEvent::IdleScan);
        }
    }

    fn tick_in_dialog(
        &mut self,
        now_ms: u64,
        input: &AutoskipInput,
        settings: &TriggerSettings,
        keywords: &OptionKeywords,
        output: &mut AutoskipOutput,
    ) {
        if !input.dialogue {
            self.state = State::Idle;
            return;
        }
        output.events.push(AutoskipEvent::TalkHistoryMatched);

        if settings.quick_skip_dialogue_enabled {
            self.click_skip_points(now_ms, input, settings, output);
        }

        // 刚点过选项的静默窗口内，不再做新的选项决策。
        let in_confirm_window = match (self.clicked_at_ms, self.clicked_option) {
            (Some(clicked_at), Some(_)) => now_ms < clicked_at.saturating_add(CONFIRM_WINDOW_MS),
            _ => false,
        };
        if in_confirm_window {
            return;
        }

        // 1) 感叹号选项优先级最高
        if settings.smart_option_enabled && settings.exclamation_click_enabled {
            if let Some(first) = input.exclamation_hits.first().copied() {
                let point = input.mapping.rect_center_to_native(first);
                output.log(format!("点击感叹号选项 ({}, {})", point.x, point.y));
                output.tap(point);
                output.events.push(AutoskipEvent::ChatIconClicked {
                    x: point.x,
                    y: point.y,
                });
                self.clicked_option = Some(first.center());
                self.clicked_at_ms = Some(now_ms);
                self.state = State::Confirming;
                return;
            }
        }

        // 2) 普通选项：OCR + 关键词决策（节流）
        if elapsed_since(self.last_option_decision_at_ms, now_ms) < OPTION_DECISION_INTERVAL_MS {
            return;
        }
        self.last_option_decision_at_ms = Some(now_ms);

        let decision = if settings.smart_option_enabled {
            decide_option(input, keywords, output)
        } else {
            select_top_chat_icon(&input.chat_hits).map(|rect| Decision { rect })
        };
        let Some(decision) = decision else {
            return;
        };

        let point = input.mapping.rect_center_to_native(decision.rect);
        output.events.push(AutoskipEvent::ChatIconsRecognized {
            count: input.chat_hits.len(),
            x: point.x,
            y: point.y,
        });
        output.log(format!("点击选项 ({}, {})", point.x, point.y));
        output.tap(point);
        output.events.push(AutoskipEvent::ChatIconClicked {
            x: point.x,
            y: point.y,
        });

        // 确认锚点必须和 Confirming 阶段比较的对象同源（都是 ChatIcon 命中）。
        let anchor = nearest_chat_icon(&input.chat_hits, decision.rect);
        self.clicked_option = Some(anchor.unwrap_or(decision.rect).center());
        self.clicked_at_ms = Some(now_ms);
        self.state = State::Confirming;
    }

    fn tick_confirming(&mut self, now_ms: u64, input: &AutoskipInput, output: &mut AutoskipOutput) {
        if !input.dialogue {
            output.log("对话已结束（点击生效）");
            self.state = State::Idle;
            return;
        }

        let still_there = match self.clicked_option {
            Some(anchor) => input.chat_hits.iter().any(|hit| {
                (hit.x - anchor.x).abs() <= OPTION_ANCHOR_TOLERANCE_PX
                    && (hit.y - anchor.y).abs() <= OPTION_ANCHOR_TOLERANCE_PX
            }),
            None => false,
        };

        if !still_there {
            output.log("选项已变化，点击生效");
            self.state = State::InDialog;
            self.clicked_option = None;
            return;
        }

        let timed_out = match self.clicked_at_ms {
            Some(clicked_at) => now_ms.saturating_sub(clicked_at) >= CONFIRM_TIMEOUT_MS,
            None => true,
        };
        if timed_out {
            output.log("选项未变化，超时重试");
            self.state = State::InDialog;
            self.clicked_at_ms = None;
            self.clicked_option = None;
        }
    }

    /// 快速跳过：底部中央固定点（原版行为）+ 可选附加点，过近则合并成一次点击。
    fn click_skip_points(
        &mut self,
        now_ms: u64,
        input: &AutoskipInput,
        settings: &TriggerSettings,
        output: &mut AutoskipOutput,
    ) {
        if elapsed_since(self.last_skip_click_ms, now_ms) < settings.skip_click_interval_ms as u64 {
            return;
        }
        self.last_skip_click_ms = Some(now_ms);

        let points = self.collect_click_points(
            input,
            settings,
            screen_bottom_center(input.screen),
            settings.quick_skip_custom_position,
            (
                settings.quick_skip_position_x,
                settings.quick_skip_position_y,
            ),
        );
        if elapsed_since(self.last_skip_log_ms, now_ms) >= SKIP_LOG_INTERVAL_MS {
            self.last_skip_log_ms = Some(now_ms);
            let rendered: Vec<String> = points
                .iter()
                .map(|p| format!("({}, {})", p.x, p.y))
                .collect();
            output.log(format!("点击跳过 {}", rendered.join(" / ")));
        }
        for point in points {
            output.tap(point);
        }
    }

    /// 黑屏转场：画面中部 1/3 足够黑时点中央（+ 可选附加点）。
    fn click_black_screen_if_needed(
        &mut self,
        now_ms: u64,
        input: &AutoskipInput,
        settings: &TriggerSettings,
        output: &mut AutoskipOutput,
    ) {
        if elapsed_since(self.last_black_click_ms, now_ms) < settings.black_click_interval_ms as u64
        {
            return;
        }
        let Some(rate) = input.black_rate else {
            return;
        };
        if !(BLACK_RATE_MIN..BLACK_RATE_MAX).contains(&rate) {
            return;
        }

        let center = input.mapping.capture_center_to_native();
        let points = self.collect_click_points(
            input,
            settings,
            center,
            settings.black_screen_custom_position,
            (
                settings.black_screen_position_x,
                settings.black_screen_position_y,
            ),
        );
        output.events.push(AutoskipEvent::BlackScreenClicked {
            x: center.x,
            y: center.y,
        });
        let rendered: Vec<String> = points
            .iter()
            .map(|p| format!("({}, {})", p.x, p.y))
            .collect();
        output.log(format!(
            "黑屏转场 rate={rate:.3}，点击 {}",
            rendered.join("/")
        ));
        for point in points {
            output.tap(point);
        }
        self.last_black_click_ms = Some(now_ms);
    }

    fn collect_click_points(
        &self,
        input: &AutoskipInput,
        settings: &TriggerSettings,
        fixed: Point,
        custom_enabled: bool,
        custom_ratio: (f32, f32),
    ) -> Vec<Point> {
        let mut points = vec![fixed];
        if custom_enabled {
            let (x, y) = settings.resolve_position(
                custom_ratio.0,
                custom_ratio.1,
                input.screen.width,
                input.screen.height,
            );
            let custom = Point::new(x, y);
            if fixed.distance_sq(custom) >= (bg_core::MIN_POINT_SEPARATION_PX as i64).pow(2) {
                points.push(custom);
            }
        }
        points
    }
}

/// 屏幕底部中央固定点，距底 20 像素（与桌面版一致）。
pub fn screen_bottom_center(screen: Size) -> Point {
    Point::new(screen.width / 2, (screen.height - 20).max(0))
}

struct Decision {
    rect: Rect,
}

/// 取最上面的对话图标。
pub fn select_top_chat_icon(hits: &[Rect]) -> Option<Rect> {
    hits.iter().copied().min_by_key(|hit| hit.y)
}

/// 与目标同排最近的对话图标（作为确认锚点）。
fn nearest_chat_icon(hits: &[Rect], target: Rect) -> Option<Rect> {
    hits.iter()
        .copied()
        .min_by_key(|hit| (hit.y - target.y).abs())
}

/// 关键词决策：
/// 以最下方气泡为基准算固定宽度 OCR 框 → 按 Y 排序 → 过滤无效行 →
/// `select` > `pause` > `default_pause` > 兜底最低项。
fn decide_option(
    input: &AutoskipInput,
    keywords: &OptionKeywords,
    output: &mut AutoskipOutput,
) -> Option<Decision> {
    if input.chat_hits.is_empty() {
        return None;
    }
    let lowest = input.chat_hits.iter().copied().max_by_key(|hit| hit.y)?;
    let scale = input.mapping.capture.width as f64 / BASELINE_WIDTH;
    let region_width = input.mapping.capture.width;
    let region_height = input.mapping.capture.height;

    let ocr_left = ((lowest.x + lowest.width) as f64 + 8.0 * scale) as i32;
    let ocr_left = ocr_left.min(region_width - 1);
    let ocr_width = ((535.0 * scale) as i32).max(80);
    let ocr_top = (region_height / 12).max(0);
    let ocr_bottom = ((lowest.y + lowest.height) as f64 + 30.0 * scale) as i32;
    let ocr_bottom = ocr_bottom.min(region_height);
    let ocr_height = ocr_bottom - ocr_top;
    if ocr_left < 0 || ocr_width <= 0 || ocr_height <= 0 {
        return Some(Decision { rect: lowest });
    }
    let ocr_region = Rect::new(ocr_left, ocr_top, ocr_width, ocr_height);

    // 平台层通常对整帧或大区域做 OCR，这里按几何过滤出选项区内的行。
    let mut lines: Vec<&OcrLine> = input
        .ocr_lines
        .iter()
        .filter(|line| ocr_region.contains_point(line.rect.center()))
        .collect();
    lines.sort_by_key(|line| line.rect.y);

    let max_y_gap = (OPTION_MAX_Y_GAP as f64 * scale) as i32;
    let filtered: Vec<&OcrLine> = lines
        .iter()
        .enumerate()
        .filter(|(index, line)| {
            let text = line.text.trim();
            if text.is_empty() {
                return false;
            }
            if text.chars().count() < 5
                && text
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || c == ' ' || c == '.')
            {
                return false;
            }
            if *index != lines.len() - 1 {
                let gap = lines[index + 1].rect.y - line.rect.y;
                if gap > max_y_gap {
                    output.log(format!("Y 轴偏差过大，忽略：{text}"));
                    return false;
                }
            }
            true
        })
        .map(|(_, line)| *line)
        .collect();

    if filtered.is_empty() {
        return Some(Decision { rect: lowest });
    }

    output.events.push(AutoskipEvent::OptionTexts(
        filtered.iter().map(|line| line.text.clone()).collect(),
    ));

    for line in &filtered {
        if keywords.matches_select(&line.text) {
            output.log(format!("主动选择命中：{}", line.text));
            return Some(Decision { rect: line.rect });
        }
        if keywords.matches_pause(&line.text) {
            output.log(format!("命中暂停词，等待手动选择：{}", line.text));
            return None;
        }
    }
    for line in &filtered {
        if keywords.matches_default_pause(&line.text) {
            output.log(format!("命中默认暂停词，等待手动选择：{}", line.text));
            return None;
        }
    }

    output.log("无关键词命中，点最低选项");
    Some(Decision {
        rect: filtered.last()?.rect,
    })
}

/// OCR 规整后的文本判断：调用方负责把 `ocr::normalize` 的结果喂给关键词表。
pub fn normalize_option_text(text: &str) -> String {
    ocr::remove_all_space(text)
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

    fn screen_1080p() -> Size {
        Size::new(1920, 1080)
    }

    fn input_at() -> AutoskipInput {
        let screen = screen_1080p();
        AutoskipInput::new(screen, CaptureMapping::new(screen, screen))
    }

    fn dialogue_input() -> AutoskipInput {
        let mut input = input_at();
        input.dialogue = true;
        input
    }

    fn keywords(select: &[&str], pause: &[&str], default_pause: &[&str]) -> OptionKeywords {
        let owned = |items: &[&str]| items.iter().map(|item| (*item).to_string()).collect();
        OptionKeywords::new(owned(select), owned(pause), owned(default_pause))
    }

    fn lines(pairs: &[(i32, i32, &str)]) -> Vec<OcrLine> {
        pairs
            .iter()
            .map(|(x, y, text)| OcrLine {
                rect: Rect::new(*x, *y, 200, 40),
                text: (*text).to_string(),
            })
            .collect()
    }

    #[test]
    fn idle_enters_dialog_without_clicking() {
        let mut machine = AutoSkip::new();
        let output = machine.tick(
            1_000,
            &dialogue_input(),
            &TriggerSettings::default(),
            &OptionKeywords::default(),
        );
        assert!(output.actions.is_empty());
        assert_eq!(machine.state(), State::InDialog);
        assert!(output.events.contains(&AutoskipEvent::TalkHistoryMatched));
    }

    #[test]
    fn quick_skip_uses_bottom_center_and_throttles() {
        let settings = TriggerSettings {
            quick_skip_dialogue_enabled: true,
            skip_click_interval_ms: 500,
            ..Default::default()
        };
        let mut machine = AutoSkip::new();
        machine.tick(
            1_000,
            &dialogue_input(),
            &settings,
            &OptionKeywords::default(),
        );

        let first = machine.tick(
            1_100,
            &dialogue_input(),
            &settings,
            &OptionKeywords::default(),
        );
        assert_eq!(first.actions, vec![Action::tap(Point::new(960, 1060))]);

        let throttled = machine.tick(
            1_200,
            &dialogue_input(),
            &settings,
            &OptionKeywords::default(),
        );
        assert!(throttled.actions.is_empty(), "节流窗口内不应重复点击");

        let later = machine.tick(
            1_600,
            &dialogue_input(),
            &settings,
            &OptionKeywords::default(),
        );
        assert_eq!(later.actions.len(), 1);
    }

    #[test]
    fn quick_skip_adds_custom_point_when_far_enough() {
        let settings = TriggerSettings {
            quick_skip_dialogue_enabled: true,
            quick_skip_custom_position: true,
            quick_skip_position_x: 0.5,
            quick_skip_position_y: 0.5,
            ..Default::default()
        };
        let mut machine = AutoSkip::new();
        machine.tick(
            1_000,
            &dialogue_input(),
            &settings,
            &OptionKeywords::default(),
        );
        let output = machine.tick(
            1_100,
            &dialogue_input(),
            &settings,
            &OptionKeywords::default(),
        );
        assert_eq!(
            output.actions,
            vec![
                Action::tap(Point::new(960, 1060)),
                Action::tap(Point::new(960, 540))
            ]
        );
    }

    #[test]
    fn quick_skip_merges_points_that_are_too_close() {
        let settings = TriggerSettings {
            quick_skip_dialogue_enabled: true,
            quick_skip_custom_position: true,
            quick_skip_position_x: 0.5,
            quick_skip_position_y: 0.98,
            ..Default::default()
        };
        let mut machine = AutoSkip::new();
        machine.tick(
            1_000,
            &dialogue_input(),
            &settings,
            &OptionKeywords::default(),
        );
        let output = machine.tick(
            1_100,
            &dialogue_input(),
            &settings,
            &OptionKeywords::default(),
        );
        assert_eq!(output.actions.len(), 1, "相距 2px 应合并为一次点击");
    }

    #[test]
    fn black_screen_click_in_idle() {
        let settings = TriggerSettings::default();
        let mut machine = AutoSkip::new();
        let mut input = input_at();
        input.black_rate = Some(0.6);
        let output = machine.tick(1_000, &input, &settings, &OptionKeywords::default());
        assert_eq!(output.actions, vec![Action::tap(Point::new(960, 540))]);
        assert_eq!(
            output.events[0],
            AutoskipEvent::BlackScreenClicked { x: 960, y: 540 }
        );

        // 黑屏点击间隔 1200ms
        let throttled = machine.tick(1_500, &input, &settings, &OptionKeywords::default());
        assert!(throttled.actions.is_empty());
        let later = machine.tick(2_300, &input, &settings, &OptionKeywords::default());
        assert_eq!(later.actions.len(), 1);
    }

    #[test]
    fn black_screen_rate_out_of_range_does_not_click() {
        let settings = TriggerSettings::default();
        let mut machine = AutoSkip::new();
        for rate in [0.2, 0.99, 1.0] {
            let mut input = input_at();
            input.black_rate = Some(rate);
            let output = machine.tick(1_000, &input, &settings, &OptionKeywords::default());
            assert!(output.actions.is_empty(), "rate={rate} 不应点击");
        }
    }

    #[test]
    fn missing_black_rate_does_not_click() {
        let settings = TriggerSettings::default();
        let mut machine = AutoSkip::new();
        let output = machine.tick(1_000, &input_at(), &settings, &OptionKeywords::default());
        assert!(output.actions.is_empty());
    }

    #[test]
    fn exclamation_option_has_priority() {
        let settings = TriggerSettings::default();
        let mut machine = AutoSkip::new();
        machine.tick(
            1_000,
            &dialogue_input(),
            &settings,
            &OptionKeywords::default(),
        );

        let mut input = dialogue_input();
        input.exclamation_hits = vec![Rect::new(100, 200, 20, 20)];
        input.chat_hits = vec![Rect::new(300, 300, 20, 20)];
        let output = machine.tick(1_200, &input, &settings, &OptionKeywords::default());
        assert_eq!(output.actions, vec![Action::tap(Point::new(110, 210))]);
        assert_eq!(machine.state(), State::Confirming);
    }

    #[test]
    fn falls_back_to_lowest_option_without_ocr_text() {
        let settings = TriggerSettings::default();
        let mut machine = AutoSkip::new();
        machine.tick(
            1_000,
            &dialogue_input(),
            &settings,
            &OptionKeywords::default(),
        );

        let mut input = dialogue_input();
        input.chat_hits = vec![Rect::new(100, 300, 20, 20), Rect::new(100, 600, 20, 20)];
        let output = machine.tick(1_200, &input, &settings, &OptionKeywords::default());
        assert_eq!(output.actions, vec![Action::tap(Point::new(110, 610))]);
    }

    #[test]
    fn select_keyword_wins() {
        let settings = TriggerSettings::default();
        let mut machine = AutoSkip::new();
        machine.tick(
            1_000,
            &dialogue_input(),
            &settings,
            &OptionKeywords::default(),
        );

        let mut input = dialogue_input();
        input.chat_hits = vec![Rect::new(100, 500, 20, 20)];
        input.ocr_lines = lines(&[(200, 300, "那么，确定吧"), (200, 400, "再想想")]);
        let output = machine.tick(1_200, &input, &settings, &keywords(&["确定"], &[], &[]));
        assert_eq!(output.actions, vec![Action::tap(Point::new(300, 320))]);
    }

    #[test]
    fn pause_keyword_skips_the_tick() {
        let settings = TriggerSettings::default();
        let mut machine = AutoSkip::new();
        machine.tick(
            1_000,
            &dialogue_input(),
            &settings,
            &OptionKeywords::default(),
        );

        let mut input = dialogue_input();
        input.chat_hits = vec![Rect::new(100, 500, 20, 20)];
        input.ocr_lines = lines(&[(200, 300, "先暂停一下")]);
        let output = machine.tick(1_200, &input, &settings, &keywords(&[], &["暂停"], &[]));
        assert!(output.actions.is_empty());
        assert!(output
            .events
            .iter()
            .any(|event| matches!(event, AutoskipEvent::AutoSkipLog(msg) if msg.contains("暂停"))));
    }

    #[test]
    fn default_pause_keyword_skips_after_select_check() {
        let settings = TriggerSettings::default();
        let mut machine = AutoSkip::new();
        machine.tick(
            1_000,
            &dialogue_input(),
            &settings,
            &OptionKeywords::default(),
        );

        let mut input = dialogue_input();
        input.chat_hits = vec![Rect::new(100, 500, 20, 20)];
        input.ocr_lines = lines(&[(200, 300, "离开这里")]);
        let output = machine.tick(1_200, &input, &settings, &keywords(&[], &[], &["离开"]));
        assert!(output.actions.is_empty());
    }

    #[test]
    fn option_decision_is_throttled_to_one_second() {
        let settings = TriggerSettings::default();
        let mut machine = AutoSkip::new();
        machine.tick(
            1_000,
            &dialogue_input(),
            &settings,
            &OptionKeywords::default(),
        );

        let mut input = dialogue_input();
        input.chat_hits = vec![Rect::new(100, 500, 20, 20)];
        let first = machine.tick(1_100, &input, &settings, &OptionKeywords::default());
        assert_eq!(first.actions.len(), 1);

        // 点击后进入 Confirming；即使立刻回到 InDialog，1 秒内也不应再次决策。
        let mut changed = dialogue_input();
        changed.chat_hits = vec![Rect::new(100, 900, 20, 20)];
        machine.tick(1_200, &changed, &settings, &OptionKeywords::default());
        assert_eq!(machine.state(), State::InDialog);

        let throttled = machine.tick(1_500, &input, &settings, &OptionKeywords::default());
        assert!(throttled.actions.is_empty(), "决策节流 1000ms");
    }

    #[test]
    fn confirming_returns_to_dialog_when_option_changes() {
        let settings = TriggerSettings::default();
        let mut machine = AutoSkip::new();
        machine.tick(
            1_000,
            &dialogue_input(),
            &settings,
            &OptionKeywords::default(),
        );

        let mut input = dialogue_input();
        input.chat_hits = vec![Rect::new(100, 500, 20, 20)];
        machine.tick(1_100, &input, &settings, &OptionKeywords::default());
        assert_eq!(machine.state(), State::Confirming);

        let mut changed = dialogue_input();
        changed.chat_hits = vec![Rect::new(100, 900, 20, 20)];
        let output = machine.tick(1_300, &changed, &settings, &OptionKeywords::default());
        assert_eq!(machine.state(), State::InDialog);
        assert!(output.events.iter().any(
            |event| matches!(event, AutoskipEvent::AutoSkipLog(msg) if msg.contains("选项已变化"))
        ));
    }

    #[test]
    fn confirming_times_out_and_retries() {
        let settings = TriggerSettings::default();
        let mut machine = AutoSkip::new();
        machine.tick(
            1_000,
            &dialogue_input(),
            &settings,
            &OptionKeywords::default(),
        );

        let mut input = dialogue_input();
        input.chat_hits = vec![Rect::new(100, 500, 20, 20)];
        machine.tick(1_100, &input, &settings, &OptionKeywords::default());
        assert_eq!(machine.state(), State::Confirming);

        // 选项仍在原处且超过 1200ms → 允许重试
        let output = machine.tick(2_400, &input, &settings, &OptionKeywords::default());
        assert_eq!(machine.state(), State::InDialog);
        assert!(output.events.iter().any(
            |event| matches!(event, AutoskipEvent::AutoSkipLog(msg) if msg.contains("超时重试"))
        ));
    }

    #[test]
    fn confirming_ends_when_dialogue_disappears() {
        let settings = TriggerSettings::default();
        let mut machine = AutoSkip::new();
        machine.tick(
            1_000,
            &dialogue_input(),
            &settings,
            &OptionKeywords::default(),
        );

        let mut input = dialogue_input();
        input.chat_hits = vec![Rect::new(100, 500, 20, 20)];
        machine.tick(1_100, &input, &settings, &OptionKeywords::default());

        let mut ended = input_at();
        ended.dialogue = false;
        machine.tick(1_200, &ended, &settings, &OptionKeywords::default());
        assert_eq!(machine.state(), State::Idle);
    }

    #[test]
    fn ocr_lines_outside_option_region_are_ignored() {
        let settings = TriggerSettings::default();
        let mut machine = AutoSkip::new();
        machine.tick(
            1_000,
            &dialogue_input(),
            &settings,
            &OptionKeywords::default(),
        );

        let mut input = dialogue_input();
        input.chat_hits = vec![Rect::new(100, 500, 20, 20)];
        // x=1500 在选项文字区（x 128..663）之外
        input.ocr_lines = lines(&[(1500, 300, "确定")]);
        let output = machine.tick(1_200, &input, &settings, &keywords(&["确定"], &[], &[]));
        // 命中不到关键词，兜底点最低的对话图标
        assert_eq!(output.actions, vec![Action::tap(Point::new(110, 510))]);
    }

    #[test]
    fn short_ascii_lines_are_filtered_out() {
        let settings = TriggerSettings::default();
        let mut machine = AutoSkip::new();
        machine.tick(
            1_000,
            &dialogue_input(),
            &settings,
            &OptionKeywords::default(),
        );

        let mut input = dialogue_input();
        input.chat_hits = vec![Rect::new(100, 500, 20, 20)];
        input.ocr_lines = lines(&[(200, 300, "abc")]);
        let output = machine.tick(1_200, &input, &settings, &keywords(&["abc"], &[], &[]));
        // "abc" 只有 3 个 ASCII 字符，被过滤 → 兜底点图标，而不是命中关键词
        assert_eq!(output.actions, vec![Action::tap(Point::new(110, 510))]);
    }

    #[test]
    fn y_gap_filter_drops_lines_that_are_too_far_apart() {
        let settings = TriggerSettings::default();
        let mut machine = AutoSkip::new();
        machine.tick(
            1_000,
            &dialogue_input(),
            &settings,
            &OptionKeywords::default(),
        );

        let mut input = dialogue_input();
        input.chat_hits = vec![Rect::new(100, 500, 20, 20)];
        input.ocr_lines = lines(&[(200, 300, "第一行选项"), (200, 460, "第二行选项")]);
        let output = machine.tick(1_200, &input, &settings, &OptionKeywords::default());
        let texts: Vec<Vec<String>> = output
            .events
            .iter()
            .filter_map(|event| match event {
                AutoskipEvent::OptionTexts(texts) => Some(texts.clone()),
                _ => None,
            })
            .collect();
        assert_eq!(texts, vec![vec!["第二行选项".to_string()]]);
    }

    #[test]
    fn screen_bottom_center_is_twenty_pixels_above_bottom() {
        assert_eq!(
            screen_bottom_center(Size::new(1920, 1080)),
            Point::new(960, 1060)
        );
        assert_eq!(
            screen_bottom_center(Size::new(1080, 240)),
            Point::new(540, 220)
        );
        assert_eq!(screen_bottom_center(Size::new(100, 10)), Point::new(50, 0));
    }

    #[test]
    fn select_top_chat_icon_picks_smallest_y() {
        let hits = vec![Rect::new(0, 300, 10, 10), Rect::new(0, 100, 10, 10)];
        assert_eq!(select_top_chat_icon(&hits), Some(Rect::new(0, 100, 10, 10)));
        assert_eq!(select_top_chat_icon(&[]), None);
    }

    #[test]
    fn normalize_option_text_removes_spaces() {
        assert_eq!(normalize_option_text(" 确 定 "), "确定");
    }
}
