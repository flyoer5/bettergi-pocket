//! BetterGI Pocket 的核心数据模型。
//!
//! 这个 crate 刻意**零依赖**：只放几何、帧、动作、设置这些「谁都要用但不该引入平台代码」的
//! 东西，因此可以直接在 CI 的 host 上跑单测。

pub mod action;
pub mod frame;
pub mod geometry;
pub mod settings;

pub use action::{Action, DEFAULT_TAP_DURATION_MS, MIN_POINT_SEPARATION_PX};
pub use frame::{BgrImage, GrayImage, GrayView};
pub use geometry::{Point, Rect, Size};
pub use settings::TriggerSettings;
