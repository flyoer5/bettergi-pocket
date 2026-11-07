//! 纯 Rust 的模板匹配，语义对齐 OpenCV `matchTemplate` 的六种模式。
//!
//! 为什么自己实现：手机上带 OpenCV `.so` 有三个麻烦——体积、native 内存生命周期、
//! 以及结果矩阵的归一化语义不直观。这里是纯 Rust，只有灰度 `u8`，不依赖任何 native 库。
//!
//! 坐标与 OpenCV 一致：`(x, y)` 是模板左上角在源图中的位置，
//! 结果矩阵尺寸为 `(W - w + 1) x (H - h + 1)`。
//!
//! 性能说明：当前实现是朴素的三重循环（每个位置完整扫一遍模板），
//! 对 Pocket 的搜索框（百级像素见方）够用；后续要提速再上积分图 + 早退。

use bg_core::{GrayView, Rect};

/// OpenCV 的 `TemplateMatchModes`。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MatchMode {
    SqDiff,
    SqDiffNormed,
    CCorr,
    CCorrNormed,
    CCoeff,
    CCoeffNormed,
}

impl MatchMode {
    /// 从 `Recognition.json` 里的枚举名解析。
    pub fn from_name(name: &str) -> Option<Self> {
        match name {
            "SqDiff" => Some(MatchMode::SqDiff),
            "SqDiffNormed" => Some(MatchMode::SqDiffNormed),
            "CCorr" => Some(MatchMode::CCorr),
            "CCorrNormed" => Some(MatchMode::CCorrNormed),
            "CCoeff" => Some(MatchMode::CCoeff),
            "CCoeffNormed" => Some(MatchMode::CCoeffNormed),
            _ => None,
        }
    }

    /// 这一模式下「值越小越好」。
    pub fn is_lower_better(self) -> bool {
        matches!(self, MatchMode::SqDiff | MatchMode::SqDiffNormed)
    }

    /// 非归一化模式（SqDiff / CCoeff / CCorr）在 OpenCV 里会先做 min-max 归一化
    /// 再比较阈值——`MatchTemplateHelper` 复刻了这个行为，这里保持一致。
    pub fn needs_minmax_normalize(self) -> bool {
        matches!(
            self,
            MatchMode::SqDiff | MatchMode::CCoeff | MatchMode::CCorr
        )
    }
}

/// 一次模板匹配命中的位置与「越高越好」的分数。
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct MatchScore {
    pub x: i32,
    pub y: i32,
    pub score: f64,
}

impl MatchScore {
    pub fn rect(&self, template_width: i32, template_height: i32) -> Rect {
        Rect::new(self.x, self.y, template_width, template_height)
    }
}

/// 匹配结果矩阵（原始 OpenCV 语义的取值，未翻转方向）。
#[derive(Debug, Clone, PartialEq)]
pub struct MatchGrid {
    width: usize,
    height: usize,
    values: Vec<f64>,
}

impl MatchGrid {
    pub fn width(&self) -> usize {
        self.width
    }

    pub fn height(&self) -> usize {
        self.height
    }

    pub fn values(&self) -> &[f64] {
        &self.values
    }

    /// 原始值；越界返回 `None`。
    pub fn at(&self, x: usize, y: usize) -> Option<f64> {
        if x >= self.width || y >= self.height {
            return None;
        }
        Some(self.values[y * self.width + x])
    }

    /// 原始矩阵里的极值位置：`lower_better` 时取最小，否则取最大。
    /// 全 NaN 或空矩阵返回 `None`。
    pub fn extreme(&self, lower_better: bool) -> Option<(usize, usize, f64)> {
        let mut best: Option<(usize, usize, f64)> = None;
        for (index, &value) in self.values.iter().enumerate() {
            if value.is_nan() {
                continue;
            }
            let better = match best {
                None => true,
                Some((_, _, current)) => {
                    if lower_better {
                        value < current
                    } else {
                        value > current
                    }
                }
            };
            if better {
                best = Some((index % self.width, index / self.width, value));
            }
        }
        best
    }
}

