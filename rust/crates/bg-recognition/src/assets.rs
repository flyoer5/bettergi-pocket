//! 解析 `recognition/**/Recognition.json`。
//!
//! 资源格式借用了桌面版 BetterGI：对象表 + 可复用的 `regions` / `templates` 别名表。
//! 这里不加载图片，只产出一个「识别配置」，具体模板由平台层按名字取图后传进来。

use std::collections::BTreeMap;
use std::fmt;

use bg_core::{Rect, Size};
use serde_json::Value;

use crate::reference::{AnchorMode, ExpandRatio, SearchOptions};
use crate::template::MatchMode;

/// 识别类型；Pocket 只实现其中四种，其余保留名字便于报错。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RecognitionType {
    TemplateMatch,
    Ocr,
    OcrMatch,
    ColorRangeAndOcr,
    Unsupported(String),
}

impl RecognitionType {
    pub fn from_name(name: &str) -> Self {
        match name {
            "TemplateMatch" => RecognitionType::TemplateMatch,
            "Ocr" => RecognitionType::Ocr,
            "OcrMatch" => RecognitionType::OcrMatch,
            "ColorRangeAndOcr" => RecognitionType::ColorRangeAndOcr,
            other => RecognitionType::Unsupported(other.to_string()),
        }
    }

    pub fn is_supported(&self) -> bool {
        !matches!(self, RecognitionType::Unsupported(_))
    }

    pub fn name(&self) -> &str {
        match self {
            RecognitionType::TemplateMatch => "TemplateMatch",
            RecognitionType::Ocr => "Ocr",
            RecognitionType::OcrMatch => "OcrMatch",
            RecognitionType::ColorRangeAndOcr => "ColorRangeAndOcr",
            RecognitionType::Unsupported(name) => name,
        }
    }
}

/// BGR 颜色，取值 0~255。
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ColorBgr {
    pub b: f64,
    pub g: f64,
    pub r: f64,
}

impl ColorBgr {
    pub const BLACK: ColorBgr = ColorBgr {
        b: 0.0,
        g: 0.0,
        r: 0.0,
    };
    pub const WHITE: ColorBgr = ColorBgr {
        b: 255.0,
        g: 255.0,
        r: 255.0,
    };
}

/// 一条识别配置。
#[derive(Debug, Clone, PartialEq)]
pub struct RecognitionObject {
    pub name: String,
    pub recognition_type: RecognitionType,
    pub template: Option<String>,
    /// 归一化阈值，1.0 表示完美匹配。
    pub threshold: f64,
    pub use_3_channels: bool,
    pub match_mode: MatchMode,
    pub max_match_count: i32,
    pub use_binary_match: bool,
    pub binary_threshold: i32,
    /// 显式 ROI；为空（全 0）时走参考搜索。
    pub roi: Rect,
    pub reference_image_size: Option<Size>,
    pub reference_bounding_box: Option<Rect>,
    pub search_options: Option<SearchOptions>,
    pub all_contain_match_text: Vec<String>,
    pub one_contain_match_text: Vec<String>,
    pub regex_match_text: Vec<String>,
    pub replace_dictionary: BTreeMap<String, Vec<String>>,
    pub color_conversion: String,
    pub lower_color: ColorBgr,
    pub upper_color: ColorBgr,
}

impl RecognitionObject {
    /// 是否配置了完整的参考画布信息。
    pub fn has_reference(&self) -> bool {
        self.reference_image_size.is_some() && self.reference_bounding_box.is_some()
    }

    /// 是否设置过显式 ROI。
    pub fn has_explicit_roi(&self) -> bool {
        !self.roi.is_default()
    }
}

/// 一个资源包里的全部识别对象（外加原始别名表）。
#[derive(Debug, Clone, Default)]
pub struct RecognitionCatalog {
    objects: BTreeMap<String, RecognitionObject>,
    regions: BTreeMap<String, String>,
    templates: BTreeMap<String, String>,
}

impl RecognitionCatalog {
    pub fn get(&self, name: &str) -> Option<&RecognitionObject> {
        self.objects.get(name)
    }

