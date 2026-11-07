//! 命令行参数解析，以及 `wm size` 输出解析。
//!
//! 参数形态由 Android 侧 `RootBridge` 决定：
//! `bgroot --server --sock <path> --uid <uid> --app-pid <pid> --w <w> --h <h>`。

use bg_core::Size;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Options {
    pub server: bool,
    /// 用 stdin/stdout 跑协议（不建 Unix socket）。Android 16 + KernelSU 下
    /// app 连接 root 创建的 socket 会被 SELinux connectto 拒绝，走管道更稳。
    pub stdio: bool,
    pub sock: Option<String>,
    /// 允许连接 socket 的 uid（socket 文件 chown 给它）。
    pub uid: u32,
    /// app 进程号；helper 每秒检查它是否还活着。
    pub app_pid: i32,
    pub width: i32,
    pub height: i32,
    pub version: bool,
    pub help: bool,
}

impl Options {
    pub fn screen(&self) -> Option<Size> {
        if self.width > 0 && self.height > 0 {
            Some(Size::new(self.width, self.height))
        } else {
            None
        }
    }
}

/// 解析参数；未知参数忽略（与 C 版一致，方便加新参数不破坏旧调用方）。
pub fn parse(args: &[String]) -> Options {
    let mut options = Options {
        server: has_flag(args, "--server"),
        stdio: has_flag(args, "--stdio"),
        sock: value_of(args, "--sock"),
        uid: number_of(args, "--uid").unwrap_or(0).max(0) as u32,
        app_pid: number_of(args, "--app-pid").unwrap_or(0),
        width: number_of(args, "--w").unwrap_or(0),
        height: number_of(args, "--h").unwrap_or(0),
        version: has_flag(args, "--version"),
        help: has_flag(args, "--help") || has_flag(args, "-h"),
    };
    // `--w 0` 这类显式传 0 视同未设置
    if options.width <= 0 {
        options.width = 0;
    }
    if options.height <= 0 {
        options.height = 0;
    }
    options
}

fn has_flag(args: &[String], name: &str) -> bool {
    args.iter().any(|arg| arg == name)
}

fn value_of(args: &[String], name: &str) -> Option<String> {
    args.iter()
        .position(|arg| arg == name)
        .and_then(|index| args.get(index + 1))
        .filter(|value| !value.is_empty())
        .cloned()
}

fn number_of(args: &[String], name: &str) -> Option<i32> {
    value_of(args, name).and_then(|value| value.parse::<i32>().ok())
}

/// 解析 `wm size` 输出，取**最后一个** `宽x高`（存在 Override size 时以它为准）。
pub fn parse_screen_size(output: &str) -> Option<Size> {
    let mut found = None;
    for token in output.split(|c: char| !c.is_ascii_digit() && c != 'x') {
        if let Some((width, height)) = token.split_once('x') {
            if let (Ok(width), Ok(height)) = (width.parse::<i32>(), height.parse::<i32>()) {
                if width > 0 && height > 0 {
                    found = Some(Size::new(width, height));
                }
            }
        }
    }
    found
}

/// 打印在 stderr 上的用法；app 会把它显示在日志面板里。
pub const USAGE: &str =
    "usage: bgroot --server --sock PATH [--uid UID] [--app-pid PID] [--w W] [--h H]
       bgroot --version";

#[cfg(test)]
mod tests {
    use super::*;

    fn args(items: &[&str]) -> Vec<String> {
        items.iter().map(|item| (*item).to_string()).collect()
    }

    #[test]
    fn parses_real_launch_arguments() {
        let options = parse(&args(&[
            "--server",
            "--sock",
            "/data/user/0/com.bettergi.pocket/files/root/sock",
            "--uid",
            "10234",
            "--app-pid",
            "4321",
            "--w",
            "1080",
            "--h",
            "2400",
        ]));
        assert!(options.server);
        assert_eq!(
            options.sock.as_deref(),
            Some("/data/user/0/com.bettergi.pocket/files/root/sock")
        );
        assert_eq!(options.uid, 10234);
        assert_eq!(options.app_pid, 4321);
        assert_eq!(options.screen(), Some(Size::new(1080, 2400)));
    }

    #[test]
    fn stdio_flag_is_parsed() {
        let options = parse(&args(&["--stdio", "--uid", "10284", "--app-pid", "4321"]));
        assert!(options.stdio);
        assert!(!options.server);
        assert_eq!(options.uid, 10284);
    }

    #[test]
    fn version_and_help_flags() {
        assert!(parse(&args(&["--version"])).version);
        assert!(parse(&args(&["--help"])).help);
        assert!(parse(&args(&["-h"])).help);
    }

    #[test]
    fn zero_or_missing_screen_falls_back_to_none() {
        assert_eq!(parse(&args(&["--w", "0", "--h", "0"])).screen(), None);
        assert_eq!(parse(&args(&["--server"])).screen(), None);
        assert_eq!(parse(&args(&["--w", "abc"])).screen(), None);
    }

    #[test]
    fn unknown_arguments_are_ignored() {
        let options = parse(&args(&["--server", "--future-flag", "x"]));
        assert!(options.server);
        assert_eq!(options.sock, None);
    }

    #[test]
    fn parses_wm_size_output() {
        assert_eq!(
            parse_screen_size("Physical size: 1080x2400\n"),
            Some(Size::new(1080, 2400))
        );
        assert_eq!(
            parse_screen_size("Physical size: 1080x2400\nOverride size: 720x1600\n"),
            Some(Size::new(720, 1600)),
            "Override size 应覆盖 Physical size"
        );
        assert_eq!(parse_screen_size("no size here"), None);
        assert_eq!(parse_screen_size(""), None);
    }
}
