# rust/

BetterGI Pocket 的 Rust 侧代码。它负责「看图 + 决策」，不负责「怎么拿到图」和「怎么点下去」；
投屏、悬浮窗、无障碍、root 注入这些仍然留在 Kotlin/Java 那一边，等接口稳定后再逐个替换。

## 目录

```
rust/
├── Cargo.toml             workspace（三个 crate）
└── crates/
    ├── bg-core/
    ├── bg-recognition/
    ├── bg-trigger/
    ├── bg-helper/          root helper（产出可执行文件 bgroot）
    └── bg-jni/             给 Kotlin 用的 JNI 桥（产出 libbg_jni.so）
```

## 跑检查

```bash
cd rust
cargo fmt --all -- --check
cargo clippy --all-targets -- -D warnings
cargo test --all
```

手机或没装工具链的机器上，直接推分支让 CI 跑也行（见下）。

## bg-core

基础类型，零第三方依赖。

| 模块 | 内容 |
|---|---|
| `geometry` | `Size` / `Rect` / `Point`；`Rect` 有 `iou` / `intersection` / `clamp_to` / `inflate` |
| `frame` | `BgrImage` → `GrayImage`；`GrayView<'a>` 带 stride，`roi()` 只做切片 |
| `action` | `Action::{Tap, Swipe, Back}`，以及附加点去重用的 `MIN_POINT_SEPARATION_PX` |
| `settings` | `TriggerSettings`，字段与 Android 侧同名同义，含比例坐标→像素换算 |

坐标一律 `i32`（与 Android 的 `IntRect` 对齐），只有缩放比用 `f64`。

## bg-recognition

只依赖 `serde_json` 与 `regex`，不链接 OpenCV。

**模板匹配**（`template`）

```rust
match_template(&source_view, &template_view, MatchMode::CCoeffNormed) // -> Option<MatchGrid>
find_matches(&source, &template, mode, threshold, max_count)          // -> Vec<MatchScore>
```

* 六种模式齐全：`SqDiff`、`SqDiffNormed`、`CCorr`、`CCorrNormed`、`CCoeff`、`CCoeffNormed`。
* 非归一化模式会先做 min-max 归一化再比阈值（OpenCV 的既有行为）。
* 返回的 `score` 统一成「越大越好」，lower-better 模式内部取 `1 - raw`。
* 多目标走贪心 NMS，重叠阈值 `IoU >= 0.5` 即抑制。
* 全黑窗口的除零按「最差」处理，避免大片纯黑被判成完美匹配。

**参考坐标换算**（`reference`）

模板是在参考画布（如 3040×1904）上标注的，上机前要换算：

```rust
try_get_region(src_size, roi, ref_size, ref_bbox, search_options, can_use_reference, is_template_match)
```

等比缩放 → 选锚点（`Auto` 按画布三分区推断左右/上下）→ 以 `search.box` 或 bbox 为基准 →
按 `expandPercent` / `expand` 扩展 → 夹进画面。返回 `None` 表示这一帧不做该识别。

**资源解析**（`assets`）

`RecognitionCatalog::from_json_str` 解析 `Recognition.json`：对象表、`@regions` / `@templates`
别名链（带循环检测）、`rect(x, y, w, h)` 表达式。只认识 `TemplateMatch` / `Ocr` /
`OcrMatch` / `ColorRangeAndOcr` 四种类型，其余直接报错，不做静默降级。

**OCR 文本**（`ocr`）

去空白 → 按错字表替换 → `all_contain` / `one_contain` / `regex` 三条规则。正则编译失败
会返回 `Err`，由调用方决定怎么处理。

## bg-trigger

纯逻辑，**不读系统时钟**：`now_ms: u64` 由调用方传入，测试用假时钟推进。

```rust
let mut machine = AutoSkip::new();
let out = machine.tick(now_ms, &input, &settings, &keywords);
// out.actions: Vec<Action>，out.events: Vec<AutoskipEvent>
```

