//! 参考画布坐标 → 当前截图搜索区域的换算。
//!
//! 模板是在一张「参考画布」（比如 3040x1904 的 PC 截图）上标出来的。
//! 手机上分辨率不同，必须先按等比缩放把 bbox 映射到当前截图，再按 `search` 配置扩展搜索框。
//! 公式与桌面版 BetterGI 的 `ImageRegionReferenceSearchHelper` 一致。

use bg_core::{Rect, Size};

/// 搜索框相对参考画布的锚点模式；`Auto` 按 bbox 在画布中的位置推断。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum AnchorMode {
    #[default]
    Auto,
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight,
    Center,
}

impl AnchorMode {
    pub fn from_name(name: &str) -> Option<Self> {
        match name {
            "Auto" => Some(AnchorMode::Auto),
            "TopLeft" => Some(AnchorMode::TopLeft),
            "TopRight" => Some(AnchorMode::TopRight),
            "BottomLeft" => Some(AnchorMode::BottomLeft),
            "BottomRight" => Some(AnchorMode::BottomRight),
            "Center" => Some(AnchorMode::Center),
            _ => None,
        }
    }
}

/// 搜索框四边扩展比例，顺序与 WPF `Thickness` 一致：Left、Top、Right、Bottom。
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ExpandRatio {
    pub left: f64,
    pub top: f64,
    pub right: f64,
    pub bottom: f64,
}

impl ExpandRatio {
    pub fn uniform(value: f64) -> Self {
        ExpandRatio {
            left: value,
            top: value,
            right: value,
            bottom: value,
        }
    }

    /// 支持 1 / 2 / 4 个数值的写法（Thickness 语义）。
    pub fn from_thickness(values: &[f64]) -> Option<Self> {
        let ratio = match values.len() {
            1 => ExpandRatio::uniform(values[0]),
            2 => ExpandRatio {
                left: values[0],
                top: values[1],
                right: values[0],
                bottom: values[1],
            },
            4 => ExpandRatio {
                left: values[0],
                top: values[1],
                right: values[2],
                bottom: values[3],
            },
            _ => return None,
        };
        if ratio.is_valid() {
            Some(ratio)
        } else {
            None
        }
    }

    pub fn is_valid(&self) -> bool {
        [self.left, self.top, self.right, self.bottom]
            .iter()
            .all(|value| value.is_finite() && *value >= 0.0)
    }
}

/// `Recognition.json` 里的 `search` 段。
#[derive(Debug, Clone, PartialEq, Default)]
pub struct SearchOptions {
    pub anchor_mode: AnchorMode,
    /// 搜索框（参考画布坐标）；缺省时用 bbox 本身。
    pub reference_search_box: Option<Rect>,
    /// 按像素扩展（四边同值）。
    pub expand_size: Option<Size>,
    /// 按画面边长比例扩展。
    pub expand_percent: Option<ExpandRatio>,
}

/// 换算结果。
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ReferenceSearchResult {
    /// 换算后、已扩展并夹紧到画面内的搜索区域。
    pub effective_roi: Rect,
    /// 参考画布 → 当前截图的缩放比。
    pub scale: f64,
    /// 是否走了参考搜索（false 表示直接用原始 roi）。
    pub used_reference_search: bool,
    /// 模板应缩放到的尺寸（仅参考搜索时有效）。
    pub effective_template_size: Option<Size>,
}

impl ReferenceSearchResult {
    fn passthrough(roi: Rect) -> Self {
        ReferenceSearchResult {
            effective_roi: roi,
            scale: 1.0,
            used_reference_search: false,
            effective_template_size: None,
        }
    }
}

/// 默认扩展像素，与桌面版一致。
pub const DEFAULT_EXPAND_PX: i32 = 10;

/// 是否具备完整的参考搜索信息（roi 未设置 + 参考尺寸 + bbox）。
pub fn has_reference_search(
    roi: Rect,
    reference_image_size: Option<Size>,
    reference_bounding_box: Option<Rect>,
) -> bool {
    roi.is_default() && reference_image_size.is_some() && reference_bounding_box.is_some()
}

/// 信息不完整：只给了其中一部分，此时无法换算，按「不识别」处理。
pub fn has_partial_reference_search(
    roi: Rect,
    reference_image_size: Option<Size>,
    reference_bounding_box: Option<Rect>,
    search_options: Option<&SearchOptions>,
) -> bool {
    roi.is_default()
        && (reference_image_size.is_some()
            || reference_bounding_box.is_some()
            || search_options.is_some())
        && !has_reference_search(roi, reference_image_size, reference_bounding_box)
}

