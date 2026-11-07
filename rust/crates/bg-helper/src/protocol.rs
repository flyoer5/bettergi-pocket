//! helper 的行协议：一行一条指令，一行一条回复。
//!
//! 协议必须与 Android 侧 `RootBridge` 完全兼容——它是按「写一行、读一行」实现的，
//! 任何多余输出都会让后续响应错位。因此这里的解析规则是**从严**的：
//! 认不出来就当未知指令回 `ERR`，绝不猜。

use bg_core::Size;

/// 单行上限。协议里最长指令不到 64 字节，超过说明流已错乱。
pub const MAX_LINE_BYTES: usize = 4096;

/// 解析出来的一条请求。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Request {
    Ping,
    Probe,
    Tap {
        x: i32,
        y: i32,
        duration_ms: i32,
    },
    Long {
        x: i32,
        y: i32,
        duration_ms: i32,
    },
    Swipe {
        x1: i32,
        y1: i32,
        x2: i32,
        y2: i32,
        duration_ms: i32,
    },
    /// 包名由调用方用 [`valid_package`] 校验。
    Pid {
        package: String,
    },
    KeepAlive {
        package: String,
    },
    Back,
    Foreground,
    Quit,
    Unknown,
}

/// 解析一行请求。规则与 C 版 `handle_line` 的前缀判断保持一致。
pub fn parse_request(line: &str) -> Request {
    if line.starts_with("PING") {
        return Request::Ping;
    }
    if line.starts_with("PROBE") {
        return Request::Probe;
    }
    if let Some(rest) = line.strip_prefix("TAP ") {
        if let Some((x, y, duration_ms)) = parse_three(rest) {
            return Request::Tap { x, y, duration_ms };
        }
    }
    if let Some(rest) = line.strip_prefix("LONG ") {
        if let Some((x, y, duration_ms)) = parse_three(rest) {
            return Request::Long { x, y, duration_ms };
        }
    }
    if let Some(rest) = line.strip_prefix("SWIPE ") {
        if let Some(values) = parse_ints::<5>(rest) {
            return Request::Swipe {
                x1: values[0],
                y1: values[1],
                x2: values[2],
                y2: values[3],
                duration_ms: values[4],
            };
        }
    }
    if let Some(rest) = line.strip_prefix("PID ") {
        if let Some(package) = first_token(rest) {
            return Request::Pid { package };
        }
    }
    if let Some(rest) = line.strip_prefix("KEEPALIVE ") {
        if let Some(package) = first_token(rest) {
            return Request::KeepAlive { package };
        }
    }
    if line.starts_with("BACK") {
        return Request::Back;
    }
    if line.starts_with("FG") {
        return Request::Foreground;
    }
    if line.starts_with("QUIT") {
        return Request::Quit;
    }
    Request::Unknown
}

fn first_token(rest: &str) -> Option<String> {
    rest.split_whitespace().next().map(str::to_string)
}

fn parse_three(rest: &str) -> Option<(i32, i32, i32)> {
    let values = parse_ints::<3>(rest)?;
    Some((values[0], values[1], values[2]))
}

/// 严格解析 N 个整数：数量必须刚好，且每个都能解析成 `i32`。
fn parse_ints<const N: usize>(rest: &str) -> Option<[i32; N]> {
    let tokens: Vec<&str> = rest.split_whitespace().collect();
    if tokens.len() < N {
        return None;
    }
    let mut values = [0i32; N];
    for (index, token) in tokens.iter().take(N).enumerate() {
        values[index] = token.parse().ok()?;
    }
    Some(values)
}

/// 包名白名单：字母数字、`.`、`_`。用于防止命令注入（包名会被拼进 shell 命令）。
pub fn valid_package(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 200
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_')
}

pub const RESP_PONG: &str = "OK pong\n";
pub const RESP_TAP_OK: &str = "OK tap\n";
pub const RESP_TAP_ERR: &str = "ERR tap\n";
pub const RESP_LONG_OK: &str = "OK long\n";
pub const RESP_LONG_ERR: &str = "ERR long\n";
pub const RESP_SWIPE_OK: &str = "OK swipe\n";
pub const RESP_SWIPE_ERR: &str = "ERR swipe\n";
pub const RESP_BACK_OK: &str = "OK back\n";
pub const RESP_KEEPALIVE_OK: &str = "OK keepalive\n";
pub const RESP_KEEPALIVE_ERR: &str = "ERR keepalive\n";
pub const RESP_BAD_PACKAGE: &str = "ERR bad package\n";
pub const RESP_UNKNOWN: &str = "ERR unknown command\n";
pub const RESP_BYE: &str = "OK bye\n";
pub const RESP_LINE_TOO_LONG: &str = "ERR line too long\n";
pub const RESP_UNAVAILABLE: &str = "ERR unavailable\n";

