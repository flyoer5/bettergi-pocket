//! 帧数据：BGR 原始帧与灰度识别帧。
//!
//! 识别只关心灰度，所以算法层统一吃 [`GrayView`]（可带 stride，便于零拷贝 ROI）。
//! BGR → 灰度的系数与 OpenCV `COLOR_BGR2GRAY` 的 BT.601 定义一致。

use crate::geometry::Rect;

/// 一张 BGR888 图像。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BgrImage {
    width: usize,
    height: usize,
    data: Vec<u8>,
}

impl BgrImage {
    /// `data` 必须是 `width * height * 3` 字节；长度不符返回 `None`。
    pub fn new(width: usize, height: usize, data: Vec<u8>) -> Option<Self> {
        if width == 0 || height == 0 || data.len() != width.checked_mul(height)?.checked_mul(3)? {
            return None;
        }
        Some(BgrImage {
            width,
            height,
            data,
        })
    }

    pub fn width(&self) -> usize {
        self.width
    }

    pub fn height(&self) -> usize {
        self.height
    }

    pub fn data(&self) -> &[u8] {
        &self.data
    }

    /// BT.601：`Y = 0.299R + 0.587G + 0.114B`，用整数定点计算避免浮点误差累积。
    pub fn to_gray(&self) -> GrayImage {
        let mut out = vec![0u8; self.width * self.height];
        for (index, pixel) in self.data.chunks_exact(3).enumerate() {
            let b = pixel[0] as u32;
            let g = pixel[1] as u32;
            let r = pixel[2] as u32;
            // 与 OpenCV 相同的定点近似：(R*4899 + G*9617 + B*1868 + 8192) >> 14
            let y = (r * 4899 + g * 9617 + b * 1868 + 8192) >> 14;
            out[index] = y.min(255) as u8;
        }
        GrayImage {
            width: self.width,
            height: self.height,
            data: out,
        }
    }
}

/// 拥有所有权的灰度图。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GrayImage {
    width: usize,
    height: usize,
    data: Vec<u8>,
}

impl GrayImage {
    /// `data` 必须是 `width * height` 字节；长度不符返回 `None`。
    pub fn new(width: usize, height: usize, data: Vec<u8>) -> Option<Self> {
        if width == 0 || height == 0 || data.len() != width * height {
            return None;
        }
        Some(GrayImage {
            width,
            height,
            data,
        })
    }

    /// 全 `value` 的图，测试与小工具用。
    pub fn filled(width: usize, height: usize, value: u8) -> Self {
        GrayImage {
            width,
            height,
            data: vec![value; width * height],
        }
    }

    pub fn width(&self) -> usize {
        self.width
    }

    pub fn height(&self) -> usize {
        self.height
    }

    pub fn size(&self) -> (usize, usize) {
        (self.width, self.height)
    }

    pub fn data(&self) -> &[u8] {
        &self.data
    }

    pub fn data_mut(&mut self) -> &mut [u8] {
        &mut self.data
    }

    pub fn view(&self) -> GrayView<'_> {
        GrayView {
            width: self.width,
            height: self.height,
            stride: self.width,
            data: &self.data,
        }
    }

    pub fn pixel(&self, x: usize, y: usize) -> u8 {
        debug_assert!(x < self.width && y < self.height);
        self.data[y * self.width + x]
    }
}

/// 带 stride 的灰度只读视图，ROI 裁剪不需要拷贝。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GrayView<'a> {
    width: usize,
    height: usize,
    stride: usize,
    data: &'a [u8],
}

impl<'a> GrayView<'a> {
    /// 构造视图；长度不足以覆盖 `(height - 1) * stride + width` 时返回 `None`。
    /// 宽或高为 0 时返回空视图（不是错误）。
    pub fn new(width: usize, height: usize, stride: usize, data: &'a [u8]) -> Option<Self> {
        if width == 0 || height == 0 {
            return Some(GrayView {
                width: 0,
                height: 0,
                stride: 0,
                data: &[],
            });
        }
        if stride < width {
            return None;
        }
        let needed = (height - 1).checked_mul(stride)?.checked_add(width)?;
        if data.len() < needed {
            return None;
        }
        Some(GrayView {
            width,
            height,
            stride,
            data,
        })
    }

    pub fn width(&self) -> usize {
        self.width
    }

    pub fn height(&self) -> usize {
        self.height
    }

    pub fn stride(&self) -> usize {
        self.stride
    }

    pub fn is_empty(&self) -> bool {
        self.width == 0 || self.height == 0
    }

    /// 取一个像素；越界返回 `None`（热路径请用 [`GrayView::pixel_unchecked`]）。
    pub fn pixel(&self, x: usize, y: usize) -> Option<u8> {
        if x >= self.width || y >= self.height {
            return None;
        }
        Some(self.pixel_unchecked(x, y))
    }

