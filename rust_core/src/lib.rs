pub mod prediction_engine;

use chrono::{Duration, NaiveDate};
use jni::objects::{JClass, JString, JByteArray};
use jni::sys::{jboolean, jbyteArray, jstring};
use jni::JNIEnv;
use argon2::{
    password_hash::{rand_core::OsRng, PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
    Argon2,
};
use lazy_static::lazy_static;
use std::sync::Mutex;
use zeroize::{Zeroize, Zeroizing};
use serde::{Deserialize, Serialize};
use chacha20poly1305::{
    aead::{Aead, KeyInit},
    XChaCha20Poly1305, XNonce,
};

lazy_static! {
    static ref MASTER_KEY: Mutex<Option<Zeroizing<Vec<u8>>>> = Mutex::new(None);
    static ref PARTNER_PERMISSIONS: Mutex<PartnerPermissions> = Mutex::new(PartnerPermissions::default());
}

#[derive(Serialize, Deserialize, Default, Clone)]
pub struct PartnerPermissions {
    pub share_cycle_phase: bool,
    pub share_flow_intensity: bool,
    pub share_symptoms_moods: bool,
    pub share_birth_control: bool,
    #[serde(default)]
    pub share_pregnancy_tests: bool,
    #[serde(default)]
    pub share_sexual_activity: bool,
}

#[derive(Serialize, Deserialize)]
pub struct SyncEntry {
    pub date: String,
    pub intensity: i32,
    pub notes: Option<String>,
    #[serde(rename = "sexualRelations", default)]
    pub sexual_relations: Option<String>,
    #[serde(rename = "painLevel", default)]
    pub pain_level: Option<i32>,
    #[serde(default)]
    pub symptoms: Option<String>,
}

#[derive(Serialize, Deserialize)]
pub struct SyncBirthControl {
    pub r#type: String,
    pub is_active: bool,
}

#[derive(Serialize, Deserialize)]
pub struct SyncPayload {
    pub last_updated: i64,
    pub birth_control: Option<SyncBirthControl>,
    pub entries: Vec<SyncEntry>,
}

// VULN-01 FIX: Legacy key derivation — kept only for backward-compatible unlock
fn derive_key_legacy(id: &str) -> [u8; 32] {
    let mut key = [0u8; 32];
    let hash = blake3::hash(id.as_bytes());
    key.copy_from_slice(hash.as_bytes());
    key
}

// VULN-01 FIX: Secure key derivation using Argon2id with a random salt
fn derive_key_secure(pin: &str, salt: &[u8]) -> Result<[u8; 32], &'static str> {
    if salt.len() < 8 {
        return Err("Salt length must be at least 8 bytes");
    }
    use argon2::{Argon2, Algorithm, Version, Params};
    let params = Params::new(65536, 3, 4, Some(32))
        .unwrap_or_else(|_| Params::default());
    let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
    let mut key = [0u8; 32];
    if argon2.hash_password_into(pin.as_bytes(), salt, &mut key).is_ok() {
        Ok(key)
    } else {
        Err("Argon2id hashing failed")
    }
}

// Pure Rust logic
pub fn calculate_phase_internal(last_period_date: &str, avg_cycle_length: i32, today: NaiveDate) -> String {
    if avg_cycle_length <= 0 {
        return "Unknown".to_string();
    }
    let start_date = match NaiveDate::parse_from_str(last_period_date, "%Y-%m-%d") {
        Ok(d) => d,
        Err(_) => return "Invalid Date".to_string(),
    };

    let days_since_start = (today - start_date).num_days();

    if days_since_start < 0 {
        "Upcoming".to_string()
    } else {
        let current_day_in_cycle = days_since_start % avg_cycle_length as i64;
        
        if current_day_in_cycle < 5 {
            "Menstrual".to_string()
        } else if current_day_in_cycle < 13 {
            "Follicular".to_string()
        } else if current_day_in_cycle < 15 {
            "Ovulation".to_string()
        } else {
            "Luteal".to_string()
        }
    }
}

pub fn predict_next_period_internal(last_period_date: &str, avg_cycle_length: i32) -> String {
    if avg_cycle_length <= 0 {
        return "".to_string();
    }
    let start_date = match NaiveDate::parse_from_str(last_period_date, "%Y-%m-%d") {
        Ok(d) => d,
        Err(_) => return "".to_string(),
    };

    let next_period = start_date + Duration::days(avg_cycle_length as i64);
    next_period.format("%Y-%m-%d").to_string()
}

