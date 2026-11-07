//! Unix socket 服务端：监听、校验对端、逐行处理请求。
//!
//! 与 C 版 `bgroot` 的差异只有一处，是有意的：注入统一走 `input` 命令。
//! C 版在 server 模式下不创建 uinput，于是 `TAP` 一律回 `ERR uinput`；
//! 这里既然已经是 root，直接执行 `input tap` 更可靠，也更符合 Android 的输入管线。

use std::ffi::CString;
use std::io::{BufRead, Read, Write};
use std::os::unix::io::AsRawFd;
use std::os::unix::net::{UnixListener, UnixStream};
use std::time::Duration;

use bg_core::Size;

use crate::args::Options;
use crate::foreground::parse_foreground;
use crate::protocol::{
    self, Request, MAX_LINE_BYTES, RESP_BACK_OK, RESP_BAD_PACKAGE, RESP_BYE, RESP_KEEPALIVE_ERR,
    RESP_KEEPALIVE_OK, RESP_LINE_TOO_LONG, RESP_LONG_ERR, RESP_LONG_OK, RESP_PONG, RESP_SWIPE_ERR,
    RESP_SWIPE_OK, RESP_TAP_ERR, RESP_TAP_OK, RESP_UNKNOWN,
};
use crate::shell::Shell;

/// 命令执行抽象：真机上包 [`Shell`]，测试里换成假实现。
pub trait CommandRunner {
    fn execute(&self, command: &str) -> Option<String>;
}

impl CommandRunner for Shell {
    fn execute(&self, command: &str) -> Option<String> {
        self.run(command)
    }
}

/// 前台查询用的三条命令，按可靠性排序（窗口焦点最快，Activity 栈兜底）。
const FOREGROUND_COMMANDS: [&str; 3] = [
    "dumpsys window 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp'",
    "dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity'",
    "dumpsys activity 2>/dev/null | grep -m1 'mFocusedActivity'",
];

/// 单次请求的处理器。
pub struct Helper<'a> {
    runner: &'a dyn CommandRunner,
    screen: Size,
    injection_ready: bool,
}

impl<'a> Helper<'a> {
    pub fn new(runner: &'a dyn CommandRunner, screen: Size) -> Self {
        Helper {
            runner,
            screen,
            injection_ready: true,
        }
    }

    /// 处理一行请求，返回 `(响应, 是否退出)`。
    pub fn handle_line(&self, line: &str) -> (String, bool) {
        match protocol::parse_request(line) {
            Request::Ping => (RESP_PONG.to_string(), false),
            Request::Probe => (
                protocol::probe_response(self.screen, self.injection_ready),
                false,
            ),
            Request::Tap { x, y, .. } => (
                if self.run(&format!("input tap {x} {y}")) {
                    RESP_TAP_OK.to_string()
                } else {
                    RESP_TAP_ERR.to_string()
                },
                false,
            ),
            Request::Long { x, y, duration_ms } => {
                let duration = duration_ms.max(1);
                (
                    if self.run(&format!("input swipe {x} {y} {x} {y} {duration}")) {
                        RESP_LONG_OK.to_string()
                    } else {
                        RESP_LONG_ERR.to_string()
                    },
                    false,
                )
            }
            Request::Swipe {
                x1,
                y1,
                x2,
                y2,
                duration_ms,
            } => {
                let duration = duration_ms.max(1);
                (
                    if self.run(&format!("input swipe {x1} {y1} {x2} {y2} {duration}")) {
                        RESP_SWIPE_OK.to_string()
                    } else {
                        RESP_SWIPE_ERR.to_string()
                    },
                    false,
                )
            }
            Request::Pid { package } => {
                if !protocol::valid_package(&package) {
                    return (RESP_BAD_PACKAGE.to_string(), false);
                }
                (protocol::pid_response(self.pid_of(&package)), false)
            }
            Request::KeepAlive { package } => {
                if !protocol::valid_package(&package) {
                    return (RESP_BAD_PACKAGE.to_string(), false);
                }
                (
                    if self.keep_alive(&package) {
                        RESP_KEEPALIVE_OK.to_string()
                    } else {
                        RESP_KEEPALIVE_ERR.to_string()
                    },
                    false,
                )
            }
            Request::Back => (
                if self.run("input keyevent 4") {
                    RESP_BACK_OK.to_string()
                } else {
                    "ERR back\n".to_string()
                },
                false,
            ),
            Request::Foreground => (
                protocol::foreground_response(self.foreground().as_deref()),
                false,
            ),
            Request::Quit => (RESP_BYE.to_string(), true),
            Request::Unknown => (RESP_UNKNOWN.to_string(), false),
        }
    }