/// `PROBE` 的回复：屏幕尺寸 + 注入通道是否可用。
pub fn probe_response(screen: Size, injection_ready: bool) -> String {
    format!(
        "OK {} {} {}\n",
        screen.width,
        screen.height,
        if injection_ready {
            "uinput_ok"
        } else {
            "uinput_fail"
        }
    )
}

/// `FG` 的回复；查不到前台时回 `unknown`。
pub fn foreground_response(package: Option<&str>) -> String {
    match package {
        Some(package) if !package.is_empty() => format!("OK {package}\n"),
        _ => "OK unknown\n".to_string(),
    }
}

/// `PID` 的回复。
pub fn pid_response(pid: Option<i32>) -> String {
    format!("OK {}\n", pid.unwrap_or(0))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_ping_and_probe() {
        assert_eq!(parse_request("PING"), Request::Ping);
        assert_eq!(parse_request("PING extra"), Request::Ping);
        assert_eq!(parse_request("PROBE"), Request::Probe);
    }

    #[test]
    fn parses_tap_and_long() {
        assert_eq!(
            parse_request("TAP 100 200 60"),
            Request::Tap {
                x: 100,
                y: 200,
                duration_ms: 60
            }
        );
        assert_eq!(
            parse_request("LONG 10 20 800"),
            Request::Long {
                x: 10,
                y: 20,
                duration_ms: 800
            }
        );
        // 缺参数 / 参数非数字一律不认
        assert_eq!(parse_request("TAP 100 200"), Request::Unknown);
        assert_eq!(parse_request("TAP a b c"), Request::Unknown);
        assert_eq!(parse_request("LONG 1 2"), Request::Unknown);
    }

    #[test]
    fn parses_swipe() {
        assert_eq!(
            parse_request("SWIPE 1 2 3 4 300"),
            Request::Swipe {
                x1: 1,
                y1: 2,
                x2: 3,
                y2: 4,
                duration_ms: 300
            }
        );
        assert_eq!(parse_request("SWIPE 1 2 3 4"), Request::Unknown);
    }

    #[test]
    fn parses_package_commands() {
        assert_eq!(
            parse_request("PID com.miHoYo.Yuanshen"),
            Request::Pid {
                package: "com.miHoYo.Yuanshen".to_string()
            }
        );
        assert_eq!(
            parse_request("KEEPALIVE com.bettergi.pocket"),
            Request::KeepAlive {
                package: "com.bettergi.pocket".to_string()
            }
        );
        // 包名后多余参数被忽略（对齐 sscanf %s）
        assert_eq!(
            parse_request("PID com.x extra"),
            Request::Pid {
                package: "com.x".to_string()
            }
        );
        assert_eq!(parse_request("PID"), Request::Unknown);
    }

    #[test]
    fn parses_simple_commands() {
        assert_eq!(parse_request("BACK"), Request::Back);
        assert_eq!(parse_request("FG"), Request::Foreground);
        assert_eq!(parse_request("QUIT"), Request::Quit);
        assert_eq!(parse_request("WHAT"), Request::Unknown);
        assert_eq!(parse_request(""), Request::Unknown);
    }

    #[test]
    fn prefix_matching_matches_c_side() {
        // C 用 strncmp，前缀命中就算，这里保持一致
        assert_eq!(parse_request("PINGX"), Request::Ping);
        assert_eq!(parse_request("BACKWARD"), Request::Back);
        assert_eq!(parse_request("FGT"), Request::Foreground);
        assert_eq!(parse_request("QUITNOW"), Request::Quit);
    }

    #[test]
    fn package_validation_rejects_shell_metacharacters() {
        assert!(valid_package("com.miHoYo.Yuanshen"));
        assert!(valid_package("com.bettergi_pocket"));
        assert!(!valid_package(""));
        assert!(!valid_package("com.x; rm -rf /"));
        assert!(!valid_package("com.x|id"));
        assert!(!valid_package("$(id)"));
        assert!(!valid_package("中文包名"));
    }

    #[test]
    fn probe_and_foreground_responses() {
        assert_eq!(
            probe_response(Size::new(1080, 2400), false),
            "OK 1080 2400 uinput_fail\n"
        );
        assert_eq!(
            probe_response(Size::new(1080, 2400), true),
            "OK 1080 2400 uinput_ok\n"
        );
        assert_eq!(foreground_response(Some("com.x")), "OK com.x\n");
        assert_eq!(foreground_response(None), "OK unknown\n");
        assert_eq!(foreground_response(Some("")), "OK unknown\n");
        assert_eq!(pid_response(Some(42)), "OK 42\n");
        assert_eq!(pid_response(None), "OK 0\n");
    }
}