/// 把参考坐标换算成当前截图的搜索区域。
///
/// 返回 `None` 的语义与 Android 侧一致：这一帧不识别（配置不完整 / 区域非法）。
pub fn try_get_region(
    src_size: Size,
    roi: Rect,
    reference_image_size: Option<Size>,
    reference_bounding_box: Option<Rect>,
    search_options: Option<&SearchOptions>,
    can_use_reference_search: bool,
    is_template_match: bool,
) -> Option<ReferenceSearchResult> {
    if has_partial_reference_search(
        roi,
        reference_image_size,
        reference_bounding_box,
        search_options,
    ) {
        return None;
    }
    if !has_reference_search(roi, reference_image_size, reference_bounding_box) {
        return Some(ReferenceSearchResult::passthrough(roi));
    }
    if !can_use_reference_search {
        return None;
    }

    let reference_size = reference_image_size?;
    let bbox = reference_bounding_box?;
    let default_options = SearchOptions::default();
    let options = search_options.unwrap_or(&default_options);

    if reference_size.is_empty()
        || bbox.is_empty()
        || options
            .reference_search_box
            .map(|search_box| search_box.is_empty())
            .unwrap_or(false)
        || options
            .expand_percent
            .map(|ratio| !ratio.is_valid())
            .unwrap_or(false)
    {
        return None;
    }

    let scale = (src_size.width as f64 / reference_size.width as f64)
        .min(src_size.height as f64 / reference_size.height as f64);
    if scale <= 0.0 {
        return None;
    }

    let (horizontal, vertical) = resolve_anchor(options.anchor_mode, bbox, reference_size);
    let scaled_ref_width = reference_size.width as f64 * scale;
    let scaled_ref_height = reference_size.height as f64 * scale;
    let offset_x = match horizontal {
        HorizontalAnchor::Right => src_size.width as f64 - scaled_ref_width,
        HorizontalAnchor::Center => (src_size.width as f64 - scaled_ref_width) / 2.0,
        HorizontalAnchor::Left => 0.0,
    };
    let offset_y = match vertical {
        VerticalAnchor::Bottom => src_size.height as f64 - scaled_ref_height,
        VerticalAnchor::Center => (src_size.height as f64 - scaled_ref_height) / 2.0,
        VerticalAnchor::Top => 0.0,
    };

    let transformed_bbox = transform_reference_rect(bbox, scale, offset_x, offset_y);
    let effective_template_size = Size::new(
        (bbox.width as f64 * scale).round().max(1.0) as i32,
        (bbox.height as f64 * scale).round().max(1.0) as i32,
    );
    let base_search_region = match options.reference_search_box {
        Some(search_box) => transform_reference_rect(search_box, scale, offset_x, offset_y),
        None => transformed_bbox,
    };
    let effective_roi =
        expand_and_clamp(base_search_region, src_size.width, src_size.height, options);
    if effective_roi.is_empty() {
        return None;
    }
    if is_template_match
        && (effective_roi.width < effective_template_size.width
            || effective_roi.height < effective_template_size.height)
    {
        return None;
    }

    Some(ReferenceSearchResult {
        effective_roi,
        scale,
        used_reference_search: true,
        effective_template_size: Some(effective_template_size),
    })
}

/// bbox 按缩放比缩放后的模板尺寸。
pub fn scaled_template_size(bbox: Rect, scale: f64) -> Size {
    Size::new(
        (bbox.width as f64 * scale).round().max(1.0) as i32,
        (bbox.height as f64 * scale).round().max(1.0) as i32,
    )
}

/// 参考矩形 → 当前截图坐标。
pub fn transform_reference_rect(rect: Rect, scale: f64, offset_x: f64, offset_y: f64) -> Rect {
    let left = (offset_x + rect.left() as f64 * scale).round() as i32;
    let top = (offset_y + rect.top() as f64 * scale).round() as i32;
    let right = (offset_x + rect.right() as f64 * scale).round() as i32;
    let bottom = (offset_y + rect.bottom() as f64 * scale).round() as i32;
    Rect::new(left, top, (right - left).max(1), (bottom - top).max(1))
}