    pub fn require(&self, name: &str) -> Result<&RecognitionObject, AssetError> {
        self.objects
            .get(name)
            .ok_or_else(|| AssetError::MissingObject(name.to_string()))
    }

    pub fn names(&self) -> impl Iterator<Item = &str> {
        self.objects.keys().map(|name| name.as_str())
    }

    pub fn len(&self) -> usize {
        self.objects.len()
    }

    pub fn is_empty(&self) -> bool {
        self.objects.is_empty()
    }

    pub fn regions(&self) -> &BTreeMap<String, String> {
        &self.regions
    }

    pub fn templates(&self) -> &BTreeMap<String, String> {
        &self.templates
    }

    /// 解析整份 `Recognition.json`。
    pub fn from_json_str(json: &str) -> Result<Self, AssetError> {
        let root: Value =
            serde_json::from_str(json).map_err(|err| AssetError::Json(err.to_string()))?;
        let objects = root
            .get("objects")
            .and_then(Value::as_object)
            .ok_or(AssetError::MissingObjects)?;
        let regions = string_map(root.get("regions"));
        let templates = string_map(root.get("templates"));

        let mut catalog = RecognitionCatalog {
            objects: BTreeMap::new(),
            regions,
            templates,
        };
        for (object_name, config) in objects {
            let object = RecognitionObject::from_json(
                object_name,
                config,
                &catalog.regions,
                &catalog.templates,
            )?;
            catalog.objects.insert(object_name.clone(), object);
        }
        Ok(catalog)
    }
}

