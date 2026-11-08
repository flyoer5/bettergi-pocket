//! Kotlin ↔ Rust 的 JNI 桥。
//!
//! 符号名对应 Kotlin 侧 `com.bettergi.pocket.rust.RustNative` 的实例方法
//! （Kotlin `object` 的成员编译成实例方法，所以第二个参数是 `JObject` 而不是 `JClass`）。
//!
//! 这一层只做三件事：取字符串、调 [`EngineState`]、把结果转回 `jstring`。
//! 所有能测的逻辑都在 [`engine`] / [`payload`] 里，不依赖 `JNIEnv`。

pub mod engine;
pub mod matcher;
pub mod payload;

use jni::objects::{JObject, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;

use crate::engine::EngineState;

/// 读取 Java 字符串；失败返回 `None`（不抛异常，由调用方返回空值）。
fn read_string(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Option<String> {
    let java_str = env.get_string(value).ok()?;
    Some(java_str.into())
}

/// 把 Rust 字符串转成 `jstring`；失败返回 null。
pub(crate) fn to_jstring(env: &mut JNIEnv<'_>, text: &str) -> jstring {
    match env.new_string(text) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// 把 handle 还原成引擎引用。
///
/// # Safety
/// `handle` 必须来自 [`Java_com_bettergi_pocket_rust_RustNative_createEngine`]，
/// 且在调用期间未被 `destroyEngine` 释放。
unsafe fn state_from_handle<'a>(handle: jlong) -> Option<&'a mut EngineState> {
    if handle == 0 {
        return None;
    }
    Some(&mut *(handle as *mut EngineState))
}

#[no_mangle]
pub extern "system" fn Java_com_bettergi_pocket_rust_RustNative_createEngine(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    settings_json: JString<'_>,
) -> jlong {
    let Some(settings) = read_string(&mut env, &settings_json) else {
        return 0;
    };
    match EngineState::from_json(&settings) {
        Ok(state) => Box::into_raw(Box::new(state)) as jlong,
        Err(message) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", message);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_bettergi_pocket_rust_RustNative_destroyEngine(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: handle 由 createEngine 分配，Kotlin 侧保证只销毁一次。
    unsafe {
        drop(Box::from_raw(handle as *mut EngineState));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_bettergi_pocket_rust_RustNative_resetEngine(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) {
    // SAFETY: 见 state_from_handle。
    if let Some(state) = unsafe { state_from_handle(handle) } {
        state.reset();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_bettergi_pocket_rust_RustNative_updateSettings(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    settings_json: JString<'_>,
) {
    // SAFETY: 见 state_from_handle。
    let Some(state) = (unsafe { state_from_handle(handle) }) else {
        return;
    };
    let Some(settings) = read_string(&mut env, &settings_json) else {
        return;
    };
    if let Err(message) = state.set_settings_json(&settings) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", message);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_bettergi_pocket_rust_RustNative_updateKeywords(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    keywords_json: JString<'_>,
) {
    // SAFETY: 见 state_from_handle。
    let Some(state) = (unsafe { state_from_handle(handle) }) else {
        return;
    };
    let Some(keywords) = read_string(&mut env, &keywords_json) else {
        return;
    };
    if let Err(message) = state.set_keywords_json(&keywords) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", message);
    }
}

/// 推进一拍；返回 `{"actions":[...],"events":[...],"state":"...","nextTickMs":200}`。
#[no_mangle]
pub extern "system" fn Java_com_bettergi_pocket_rust_RustNative_tick(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    observation_json: JString<'_>,
) -> jstring {
    // SAFETY: 见 state_from_handle。
    let Some(state) = (unsafe { state_from_handle(handle) }) else {
        return std::ptr::null_mut();
    };
    let Some(observation) = read_string(&mut env, &observation_json) else {
        return std::ptr::null_mut();
    };
    match state.tick_json(&observation) {
        Ok(result) => to_jstring(&mut env, &result),
        Err(message) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", message);
            std::ptr::null_mut()
        }
    }
}

/// 版本号，方便 Kotlin 侧在日志里确认加载的是哪一版 `.so`。
#[no_mangle]
pub extern "system" fn Java_com_bettergi_pocket_rust_RustNative_version(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
) -> jstring {
    let version = format!("bg_jni {} ({})", env!("CARGO_PKG_VERSION"), TARGET);
    to_jstring(&mut env, &version)
}

const TARGET: &str = if cfg!(target_os = "android") {
    "android"
} else {
    "host"
};