/// 按比例或像素扩展搜索区域，并夹到画面内。
pub fn expand_and_clamp(
    base_region: Rect,
    image_width: i32,
    image_height: i32,
    options: &SearchOptions,
) -> Rect {
    let (expand_left, expand_top, expand_right, expand_bottom) = match options.expand_percent {
        Some(ratio) => (
            image_width as f64 * ratio.left,
            image_height as f64 * ratio.top,
            image_width as f64 * ratio.right,
            image_height as f64 * ratio.bottom,
        ),
        None => {
            let expand_size = options
                .expand_size
                .unwrap_or(Size::new(DEFAULT_EXPAND_PX, DEFAULT_EXPAND_PX));
            (
                expand_size.width as f64,
                expand_size.height as f64,
                expand_size.width as f64,
                expand_size.height as f64,
            )
        }
    };

    let left = (base_region.left() as f64 - expand_left)
        .clamp(0.0, image_width as f64)
        .round() as i32;
    let top = (base_region.top() as f64 - expand_top)
        .clamp(0.0, image_height as f64)
        .round() as i32;
    let right = (base_region.right() as f64 + expand_right)
        .clamp(0.0, image_width as f64)
        .round() as i32;
    let bottom = (base_region.bottom() as f64 + expand_bottom)
        .clamp(0.0, image_height as f64)
        .round() as i32;
    Rect::new(left, top, (right - left).max(0), (bottom - top).max(0))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HorizontalAnchor {
    Left,
    Center,
    Right,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VerticalAnchor {
    Top,
    Center,
    Bottom,
}

/// 解析锚点；`Auto` 依据 bbox 中心落在画布的哪个三分之一区域推断。
pub fn resolve_anchor(
    mode: AnchorMode,
    bbox: Rect,
    reference_size: Size,
) -> (HorizontalAnchor, VerticalAnchor) {
    match mode {
        AnchorMode::TopLeft => (HorizontalAnchor::Left, VerticalAnchor::Top),
        AnchorMode::TopRight => (HorizontalAnchor::Right, VerticalAnchor::Top),
        AnchorMode::BottomLeft => (HorizontalAnchor::Left, VerticalAnchor::Bottom),
        AnchorMode::BottomRight => (HorizontalAnchor::Right, VerticalAnchor::Bottom),
        AnchorMode::Center => (HorizontalAnchor::Center, VerticalAnchor::Center),
        AnchorMode::Auto => resolve_auto_anchor(bbox, reference_size),
    }
}

fn resolve_auto_anchor(bbox: Rect, reference_size: Size) -> (HorizontalAnchor, VerticalAnchor) {
    let center_x = bbox.x as f64 + bbox.width as f64 / 2.0;
    let center_y = bbox.y as f64 + bbox.height as f64 / 2.0;
    let horizontal = if center_x < reference_size.width as f64 * 0.4 {
        HorizontalAnchor::Left
    } else if center_x > reference_size.width as f64 * 0.6 {
        HorizontalAnchor::Right
    } else {
        HorizontalAnchor::Center
    };
    let vertical = if center_y < reference_size.height as f64 * 0.4 {
        VerticalAnchor::Top
    } else if center_y > reference_size.height as f64 * 0.6 {
        VerticalAnchor::Bottom
    } else {
        VerticalAnchor::Center
    };
    (horizontal, vertical)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn approx(a: f64, b: f64) {
        assert!((a - b).abs() < 1e-6, "期望 {b}，实际 {a}");
    }

    #[test]
    fn auto_anchor_picks_third_region() {
        let size = Size::new(3040, 1904);
        // 左上角：x/y 都很小
        assert_eq!(
            resolve_anchor(AnchorMode::Auto, Rect::new(375, 72, 55, 47), size),
            (HorizontalAnchor::Left, VerticalAnchor::Top)
        );
        // 屏幕正中央
        assert_eq!(
            resolve_anchor(AnchorMode::Auto, Rect::new(1500, 900, 50, 50), size),
            (HorizontalAnchor::Center, VerticalAnchor::Center)
        );
        // 右下角
        assert_eq!(
            resolve_anchor(AnchorMode::Auto, Rect::new(2900, 1800, 50, 50), size),
            (HorizontalAnchor::Right, VerticalAnchor::Bottom)
        );
    }

    #[test]
    fn explicit_anchor_overrides_auto() {
        let size = Size::new(1000, 1000);
        assert_eq!(
            resolve_anchor(AnchorMode::BottomRight, Rect::new(0, 0, 10, 10), size),
            (HorizontalAnchor::Right, VerticalAnchor::Bottom)
        );
    }

    #[test]
    fn no_reference_passes_roi_through() {
        let roi = Rect::new(10, 20, 30, 40);
        let result =
            try_get_region(Size::new(1920, 1080), roi, None, None, None, false, true).unwrap();
        assert!(!result.used_reference_search);
        assert_eq!(result.effective_roi, roi);
        approx(result.scale, 1.0);
    }

    #[test]
    fn partial_reference_is_rejected() {
        // 只有 bbox，没有参考尺寸 → 不识别
        assert!(try_get_region(
            Size::new(1920, 1080),
            Rect::EMPTY,
            None,
            Some(Rect::new(0, 0, 10, 10)),
            None,
            true,
            true,
        )
        .is_none());
        // 只有 search 配置 → 不识别
        assert!(try_get_region(
            Size::new(1920, 1080),
            Rect::EMPTY,
            None,
            None,
            Some(&SearchOptions::default()),
            true,
            true,
        )
        .is_none());
    }

    #[test]
    fn reference_requires_capable_region() {
        let result = try_get_region(
            Size::new(1920, 1080),
            Rect::EMPTY,
            Some(Size::new(3040, 1904)),
            Some(Rect::new(375, 72, 55, 47)),
            None,
            false,
            true,
        );
        assert!(result.is_none());
    }

    #[test]
    fn scales_template_and_search_area_for_1080p_screen() {
        // 真实配置：TalkHistory 的模板在 3040x1904 画布上的 bbox。
        let options = SearchOptions {
            anchor_mode: AnchorMode::Auto,
            reference_search_box: None,
            expand_size: None,
            expand_percent: Some(ExpandRatio::from_thickness(&[0.0, 0.02, 0.12, 0.02]).unwrap()),
        };
        let result = try_get_region(
            Size::new(1920, 1080),
            Rect::EMPTY,
            Some(Size::new(3040, 1904)),
            Some(Rect::new(375, 72, 55, 47)),
            Some(&options),
            true,
            true,
        )
        .expect("应能换算");

        assert!(result.used_reference_search);
        approx(result.scale, 1080.0 / 1904.0);

        let template_size = result.effective_template_size.unwrap();
        approx(template_size.width as f64, (55.0 * result.scale).round());
        approx(template_size.height as f64, (47.0 * result.scale).round());

        // 左上角锚点 + 按比例扩展 → ROI 必须覆盖缩放后的 bbox，且不越界。
        assert!(!result.effective_roi.is_empty());
        assert!(result.effective_roi.left() >= 0 && result.effective_roi.top() >= 0);
        assert!(result.effective_roi.right() <= 1920);
        assert!(result.effective_roi.bottom() <= 1080);
        let bbox_scaled =
            transform_reference_rect(Rect::new(375, 72, 55, 47), result.scale, 0.0, 0.0);
        assert!(result.effective_roi.contains(bbox_scaled));
    }

    #[test]
    fn search_box_replaces_bbox_as_base() {
        let options = SearchOptions {
            reference_search_box: Some(Rect::new(1787, 126, 1098, 1470)),
            expand_percent: Some(ExpandRatio::from_thickness(&[0.1, 0.0, 0.0, 0.0]).unwrap()),
            ..Default::default()
        };
        let result = try_get_region(
            Size::new(1920, 1080),
            Rect::EMPTY,
            Some(Size::new(3040, 1904)),
            Some(Rect::new(1930, 1342, 52, 30)),
            Some(&options),
            true,
            true,
        )
        .unwrap();
        // 搜索框比 bbox 大得多，换算后应覆盖屏幕右侧一大片。
        assert!(result.effective_roi.width > 500);
        assert!(result.effective_roi.right() <= 1920);
    }

    #[test]
    fn template_search_rejected_when_roi_smaller_than_template() {
        // 参考画布和截图一样大，bbox 比搜索框还大 → 判定 ROI 放不下模板
        let options = SearchOptions {
            reference_search_box: Some(Rect::new(0, 0, 10, 10)),
            expand_percent: Some(ExpandRatio::uniform(0.0)),
            ..Default::default()
        };
        assert!(try_get_region(
            Size::new(1920, 1080),
            Rect::EMPTY,
            Some(Size::new(1920, 1080)),
            Some(Rect::new(0, 0, 200, 200)),
            Some(&options),
            true,
            true,
        )
        .is_none());
    }

    #[test]
    fn expand_clamps_to_image_bounds() {
        let options = SearchOptions {
            expand_percent: Some(ExpandRatio::uniform(0.5)),
            ..Default::default()
        };
        let roi = expand_and_clamp(Rect::new(100, 50, 200, 100), 400, 300, &options);
        assert_eq!(roi, Rect::new(0, 0, 400, 300));
    }

    #[test]
    fn expand_size_in_pixels() {
        let options = SearchOptions {
            expand_size: Some(Size::new(10, 20)),
            ..Default::default()
        };
        let roi = expand_and_clamp(Rect::new(100, 100, 50, 50), 1000, 1000, &options);
        assert_eq!(roi, Rect::new(90, 80, 70, 90));
    }

    #[test]
    fn expand_percent_parsing() {
        assert_eq!(
            ExpandRatio::from_thickness(&[0.1]),
            Some(ExpandRatio::uniform(0.1))
        );
        assert_eq!(
            ExpandRatio::from_thickness(&[0.1, 0.2]),
            Some(ExpandRatio {
                left: 0.1,
                top: 0.2,
                right: 0.1,
                bottom: 0.2
            })
        );
        assert_eq!(ExpandRatio::from_thickness(&[0.1, 0.2, 0.3]), None);
        assert_eq!(ExpandRatio::from_thickness(&[-1.0]), None);
        assert_eq!(ExpandRatio::from_thickness(&[f64::NAN]), None);
    }
}