    /// 取像素，不检查边界。调用方必须保证 `x < width && y < height`。
    ///
    /// # Safety
    /// 越界访问属于调用方的逻辑错误，这里只做 debug 断言。
    pub fn pixel_unchecked(&self, x: usize, y: usize) -> u8 {
        debug_assert!(x < self.width && y < self.height);
        // SAFETY: 构造时已保证 `(height - 1) * stride + width <= data.len()`，
        // 且调用方保证 `x < width`、`y < height`。
        unsafe { *self.data.get_unchecked(y * self.stride + x) }
    }

    /// 取第 `y` 行前 `width` 个字节。
    pub fn row(&self, y: usize) -> &'a [u8] {
        debug_assert!(y < self.height);
        let start = y * self.stride;
        &self.data[start..start + self.width]
    }

    /// 裁剪 ROI（坐标相对本视图）。裁到边界外会被夹紧；完全在外则得到空视图。
    pub fn roi(&self, rect: Rect) -> GrayView<'a> {
        let width = self.width as i32;
        let height = self.height as i32;
        let clamped = rect.clamp_to(width, height);
        if clamped.is_empty() {
            return GrayView {
                width: 0,
                height: 0,
                stride: 0,
                data: &[],
            };
        }
        let x = clamped.x as usize;
        let y = clamped.y as usize;
        let offset = y * self.stride + x;
        GrayView {
            width: clamped.width as usize,
            height: clamped.height as usize,
            stride: self.stride,
            data: &self.data[offset..],
        }
    }

    /// 拷贝成独立图像。
    pub fn to_owned_image(&self) -> GrayImage {
        let mut data = Vec::with_capacity(self.width * self.height);
        for y in 0..self.height {
            data.extend_from_slice(self.row(y));
        }
        GrayImage {
            width: self.width,
            height: self.height,
            data,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bgr_to_gray_primary_colors() {
        // 白 / 黑 / 纯红 / 纯绿 / 纯蓝（BGR 顺序）
        let data = vec![
            255, 255, 255, // 白
            0, 0, 0, // 黑
            0, 0, 255, // 红
            0, 255, 0, // 绿
            255, 0, 0, // 蓝
        ];
        let img = BgrImage::new(5, 1, data).unwrap();
        let gray = img.to_gray();
        assert!((gray.pixel(0, 0) as i32 - 255).abs() <= 1);
        assert_eq!(gray.pixel(1, 0), 0);
        // OpenCV: 0.299 * 255 ≈ 76
        assert!((gray.pixel(2, 0) as i32 - 76).abs() <= 1);
        // OpenCV: 0.587 * 255 ≈ 150
        assert!((gray.pixel(3, 0) as i32 - 150).abs() <= 1);
        // OpenCV: 0.114 * 255 ≈ 29
        assert!((gray.pixel(4, 0) as i32 - 29).abs() <= 1);
    }

    #[test]
    fn rejects_mismatched_lengths() {
        assert!(BgrImage::new(2, 2, vec![0; 11]).is_none());
        assert!(GrayImage::new(2, 2, vec![0; 3]).is_none());
        assert!(GrayImage::new(0, 2, vec![]).is_none());
    }

    #[test]
    fn roi_is_zero_copy_and_offsets() {
        let img = GrayImage::new(4, 4, (0..16u8).collect()).unwrap();
        let view = img.view();
        let roi = view.roi(Rect::new(1, 1, 2, 2));
        assert_eq!((roi.width(), roi.height()), (2, 2));
        assert_eq!(roi.pixel(0, 0), Some(5));
        assert_eq!(roi.pixel(1, 1), Some(10));
    }

    #[test]
    fn roi_clamps_out_of_bounds() {
        let img = GrayImage::new(4, 4, vec![7; 16]).unwrap();
        let roi = img.view().roi(Rect::new(2, 2, 10, 10));
        assert_eq!((roi.width(), roi.height()), (2, 2));
        let empty = img.view().roi(Rect::new(10, 10, 5, 5));
        assert!(empty.is_empty());
        assert_eq!(empty.pixel(0, 0), None);
    }

    #[test]
    fn stride_view_rows() {
        // 3x2 图像，stride 5（每行尾部 2 字节 padding）
        let data = vec![
            1, 2, 3, 0, 0, //
            4, 5, 6, 0, 0, //
        ];
        let view = GrayView::new(3, 2, 5, &data).unwrap();
        assert_eq!(view.row(0), &[1, 2, 3]);
        assert_eq!(view.row(1), &[4, 5, 6]);
        assert_eq!(view.pixel(2, 1), Some(6));
        assert_eq!(view.pixel(3, 0), None);
    }

    #[test]
    fn to_owned_compacts_stride() {
        let data = vec![1, 2, 3, 0, 0, 4, 5, 6, 0, 0];
        let view = GrayView::new(3, 2, 5, &data).unwrap();
        let owned = view.to_owned_image();
        assert_eq!(owned.data(), &[1, 2, 3, 4, 5, 6]);
    }

    #[test]
    fn rejects_short_buffer() {
        let data = vec![0u8; 5];
        assert!(GrayView::new(3, 2, 3, &data).is_none());
        assert!(GrayView::new(3, 2, 2, &[0; 100]).is_none());
    }
}
