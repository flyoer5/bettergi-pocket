//! 触发层：自动对话状态机 + 引擎调度。
//!
//! 全部是纯逻辑：时间由外部注入，动作由外部执行，识别结果由外部提供。
//! 这样 CI 上不需要设备、不需要 OpenCV、也不需要 OCR 引擎就能跑完整单测。

pub mod autoskip;
pub mod engine;
pub mod keywords;

pub use autoskip::{
    screen_bottom_center, AutoSkip, AutoskipEvent, AutoskipInput, AutoskipOutput, CaptureMapping,
    OcrLine, State,
};
pub use engine::{Observation, TickResult, TriggerEngine};
pub use keywords::OptionKeywords;