pub fn analyze_cycle_history_internal(dates_csv: &str) -> String {
    let default_result = prediction_engine::CyclePrediction { estimated_length_days: 28, variance_days: 0, is_irregular: false };
    
    if dates_csv.is_empty() {
        return serde_json::to_string(&default_result).unwrap_or_else(|_| "{}".to_string());
    }

    let mut dates: Vec<NaiveDate> = dates_csv
        .split(',')
        .filter_map(|s| NaiveDate::parse_from_str(s, "%Y-%m-%d").ok())
        .collect();

    dates.sort();
    
    if dates.len() < 2 {
        return serde_json::to_string(&default_result).unwrap_or_else(|_| "{}".to_string());
    }

    let mut intervals = Vec::new();
    for i in 1..dates.len() {
        let diff = (dates[i] - dates[i-1]).num_days();
        if diff > 15 && diff < 90 {
            intervals.push(diff as u32);
        }
    }

    let result = prediction_engine::predict_irregular_cycle(&intervals);
    serde_json::to_string(&result).unwrap_or_else(|_| "{}".to_string())
}

// Authentication Internal Logic
pub fn generate_pin_hash_internal(pin: &str) -> Vec<u8> {
    let salt = SaltString::generate(&mut OsRng);
    let params = argon2::Params::new(65536, 3, 4, Some(32)).unwrap_or_else(|_| argon2::Params::default());
    let argon2 = Argon2::new(argon2::Algorithm::Argon2id, argon2::Version::V0x13, params);
    match argon2.hash_password(pin.as_bytes(), &salt) {
        Ok(hash) => hash.to_string().into_bytes(),
        Err(_) => vec![],
    }
}

// VULN-01 FIX: Accepts derivation salt — empty salt triggers legacy BLAKE3 path
pub fn verify_and_unlock_internal(pin_hash: &[u8], input_pin: &str, derivation_salt: &[u8]) -> bool {
    let hash_str = match std::str::from_utf8(pin_hash) {
        Ok(v) => v,
        Err(_) => return false,
    };
    
    let parsed_hash = match PasswordHash::new(hash_str) {
        Ok(v) => v,
        Err(_) => return false,
    };
    
    if Argon2::default().verify_password(input_pin.as_bytes(), &parsed_hash).is_ok() {
        // VULN-02 FIX: Recover from poisoned mutex instead of panicking across JNI
        let mut key = MASTER_KEY.lock().unwrap_or_else(|e| e.into_inner());
        // VULN-01 FIX: Use Argon2id if salt provided, legacy BLAKE3 for pre-existing users
        let mut derived = if derivation_salt.is_empty() {
            derive_key_legacy(input_pin)
        } else {
            match derive_key_secure(input_pin, derivation_salt) {
                Ok(k) => k,
                Err(_) => return false,
            }
        };
        *key = Some(Zeroizing::new(derived.to_vec()));
        derived.zeroize();
        true
    } else {
        false
    }
}

pub fn unlock_with_biometric_internal(key_bytes: &[u8]) -> bool {
    if key_bytes.len() != 32 { return false; }
    // VULN-02 FIX: Recover from poisoned mutex instead of panicking across JNI
    let mut key = MASTER_KEY.lock().unwrap_or_else(|e| e.into_inner());
    *key = Some(Zeroizing::new(key_bytes.to_vec()));
    true
}

pub fn lock_database_internal() {
    // VULN-02 FIX: Recover from poisoned mutex instead of panicking across JNI
    let mut key = MASTER_KEY.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(mut k) = key.take() {
        k.zeroize();
    }
}

// Permissions & Filtering Logic
pub fn save_partner_permissions_internal(perms: PartnerPermissions) {
    // VULN-02 FIX: Recover from poisoned mutex instead of panicking across JNI
    let mut current = PARTNER_PERMISSIONS.lock().unwrap_or_else(|e| e.into_inner());
    *current = perms;
}

pub fn get_partner_permissions_internal() -> PartnerPermissions {
    // VULN-02 FIX: Recover from poisoned mutex instead of panicking across JNI
    PARTNER_PERMISSIONS.lock().unwrap_or_else(|e| e.into_inner()).clone()
}

