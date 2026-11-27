use jni::objects::{JClass, JString, JByteArray};
use jni::sys::{jboolean, jbyteArray, jstring};
use jni::JNIEnv;
use zeroize::Zeroizing;
use base64::{Engine as _, engine::general_purpose};
use crate::crypto::*;
use crate::domain::*;

pub fn jstring_to_bounded_string(env: &mut JNIEnv, jstr: &JString) -> Result<String, &'static str> {
    if jstr.is_null() {
        return Err("JNI string is null");
    }
    let s: String = env.get_string(jstr)
        .map_err(|_| "Failed to get JNI string")?
        .into();
    if s.len() > MAX_JNI_INPUT_BYTES {
        return Err("JNI input exceeds size limit");
    }
    Ok(s)
}

pub fn jbytearray_to_bounded_vec(env: &JNIEnv, arr: &JByteArray) -> Result<Vec<u8>, &'static str> {
    if arr.is_null() {
        return Err("JNI byte array is null");
    }
    let len = env.get_array_length(arr)
        .map_err(|_| "Failed to read JNI byte array length")?;
    if len < 0 || len as usize > MAX_JNI_INPUT_BYTES {
        return Err("JNI byte array length invalid or exceeds limit");
    }
    env.convert_byte_array(arr)
        .map_err(|_| "Failed to convert JNI byte array")
}

pub fn with_jni_json_io<F>(env: JNIEnv, jstr: JString, f: F) -> jstring
where
    F: FnOnce(&mut JNIEnv, &str) -> String,
{
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut env_mut = env; let _ = &mut env_mut; #[allow(unused_mut)] let _ = &mut env_mut;
        let input = match jstring_to_bounded_string(&mut env_mut, &jstr) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut() as jstring,
        };
        let output = f(&mut env_mut, &input);
        match env_mut.new_string(output) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut() as jstring,
        }
    })).unwrap_or(std::ptr::null_mut() as jstring)
}

pub fn with_jni_bytes_io<F>(env: JNIEnv, arr: JByteArray, f: F) -> jbyteArray
where
    F: FnOnce(&JNIEnv, &[u8]) -> Vec<u8>,
{
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let input = match jbytearray_to_bounded_vec(&env, &arr) {
            Ok(v) => Zeroizing::new(v),
            Err(_) => return std::ptr::null_mut() as jbyteArray,
        };
        let output = f(&env, &input);
        match env.byte_array_from_slice(&output) {
            Ok(jarr) => jarr.into_raw(),
            Err(_) => std::ptr::null_mut() as jbyteArray,
        }
    })).unwrap_or(std::ptr::null_mut() as jbyteArray)
}