状态流转：`Idle`（等对话）→ `InDialog`（推进 / 选选项）→ `Confirming`（验证点击是否生效）。
`Confirming` 里选项变化就回 `InDialog`，超过 1200ms 没变化则允许重试。

要点：

* 感叹号选项优先于普通选项；
* 快速跳过点「屏幕底部中央 (w/2, h-20)」，开了附加点则**叠加**，两点距 < 32px 合并；
* 选项决策节流 1000ms，确认静默窗 600ms；
* 黑屏转场要求画面中部黑占比落在 `[0.5, 0.98999)`，点画面中央（可叠加附加点）。

`TriggerEngine` 在外面再包一层：前台不是原神时直接不产生动作（识别跑在屏幕流上，
桌面图标同样可能命中模板），并据此给出下一拍延迟——最近有动作 200ms，否则 600ms。

`OptionKeywords` 管三张词表（select / pause / default_pause），顺带实现桌面版词表的
JSONC 清洗（去掉 `//` 注释行与尾逗号）。

## bg-helper

`bgroot` 的 Rust 实现，编译产物是可执行文件 `bgroot`。它由 app 用 `su -c` 拉起，
经 Unix socket 通信，行协议与 Kotlin 侧 `RootBridge` 完全兼容。

| 模块 | 内容 |
|---|---|
| `protocol` | 请求解析（`PING` / `PROBE` / `TAP` / `LONG` / `SWIPE` / `PID` / `KEEPALIVE` / `BACK` / `FG` / `QUIT`）与响应格式 |
| `foreground` | 从 `dumpsys` 输出按 `/` 提前台包名，兼容 `mCurrentFocus`、`topResumedActivity` 等格式 |
| `args` | 命令行参数、`wm size` 输出解析（Override size 优先） |
| `shell` | 依次探测 `/system/bin/sh` → `/bin/sh` → Magisk busybox，带超时执行 |
| `server` | Unix socket 监听、`SO_PEERCRED` 校验、连接处理、app 存活监视 |

几处刻意的选择：

* **注入走 `input` 命令**，不创建 uinput 虚拟设备。uinput 事件在米哈游引擎里不生效
  （光圈出现但游戏无响应），`input` 走的是 InputManager 正规管线。
* **包名先校验再拼命令**：只允许字母数字与 `.` / `_`，避免命令注入。
* **socket 交给 app uid**：`chmod 0600` + `chown <uid>`，连接时再对 `SO_PEERCRED` 做一次校验，
  拿不到凭据就拒绝。
* **app 死了 helper 自己退**：每秒 `kill(app_pid, 0)` 检查一次，避免留下驻留的 root 进程。

仓库里 `app/src/main/assets/root/arm64-v8a/bgroot` 现在就是**这个 crate 交叉编译出来的产物**
（arm64-v8a，strip 后约 356KB）。C 版 `app/src/main/cpp/bgroot.c` 暂时保留作参考，
它的 `build.sh` 产出的旧二进制已被替换。

更新这个二进制的方式：

```bash
gh run download -R flyoer5/bettergi-pocket -n bgroot-aarch64 -D /tmp/bgroot
cp /tmp/bgroot/bgroot app/src/main/assets/root/arm64-v8a/bgroot
chmod +x app/src/main/assets/root/arm64-v8a/bgroot
```

`apk` job 会在打包前对比仓库里的资产与最新构建，不一致时打一条 warning 并仍用最新构建打包。

## bg-jni

把 `bg-trigger` 的自动对话引擎通过 JNI 暴露给 Kotlin，产物是 `libbg_jni.so`。
Kotlin 侧对应 `com.bettergi.pocket.rust.RustNative`。

接口只有「创建 / 释放 / 喂一帧 / 改设置」几条：

```kotlin
RustNative.createEngine(settingsJson): Long
RustNative.tick(handle, observationJson): String?
RustNative.resetEngine(handle)
RustNative.updateSettings(handle, json)
RustNative.updateKeywords(handle, json)
RustNative.destroyEngine(handle)
```