// VULN-05 FIX: Added secret_key — mixed into KDF so partner_id alone cannot derive the key
pub fn encrypt_data(data: &str, id: &str, secret_key: &[u8]) -> Vec<u8> {
    if secret_key.len() < 32 {
        return Vec::new();
    }
    let mut salt = [0u8; 16];
    let mut rng = OsRng;
    rand_core::RngCore::fill_bytes(&mut rng, &mut salt);
    
    let mut hasher = blake3::Hasher::new_derive_key("novarytm_sync_payload_v1");
    hasher.update(secret_key); // VULN-05 FIX: Mix in actual secret material
    hasher.update(id.as_bytes());
    hasher.update(&salt);
    let mut key_bytes = [0u8; 32];
    hasher.finalize_xof().fill(&mut key_bytes);
    
    let cipher = XChaCha20Poly1305::new(&key_bytes.into());
    key_bytes.zeroize(); // VULN-08 FIX: Zeroize ephemeral key material
    
    let mut nonce_bytes = [0u8; 24];
    rand_core::RngCore::fill_bytes(&mut rng, &mut nonce_bytes);
    let nonce = XNonce::from_slice(&nonce_bytes);

    let mut ciphertext = match cipher.encrypt(nonce, data.as_bytes()) {
        Ok(ct) => ct,
        Err(_) => {
            #[cfg(debug_assertions)]
            eprintln!("XChaCha20Poly1305 encryption failed");
            return Vec::new();
        }
    };
    
    let mut result = Vec::with_capacity(16 + 24 + ciphertext.len());
    result.extend_from_slice(&salt);
    result.extend_from_slice(&nonce_bytes);
    result.append(&mut ciphertext);
    
    result
}

// VULN-05 FIX: Added secret_key — must match the key used during encryption
pub fn decrypt_payload_internal(encrypted_data: &[u8], id: &str, secret_key: &[u8]) -> Option<String> {
    if encrypted_data.len() < 40 {
        return None;
    }
    
    let salt = &encrypted_data[0..16];
    let nonce_bytes = &encrypted_data[16..40];
    let ciphertext = &encrypted_data[40..];
    
    let mut hasher = blake3::Hasher::new_derive_key("novarytm_sync_payload_v1");
    hasher.update(secret_key); // VULN-05 FIX: Mix in actual secret material
    hasher.update(id.as_bytes());
    hasher.update(salt);
    let mut key_bytes = [0u8; 32];
    hasher.finalize_xof().fill(&mut key_bytes);
    
    let cipher = XChaCha20Poly1305::new(&key_bytes.into());
    key_bytes.zeroize(); // VULN-08 FIX: Zeroize ephemeral key material
    let nonce = XNonce::from_slice(nonce_bytes);

    let plaintext = cipher.decrypt(nonce, ciphertext).ok()?;
    String::from_utf8(plaintext).ok()
}

pub fn generate_filtered_payload_internal(
    last_updated: i64,
    birth_control_json: Option<String>,
    entries_json: String,
    partner_id: &str,
    shared_key_base64: &str,
) -> Vec<u8> {
    let perms = get_partner_permissions_internal();
    
    let birth_control: Option<SyncBirthControl> = if perms.share_birth_control {
        birth_control_json.and_then(|json| serde_json::from_str(&json).ok())
    } else {
        None
    };

    let mut entries: Vec<SyncEntry> = serde_json::from_str(&entries_json).unwrap_or_default();
    
    if !perms.share_cycle_phase {
        entries.clear();
    } else {
        for entry in entries.iter_mut() {
            if !perms.share_flow_intensity {
                if entry.intensity > 0 {
                    entry.intensity = 1;
                } else {
                    entry.intensity = 0;
                }
            }
            if !perms.share_symptoms_moods {
                entry.notes = None;
                entry.symptoms = None;
                entry.pain_level = None;
            }
            if !perms.share_sexual_activity {
                entry.sexual_relations = None;
            }
        }
    }

    let payload = SyncPayload {
        last_updated: if perms.share_cycle_phase { last_updated } else { 0 },
        birth_control,
        entries,
    };

    let raw_json = serde_json::to_string(&payload).unwrap_or_default();
    use base64::{Engine as _, engine::general_purpose};
    let secret_key = Zeroizing::new(general_purpose::STANDARD.decode(shared_key_base64).unwrap_or_default());
    encrypt_data(&raw_json, partner_id, &secret_key)
}

pub fn generate_random_key_internal() -> Vec<u8> {
    let mut key = [0u8; 32];
    let mut rng = OsRng;
    rand_core::RngCore::fill_bytes(&mut rng, &mut key);
    let vec = key.to_vec();
    key.zeroize();
    vec
}