#[no_mangle]
pub extern "system" fn Java_com_bloom_ffi_RustBridge_calculate_1cycle_1phase(
    env: JNIEnv,
    _class: JClass,
    last_period_date: JString,
    avg_cycle_length: i32,
) -> jstring {
    with_jni_json_io(env, last_period_date, |_env, date_str| {
        let today = chrono::Local::now().date_naive();
        calculate_phase_internal(date_str, avg_cycle_length, today)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_bloom_ffi_RustBridge_predict_1next_1period(
    env: JNIEnv,
    _class: JClass,
    last_period_date: JString,
    avg_cycle_length: i32,
) -> jstring {
    with_jni_json_io(env, last_period_date, |_env, date_str| {
        predict_next_period_internal(date_str, avg_cycle_length)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_bloom_ffi_RustBridge_analyze_1cycle_1history(
    env: JNIEnv,
    _class: JClass,
    dates_csv: JString,
) -> jstring {
    with_jni_json_io(env, dates_csv, |_env, csv_str| {
        analyze_cycle_history_internal(csv_str)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_bloom_ffi_RustBridge_generate_1pin_1hash(
    env: JNIEnv,
    _class: JClass,
    pin: JByteArray,
) -> jbyteArray {
    with_jni_bytes_io(env, pin, |_env, pin_bytes| {
        if let Ok(pin_str) = std::str::from_utf8(pin_bytes) {
            generate_pin_hash_internal(pin_str)
        } else {
            vec![]
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_bloom_ffi_RustBridge_verify_1and_1unlock(
    env: JNIEnv,
    _class: JClass,
    pin_hash: JByteArray,
    input_pin: JByteArray,
    derivation_salt: JByteArray,
) -> jboolean {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let hash_bytes = match jbytearray_to_bounded_vec(&env, &pin_hash) {
            Ok(v) => Zeroizing::new(v),
            Err(_) => return 0,
        };
        let pin_bytes = match jbytearray_to_bounded_vec(&env, &input_pin) {
            Ok(v) => Zeroizing::new(v),
            Err(_) => return 0,
        };
        let salt_bytes = match jbytearray_to_bounded_vec(&env, &derivation_salt) {
            Ok(v) => Zeroizing::new(v),
            Err(_) => return 0,
        };
        let result = if let Ok(pin_str) = std::str::from_utf8(&pin_bytes) {
            verify_and_unlock_internal(&hash_bytes, pin_str, &salt_bytes)
        } else {
            false
        };
        if result { 1 } else { 0 }
    })).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_bloom_ffi_RustBridge_derive_1key(
    env: JNIEnv,
    _class: JClass,
    pin: JByteArray,
    derivation_salt: JByteArray,
) -> jbyteArray {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let pin_bytes = match jbytearray_to_bounded_vec(&env, &pin) {
            Ok(v) => Zeroizing::new(v),
            Err(_) => return std::ptr::null_mut() as jbyteArray,
        };
        let salt_bytes = match jbytearray_to_bounded_vec(&env, &derivation_salt) {
            Ok(v) => Zeroizing::new(v),
            Err(_) => return std::ptr::null_mut() as jbyteArray,
        };
        let derived = Zeroizing::new(if let Ok(pin_str) = std::str::from_utf8(&pin_bytes) {
            derive_key_argon2(pin_str, &salt_bytes)
        } else {
            vec![]
        });
        match env.byte_array_from_slice(&derived) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut() as jbyteArray,
        }
    })).unwrap_or(std::ptr::null_mut() as jbyteArray)
}

#[no_mangle]
pub extern "system" fn Java_com_bloom_ffi_RustBridge_unlock_1with_1biometric(
    env: JNIEnv,
    _class: JClass,
    key_bytes: JByteArray,
) -> jboolean {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let bytes = match jbytearray_to_bounded_vec(&env, &key_bytes) {
            Ok(v) => Zeroizing::new(v),
            Err(_) => return 0,
        };
        if unlock_with_biometric_internal(&bytes) { 1 } else { 0 }
    })).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_bloom_ffi_RustBridge_lock_1database(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        lock_database_internal();
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_bloom_ffi_RustBridge_save_1partner_1permissions(
    _env: JNIEnv,
    _class: JClass,
    share_cycle_phase: jboolean,
    share_flow_intensity: jboolean,
    share_symptoms_moods: jboolean,
    share_birth_control: jboolean,
    share_pregnancy_tests: jboolean,
    share_sexual_activity: jboolean,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let perms = PartnerPermissions {
            share_cycle_phase: share_cycle_phase != 0,
            share_flow_intensity: share_flow_intensity != 0,
            share_symptoms_moods: share_symptoms_moods != 0,
            share_birth_control: share_birth_control != 0,
            share_pregnancy_tests: share_pregnancy_tests != 0,
            share_sexual_activity: share_sexual_activity != 0,
        };
        save_partner_permissions_internal(perms);
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_bloom_ffi_RustBridge_get_1partner_1permissions_1json(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut env_mut = env; let _ = &mut env_mut; #[allow(unused_mut)] let _ = &mut env_mut;
        let perms = get_partner_permissions_internal();
        let json = serde_json::to_string(&perms).unwrap_or_default();
        match env_mut.new_string(json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut() as jstring,
        }
    })).unwrap_or(std::ptr::null_mut() as jstring)
}

#[no_mangle]
pub extern "system" fn Java_com_bloom_ffi_RustBridge_generate_1filtered_1payload(
    env: JNIEnv,
    _class: JClass,
    last_updated: i64,
    birth_control_json: JString,
    entries_json: JString,
    partner_id: JString,
    shared_key_base64: JString,
) -> jbyteArray {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut env_mut = env; let _ = &mut env_mut; #[allow(unused_mut)] let _ = &mut env_mut;
        let bc_str: Option<String> = if !birth_control_json.is_null() {
            match jstring_to_bounded_string(&mut env_mut, &birth_control_json) {
                Ok(s) => Some(s),
                Err(_) => return std::ptr::null_mut() as jbyteArray,
            }
        } else {
            None
        };
        let entries_str = match jstring_to_bounded_string(&mut env_mut, &entries_json) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut() as jbyteArray,
        };
        let id_str = match jstring_to_bounded_string(&mut env_mut, &partner_id) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut() as jbyteArray,
        };
        let key_str = match jstring_to_bounded_string(&mut env_mut, &shared_key_base64) {
            Ok(s) => Zeroizing::new(s),
            Err(_) => return std::ptr::null_mut() as jbyteArray,
        };
        let result = generate_filtered_payload_internal(last_updated, bc_str, entries_str, &id_str, &key_str);
        match env_mut.byte_array_from_slice(&result) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut() as jbyteArray,
        }
    })).unwrap_or(std::ptr::null_mut() as jbyteArray)
}

