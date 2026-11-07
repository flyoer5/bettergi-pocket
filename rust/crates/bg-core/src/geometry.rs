//! 屏幕与图像几何：尺寸、矩形、点。
//!
//! 这里刻意保持与 Android 侧 `IntSize` / `IntRect` 一致的整数语义：
//! 坐标原点在左上角，`width`/`height` 为 0 表示空。

use std::fmt;

/// 二维尺寸（像素）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Size {
    pub width: i32,
    pub height: i32,
}

impl Size {
    pub const ZERO: Size = Size {
        width: 0,
        height: 0,
    };

    pub const fn new(width: i32, height: i32) -> Self {
        Size { width, height }
    }

    pub fn is_empty(self) -> bool {
        self.width <= 0 || self.height <= 0
    }

    /// 宽高比；空尺寸返回 0。
    pub fn aspect(self) -> f64 {
        if self.is_empty() {
            0.0
        } else {
            self.width as f64 / self.height as f64
        }
    }
}

impl fmt::Display for Size {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}x{}", self.width, self.height)
    }
}

/// 轴对齐矩形（左上角 + 宽高）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Rect {
    pub x: i32,
    pub y: i32,
    pub width: i32,
    pub height: i32,
}

impl Rect {
    pub const EMPTY: Rect = Rect {
        x: 0,
        y: 0,
        width: 0,
        height: 0,
    };

    pub const fn new(x: i32, y: i32, width: i32, height: i32) -> Self {
        Rect {
            x,
            y,
            width,
            height,
        }
    }

    pub fn left(self) -> i32 {
        self.x
    }

    pub fn top(self) -> i32 {
        self.y
    }

    pub fn right(self) -> i32 {
        self.x.saturating_add(self.width)
    }

    pub fn bottom(self) -> i32 {
        self.y.saturating_add(self.height)
    }

    pub fn center_x(self) -> i32 {
        self.x + self.width / 2
    }

    pub fn center_y(self) -> i32 {
        self.y + self.height / 2
    }

    pub fn center(self) -> Point {
        Point::new(self.center_x(), self.center_y())
    }

    /// 全 0 的矩形表示「未设置」，与 Android 侧 `isDefault()` 同义。
    pub fn is_default(self) -> bool {
        self.x == 0 && self.y == 0 && self.width == 0 && self.height == 0
    }

    pub fn is_empty(self) -> bool {
        self.width <= 0 || self.height <= 0
    }

    pub fn offset(self, dx: i32, dy: i32) -> Rect {
        Rect {
            x: self.x + dx,
            y: self.y + dy,
            ..self
        }
    }

    /// 收缩/外扩四边（正数向外扩，负数向内收）。
    pub fn inflate(self, left: i32, top: i32, right: i32, bottom: i32) -> Rect {
        let x1 = self.left() - left;
        let y1 = self.top() - top;
        let x2 = self.right() + right;
        let y2 = self.bottom() + bottom;
        Rect::new(x1, y1, x2 - x1, y2 - y1)
    }

    /// 夹到 `0..width x 0..height` 内，可能得到空矩形。
    pub fn clamp_to(self, width: i32, height: i32) -> Rect {
        let x1 = self.x.clamp(0, width);
        let y1 = self.y.clamp(0, height);
        let x2 = self.right().clamp(0, width);
        let y2 = self.bottom().clamp(0, height);
        Rect::new(x1, y1, x2 - x1, y2 - y1)
    }

    pub fn contains(self, other: Rect) -> bool {
        !other.is_empty()
            && other.left() >= self.left()
            && other.top() >= self.top()
            && other.right() <= self.right()
            && other.bottom() <= self.bottom()
    }

    pub fn contains_point(self, point: Point) -> bool {
        !self.is_empty()
            && point.x >= self.left()
            && point.x < self.right()
            && point.y >= self.top()
            && point.y < self.bottom()
    }

    pub fn intersects(self, other: Rect) -> bool {
        if self.is_empty() || other.is_empty() {
            return false;
        }
        self.left() < other.right()
            && other.left() < self.right()
            && self.top() < other.bottom()
            && other.top() < self.bottom()
    }

    pub fn intersection(self, other: Rect) -> Rect {
        if !self.intersects(other) {
            return Rect::EMPTY;
        }
        let x1 = self.left().max(other.left());
        let y1 = self.top().max(other.top());
        let x2 = self.right().min(other.right());
        let y2 = self.bottom().min(other.bottom());
        Rect::new(x1, y1, x2 - x1, y2 - y1)
    }

    pub fn area(self) -> i64 {
        if self.is_empty() {
            0
        } else {
            self.width as i64 * self.height as i64
        }
    }

    /// 两个同尺寸框的交并比（IoU），用于多目标去重。
    pub fn iou(self, other: Rect) -> f64 {
        let inter = self.intersection(other).area();
        if inter == 0 {
            return 0.0;
        }
        let union = self.area() + other.area() - inter;
        if union <= 0 {
            0.0
        } else {
            inter as f64 / union as f64
        }
    }
}

impl fmt::Display for Rect {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "({}, {}, {}, {})",
            self.x, self.y, self.width, self.height
        )
    }
}

/// 整数点。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Point {
    pub x: i32,
    pub y: i32,
}

impl Point {
    pub const ORIGIN: Point = Point { x: 0, y: 0 };

    pub const fn new(x: i32, y: i32) -> Self {
        Point { x, y }
    }

    pub fn distance_sq(self, other: Point) -> i64 {
        let dx = (self.x - other.x) as i64;
        let dy = (self.y - other.y) as i64;
        dx * dx + dy * dy
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rect_edges_and_center() {
        let r = Rect::new(10, 20, 100, 50);
        assert_eq!(r.left(), 10);
        assert_eq!(r.top(), 20);
        assert_eq!(r.right(), 110);
        assert_eq!(r.bottom(), 70);
        assert_eq!(r.center(), Point::new(60, 45));
    }

    #[test]
    fn default_and_empty() {
        assert!(Rect::EMPTY.is_default());
        assert!(Rect::EMPTY.is_empty());
        assert!(!Rect::new(1, 0, 0, 0).is_default());
        assert!(Rect::new(1, 0, 0, 0).is_empty());
    }

    #[test]
    fn clamp_pulls_inside() {
        let r = Rect::new(-5, -5, 20, 20).clamp_to(10, 10);
        assert_eq!(r, Rect::new(0, 0, 10, 10));
    }

    #[test]
    fn intersection_and_iou() {
        let a = Rect::new(0, 0, 10, 10);
        let b = Rect::new(5, 0, 10, 10);
        assert!(a.intersects(b));
        assert_eq!(a.intersection(b), Rect::new(5, 0, 5, 10));
        // 交集 50，并集 150
        assert!((a.iou(b) - 1.0 / 3.0).abs() < 1e-9);
        assert_eq!(a.iou(Rect::new(50, 50, 10, 10)), 0.0);
    }

    #[test]
    fn point_distance() {
        assert_eq!(Point::new(0, 0).distance_sq(Point::new(3, 4)), 25);
    }
}
