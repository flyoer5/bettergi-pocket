//! 从 `dumpsys` 输出里解析前台包名。
//!
//! Android 上不同 ROM 的 `dumpsys window` / `dumpsys activity` 格式差异很大，
//! 这里不解析完整结构，只做「按 `/` 找包名」这一件事：
//! `mCurrentFocus=Window{8f1 u0 com.x/com.x.MainActivity}` → `com.x`。

/// 逐行扫描 `dumpsys` 输出，返回第一个能解析出的包名。
pub fn parse_foreground(dump: &str) -> Option<String> {
    dump.lines().find_map(extract_package)
}

/// 从一行里提取 `/` 前面的包名（去掉粘连的 `u0` / `u10` 用户前缀）。
pub fn extract_package(line: &str) -> Option<String> {
    let slash = line.find('/')?;
    let prefix = &line[..slash];

    // 从斜杠往前扫到分隔符，拿到形如 "com.x" 或 "u0com.x" 的片段。
    let mut start = 0;
    for (index, ch) in prefix.char_indices().rev() {
        if matches!(ch, ' ' | '\t' | '{' | '}') {
            start = index + ch.len_utf8();
            break;
        }
        start = index;
    }
    let mut candidate = &prefix[start..];

    // 去掉 "u<数字>" 前缀：要求后面还有内容，否则 "u0" 这种整段就是包名的一部分。
    if let Some(rest) = candidate.strip_prefix('u') {
        let digits = rest.chars().take_while(char::is_ascii_digit).count();
        if digits > 0 && digits < rest.chars().count() {
            candidate = &rest[digits..];
        }
    }

    if candidate.is_empty() {
        None
    } else {
        Some(candidate.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_window_focus_line() {
        let dump = "  mCurrentFocus=Window{8f1 u0 com.miHoYo.Yuanshen/com.miHoYo.GetMobileInfo.MainActivity}";
        assert_eq!(
            parse_foreground(dump).as_deref(),
            Some("com.miHoYo.Yuanshen")
        );
    }

    #[test]
    fn parses_resumed_activity_line() {
        let dump = "  topResumedActivity=ActivityRecord{abc u10 com.example.app/.MainActivity t22}";
        assert_eq!(parse_foreground(dump).as_deref(), Some("com.example.app"));
    }

    #[test]
    fn strips_user_prefix_without_space() {
        assert_eq!(extract_package("u0com.x/y").as_deref(), Some("com.x"));
        assert_eq!(extract_package("u10com.y/z").as_deref(), Some("com.y"));
    }

    #[test]
    fn keeps_short_u_segments() {
        // "u0" 后面没有内容 → 不当作前缀剥离
        assert_eq!(extract_package("u0/x").as_deref(), Some("u0"));
    }

    #[test]
    fn picks_first_line_with_slash() {
        let dump = "some header\nno package here\n  mFocusedApp=AppWindowToken{a u0 com.first/.Main t1}\n  mCurrentFocus=Window{b u0 com.second/.Main}";
        assert_eq!(parse_foreground(dump).as_deref(), Some("com.first"));
    }

    #[test]
    fn returns_none_without_slash() {
        assert_eq!(parse_foreground("nothing to see"), None);
        assert_eq!(parse_foreground(""), None);
    }

    #[test]
    fn handles_activity_record_with_braces() {
        let line = "ActivityRecord{123 u0 com.tencent.mm/.ui.LauncherUI t5}";
        assert_eq!(extract_package(line).as_deref(), Some("com.tencent.mm"));
    }
}