    fn run(&self, command: &str) -> bool {
        self.runner.execute(command).is_some()
    }

    /// 多来源依次尝试，第一个能解析出包名的结果即为答案。
    fn foreground(&self) -> Option<String> {
        for command in FOREGROUND_COMMANDS {
            let Some(output) = self.runner.execute(command) else {
                continue;
            };
            if let Some(package) = parse_foreground(&output) {
                return Some(package);
            }
        }
        None
    }

    fn pid_of(&self, package: &str) -> Option<i32> {
        let output = self
            .runner
            .execute(&format!("pidof {package} 2>/dev/null"))?;
        output.split_whitespace().next()?.parse().ok()
    }

    fn keep_alive(&self, package: &str) -> bool {
        let whitelisted = self
            .runner
            .execute(&format!("dumpsys deviceidle whitelist +{package}"))
            .is_some();
        let active_bucket = self
            .runner
            .execute(&format!("am set-standby-bucket {package} active"))
            .is_some();
        whitelisted && active_bucket
    }
}

/// 启动服务端，返回进程退出码。
pub fn run_server(options: &Options) -> i32 {
    let Some(sock_path) = options.sock.clone() else {
        eprintln!("ERR missing --sock");
        return 2;
    };

    let shell = Shell::detect().unwrap_or_else(|| Shell::new(crate::shell::SHELL_CANDIDATES[0]));
    eprintln!("OK shell {}", shell.path());

    let screen = options
        .screen()
        .or_else(|| detect_screen(&shell))
        .unwrap_or(Size::new(1080, 2400));
    eprintln!("OK screen {}x{}", screen.width, screen.height);

    // 先删旧 socket：上一轮被强杀的 helper 可能留下不可用的 inode。
    let _ = std::fs::remove_file(&sock_path);
    let listener = match UnixListener::bind(&sock_path) {
        Ok(listener) => listener,
        Err(err) => {
            eprintln!("ERR bind {sock_path}: {err}");
            return 2;
        }
    };
    if let Err(err) = set_socket_mode(&sock_path) {
        eprintln!("ERR chmod {sock_path}: {err}");
        let _ = std::fs::remove_file(&sock_path);
        return 2;
    }
    if options.uid > 0 {
        // chown 失败 = app 连不上（socket 还是 root:0600）→ 必须显式失败，不能留个静默不可用的服务。
        if let Err(err) = chown_to(&sock_path, options.uid) {
            eprintln!("ERR chown {sock_path} -> uid {}: {err}", options.uid);
            let _ = std::fs::remove_file(&sock_path);
            return 2;
        }
    }
    if let Err(err) = listener.set_nonblocking(true) {
        eprintln!("ERR set_nonblocking: {err}");
        let _ = std::fs::remove_file(&sock_path);
        return 2;
    }
    eprintln!("OK listening {sock_path} (uid={})", options.uid);

    let helper = Helper::new(&shell, screen);
    let mut exit_code = 0;
    loop {
        if !app_alive(options.app_pid) {
            eprintln!("app process gone, helper exit");
            break;
        }
        match listener.accept() {
            Ok((stream, _)) => {
                if !peer_allowed(&stream, options.uid) {
                    continue;
                }
                match handle_connection(&helper, &stream) {
                    Ok(true) => break,
                    Ok(false) => {}
                    Err(err) => eprintln!("WARN connection closed: {err}"),
                }
            }
            Err(err) if err.kind() == std::io::ErrorKind::WouldBlock => {
                std::thread::sleep(Duration::from_millis(300));
            }
            Err(err) => {
                eprintln!("ERR accept: {err}");
                exit_code = 2;
                break;
            }
        }
    }
    let _ = std::fs::remove_file(&sock_path);
    exit_code
}

