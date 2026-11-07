//! OCR 文本的规整与匹配规则。
//!
//! OCR 引擎在平台层（Android 的 ML Kit / 自建模型），这里只做纯文本处理：
//! 去空格、错字替换、包含/正则匹配。逻辑与 Android 侧 `OcrText` 一致。

use std::collections::BTreeMap;

/// 去掉所有半角空格与制表符（OCR 常在汉字之间插空格）。
pub fn remove_all_space(text: &str) -> String {
    text.chars().filter(|c| *c != ' ' && *c != '\t').collect()
}

/// 按替换表把「常见错字」换回正确写法。
///
/// 替换表结构：`正确词 -> [错字...]`，按 `BTreeMap` 顺序依次替换。
pub fn apply_replacements(text: &str, replacements: &BTreeMap<String, Vec<String>>) -> String {
    let mut result = text.to_string();
    for (canonical, wrongs) in replacements {
        for wrong in wrongs {
            if wrong.is_empty() {
                continue;
            }
            result = result.replace(wrong.as_str(), canonical);
        }
    }
    result
}

/// 先去掉空白，再按替换表纠错。
pub fn normalize(text: &str, replacements: &BTreeMap<String, Vec<String>>) -> String {
    apply_replacements(&remove_all_space(text), replacements)
}

/// OCR 匹配规则：
/// * `all_contain` 全部命中
/// * `one_contain` 至少命中一个（为空表示不限制）
/// * `regex` 全部命中
pub fn matches(
    text: &str,
    all_contain: &[String],
    one_contain: &[String],
    regex: &[String],
) -> Result<bool, regex::Error> {
    let all_ok = all_contain.iter().all(|needle| text.contains(needle));
    if !all_ok {
        return Ok(false);
    }
    let one_ok = one_contain.is_empty() || one_contain.iter().any(|needle| text.contains(needle));
    if !one_ok {
        return Ok(false);
    }
    for pattern in regex {
        let compiled = regex::Regex::new(pattern)?;
        if !compiled.is_match(text) {
            return Ok(false);
        }
    }
    Ok(true)
}

/// 是否配置了任何匹配条件（三条全空表示「无条件命中」，与 Android 侧抛错的判断相反）。
pub fn has_any_condition(all_contain: &[String], one_contain: &[String], regex: &[String]) -> bool {
    !all_contain.is_empty() || !one_contain.is_empty() || !regex.is_empty()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dict(pairs: &[(&str, &[&str])]) -> BTreeMap<String, Vec<String>> {
        pairs
            .iter()
            .map(|(canonical, wrongs)| {
                (
                    (*canonical).to_string(),
                    wrongs.iter().map(|wrong| (*wrong).to_string()).collect(),
                )
            })
            .collect()
    }

    fn owned(items: &[&str]) -> Vec<String> {
        items.iter().map(|item| (*item).to_string()).collect()
    }

    #[test]
    fn removes_spaces_and_tabs() {
        assert_eq!(remove_all_space(" 你好 世界\t!"), "你好世界!");
        assert_eq!(remove_all_space(""), "");
    }

    #[test]
    fn applies_replacements() {
        let replacements = dict(&[("里面", &["里 面", "里囱"])]);
        assert_eq!(
            apply_replacements("请进里 面坐", &replacements),
            "请进里面坐"
        );
        assert_eq!(apply_replacements("里囱请", &replacements), "里面请");
    }

    #[test]
    fn normalize_combines_steps() {
        let replacements = dict(&[("而且", &["而 且"])]);
        assert_eq!(normalize(" 而 且 很 好 ", &replacements), "而且很好");
    }

    #[test]
    fn all_contain_requires_every_needle() {
        let text = "确定要继续吗";
        assert!(matches(text, &owned(&["确定", "继续"]), &[], &[]).unwrap());
        assert!(!matches(text, &owned(&["确定", "取消"]), &[], &[]).unwrap());
    }

    #[test]
    fn one_contain_requires_at_least_one() {
        let text = "领取奖励";
        assert!(matches(text, &[], &owned(&["领取", "放弃"]), &[]).unwrap());
        assert!(!matches(text, &[], &owned(&["离开", "放弃"]), &[]).unwrap());
        assert!(matches(text, &[], &[], &[]).unwrap(), "空条件不限制");
    }

    #[test]
    fn regex_patterns_must_all_match() {
        let text = "第 12 章";
        assert!(matches(text, &[], &[], &owned(&[r"\d+"])).unwrap());
        assert!(!matches(text, &[], &[], &owned(&[r"\d+", r"^章"])).unwrap());
    }

    #[test]
    fn invalid_regex_is_reported_not_swallowed() {
        assert!(matches("abc", &[], &[], &owned(&["("])).is_err());
    }

    #[test]
    fn detection_of_any_condition() {
        assert!(!has_any_condition(&[], &[], &[]));
        assert!(has_any_condition(&owned(&["x"]), &[], &[]));
    }
}