#[no_mangle]
pub extern "system" fn Java_com_bloom_ffi_RustBridge_decrypt_1payload(
    env: JNIEnv,
    _class: JClass,
    encrypted_data: JByteArray,
    partner_id: JString,
    shared_key_base64: JString,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut env_mut = env; let _ = &mut env_mut; #[allow(unused_mut)] let _ = &mut env_mut;
        let data_bytes = match jbytearray_to_bounded_vec(&env_mut, &encrypted_data) {
            Ok(v) => v,
            Err(_) => return std::ptr::null_mut() as jstring,
        };
        let id_str = match jstring_to_bounded_string(&mut env_mut, &partner_id) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut() as jstring,
        };
        let key_str = match jstring_to_bounded_string(&mut env_mut, &shared_key_base64) {
            Ok(s) => Zeroizing::new(s),
            Err(_) => return std::ptr::null_mut() as jstring,
        };
        let secret_key = Zeroizing::new(general_purpose::STANDARD.decode(&*key_str).unwrap_or_default());
        let result = Zeroizing::new(decrypt_payload_internal(&data_bytes, &id_str, &secret_key).unwrap_or_default());
        match env_mut.new_string(&*result) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut() as jstring,
        }
    })).unwrap_or(std::ptr::null_mut() as jstring)
}

#[no_mangle]
pub extern "system" fn Java_com_bloom_ffi_RustBridge_generate_1random_1key(
    env: JNIEnv,
    _class: JClass,
) -> jbyteArray {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut env_mut = env; let _ = &mut env_mut; #[allow(unused_mut)] let _ = &mut env_mut;
        let key = generate_random_key_internal();
        match env_mut.byte_array_from_slice(&key) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut() as jbyteArray,
        }
    })).unwrap_or(std::ptr::null_mut() as jbyteArray)
}

#[no_mangle]
pub extern "system" fn Java_com_bloom_ffi_RustBridge_calculateNextCycleNative(
    env: JNIEnv,
    _class: JClass,
    history_json: JString,
) -> jstring {
    with_jni_json_io(env, history_json, |_env, history_str| {
        calculate_next_cycle_native_internal(history_str)
    })
}

fn run_sync_crypto_jni(
    env: JNIEnv,
    data_json: JString,
    shared_key_base64: JString,
    transform_fn: impl Fn(&str, &str) -> String,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut env_mut = env; let _ = &mut env_mut; #[allow(unused_mut)] let _ = &mut env_mut;
        let data = match jstring_to_bounded_string(&mut env_mut, &data_json) {
            Ok(s) => Zeroizing::new(s),
            Err(_) => return std::ptr::null_mut() as jstring,
        };
        let key = match jstring_to_bounded_string(&mut env_mut, &shared_key_base64) {
            Ok(s) => Zeroizing::new(s),
            Err(_) => return std::ptr::null_mut() as jstring,
        };
        let result = transform_fn(&data, &key);
        match env_mut.new_string(result) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut() as jstring,
        }
    })).unwrap_or(std::ptr::null_mut() as jstring)
}

#[no_mangle]
pub extern "system" fn Java_com_bloom_ffi_RustBridge_encrypt_1for_1sync(
    env: JNIEnv, _class: JClass, payload_json: JString, shared_key_base64: JString,
) -> jstring {
    run_sync_crypto_jni(env, payload_json, shared_key_base64, |p, k| {
        encrypt_for_sync_internal(p, k).unwrap_or_default()
    })
}

#[no_mangle]
pub extern "system" fn Java_com_bloom_ffi_RustBridge_decrypt_1from_1sync(
    env: JNIEnv, _class: JClass, encrypted_base64: JString, shared_key_base64: JString,
) -> jstring {
    run_sync_crypto_jni(env, encrypted_base64, shared_key_base64, |e, k| {
        decrypt_from_sync_internal(e, k).unwrap_or_default()
    })
}