// JNI Wrappers
#[no_mangle]
pub extern "system" fn Java_com_novarytm_ffi_RustBridge_calculate_1cycle_1phase(
    mut env: JNIEnv,
    _class: JClass,
    last_period_date: JString,
    avg_cycle_length: i32,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let date_str: String = match env.get_string(&last_period_date) { Ok(s) => s.into(), Err(_) => "".to_string() };
        let today = chrono::Local::now().date_naive();
        let phase = calculate_phase_internal(&date_str, avg_cycle_length, today);
        match env.new_string(phase) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut() as jstring,
        }
    })).unwrap_or(std::ptr::null_mut() as jstring)
}

#[no_mangle]
pub extern "system" fn Java_com_novarytm_ffi_RustBridge_predict_1next_1period(
    mut env: JNIEnv,
    _class: JClass,
    last_period_date: JString,
    avg_cycle_length: i32,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let date_str: String = match env.get_string(&last_period_date) { Ok(s) => s.into(), Err(_) => "".to_string() };
        let next_period = predict_next_period_internal(&date_str, avg_cycle_length);
        match env.new_string(next_period) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut() as jstring,
        }
    })).unwrap_or(std::ptr::null_mut() as jstring)
}

#[no_mangle]
pub extern "system" fn Java_com_novarytm_ffi_RustBridge_analyze_1cycle_1history(
    mut env: JNIEnv,
    _class: JClass,
    dates_csv: JString,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let dates_str: String = match env.get_string(&dates_csv) {
            Ok(s) => s.into(),
            Err(_) => "".to_string(),
        };
        let result = analyze_cycle_history_internal(&dates_str);
        match env.new_string(result) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut() as jstring,
        }
    })).unwrap_or(std::ptr::null_mut() as jstring)
}

#[no_mangle]
pub extern "system" fn Java_com_novarytm_ffi_RustBridge_generate_1pin_1hash(
    env: JNIEnv,
    _class: JClass,
    pin: JByteArray,
) -> jbyteArray {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let pin_bytes = Zeroizing::new(env.convert_byte_array(&pin).unwrap_or_default());
        let hash_bytes = Zeroizing::new(if let Ok(pin_str) = std::str::from_utf8(&pin_bytes) {
            generate_pin_hash_internal(pin_str)
        } else { vec![] });
        match env.byte_array_from_slice(&hash_bytes) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut() as jbyteArray,
        }
    })).unwrap_or(std::ptr::null_mut() as jbyteArray)
}

#[no_mangle]
pub extern "system" fn Java_com_novarytm_ffi_RustBridge_verify_1and_1unlock(
    env: JNIEnv,
    _class: JClass,
    pin_hash: JByteArray,
    input_pin: JByteArray,
    derivation_salt: JByteArray,
) -> jboolean {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let hash_bytes = Zeroizing::new(env.convert_byte_array(&pin_hash).unwrap_or_default());
        let pin_bytes = Zeroizing::new(env.convert_byte_array(&input_pin).unwrap_or_default());
        let salt_bytes = Zeroizing::new(env.convert_byte_array(&derivation_salt).unwrap_or_default());
        let result = if let Ok(pin_str) = std::str::from_utf8(&pin_bytes) {
            verify_and_unlock_internal(&hash_bytes, pin_str, &salt_bytes)
        } else { false };
        if result { 1 } else { 0 }
    })).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_novarytm_ffi_RustBridge_derive_1key(
    env: JNIEnv,
    _class: JClass,
    pin: JByteArray,
    derivation_salt: JByteArray,
) -> jbyteArray {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let pin_bytes = Zeroizing::new(env.convert_byte_array(&pin).unwrap_or_default());
        let salt_bytes = Zeroizing::new(env.convert_byte_array(&derivation_salt).unwrap_or_default());
        let key = Zeroizing::new(if let Ok(pin_str) = std::str::from_utf8(&pin_bytes) {
            if salt_bytes.is_empty() {
                derive_key_legacy(pin_str)
            } else {
                derive_key_secure(pin_str, &salt_bytes).unwrap_or([0u8; 32])
            }
        } else { [0u8; 32] });
        match env.byte_array_from_slice(key.as_slice()) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut() as jbyteArray,
        }
    })).unwrap_or(std::ptr::null_mut() as jbyteArray)
}