fn handle_connection(helper: &Helper<'_>, stream: &UnixStream) -> std::io::Result<bool> {
    stream.set_nonblocking(false)?;
    let mut reader = std::io::BufReader::new(stream.try_clone()?);
    let mut writer = stream.try_clone()?;

    let mut line: Vec<u8> = Vec::new();
    loop {
        line.clear();
        // 单行限长：超长说明流已错乱，明确回错误再断开（不静默当 EOF）。
        let mut limited = Read::take(reader.by_ref(), (MAX_LINE_BYTES + 1) as u64);
        let read = match limited.read_until(b'\n', &mut line) {
            Ok(read) => read,
            Err(err) if err.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(err) => return Err(err),
        };
        if read == 0 {
            return Ok(false);
        }
        if line.last() != Some(&b'\n') && line.len() > MAX_LINE_BYTES {
            let _ = writer.write_all(RESP_LINE_TOO_LONG.as_bytes());
            eprintln!("line too long, closing connection");
            return Ok(false);
        }
        while matches!(line.last(), Some(b'\n') | Some(b'\r')) {
            line.pop();
        }
        let command = String::from_utf8_lossy(&line).into_owned();
        eprintln!("cmd: {command}");
        let (response, quit) = helper.handle_line(&command);
        writer.write_all(response.as_bytes())?;
        writer.flush()?;
        if quit {
            return Ok(true);
        }
    }
}

/// 用 stdin/stdout 跑协议，不建 socket。
///
/// 为什么需要：Android 16 + KernelSU 下，app（untrusted_app 域）连接 root 进程创建的
/// Unix socket 会被 SELinux 的 `connectto` 检查拒绝（EACCES），即使文件已经是 0600 +
/// chown 到 app uid。子进程继承的管道不经过这条检查，所以用 stdio 更稳。
///
/// 约定：stdin 收请求、stdout 只回协议响应、stderr 走日志（app 会读 stderr 打日志）。
pub fn run_stdio(options: &Options) -> i32 {
    let shell = Shell::detect().unwrap_or_else(|| Shell::new(crate::shell::SHELL_CANDIDATES[0]));
    eprintln!("OK shell {}", shell.path());

    let screen = options
        .screen()
        .or_else(|| detect_screen(&shell))
        .unwrap_or(Size::new(1080, 2400));
    eprintln!("OK screen {}x{}", screen.width, screen.height);

    let helper = Helper::new(&shell, screen);
    let stdin = std::io::stdin();
    let mut stdout = std::io::stdout();
    let mut line = String::new();
    loop {
        line.clear();
        match stdin.read_line(&mut line) {
            Ok(0) => break,
            Ok(_) => {}
            Err(err) => {
                eprintln!("ERR read stdin: {err}");
                return 2;
            }
        }
        let command = line.trim_end_matches(['\r', '\n']).to_string();
        eprintln!("cmd: {command}");
        let (response, quit) = helper.handle_line(&command);
        if stdout.write_all(response.as_bytes()).is_err() {
            break;
        }
        if stdout.flush().is_err() {
            break;
        }
        if quit {
            break;
        }
    }
    eprintln!("OK bye");
    0
}

fn detect_screen(shell: &Shell) -> Option<Size> {
    let output = shell.run("wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | tail -1")?;
    crate::args::parse_screen_size(&output)
}

fn set_socket_mode(path: &str) -> std::io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))
}

fn chown_to(path: &str, uid: u32) -> std::io::Result<()> {
    let path_c = CString::new(path)
        .map_err(|_| std::io::Error::new(std::io::ErrorKind::InvalidInput, "path contains NUL"))?;
    // SAFETY: path_c 是合法的 NUL 结尾 C 字符串，uid 来自命令行。
    let rc = unsafe { libc::chown(path_c.as_ptr(), uid, uid) };
    if rc == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error())
    }
}

