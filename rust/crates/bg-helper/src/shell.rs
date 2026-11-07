//! 子进程 shell：探测可用的 `sh`，带超时地执行命令并收集 stdout。
//!
//! Android 上没有 `/bin/sh`，只有 `/system/bin/sh`；部分设备还要靠 Magisk 的 busybox。
//! 用 `popen()` 这类硬编码 `/bin/sh` 的做法会静默失败（子进程 127），
//! 所以这里显式列出候选、逐个冒烟测试。
//!
//! 超时是必须的：`wm size`、`dumpsys` 在个别 ROM 上会卡住，
//! 没有超时就会把 helper 的主循环一起拖死。

use std::io::Read;
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

/// 按优先级排列的 shell 候选（与 C 版 `shell_capture` 一致）。
pub const SHELL_CANDIDATES: &[&str] = &["/system/bin/sh", "/bin/sh", "/data/adb/magisk/busybox"];

/// 单条命令的默认超时。
pub const DEFAULT_TIMEOUT: Duration = Duration::from_millis(2500);

/// 一个可执行的 shell。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Shell {
    path: String,
}

impl Shell {
    pub fn new(path: impl Into<String>) -> Self {
        Shell { path: path.into() }
    }

    pub fn path(&self) -> &str {
        &self.path
    }

    /// 依次试探候选路径：存在且能跑通 `echo` 才算可用。
    pub fn detect() -> Option<Shell> {
        for candidate in SHELL_CANDIDATES {
            if !std::path::Path::new(candidate).exists() {
                continue;
            }
            let shell = Shell::new(*candidate);
            if shell
                .run_with_timeout("echo bg_sh_ok", Duration::from_millis(800))
                .is_some()
            {
                return Some(shell);
            }
        }
        None
    }

    /// 执行一条命令并返回 stdout（stderr 丢弃）；超时或非 0 退出码返回 `None`。
    pub fn run(&self, command: &str) -> Option<String> {
        self.run_with_timeout(command, DEFAULT_TIMEOUT)
    }

    /// 带超时执行。超时会 kill 子进程并返回 `None`。
    pub fn run_with_timeout(&self, command: &str, timeout: Duration) -> Option<String> {
        let mut child = spawn_command(&self.path, command)?;
        let stdout_pipe = child.stdout.take()?;
        let stderr_pipe = child.stderr.take()?;
        let stdout_reader = drain(stdout_pipe);
        let stderr_reader = drain(stderr_pipe);

        let deadline = Instant::now() + timeout;
        let status = loop {
            match child.try_wait() {
                Ok(Some(status)) => break status,
                Ok(None) => {
                    if Instant::now() >= deadline {
                        let _ = child.kill();
                        let _ = child.wait();
                        let _ = stdout_reader.join();
                        let _ = stderr_reader.join();
                        return None;
                    }
                    std::thread::sleep(Duration::from_millis(5));
                }
                Err(_) => {
                    let _ = child.kill();
                    let _ = child.wait();
                    return None;
                }
            }
        };

        // 进程已退出，读端随即 EOF；join 一下保证输出完整。
        let output = stdout_reader.join().unwrap_or_default();
        let _ = stderr_reader.join();
        if status.success() {
            Some(String::from_utf8_lossy(&output).into_owned())
        } else {
            None
        }
    }
}

fn spawn_command(path: &str, command: &str) -> Option<Child> {
    use std::os::unix::process::CommandExt;
    Command::new(path)
        // busybox 需要 argv[0]="sh" 才会进 shell applet，与 C 版 execl 一致。
        .arg0("sh")
        .arg("-c")
        .arg(command)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .ok()
}

fn drain<R: Read + Send + 'static>(mut reader: R) -> std::thread::JoinHandle<Vec<u8>> {
    std::thread::spawn(move || {
        let mut buffer = Vec::new();
        let _ = reader.read_to_end(&mut buffer);
        buffer
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn host_shell() -> Shell {
        // 测试环境是 Linux CI / host，/bin/sh 必然存在。
        Shell::detect().expect("host 上应能找到 sh")
    }

    #[test]
    fn detect_finds_a_shell_on_host() {
        let shell = host_shell();
        assert!(!shell.path().is_empty());
    }

    #[test]
    fn runs_and_captures_stdout() {
        let shell = host_shell();
        let output = shell.run("echo hello").unwrap();
        assert_eq!(output, "hello\n");
    }

    #[test]
    fn captures_multiline_output() {
        let shell = host_shell();
        let output = shell.run("printf 'a\\nb\\n'").unwrap();
        assert_eq!(output, "a\nb\n");
    }

    #[test]
    fn non_zero_exit_returns_none() {
        let shell = host_shell();
        assert_eq!(shell.run("exit 3"), None);
        assert_eq!(shell.run("command_that_does_not_exist_xyz"), None);
    }

    #[test]
    fn timeout_kills_process() {
        let shell = host_shell();
        let started = Instant::now();
        let result = shell.run_with_timeout("sleep 5", Duration::from_millis(120));
        assert_eq!(result, None);
        assert!(started.elapsed() < Duration::from_secs(3), "超时应及时返回");
    }

    #[test]
    fn missing_shell_returns_none() {
        let shell = Shell::new("/definitely/not/a/shell");
        assert_eq!(shell.run("echo hi"), None);
        assert!(Shell::detect().is_some());
    }

    #[test]
    fn stderr_is_not_returned() {
        let shell = host_shell();
        let output = shell.run("echo out; echo err 1>&2").unwrap();
        assert_eq!(output, "out\n");
    }
}