#[no_mangle]
pub extern "system" fn Java_com_novarytm_ffi_RustBridge_unlock_1with_1biometric(
    env: JNIEnv,
    _class: JClass,
    key_bytes: JByteArray,
) -> jboolean {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let bytes = Zeroizing::new(env.convert_byte_array(&key_bytes).unwrap_or_default());
        let result = unlock_with_biometric_internal(&bytes);
        if result { 1 } else { 0 }
    })).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_novarytm_ffi_RustBridge_lock_1database(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        lock_database_internal();
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_novarytm_ffi_RustBridge_save_1partner_1permissions(
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
pub extern "system" fn Java_com_novarytm_ffi_RustBridge_get_1partner_1permissions_1json(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let perms = get_partner_permissions_internal();
        let json = serde_json::to_string(&perms).unwrap_or_default();
        match env.new_string(json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut() as jstring,
        }
    })).unwrap_or(std::ptr::null_mut() as jstring)
}

#[no_mangle]
pub extern "system" fn Java_com_novarytm_ffi_RustBridge_generate_1filtered_1payload(
    mut env: JNIEnv,
    _class: JClass,
    last_updated: i64,
    birth_control_json: JString,
    entries_json: JString,
    partner_id: JString,
    shared_key_base64: JString,
) -> jbyteArray {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let bc_str: Option<String> = if !birth_control_json.is_null() {
            Some(match env.get_string(&birth_control_json) { Ok(s) => s.into(), Err(_) => "".to_string() })
        } else {
            None
        };
        let entries_str: String = match env.get_string(&entries_json) { Ok(s) => s.into(), Err(_) => "".to_string() };
        let id_str: String = match env.get_string(&partner_id) { Ok(s) => s.into(), Err(_) => "".to_string() };
        let key_str: String = match env.get_string(&shared_key_base64) { Ok(s) => s.into(), Err(_) => "".to_string() };
        let result = generate_filtered_payload_internal(last_updated, bc_str, entries_str, &id_str, &key_str);
        match env.byte_array_from_slice(&result) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut() as jbyteArray,
        }
    })).unwrap_or(std::ptr::null_mut() as jbyteArray)
}

#[no_mangle]
pub extern "system" fn Java_com_novarytm_ffi_RustBridge_decrypt_1payload(
    mut env: JNIEnv,
    _class: JClass,
    encrypted_data: JByteArray,
    partner_id: JString,
    shared_key_base64: JString,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let data_bytes = env.convert_byte_array(&encrypted_data).unwrap_or_default();
        let id_str: String = match env.get_string(&partner_id) { Ok(s) => s.into(), Err(_) => "".to_string() };
        let key_str: String = match env.get_string(&shared_key_base64) { Ok(s) => s.into(), Err(_) => "".to_string() };
        use base64::{Engine as _, engine::general_purpose};
        let secret_key = Zeroizing::new(general_purpose::STANDARD.decode(key_str).unwrap_or_default());
        let result = decrypt_payload_internal(&data_bytes, &id_str, &secret_key).unwrap_or_default();
        match env.new_string(result) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut() as jstring,
        }
    })).unwrap_or(std::ptr::null_mut() as jstring)
}

#[no_mangle]
pub extern "system" fn Java_com_novarytm_ffi_RustBridge_generate_1random_1key(
    env: JNIEnv,
    _class: JClass,
) -> jbyteArray {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let key = generate_random_key_internal();
        match env.byte_array_from_slice(&key) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut() as jbyteArray,
        }
    })).unwrap_or(std::ptr::null_mut() as jbyteArray)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_phase_logic() {
        let today = NaiveDate::from_ymd_opt(2024, 1, 15).unwrap();
        assert_eq!(calculate_phase_internal("2024-01-15", 28, today), "Menstrual");
        assert_eq!(calculate_phase_internal("2024-01-10", 28, today), "Follicular");
        assert_eq!(calculate_phase_internal("2024-01-02", 28, today), "Ovulation");
        assert_eq!(calculate_phase_internal("2023-12-27", 28, today), "Luteal");
        assert_eq!(calculate_phase_internal("2024-01-20", 28, today), "Upcoming");
    }
}

use serde_json::json;