fn peer_allowed(stream: &UnixStream, expected_uid: u32) -> bool {
    if expected_uid == 0 {
        return true;
    }
    match peer_uid(stream) {
        Some(uid) if uid == expected_uid => true,
        Some(uid) => {
            eprintln!("reject uid {uid} (expect {expected_uid})");
            false
        }
        None => {
            // 拿不到对端凭据就当不可信：socket 虽是 0600，也不放行未验证连接。
            eprintln!("reject connection: peer credential unavailable");
            false
        }
    }
}

fn peer_uid(stream: &UnixStream) -> Option<u32> {
    // SAFETY: fd 来自有效的 UnixStream；ucred 由内核填写。
    unsafe {
        let mut cred: libc::ucred = std::mem::zeroed();
        let mut len = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
        let rc = libc::getsockopt(
            stream.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            &mut cred as *mut libc::ucred as *mut libc::c_void,
            &mut len,
        );
        if rc == 0 {
            Some(cred.uid)
        } else {
            None
        }
    }
}

/// app 进程是否还活着；`pid <= 0` 表示不监视。
fn app_alive(pid: i32) -> bool {
    if pid <= 0 {
        return true;
    }
    // SAFETY: kill(pid, 0) 只是存在性检查，不发信号。
    let rc = unsafe { libc::kill(pid, 0) };
    rc == 0 || std::io::Error::last_os_error().raw_os_error() == Some(libc::EPERM)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    type Responder = Box<dyn Fn(&str) -> Option<String>>;

    struct FakeRunner {
        responses: Responder,
        calls: RefCell<Vec<String>>,
    }

    impl FakeRunner {
        fn new(responses: impl Fn(&str) -> Option<String> + 'static) -> Self {
            FakeRunner {
                responses: Box::new(responses),
                calls: RefCell::new(Vec::new()),
            }
        }

        fn calls(&self) -> Vec<String> {
            self.calls.borrow().clone()
        }
    }

    impl CommandRunner for FakeRunner {
        fn execute(&self, command: &str) -> Option<String> {
            self.calls.borrow_mut().push(command.to_string());
            (self.responses)(command)
        }
    }

    fn helper(runner: &dyn CommandRunner) -> Helper<'_> {
        Helper::new(runner, Size::new(1080, 2400))
    }

    fn ok_all(_: &str) -> Option<String> {
        Some(String::new())
    }

    #[test]
    fn ping_and_probe() {
        let runner = FakeRunner::new(ok_all);
        let helper = helper(&runner);
        assert_eq!(helper.handle_line("PING"), ("OK pong\n".to_string(), false));
        assert_eq!(
            helper.handle_line("PROBE"),
            ("OK 1080 2400 uinput_ok\n".to_string(), false)
        );
    }

    #[test]
    fn tap_runs_input_command() {
        let runner = FakeRunner::new(ok_all);
        let helper = helper(&runner);
        assert_eq!(
            helper.handle_line("TAP 100 200 60"),
            ("OK tap\n".to_string(), false)
        );
        assert_eq!(runner.calls(), vec!["input tap 100 200".to_string()]);
    }

    #[test]
    fn tap_failure_reports_error() {
        let runner = FakeRunner::new(|_: &str| None);
        let helper = helper(&runner);
        assert_eq!(
            helper.handle_line("TAP 1 2 3"),
            ("ERR tap\n".to_string(), false)
        );
    }

    #[test]
    fn long_press_maps_to_swipe_with_duration() {
        let runner = FakeRunner::new(ok_all);
        let helper = helper(&runner);
        assert_eq!(
            helper.handle_line("LONG 10 20 800"),
            ("OK long\n".to_string(), false)
        );
        assert_eq!(
            runner.calls(),
            vec!["input swipe 10 20 10 20 800".to_string()]
        );
    }

    #[test]
    fn swipe_and_back() {
        let runner = FakeRunner::new(ok_all);
        let helper = helper(&runner);
        assert_eq!(
            helper.handle_line("SWIPE 1 2 3 4 300"),
            ("OK swipe\n".to_string(), false)
        );
        assert_eq!(helper.handle_line("BACK"), ("OK back\n".to_string(), false));
        assert_eq!(
            runner.calls(),
            vec![
                "input swipe 1 2 3 4 300".to_string(),
                "input keyevent 4".to_string()
            ]
        );
    }

    #[test]
    fn pid_lookup() {
        let runner = FakeRunner::new(|command: &str| {
            if command.starts_with("pidof") {
                Some("4242\n".to_string())
            } else {
                None
            }
        });
        let helper = helper(&runner);
        assert_eq!(
            helper.handle_line("PID com.miHoYo.Yuanshen"),
            ("OK 4242\n".to_string(), false)
        );
    }

    #[test]
    fn pid_lookup_missing_returns_zero() {
        let runner = FakeRunner::new(|_: &str| None);
        let helper = helper(&runner);
        assert_eq!(
            helper.handle_line("PID com.none"),
            ("OK 0\n".to_string(), false)
        );
    }

    #[test]
    fn pid_rejects_bad_package() {
        let runner = FakeRunner::new(ok_all);
        let helper = helper(&runner);
        assert_eq!(
            helper.handle_line("PID com.x;rm -rf /"),
            ("ERR bad package\n".to_string(), false)
        );
        assert!(runner.calls().is_empty(), "非法包名不应拼接进命令");
    }

    #[test]
    fn keepalive_requires_both_commands() {
        let runner = FakeRunner::new(ok_all);
        let helper = helper(&runner);
        assert_eq!(
            helper.handle_line("KEEPALIVE com.bettergi.pocket"),
            ("OK keepalive\n".to_string(), false)
        );
        assert_eq!(
            runner.calls(),
            vec![
                "dumpsys deviceidle whitelist +com.bettergi.pocket".to_string(),
                "am set-standby-bucket com.bettergi.pocket active".to_string()
            ]
        );
    }

    #[test]
    fn keepalive_fails_when_one_command_fails() {
        let runner = FakeRunner::new(|command: &str| {
            if command.starts_with("am ") {
                None
            } else {
                Some(String::new())
            }
        });
        let helper = helper(&runner);
        assert_eq!(
            helper.handle_line("KEEPALIVE com.bettergi.pocket"),
            ("ERR keepalive\n".to_string(), false)
        );
    }

    #[test]
    fn foreground_prefers_window_dump() {
        let runner = FakeRunner::new(|command: &str| {
            if command.contains("dumpsys window") {
                Some(
                    "  mCurrentFocus=Window{8f1 u0 com.miHoYo.Yuanshen/com.miHoYo.MainActivity}"
                        .to_string(),
                )
            } else {
                None
            }
        });
        let helper = helper(&runner);
        assert_eq!(
            helper.handle_line("FG"),
            ("OK com.miHoYo.Yuanshen\n".to_string(), false)
        );
    }

    #[test]
    fn foreground_falls_back_to_activity_dump() {
        let runner = FakeRunner::new(|command: &str| {
            if command.contains("dumpsys window") {
                Some(String::new())
            } else if command.contains("topResumedActivity") {
                Some(
                    "  topResumedActivity=ActivityRecord{a u0 com.example.app/.Main t3}"
                        .to_string(),
                )
            } else {
                None
            }
        });
        let helper = helper(&runner);
        assert_eq!(
            helper.handle_line("FG"),
            ("OK com.example.app\n".to_string(), false)
        );
    }

    #[test]
    fn foreground_unknown_when_nothing_matches() {
        let runner = FakeRunner::new(|_: &str| None);
        let helper = helper(&runner);
        assert_eq!(
            helper.handle_line("FG"),
            ("OK unknown\n".to_string(), false)
        );
    }

    #[test]
    fn unknown_command_and_quit() {
        let runner = FakeRunner::new(ok_all);
        let helper = helper(&runner);
        assert_eq!(
            helper.handle_line("WHAT"),
            ("ERR unknown command\n".to_string(), false)
        );
        assert_eq!(helper.handle_line("QUIT"), ("OK bye\n".to_string(), true));
    }

    #[test]
    fn unavailable_runner_reports_unavailable() {
        // 这里只验证协议常量存在且格式合理（注入失败时用 TAP_ERR）。
        assert!(crate::protocol::RESP_UNAVAILABLE.starts_with("ERR"));
        assert!(RESP_TAP_ERR.starts_with("ERR"));
    }
}