impl RecognitionObject {
    fn from_json(
        object_name: &str,
        config: &Value,
        regions: &BTreeMap<String, String>,
        templates: &BTreeMap<String, String>,
    ) -> Result<Self, AssetError> {
        let type_name =
            config
                .get("type")
                .and_then(Value::as_str)
                .ok_or_else(|| AssetError::InvalidField {
                    object: object_name.to_string(),
                    field: "type".to_string(),
                    detail: "不能为空".to_string(),
                })?;
        let recognition_type = RecognitionType::from_name(type_name);
        if !recognition_type.is_supported() {
            return Err(AssetError::UnsupportedType(type_name.to_string()));
        }

        let template = if recognition_type == RecognitionType::TemplateMatch {
            let raw = config.get("template").and_then(Value::as_str);
            Some(resolve_alias(raw, templates, "templates")?.ok_or_else(|| {
                AssetError::InvalidField {
                    object: object_name.to_string(),
                    field: "template".to_string(),
                    detail: "TemplateMatch 必须指定模板".to_string(),
                }
            })?)
        } else {
            None
        };

        let name = config
            .get("name")
            .and_then(Value::as_str)
            .filter(|value| !value.is_empty())
            .map(str::to_string)
            .or_else(|| {
                template.as_ref().map(|file| {
                    file.rsplit_once('.')
                        .map(|(stem, _)| stem)
                        .unwrap_or(file)
                        .to_string()
                })
            })
            .unwrap_or_else(|| object_name.to_string());

        let roi = match config.get("roi").and_then(Value::as_str) {
            Some(expression) => match resolve_alias(Some(expression), regions, "regions")? {
                Some(resolved) => parse_rect(&resolved)?,
                None => Rect::EMPTY,
            },
            None => Rect::EMPTY,
        };

        let threshold = config
            .get("threshold")
            .and_then(Value::as_f64)
            .unwrap_or(0.8);
        let match_mode = config
            .get("templateMatchMode")
            .and_then(Value::as_str)
            .map(|raw| {
                MatchMode::from_name(raw).ok_or_else(|| AssetError::InvalidField {
                    object: object_name.to_string(),
                    field: "templateMatchMode".to_string(),
                    detail: raw.to_string(),
                })
            })
            .transpose()?
            .unwrap_or(MatchMode::CCoeffNormed);

        let reference = config.get("reference");
        let reference_image_size = reference
            .and_then(|value| value.get("size"))
            .and_then(Value::as_array)
            .filter(|sizes| sizes.len() == 2)
            .map(|sizes| {
                Size::new(
                    sizes[0].as_i64().unwrap_or(0) as i32,
                    sizes[1].as_i64().unwrap_or(0) as i32,
                )
            });
        let reference_bounding_box = match reference
            .and_then(|value| value.get("bbox"))
            .and_then(Value::as_str)
        {
            Some(expression) => match resolve_alias(Some(expression), regions, "regions")? {
                Some(resolved) => Some(parse_rect(&resolved)?),
                None => None,
            },
            None => None,
        };

        let search_options = match config.get("search") {
            Some(search) => {
                let mut options = SearchOptions::default();
                if let Some(anchor) = search.get("anchor").and_then(Value::as_str) {
                    options.anchor_mode =
                        AnchorMode::from_name(anchor).ok_or_else(|| AssetError::InvalidField {
                            object: object_name.to_string(),
                            field: "search.anchor".to_string(),
                            detail: anchor.to_string(),
                        })?;
                }
                if let Some(expression) = search.get("box").and_then(Value::as_str) {
                    let resolved = resolve_alias(Some(expression), regions, "regions")?
                        .ok_or_else(|| AssetError::InvalidField {
                            object: object_name.to_string(),
                            field: "search.box".to_string(),
                            detail: "为空".to_string(),
                        })?;
                    options.reference_search_box = Some(parse_rect(&resolved)?);
                }
                if let Some(expand) = search.get("expand").and_then(Value::as_array) {
                    if expand.len() == 2 {
                        let width = expand[0].as_i64().unwrap_or(0) as i32;
                        let height = expand[1].as_i64().unwrap_or(0) as i32;
                        options.expand_size = Some(Size::new(width, height));
                    }
                }
                if let Some(percentages) = search.get("expandPercent").and_then(Value::as_array) {
                    let values: Vec<f64> = percentages.iter().filter_map(Value::as_f64).collect();
                    if values.len() != percentages.len() {
                        return Err(AssetError::InvalidField {
                            object: object_name.to_string(),
                            field: "search.expandPercent".to_string(),
                            detail: "存在非数字项".to_string(),
                        });
                    }
                    options.expand_percent =
                        Some(ExpandRatio::from_thickness(&values).ok_or_else(|| {
                            AssetError::InvalidField {
                                object: object_name.to_string(),
                                field: "search.expandPercent".to_string(),
                                detail: "必须是 1/2/4 个非负数".to_string(),
                            }
                        })?);
                }
                Some(options)
            }
            None => None,
        };

        let replace_dictionary = config
            .get("replaceDictionary")
            .and_then(Value::as_object)
            .map(|dictionary| {
                dictionary
                    .iter()
                    .map(|(canonical, wrongs)| (canonical.clone(), string_list(wrongs)))
                    .collect()
            })
            .unwrap_or_default();

        Ok(RecognitionObject {
            name,
            recognition_type,
            template,
            threshold,
            use_3_channels: config
                .get("use3Channels")
                .and_then(Value::as_bool)
                .unwrap_or(false),
            match_mode,
            max_match_count: config
                .get("maxMatchCount")
                .and_then(Value::as_i64)
                .unwrap_or(-1) as i32,
            use_binary_match: config
                .get("useBinaryMatch")
                .and_then(Value::as_bool)
                .unwrap_or(false),
            binary_threshold: config
                .get("binaryThreshold")
                .and_then(Value::as_i64)
                .unwrap_or(128) as i32,
            roi,
            reference_image_size,
            reference_bounding_box,
            search_options,
            all_contain_match_text: string_list_field(config, "allContainMatchText"),
            one_contain_match_text: string_list_field(config, "oneContainMatchText"),
            regex_match_text: string_list_field(config, "regexMatchText"),
            replace_dictionary,
            color_conversion: config
                .get("colorConversion")
                .and_then(Value::as_str)
                .unwrap_or("BgrToRgb")
                .to_string(),
            lower_color: color_field(config, "lowerColor", ColorBgr::BLACK),
            upper_color: color_field(config, "upperColor", ColorBgr::WHITE),
        })
    }
}