/// 在 `source` 上按 `mode` 匹配 `template`，返回完整结果矩阵。
///
/// 源图或模板为空、模板比源图大时返回 `None`。
pub fn match_template(
    source: &GrayView,
    template: &GrayView,
    mode: MatchMode,
) -> Option<MatchGrid> {
    let (sw, sh) = (source.width(), source.height());
    let (tw, th) = (template.width(), template.height());
    if sw == 0 || sh == 0 || tw == 0 || th == 0 || tw > sw || th > sh {
        return None;
    }

    let rw = sw - tw + 1;
    let rh = sh - th + 1;
    let mut values = vec![0.0f64; rw * rh];

    // 模板的统计量：整幅匹配里恒定，循环外算一次。
    let mut tpl_sum = 0.0f64;
    let mut tpl_sq_sum = 0.0f64;
    for y in 0..th {
        for x in 0..tw {
            let value = template.pixel_unchecked(x, y) as f64;
            tpl_sum += value;
            tpl_sq_sum += value * value;
        }
    }
    let count = (tw * th) as f64;

    for oy in 0..rh {
        for ox in 0..rw {
            let mut img_sum = 0.0f64;
            let mut img_sq_sum = 0.0f64;
            let mut cross = 0.0f64;

            for ty in 0..th {
                let src_row = source.row(oy + ty);
                let tpl_row = template.row(ty);
                for tx in 0..tw {
                    let image_value = src_row[ox + tx] as f64;
                    let template_value = tpl_row[tx] as f64;
                    img_sum += image_value;
                    img_sq_sum += image_value * image_value;
                    cross += image_value * template_value;
                }
            }

            let raw = match mode {
                MatchMode::SqDiff | MatchMode::SqDiffNormed => {
                    let sq_diff = img_sq_sum - 2.0 * cross + tpl_sq_sum;
                    if mode == MatchMode::SqDiff {
                        sq_diff
                    } else {
                        let denom = (tpl_sq_sum * img_sq_sum).sqrt();
                        if denom <= 1e-12 {
                            // 全黑窗口：0/0 无定义。取 1.0（最差），否则大片纯黑会被判成完美命中。
                            1.0
                        } else {
                            (sq_diff / denom).max(0.0)
                        }
                    }
                }
                MatchMode::CCorr | MatchMode::CCorrNormed => {
                    if mode == MatchMode::CCorr {
                        cross
                    } else {
                        let denom = (tpl_sq_sum * img_sq_sum).sqrt();
                        if denom <= 1e-12 {
                            1.0
                        } else {
                            cross / denom
                        }
                    }
                }
                MatchMode::CCoeff | MatchMode::CCoeffNormed => {
                    let numerator = cross - tpl_sum * img_sum / count;
                    if mode == MatchMode::CCoeff {
                        numerator
                    } else {
                        let denom_t = tpl_sq_sum - tpl_sum * tpl_sum / count;
                        let denom_i = img_sq_sum - img_sum * img_sum / count;
                        let denom = (denom_t.max(0.0) * denom_i.max(0.0)).sqrt();
                        if denom <= 1e-12 {
                            0.0
                        } else {
                            numerator / denom
                        }
                    }
                }
            };

            values[oy * rw + ox] = raw;
        }
    }

    let mut grid = MatchGrid {
        width: rw,
        height: rh,
        values,
    };
    if mode.needs_minmax_normalize() {
        grid.normalize_minmax();
    }
    Some(grid)
}

impl MatchGrid {
    /// 与 OpenCV `normalize(NORM_MINMAX, 0, 1)` 一致：线性拉到 `[0, 1]`。
    fn normalize_minmax(&mut self) {
        let mut min = f64::INFINITY;
        let mut max = f64::NEG_INFINITY;
        for &value in &self.values {
            if value.is_nan() {
                continue;
            }
            if value < min {
                min = value;
            }
            if value > max {
                max = value;
            }
        }
        if !min.is_finite() || !max.is_finite() {
            self.values.iter_mut().for_each(|v| *v = 0.0);
            return;
        }
        let range = max - min;
        if range <= f64::EPSILON {
            self.values.iter_mut().for_each(|v| *v = 0.0);
            return;
        }
        for value in &mut self.values {
            *value = (*value - min) / range;
        }
    }
}