pub fn calculate_next_cycle_native_internal(history_json: &str) -> String {
    let entries: Vec<SyncEntry> = serde_json::from_str(history_json).unwrap_or_default();
    let mut dates: Vec<NaiveDate> = entries
        .iter()
        .filter(|e| e.intensity > 0)
        .filter_map(|e| NaiveDate::parse_from_str(&e.date, "%Y-%m-%d").ok())
        .collect();

    dates.sort();
    
    let empty_vec: Vec<String> = Vec::new();
    let default_response = json!({
        "expectedPeriodDays": empty_vec,
        "fertileWindow": empty_vec,
        "currentDayOfCycle": 1
    });

    if dates.is_empty() {
        return default_response.to_string();
    }

    // Group dates into distinct cycle starts (gap > 15 days)
    let mut cycle_starts = Vec::new();
    cycle_starts.push(dates[0]);
    for i in 1..dates.len() {
        if (dates[i] - dates[i-1]).num_days() > 15 {
            cycle_starts.push(dates[i]);
        }
    }

    // VULN-03 FIX: Guard against empty cycle_starts — no unwrap() panic
    let last_period_start = match cycle_starts.last() {
        Some(&d) => d,
        None => return default_response.to_string(),
    };
    let today = chrono::Local::now().date_naive();
    let days_since_start = (today - last_period_start).num_days() as i32;
    let current_day_of_cycle = std::cmp::max(1, days_since_start + 1);

    let dates_csv = cycle_starts.iter().map(|d| d.format("%Y-%m-%d").to_string()).collect::<Vec<String>>().join(",");
    let analysis_json = analyze_cycle_history_internal(&dates_csv);
    
    let analysis: prediction_engine::CyclePrediction = serde_json::from_str(&analysis_json).unwrap_or(
        prediction_engine::CyclePrediction { estimated_length_days: 28, variance_days: 0, is_irregular: false }
    );
    

    // Calculate average period duration (consecutive dates)
    let mut period_durations = Vec::new();
    let mut current_duration = 1;
    for i in 1..dates.len() {
        if (dates[i] - dates[i-1]).num_days() == 1 {
            current_duration += 1;
        } else if (dates[i] - dates[i-1]).num_days() > 15 {
            period_durations.push(current_duration);
            current_duration = 1;
        }
    }
    period_durations.push(current_duration);
    
    let avg_period_duration = if !period_durations.is_empty() {
        let sum: i32 = period_durations.iter().sum();
        std::cmp::max(3, sum / (period_durations.len() as i32))
    } else {
        5
    };

    let avg_length = analysis.estimated_length_days as i64;

    
    let mut expected_period_days = Vec::new();
    let mut fertile_window = Vec::new();

    // 1. Backfill expected periods and fertile windows for all past cycles
    for i in 0..cycle_starts.len() {
        let start = cycle_starts[i];
        
        // Expected period (assumes standard 5 days length for UI dotted rings)
        for j in 0..avg_period_duration {
            expected_period_days.push((start + chrono::Duration::days(j as i64)).format("%Y-%m-%d").to_string());
        }

        // Fertile window: ~14 days before the start of the next cycle
        let next_start = if i + 1 < cycle_starts.len() {
            cycle_starts[i + 1]
        } else {
            start + chrono::Duration::days(avg_length)
        };

        let ovulation_day = next_start - chrono::Duration::days(14);
        for j in -2..=2 {
            fertile_window.push((ovulation_day + chrono::Duration::days(j as i64)).format("%Y-%m-%d").to_string());
        }
    }

    // 2. Predict future cycles from the current cycle forward
    for cycle_idx in 1..=3 {
        let cycle_start = last_period_start + chrono::Duration::days(avg_length * cycle_idx);
        
        for i in 0..avg_period_duration {
            expected_period_days.push((cycle_start + chrono::Duration::days(i as i64)).format("%Y-%m-%d").to_string());
        }

        let next_cycle_start = last_period_start + chrono::Duration::days(avg_length * (cycle_idx + 1));
        let ovulation_day = next_cycle_start - chrono::Duration::days(14);
        
        for i in -2..=2 {
            fertile_window.push((ovulation_day + chrono::Duration::days(i as i64)).format("%Y-%m-%d").to_string());
        }
    }

    expected_period_days.sort();
    expected_period_days.dedup();
    fertile_window.sort();
    fertile_window.dedup();

    let response = json!({
        "expectedPeriodDays": expected_period_days,
        "fertileWindow": fertile_window,
        "currentDayOfCycle": current_day_of_cycle
    });

    response.to_string()
}