用 JSON 而不是二进制：每拍只有几十字节，开销可忽略；Kotlin 侧不用手写内存布局，
加字段不用两边同步改，出问题能直接打印原文。观测报文长这样：

```json
{
  "nowMs": 12345,
  "screen": {"w": 1920, "h": 1080},
  "capture": {"w": 1920, "h": 1080},
  "foreground": true,
  "dialogue": true,
  "chatIcons": [{"x": 100, "y": 500, "w": 20, "h": 20}],
  "ocrLines": [{"x": 200, "y": 300, "w": 200, "h": 40, "text": "确定"}],
  "blackRate": 0.62
}
```

返回：

```json
{"actions":[{"type":"tap","x":960,"y":1060,"durationMs":50}],
 "events":["点击对话图标 (960, 1060)"],"state":"inDialog","nextTickMs":200}
```

边界约定：

* `foreground` 为 `false` / 省略时引擎不产生任何动作；
* 传进来的 JSON 不合法会抛 `IllegalArgumentException`（只有这一种情况会抛）；
* `.so` 只打包了 `arm64-v8a`，其它 ABI 上 `RustNative.isAvailable` 为 false，
  调用方必须先判断，不要直接调。

仓库里 `app/src/main/jniLibs/arm64-v8a/libbg_jni.so` 是这个 crate 的交叉编译产物，
与 OpenCV 的 `.so` 一起提交。更新方式：

```bash
gh run download -R flyoer5/bettergi-pocket -n bg-jni-aarch64 -D /tmp/jni
cp /tmp/jni/libbg_jni.so app/src/main/jniLibs/arm64-v8a/libbg_jni.so
```

## 测试方式

98 个单测全部跑在 host 上：不需要设备、不需要 OpenCV、不需要 OCR 引擎。

* 模板匹配用已知数值的小图逐模式验算（完全一致、全黑、负相关、多目标 NMS）；
* 坐标换算拿真实 `Recognition.json` 的 bbox 和 3040×1904 画布验算；
* 状态机用假时钟把「进入对话 → 点击 → 确认 → 超时重试」整条链跑完；
* 资源解析覆盖别名循环、缺失别名、非法 rect、未知类型等错误路径。

## CI

`.github/workflows/rust.yml`，三个独立 job：

| job | 命令 |
|---|---|
| fmt | `cargo fmt --all -- --check` |
| clippy | `cargo clippy --all-targets -- -D warnings` |
| test | `cargo test --all` |
| android | NDK r26d 交叉编译 `bg-helper` / `bg-jni` 到 `aarch64-linux-android`，产物作为 artifact 上传 |

推送涉及 `rust/**` 的改动会自动触发；也可以手动：

```bash
gh workflow run rust.yml --ref <branch>
gh run watch
```

## 迁移顺序

当前 Kotlin 实现仍在线上跑，Rust 侧按依赖顺序推进，每一步都能单独验证：

1. **算法与状态机** —— 已完成；
2. **`bg-helper`** —— 已完成，可直接替换 `app/src/main/cpp/bgroot.c`（协议不变，Kotlin 侧不用改）；
3. **JNI 桥** —— `bg-jni` 已产出 `libbg_jni.so`，Kotlin 侧 `RustNative` 已就位，
   但**调用点还没有切**（识别层替换需要真机验证）—— 进行中；
4. **平台层** —— 投屏、悬浮窗、无障碍服务 —— 待做。

## 已知限制

* 模板匹配是朴素三重循环（`O(位置数 × 模板像素)`），搜索框大时会偏慢，
  后续再上积分图 / 提前退出 / 降采样粗定位。
* 暂不支持 `useMask` / `use3Channels` / `useBinaryMatch`，这些字段会被解析但当前匹配不用它们。
* OCR 引擎仍在平台层，Rust 只拿到已经切好的文本行。
* `MatchGrid` 会把整张结果矩阵放进内存，模板接近整屏时要注意内存。
* `bg-helper` 只实现 `--server` 模式；C 版的 `--tap` / `--swipe` / `--probe` 这类一次性调试入口没有搬过来。