/// 多目标查找：按阈值过滤后做贪心 NMS（IoU < 0.5），分数统一成「越高越好」。
///
/// * `threshold`：归一化阈值（1.0 = 完美），对 lower-better 模式内部换算。
/// * `max_count`：最多返回几个；`<= 0` 时按面积估算上限（至少 1）。
pub fn find_matches(
    source: &GrayView,
    template: &GrayView,
    mode: MatchMode,
    threshold: f64,
    max_count: i32,
) -> Vec<MatchScore> {
    let (tw, th) = (template.width(), template.height());
    let lower_better = mode.is_lower_better();

    let limit = if max_count < 0 {
        (source.width() * source.height()).saturating_div(tw.max(1)) / th.max(1)
    } else {
        max_count as usize
    };
    if limit == 0 {
        return Vec::new();
    }

    let Some(grid) = match_template(source, template, mode) else {
        return Vec::new();
    };

    let score_threshold = if lower_better {
        1.0 - threshold
    } else {
        threshold
    };
    let mut candidates: Vec<MatchScore> = Vec::new();
    for y in 0..grid.height() {
        for x in 0..grid.width() {
            let raw = grid.values[y * grid.width() + x];
            if raw.is_nan() {
                continue;
            }
            let passed = if lower_better {
                raw <= score_threshold
            } else {
                raw >= score_threshold
            };
            if !passed {
                continue;
            }
            let score = if lower_better { 1.0 - raw } else { raw };
            candidates.push(MatchScore {
                x: x as i32,
                y: y as i32,
                score,
            });
        }
    }

    // 分数高的优先，其次按位置稳定排序（同样分数时左上优先，和 OpenCV minMaxLoc 习惯一致）。
    candidates.sort_by(|a, b| {
        b.score
            .partial_cmp(&a.score)
            .unwrap_or(std::cmp::Ordering::Equal)
            .then(a.y.cmp(&b.y))
            .then(a.x.cmp(&b.x))
    });

    let mut picked: Vec<MatchScore> = Vec::with_capacity(limit.min(candidates.len()));
    for candidate in candidates {
        if picked.len() >= limit {
            break;
        }
        let rect = candidate.rect(tw as i32, th as i32);
        let overlaps = picked
            .iter()
            .any(|kept| kept.rect(tw as i32, th as i32).iou(rect) >= NMS_IOU);
        if !overlaps {
            picked.push(candidate);
        }
    }
    picked
}

/// NMS 阈值，与 Android 侧 `MatchTemplateHelper` 保持一致。
pub const NMS_IOU: f64 = 0.5;

#[cfg(test)]
mod tests {
    use super::*;
    use bg_core::GrayImage;

    fn image(width: usize, height: usize, data: &[u8]) -> GrayImage {
        GrayImage::new(width, height, data.to_vec()).expect("有效尺寸")
    }

    #[test]
    fn sqdiff_finds_exact_patch() {
        let src = image(
            5,
            5,
            &[
                0, 0, 0, 0, 0, //
                0, 1, 2, 3, 0, //
                0, 4, 5, 6, 0, //
                0, 7, 8, 9, 0, //
                0, 0, 0, 0, 0, //
            ],
        );
        let tpl = image(3, 3, &[1, 2, 3, 4, 5, 6, 7, 8, 9]);
        let grid = match_template(&src.view(), &tpl.view(), MatchMode::SqDiffNormed).unwrap();
        assert_eq!((grid.width(), grid.height()), (3, 3));
        let best = grid.extreme(true).unwrap();
        assert_eq!((best.0, best.1), (1, 1));
        assert!(
            best.2.abs() < 1e-9,
            "完全一致时归一化平方差应为 0，实际 {}",
            best.2
        );
    }

    #[test]
    fn sqdiff_normed_is_between_zero_and_one() {
        let src = GrayImage::filled(16, 16, 100);
        let mut tpl = GrayImage::filled(4, 4, 100);
        tpl.data_mut()[0] = 0;
        let grid = match_template(&src.view(), &tpl.view(), MatchMode::SqDiffNormed).unwrap();
        for value in grid.values() {
            assert!(*value >= 0.0 && *value <= 1.0, "越界值 {}", value);
        }
    }

    #[test]
    fn all_black_window_does_not_score_perfect() {
        // 全黑图像 + 全黑模板：除零必须取 1.0（最差），不能判成完美命中。
        let src = GrayImage::filled(8, 8, 0);
        let tpl = GrayImage::filled(2, 2, 0);
        let grid = match_template(&src.view(), &tpl.view(), MatchMode::SqDiffNormed).unwrap();
        assert!(grid.values().iter().all(|v| (*v - 1.0).abs() < 1e-12));
        let hits = find_matches(&src.view(), &tpl.view(), MatchMode::SqDiffNormed, 0.75, 4);
        assert!(hits.is_empty(), "全黑窗口不应产生命中");
    }

    #[test]
    fn ccff_normed_scores_one_for_identical() {
        // 只有 (1,1) 处的 2x2 窗口是 [5, 9 / 4, 8]，其余窗口都不同。
        let src = image(
            4,
            4,
            &[
                0, 0, 0, 0, //
                0, 5, 9, 0, //
                0, 4, 8, 0, //
                0, 0, 0, 0, //
            ],
        );
        let tpl = image(2, 2, &[5, 9, 4, 8]);
        let grid = match_template(&src.view(), &tpl.view(), MatchMode::CCoeffNormed).unwrap();
        let best = grid.extreme(false).unwrap();
        assert_eq!((best.0, best.1), (1, 1));
        assert!(
            (best.2 - 1.0).abs() < 1e-9,
            "完全线性相关应得 1.0，实际 {}",
            best.2
        );
    }

