# BetterGI Pocket · Rust 重构

这个目录是 Kotlin 实现的**算法层重写**。目标不是把 Kotlin 逐行翻译成 Rust，而是把三件
互相纠缠的东西拆开：

| 层 | 内容 | 能不能在 host 上单测 |
|---|---|---|
| 算法 | 模板匹配、参考坐标换算、OCR 文本规则 | ✅ 能 |
| 状态机 | 自动对话的三态流转、点击节流、黑屏判定 | ✅ 能 |
| 平台胶水 | 投屏、悬浮窗、无障碍、root helper、JNI | ❌ 需要真机 |

只要前两层不依赖 Android API，它们就能在 CI 上跑；真机只用来验证胶水。

## 目录

```
rust/
├── Cargo.toml                 workspace
└── crates/
    ├── bg-core/               几何 / 帧 / 动作 / 设置（零依赖）
    ├── bg-recognition/        模板匹配、参考搜索、资源解析、OCR 规则
    └── bg-trigger/            自动对话状态机 + 引擎调度
```

### bg-core

* `geometry` —— `Size` / `Rect` / `Point`，整数语义与 Android 侧 `IntSize` / `IntRect` 一致。
* `frame` —— `BgrImage` / `GrayImage` / `GrayView`。`GrayView` 带 stride，ROI 裁剪零拷贝。
* `action` —— 引擎只产出 `Action::Tap/Swipe/Back`，注入由平台层负责。
* `settings` —— 与 `TriggerSettings` 逐字段对应，含比例坐标→像素的换算。

### bg-recognition

* `template` —— 纯 Rust 模板匹配，覆盖 OpenCV 六种 `TM_*` 模式（含归一化与非归一化），
  并实现阈值过滤 + IoU 0.5 的贪心 NMS 多目标。**不再依赖 OpenCV `.so`**。
* `reference` —— 参考画布（如 3040×1904）坐标 → 当前截图 ROI 的换算：等比缩放、
  锚点选择（Auto 按三分区推断）、按像素/比例扩展、夹到画面内。
* `assets` —— 解析 `Recognition.json`：对象表、`@regions` / `@templates` 别名链（含循环检测）、
  `rect(x, y, w, h)` 表达式。
* `ocr` —— 去空格、错字替换表、包含/正则匹配。

### bg-trigger

* `autoskip` —— 自动对话状态机：`Idle → InDialog → Confirming`。
  时间由外部注入（毫秒），因此可以用假时钟把整条流程跑完：
  快速跳过固定点/附加点、感叹号优先、关键词决策、确认超时重试、黑屏转场。
* `keywords` —— 三张词表（select / pause / default_pause）与 JSONC 清洗。
* `engine` —— 前台门控 + 节拍调度（最近有动作时 200ms，否则 600ms）。

## 本地验证

```bash
cd rust
cargo fmt --all -- --check
cargo clippy --all-targets -- -D warnings
cargo test --all
```

## CI

`.github/workflows/rust.yml` 三个 job：

1. **fmt** —— `cargo fmt --all -- --check`，格式不一致直接失败。
2. **clippy** —— `-D warnings`，警告即失败。
3. **test** —— `cargo test --all`。

本地没有 Rust 工具链时，可以直接把分支推上去让 CI 当编译器：

```bash
gh workflow run rust.yml --ref <branch>
gh run watch
```

## 与 Kotlin 实现的关系

当前 Kotlin 版本仍是线上实现，Rust 侧按下面的顺序逐步接管，每一步都能独立验证：

| 阶段 | 内容 | 状态 |
|---|---|---|
| 1 | 算法层 + 状态机 + 单测 | ✅ 本目录 |
| 2 | `bg-helper`：用 Rust 重写 `bgroot`（Unix socket + 输入注入） | ⬜ 待做 |
| 3 | JNI 桥：把 `bg-recognition` / `bg-trigger` 暴露给 Kotlin | ⬜ 待做 |
| 4 | 平台层迁移：投屏、悬浮窗、无障碍服务 | ⬜ 待做 |

## 设计取舍

* **整数坐标**：识别坐标全用 `i32`，避免浮点在不同设备上累积误差；只有缩放比是 `f64`。
* **时间注入**：状态机不读系统时钟，测试用假时钟推进，避免 `sleep` 拖慢 CI。
* **零拷贝视图**：`GrayView` 带 stride，搜索框裁剪只是切片，不复制整帧。
* **不静默失败**：资源解析、正则编译的错误都返回 `Result`，由调用方决定降级还是报错。