fn string_map(value: Option<&Value>) -> BTreeMap<String, String> {
    value
        .and_then(Value::as_object)
        .map(|map| {
            map.iter()
                .filter_map(|(key, value)| {
                    value.as_str().map(|text| (key.clone(), text.to_string()))
                })
                .collect()
        })
        .unwrap_or_default()
}

fn string_list(value: &Value) -> Vec<String> {
    value
        .as_array()
        .map(|items| {
            items
                .iter()
                .filter_map(Value::as_str)
                .map(|item| item.trim().to_string())
                .filter(|item| !item.is_empty())
                .collect()
        })
        .unwrap_or_default()
}

fn string_list_field(config: &Value, field: &str) -> Vec<String> {
    config.get(field).map(string_list).unwrap_or_default()
}

fn color_field(config: &Value, field: &str, default: ColorBgr) -> ColorBgr {
    config
        .get(field)
        .and_then(Value::as_array)
        .filter(|channels| channels.len() >= 3)
        .map(|channels| ColorBgr {
            b: channels[0].as_f64().unwrap_or(0.0),
            g: channels[1].as_f64().unwrap_or(0.0),
            r: channels[2].as_f64().unwrap_or(0.0),
        })
        .unwrap_or(default)
}

/// 解析 `"@alias"` 形式的间接引用；循环引用报错。
pub fn resolve_alias(
    raw: Option<&str>,
    table: &BTreeMap<String, String>,
    kind: &str,
) -> Result<Option<String>, AssetError> {
    let mut current = match raw {
        Some(raw) => raw.trim().to_string(),
        None => return Ok(None),
    };
    if current.is_empty() {
        return Ok(None);
    }
    let mut seen = Vec::new();
    while let Some(alias) = current.strip_prefix('@') {
        if seen.iter().any(|item| item == alias) {
            return Err(AssetError::AliasCycle(alias.to_string()));
        }
        seen.push(alias.to_string());
        match table.get(alias) {
            Some(value) => current = value.trim().to_string(),
            None => {
                return Err(AssetError::MissingAlias {
                    kind: kind.to_string(),
                    alias: alias.to_string(),
                })
            }
        }
    }
    Ok(Some(current))
}

/// 解析 `rect(x, y, w, h)` 表达式（允许小数与空格）。
pub fn parse_rect(expression: &str) -> Result<Rect, AssetError> {
    let trimmed = expression.trim();
    let lower = trimmed.to_ascii_lowercase();
    if !lower.starts_with("rect") {
        return Err(AssetError::InvalidRect(expression.to_string()));
    }
    let open = trimmed
        .find('(')
        .ok_or_else(|| AssetError::InvalidRect(expression.to_string()))?;
    let close = trimmed
        .rfind(')')
        .ok_or_else(|| AssetError::InvalidRect(expression.to_string()))?;
    if close <= open {
        return Err(AssetError::InvalidRect(expression.to_string()));
    }
    let inner = &trimmed[open + 1..close];
    let parts: Vec<&str> = inner.split(',').map(str::trim).collect();
    if parts.len() != 4 {
        return Err(AssetError::InvalidRect(expression.to_string()));
    }
    let mut numbers = [0.0f64; 4];
    for (index, part) in parts.iter().enumerate() {
        numbers[index] = part
            .parse::<f64>()
            .map_err(|_| AssetError::InvalidRect(expression.to_string()))?;
    }
    Ok(Rect::new(
        numbers[0].round() as i32,
        numbers[1].round() as i32,
        numbers[2].round() as i32,
        numbers[3].round() as i32,
    ))
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AssetError {
    Json(String),
    MissingObjects,
    MissingObject(String),
    UnsupportedType(String),
    InvalidRect(String),
    AliasCycle(String),
    MissingAlias {
        kind: String,
        alias: String,
    },
    InvalidField {
        object: String,
        field: String,
        detail: String,
    },
}

impl fmt::Display for AssetError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            AssetError::Json(detail) => write!(f, "识别资源不是合法 JSON: {detail}"),
            AssetError::MissingObjects => write!(f, "Recognition.json 缺少 objects 段"),
            AssetError::MissingObject(name) => write!(f, "未找到识别对象 {name}"),
            AssetError::UnsupportedType(name) => write!(f, "尚未实现的识别类型 {name}"),
            AssetError::InvalidRect(expression) => {
                write!(f, "不是合法的 rect 表达式: {expression}")
            }
            AssetError::AliasCycle(alias) => write!(f, "别名 {alias} 存在循环引用"),
            AssetError::MissingAlias { kind, alias } => write!(f, "未找到 {kind} 别名 {alias}"),
            AssetError::InvalidField {
                object,
                field,
                detail,
            } => {
                write!(f, "对象 {object} 的 {field} 非法：{detail}")
            }
        }
    }
}

