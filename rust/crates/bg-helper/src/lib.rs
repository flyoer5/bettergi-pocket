//! root helper（`bgroot` 的 Rust 实现）的库部分。
//!
//! 拆成四块，前三块都能在 host 上单测：
//!
//! | 模块 | 职责 |
//! |---|---|
//! | [`protocol`] | 行协议的请求解析与响应格式 |
//! | [`foreground`] | 从 `dumpsys` 输出里提前台包名 |
//! | [`args`] | 命令行参数、`wm size` 输出解析 |
//! | [`shell`] | 探测 `sh`、带超时执行命令 |
//! | [`server`] | Unix socket 服务端与请求分发（注入走 `input` 命令） |

pub mod args;
pub mod foreground;
pub mod protocol;
pub mod server;
pub mod shell;