#[no_mangle]
pub extern "system" fn Java_com_novarytm_ffi_RustBridge_calculateNextCycleNative(
    mut env: JNIEnv,
    _class: JClass,
    history_json: JString,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let history_str: String = match env.get_string(&history_json) {
            Ok(s) => s.into(),
            Err(_) => "".to_string(),
        };
        let result = calculate_next_cycle_native_internal(&history_str);
        match env.new_string(result) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut() as jstring,
        }
    })).unwrap_or(std::ptr::null_mut() as jstring)
}

pub fn encrypt_for_sync_internal(payload: &str, shared_key_base64: &str) -> Option<String> {
    use base64::{Engine as _, engine::general_purpose};
    let mut key_bytes = general_purpose::STANDARD.decode(shared_key_base64).ok()?;
    if key_bytes.len() != 32 { return None; }
    
    let cipher = XChaCha20Poly1305::new(chacha20poly1305::Key::from_slice(&key_bytes));
    key_bytes.zeroize(); // VULN-08 FIX: Zeroize ephemeral key material
    
    let mut nonce_bytes = [0u8; 24];
    let mut rng = rand_core::OsRng;
    rand_core::RngCore::fill_bytes(&mut rng, &mut nonce_bytes);
    let nonce = XNonce::from_slice(&nonce_bytes);

    let mut ciphertext = cipher.encrypt(nonce, payload.as_bytes()).ok()?;
    
    let mut result = Vec::with_capacity(24 + ciphertext.len());
    result.extend_from_slice(&nonce_bytes);
    result.append(&mut ciphertext);
    
    Some(general_purpose::STANDARD.encode(&result))
}

pub fn decrypt_from_sync_internal(encrypted_base64: &str, shared_key_base64: &str) -> Option<String> {
    use base64::{Engine as _, engine::general_purpose};
    let mut key_bytes = general_purpose::STANDARD.decode(shared_key_base64).ok()?;
    if key_bytes.len() != 32 { return None; }
    
    let encrypted_data = general_purpose::STANDARD.decode(encrypted_base64).ok()?;
    if encrypted_data.len() < 24 { return None; }
    
    let nonce_bytes = &encrypted_data[0..24];
    let ciphertext = &encrypted_data[24..];
    
    let cipher = XChaCha20Poly1305::new(chacha20poly1305::Key::from_slice(&key_bytes));
    key_bytes.zeroize(); // VULN-08 FIX: Zeroize ephemeral key material
    let nonce = XNonce::from_slice(nonce_bytes);

    let plaintext = cipher.decrypt(nonce, ciphertext).ok()?;
    String::from_utf8(plaintext).ok()
}

#[no_mangle]
pub extern "system" fn Java_com_novarytm_ffi_RustBridge_encrypt_1for_1sync(
    mut env: JNIEnv,
    _class: JClass,
    payload_json: JString,
    shared_key_base64: JString,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let payload: String = match env.get_string(&payload_json) { Ok(s) => s.into(), Err(_) => "".to_string() };
        let key: String = match env.get_string(&shared_key_base64) { Ok(s) => s.into(), Err(_) => "".to_string() };
        
        let result = encrypt_for_sync_internal(&payload, &key).unwrap_or_default();
        match env.new_string(result) {
            Ok(s) => s.into_raw(),
            Err(_) => match env.new_string("") {
                Ok(s) => s.into_raw(),
                Err(_) => std::ptr::null_mut() as jstring
            }
        }
    })).unwrap_or(std::ptr::null_mut() as jstring)
}

#[no_mangle]
pub extern "system" fn Java_com_novarytm_ffi_RustBridge_decrypt_1from_1sync(
    mut env: JNIEnv,
    _class: JClass,
    encrypted_base64: JString,
    shared_key_base64: JString,
) -> jstring {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let encrypted: String = match env.get_string(&encrypted_base64) { Ok(s) => s.into(), Err(_) => "".to_string() };
        let key: String = match env.get_string(&shared_key_base64) { Ok(s) => s.into(), Err(_) => "".to_string() };
        
        let result = decrypt_from_sync_internal(&encrypted, &key).unwrap_or_default();
        match env.new_string(result) {
            Ok(s) => s.into_raw(),
            Err(_) => match env.new_string("") {
                Ok(s) => s.into_raw(),
                Err(_) => std::ptr::null_mut() as jstring
            }
        }
    })).unwrap_or(std::ptr::null_mut() as jstring)
}
