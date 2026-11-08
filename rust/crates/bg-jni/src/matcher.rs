//! 模板匹配的 JNI 入口：把一帧灰度图 + 模板灰度图交给 `bg-recognition`。
//!
//! 报文用 JSON：命中数量很少（一般 1~10 个），JSON 的开销可以忽略，
//! 换来的是 Kotlin 侧不用手写结构体布局。

use bg_core::GrayView;
use bg_recognition::template::{find_matches, MatchMode, MatchScore};
use jni::objects::{JByteArray, JObject, JString};
use jni::sys::{jdouble, jint, jstring};
use jni::JNIEnv;

/// 一块灰度数据（行优先、无 stride）。
#[derive(Debug, Clone, Copy)]
pub struct GrayBuffer<'a> {
    pub data: &'a [u8],
    pub width: usize,
    pub height: usize,
}

impl<'a> GrayBuffer<'a> {
    fn view(&self, what: &str) -> Result<GrayView<'a>, String> {
        GrayView::new(self.width, self.height, self.width, self.data).ok_or_else(|| {
            format!(
                "{what}尺寸不合法: {}x{}，数据 {} 字节",
                self.width,
                self.height,
                self.data.len()
            )
        })
    }
}

/// 纯逻辑：输入灰度缓冲，输出命中 JSON。
///
/// 返回 `{"matches":[{"x":1,"y":2,"score":0.98}]}`。
pub fn match_template_impl(
    frame: GrayBuffer<'_>,
    template: GrayBuffer<'_>,
    mode_name: &str,
    threshold: f64,
    max_count: i32,
) -> Result<String, String> {
    let mode =
        MatchMode::from_name(mode_name).ok_or_else(|| format!("未知匹配模式: {mode_name}"))?;
    let frame_view = frame.view("帧")?;
    let template_view = template.view("模板")?;
    let hits = find_matches(&frame_view, &template_view, mode, threshold, max_count);
    Ok(encode_hits(&hits))
}

fn encode_hits(hits: &[MatchScore]) -> String {
    let mut json = String::from("{\"matches\":[");
    for (index, hit) in hits.iter().enumerate() {
        if index > 0 {
            json.push(',');
        }
        json.push_str(&format!(
            "{{\"x\":{},\"y\":{},\"score\":{:.6}}}",
            hit.x, hit.y, hit.score
        ));
    }
    json.push_str("]}");
    json
}

#[no_mangle]
pub extern "system" fn Java_com_bettergi_pocket_rust_RustNative_matchTemplate(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    frame: JByteArray<'_>,
    frame_width: jint,
    frame_height: jint,
    template: JByteArray<'_>,
    template_width: jint,
    template_height: jint,
    mode: JString<'_>,
    threshold: jdouble,
    max_count: jint,
) -> jstring {
    if frame_width <= 0 || frame_height <= 0 || template_width <= 0 || template_height <= 0 {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "尺寸必须为正");
        return std::ptr::null_mut();
    }

    let frame_bytes = match env.convert_byte_array(frame) {
        Ok(bytes) => bytes,
        Err(err) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("读取帧失败: {err}"),
            );
            return std::ptr::null_mut();
        }
    };
    let template_bytes = match env.convert_byte_array(template) {
        Ok(bytes) => bytes,
        Err(err) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("读取模板失败: {err}"),
            );
            return std::ptr::null_mut();
        }
    };
    let mode_name: String = match env.get_string(&mode) {
        Ok(value) => value.into(),
        Err(err) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                format!("读取模式失败: {err}"),
            );
            return std::ptr::null_mut();
        }
    };

    let frame_buffer = GrayBuffer {
        data: &frame_bytes,
        width: frame_width as usize,
        height: frame_height as usize,
    };
    let template_buffer = GrayBuffer {
        data: &template_bytes,
        width: template_width as usize,
        height: template_height as usize,
    };

    match match_template_impl(
        frame_buffer,
        template_buffer,
        &mode_name,
        threshold,
        max_count,
    ) {
        Ok(json) => crate::to_jstring(&mut env, &json),
        Err(message) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", message);
            std::ptr::null_mut()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn buffer(data: &[u8], width: usize, height: usize) -> GrayBuffer<'_> {
        GrayBuffer {
            data,
            width,
            height,
        }
    }

    fn frame_data(width: usize, height: usize, blocks: &[(usize, usize, u8)]) -> Vec<u8> {
        let mut data = vec![0u8; width * height];
        for &(x, y, value) in blocks {
            data[y * width + x] = value;
        }
        data
    }

    #[test]
    fn finds_exact_match_and_reports_json() {
        // 10x10 帧里放一个 3x3 的 200 灰块
        let mut data = vec![0u8; 100];
        for y in 2..5 {
            for x in 3..6 {
                data[y * 10 + x] = 200;
            }
        }
        let template = vec![200u8; 9];
        let json = match_template_impl(
            buffer(&data, 10, 10),
            buffer(&template, 3, 3),
            "SqDiffNormed",
            0.9,
            4,
        )
        .expect("应能匹配");
        assert!(json.starts_with("{\"matches\":["), "{json}");
        assert!(json.contains("\"x\":3"), "{json}");
        assert!(json.contains("\"y\":2"), "{json}");
    }

    #[test]
    fn returns_empty_when_below_threshold() {
        let data = frame_data(8, 8, &[]);
        let template = vec![255u8; 4];
        let json = match_template_impl(
            buffer(&data, 8, 8),
            buffer(&template, 2, 2),
            "SqDiffNormed",
            0.9,
            4,
        )
        .expect("空命中也是正常结果");
        assert_eq!(json, "{\"matches\":[]}");
    }

    #[test]
    fn rejects_unknown_mode() {
        let data = vec![0u8; 16];
        let template = vec![0u8; 4];
        let error =
            match_template_impl(buffer(&data, 4, 4), buffer(&template, 2, 2), "Nope", 0.5, 1)
                .unwrap_err();
        assert!(error.contains("未知匹配模式"), "{error}");
    }

    #[test]
    fn rejects_mismatched_buffer_size() {
        let data = vec![0u8; 15];
        let template = vec![0u8; 4];
        let error = match_template_impl(
            buffer(&data, 4, 4),
            buffer(&template, 2, 2),
            "SqDiff",
            0.5,
            1,
        )
        .unwrap_err();
        assert!(error.contains("帧尺寸不合法"), "{error}");
    }

    #[test]
    fn respects_max_count() {
        // 三块分离的 200 灰块
        let mut data = vec![0u8; 40 * 10];
        for offset in [2usize, 17, 32] {
            for y in 1..5 {
                for x in offset..offset + 4 {
                    data[y * 40 + x] = 200;
                }
            }
        }
        let template = vec![200u8; 16];
        let json = match_template_impl(
            buffer(&data, 40, 10),
            buffer(&template, 4, 4),
            "SqDiffNormed",
            0.9,
            2,
        )
        .expect("应能匹配");
        assert_eq!(
            json.matches("\"x\":").count(),
            2,
            "maxCount=2 应只返回 2 个: {json}"
        );
    }
}