    #[test]
    fn ccff_normed_negative_for_inverted() {
        let src = image(2, 2, &[0, 10, 20, 30]);
        let tpl = image(2, 2, &[30, 20, 10, 0]);
        let grid = match_template(&src.view(), &tpl.view(), MatchMode::CCoeffNormed).unwrap();
        let value = grid.at(0, 0).unwrap();
        assert!(
            (value + 1.0).abs() < 1e-9,
            "负相关应得 -1.0，实际 {}",
            value
        );
    }

    #[test]
    fn ccorr_normed_identical_is_one() {
        let src = image(3, 3, &[1, 2, 3, 4, 5, 6, 7, 8, 9]);
        let tpl = image(2, 2, &[5, 6, 8, 9]);
        let grid = match_template(&src.view(), &tpl.view(), MatchMode::CCorrNormed).unwrap();
        let best = grid.extreme(false).unwrap();
        assert_eq!((best.0, best.1), (1, 1));
        assert!((best.2 - 1.0).abs() < 1e-9);
    }

    #[test]
    fn empty_or_too_large_inputs_return_none() {
        let src = GrayImage::filled(4, 4, 1);
        let big = GrayImage::filled(5, 5, 1);
        let empty = GrayImage::filled(0, 0, 1);
        assert!(match_template(&src.view(), &big.view(), MatchMode::SqDiff).is_none());
        assert!(match_template(&src.view(), &empty.view(), MatchMode::SqDiff).is_none());
    }

    #[test]
    fn find_matches_suppresses_neighbours() {
        // 两个相距很远的白块，各 4x4。
        let mut src = GrayImage::filled(40, 20, 0);
        for y in 2..6 {
            for x in 2..6 {
                src.data_mut()[y * 40 + x] = 255;
            }
        }
        for y in 2..6 {
            for x in 30..34 {
                src.data_mut()[y * 40 + x] = 255;
            }
        }
        let tpl = GrayImage::filled(4, 4, 255);
        let hits = find_matches(&src.view(), &tpl.view(), MatchMode::SqDiffNormed, 0.75, 4);
        assert_eq!(hits.len(), 2, "应命中两块，实际 {:?}", hits);
        assert_eq!((hits[0].x, hits[0].y), (2, 2));
        assert_eq!((hits[1].x, hits[1].y), (30, 2));
        assert!(hits.iter().all(|h| (h.score - 1.0).abs() < 1e-9));
    }

    #[test]
    fn find_matches_respects_limit() {
        let mut src = GrayImage::filled(60, 10, 0);
        for (offset, block) in [(2usize, 0usize), (22, 0), (42, 0)] {
            for y in block..block + 4 {
                for x in offset..offset + 4 {
                    src.data_mut()[y * 60 + x] = 200;
                }
            }
        }
        let tpl = GrayImage::filled(4, 4, 200);
        let hits = find_matches(&src.view(), &tpl.view(), MatchMode::SqDiffNormed, 0.9, 2);
        assert_eq!(hits.len(), 2);
    }

    #[test]
    fn find_matches_zero_limit_returns_nothing() {
        let src = GrayImage::filled(10, 10, 5);
        let tpl = GrayImage::filled(2, 2, 5);
        assert!(find_matches(&src.view(), &tpl.view(), MatchMode::SqDiffNormed, 0.1, 0).is_empty());
    }

    #[test]
    fn minmax_normalize_maps_range() {
        let mut grid = MatchGrid {
            width: 2,
            height: 1,
            values: vec![10.0, 20.0],
        };
        grid.normalize_minmax();
        assert_eq!(grid.values(), &[0.0, 1.0]);
    }

    #[test]
    fn mode_names_round_trip() {
        for (name, mode) in [
            ("SqDiff", MatchMode::SqDiff),
            ("SqDiffNormed", MatchMode::SqDiffNormed),
            ("CCorr", MatchMode::CCorr),
            ("CCorrNormed", MatchMode::CCorrNormed),
            ("CCoeff", MatchMode::CCoeff),
            ("CCoeffNormed", MatchMode::CCoeffNormed),
        ] {
            assert_eq!(MatchMode::from_name(name), Some(mode));
        }
        assert_eq!(MatchMode::from_name("nope"), None);
    }
}
