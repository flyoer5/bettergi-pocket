//! 识别层：不碰 Android API，全部可在 host 上跑单测。
//!
//! 组成：
//! * [`template`] —— 纯 Rust 模板匹配（对齐 OpenCV 六种模式 + NMS 多目标）
//! * [`reference`] —— 参考画布坐标 → 当前截图 ROI 的换算
//! * [`assets`] —— `Recognition.json` 解析
//! * [`ocr`] —— OCR 文本规整与匹配规则

pub mod assets;
pub mod ocr;
pub mod reference;
pub mod template;

pub use assets::{RecognitionCatalog, RecognitionObject, RecognitionType};
pub use reference::{AnchorMode, ExpandRatio, ReferenceSearchResult, SearchOptions};
pub use template::{find_matches, match_template, MatchGrid, MatchMode, MatchScore};