impl std::error::Error for AssetError {}

#[cfg(test)]
mod tests {
    use super::*;

    /// 与仓库里 `assets/recognition/AutoSkip/Recognition.json` 同构的最小样例。
    const SAMPLE: &str = r#"{
      "version": 1,
      "regions": { "chatBox": "rect(0, 0, 100, 100)", "chat": "@chatBox" },
      "templates": { "chat_icon_file": "chat_icon.png" },
      "objects": {
        "TalkHistory": {
          "type": "TemplateMatch",
          "template": "talk_history.png",
          "threshold": 0.75,
          "reference": { "size": [3040, 1904], "bbox": "rect(375, 72, 55, 47)" },
          "search": { "expandPercent": [0, 0.02, 0.12, 0.02] }
        },
        "ChatIcon": {
          "type": "TemplateMatch",
          "template": "@chat_icon_file",
          "reference": { "size": [3040, 1904], "bbox": "rect(1930, 1342, 52, 30)" },
          "search": { "box": "rect(1787, 126, 1098, 1470)", "expandPercent": [0.1, 0, 0, 0] }
        },
        "TalkHistoryLabel": {
          "type": "Ocr",
          "reference": { "size": [3040, 1904], "bbox": "rect(0, 96, 0, 0)" },
          "search": { "box": "@chat" }
        }
      }
    }"#;

    #[test]
    fn parses_full_catalog() {
        let catalog = RecognitionCatalog::from_json_str(SAMPLE).unwrap();
        assert_eq!(catalog.len(), 3);

        let talk = catalog.require("TalkHistory").unwrap();
        assert_eq!(talk.recognition_type, RecognitionType::TemplateMatch);
        assert_eq!(talk.template.as_deref(), Some("talk_history.png"));
        assert!((talk.threshold - 0.75).abs() < 1e-9);
        assert_eq!(
            talk.match_mode,
            MatchMode::CCoeffNormed,
            "默认匹配模式应为 CCoeffNormed"
        );
        assert_eq!(talk.reference_image_size, Some(Size::new(3040, 1904)));
        assert_eq!(
            talk.reference_bounding_box,
            Some(Rect::new(375, 72, 55, 47))
        );
        let search = talk.search_options.as_ref().unwrap();
        assert_eq!(
            search.expand_percent,
            Some(ExpandRatio {
                left: 0.0,
                top: 0.02,
                right: 0.12,
                bottom: 0.02
            })
        );
        assert!(talk.has_reference());
        assert!(!talk.has_explicit_roi());
    }

    #[test]
    fn resolves_template_alias_to_file() {
        let catalog = RecognitionCatalog::from_json_str(SAMPLE).unwrap();
        let chat = catalog.require("ChatIcon").unwrap();
        assert_eq!(chat.template.as_deref(), Some("chat_icon.png"));
    }

    #[test]
    fn resolves_search_box_region_alias() {
        let catalog = RecognitionCatalog::from_json_str(SAMPLE).unwrap();
        let label = catalog.require("TalkHistoryLabel").unwrap();
        assert_eq!(label.recognition_type, RecognitionType::Ocr);
        let search = label.search_options.as_ref().unwrap();
        // @chat -> @chatBox -> rect(0, 0, 100, 100)
        assert_eq!(search.reference_search_box, Some(Rect::new(0, 0, 100, 100)));
    }

    #[test]
    fn missing_alias_is_an_error() {
        let broken = r#"{
          "regions": { "chat": "@chatBox" },
          "objects": {
            "X": { "type": "Ocr", "search": { "box": "@chat" } }
          }
        }"#;
        let err = RecognitionCatalog::from_json_str(broken).unwrap_err();
        assert!(
            matches!(err, AssetError::MissingAlias { .. }),
            "实际 {err:?}"
        );
    }

    #[test]
    fn detects_alias_cycle() {
        let cyclic = r#"{
          "regions": { "a": "@b", "b": "@a" },
          "objects": {
            "X": { "type": "Ocr", "search": { "box": "@a" } }
          }
        }"#;
        let err = RecognitionCatalog::from_json_str(cyclic).unwrap_err();
        assert!(matches!(err, AssetError::AliasCycle(_)), "实际 {err:?}");
    }

    #[test]
    fn rejects_unsupported_type() {
        let json = r#"{ "objects": { "X": { "type": "Detect" } } }"#;
        let err = RecognitionCatalog::from_json_str(json).unwrap_err();
        assert!(matches!(err, AssetError::UnsupportedType(_)));
    }

    #[test]
    fn rejects_missing_objects() {
        assert_eq!(
            RecognitionCatalog::from_json_str(r#"{"version": 1}"#).unwrap_err(),
            AssetError::MissingObjects
        );
    }

    #[test]
    fn rejects_broken_json() {
        assert!(matches!(
            RecognitionCatalog::from_json_str("{ oops").unwrap_err(),
            AssetError::Json(_)
        ));
    }

    #[test]
    fn template_match_requires_template() {
        let json = r#"{ "objects": { "X": { "type": "TemplateMatch" } } }"#;
        let err = RecognitionCatalog::from_json_str(json).unwrap_err();
        assert!(
            matches!(err, AssetError::InvalidField { .. }),
            "实际 {err:?}"
        );
    }

    #[test]
    fn derives_object_name_from_template_file() {
        let json =
            r#"{ "objects": { "Obj": { "type": "TemplateMatch", "template": "icon.png" } } }"#;
        let catalog = RecognitionCatalog::from_json_str(json).unwrap();
        assert_eq!(catalog.require("Obj").unwrap().name, "icon");
    }

    #[test]
    fn parse_rect_accepts_spaces_and_decimals() {
        assert_eq!(parse_rect("rect(1,2,3,4)").unwrap(), Rect::new(1, 2, 3, 4));
        assert_eq!(
            parse_rect(" rect( 1.4 , 2.6 , 3 , 4 ) ").unwrap(),
            Rect::new(1, 3, 3, 4)
        );
        assert_eq!(
            parse_rect("RECT(0,0,10,10)").unwrap(),
            Rect::new(0, 0, 10, 10)
        );
        assert!(parse_rect("rect(1,2,3)").is_err());
        assert!(parse_rect("box(1,2,3,4)").is_err());
        assert!(parse_rect("rect(a,b,c,d)").is_err());
    }

    #[test]
    fn explicit_roi_is_parsed() {
        let json = r#"{ "objects": { "X": { "type": "Ocr", "roi": "rect(10,20,30,40)" } } }"#;
        let catalog = RecognitionCatalog::from_json_str(json).unwrap();
        assert_eq!(catalog.require("X").unwrap().roi, Rect::new(10, 20, 30, 40));
        assert!(catalog.require("X").unwrap().has_explicit_roi());
    }

    #[test]
    fn ocr_text_lists_are_parsed() {
        let json = r#"{
          "objects": {
            "X": {
              "type": "OcrMatch",
              "allContainMatchText": ["  确定 ", ""],
              "oneContainMatchText": ["继续"],
              "regexMatchText": ["\\d{2}"]
            }
          }
        }"#;
        let catalog = RecognitionCatalog::from_json_str(json).unwrap();
        let object = catalog.require("X").unwrap();
        assert_eq!(object.all_contain_match_text, vec!["确定".to_string()]);
        assert_eq!(object.one_contain_match_text, vec!["继续".to_string()]);
        assert_eq!(object.regex_match_text, vec!["\\d{2}".to_string()]);
    }
}
