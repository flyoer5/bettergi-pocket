//! 对话选项关键词决策表。
//!
//! 词表直接沿用桌面版 BetterGI 的 `select_options.json` / `pause_options.json` /
//! `default_pause_options.json`。优先级：
//! 1. `select` 主动选择（命中最优先）
//! 2. `pause` 高优先级暂停（本拍不点，交给用户）
//! 3. `default_pause` 默认暂停（低于主动选择，在兜底之前判断）
//! 4. 都不命中 → 点最下面一项

/// 三张词表；词条之间是「包含」匹配（中文 OCR 常在字间插空格，调用前应已 normalize）。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct OptionKeywords {
    pub select: Vec<String>,
    pub pause: Vec<String>,
    pub default_pause: Vec<String>,
}

impl OptionKeywords {
    pub fn new(select: Vec<String>, pause: Vec<String>, default_pause: Vec<String>) -> Self {
        OptionKeywords {
            select,
            pause,
            default_pause,
        }
    }

    pub fn is_empty(&self) -> bool {
        self.select.is_empty() && self.pause.is_empty() && self.default_pause.is_empty()
    }

    pub fn matches_select(&self, text: &str) -> bool {
        contains_any(&self.select, text)
    }

    pub fn matches_pause(&self, text: &str) -> bool {
        contains_any(&self.pause, text)
    }

    pub fn matches_default_pause(&self, text: &str) -> bool {
        contains_any(&self.default_pause, text)
    }
}

fn contains_any(keywords: &[String], text: &str) -> bool {
    keywords
        .iter()
        .any(|keyword| !keyword.is_empty() && text.contains(keyword.as_str()))
}

/// 解析词表文件：桌面版文件带 `//` 注释行和尾逗号，不是严格 JSON。
///
/// 返回 `None` 表示清洗后仍无法解析（调用方应回退到空词表并记日志，而不是 panic）。
pub fn parse_keyword_list(raw: &str) -> Option<Vec<String>> {
    let cleaned = clean_jsonc(raw);
    let parsed: Vec<String> = serde_json::from_str(&cleaned).ok()?;
    Some(
        parsed
            .into_iter()
            .map(|item| item.trim().to_string())
            .filter(|item| !item.is_empty())
            .collect(),
    )
}

/// 去掉整行 `//` 注释与「逗号后紧跟 `]` / `}`」的尾逗号，让 JSONC 变成严格 JSON。
///
/// 只处理词表这种扁平字符串数组，不做完整 JSON5 解析。
pub fn clean_jsonc(raw: &str) -> String {
    let mut stripped = String::new();
    for line in raw.lines() {
        if line.trim_start().starts_with("//") {
            continue;
        }
        stripped.push_str(line);
        stripped.push('\n');
    }

    let chars: Vec<char> = stripped.chars().collect();
    let mut result = String::with_capacity(stripped.len());
    let mut index = 0;
    while index < chars.len() {
        if chars[index] == ',' {
            let mut next = index + 1;
            while next < chars.len() && chars[next].is_whitespace() {
                next += 1;
            }
            let closes = next < chars.len() && (chars[next] == ']' || chars[next] == '}');
            if closes {
                index += 1;
                continue;
            }
        }
        result.push(chars[index]);
        index += 1;
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    fn words(items: &[&str]) -> Vec<String> {
        items.iter().map(|item| (*item).to_string()).collect()
    }

    #[test]
    fn matches_by_substring() {
        let keywords = OptionKeywords::new(
            words(&["确定", "好的"]),
            words(&["暂停"]),
            words(&["离开", "结束"]),
        );
        assert!(keywords.matches_select("那么，确定吧"));
        assert!(!keywords.matches_select("拒绝"));
        assert!(keywords.matches_pause("稍等暂停一下"));
        assert!(keywords.matches_default_pause("离开这里"));
    }

    #[test]
    fn empty_keyword_never_matches() {
        let keywords = OptionKeywords::new(vec![String::new()], vec![], vec![]);
        assert!(!keywords.matches_select("任何文字"));
        assert!(!keywords.is_empty());
        assert!(OptionKeywords::default().is_empty());
    }

    #[test]
    fn jsonc_cleaning_strips_comments_and_trailing_commas() {
        let raw = r#"[
            // 这是注释
            "确定",
            "继续",
        ]"#;
        let cleaned = clean_jsonc(raw);
        assert!(!cleaned.contains("//"), "注释应被去掉: {cleaned}");
        let parsed = parse_keyword_list(raw).unwrap();
        assert_eq!(parsed, words(&["确定", "继续"]));
    }

    #[test]
    fn jsonc_cleaning_handles_compact_trailing_comma() {
        assert_eq!(clean_jsonc(r#"["a","b",]"#), "[\"a\",\"b\"]\n");
        let parsed = parse_keyword_list(r#"["a", "b", ]"#).unwrap();
        assert_eq!(parsed, words(&["a", "b"]));
    }

    #[test]
    fn invalid_json_returns_none() {
        assert!(parse_keyword_list("{ oops").is_none());
        assert!(parse_keyword_list("").is_none());
    }

    #[test]
    fn blank_entries_are_dropped_and_trimmed() {
        let parsed = parse_keyword_list(r#"["  确定 ", "", "  "]"#).unwrap();
        assert_eq!(parsed, words(&["确定"]));
    }
}
