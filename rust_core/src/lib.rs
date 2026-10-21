pub mod prediction_engine;

use chrono::{Duration, NaiveDate};
use jni::objects::{JClass, JString, JByteArray};
use jni::sys::{jboolean, jbyteArray, jstring};
use jni::JNIEnv;
use argon2::{
    Argon2,
};
use password_hash::{
    PasswordHasher, PasswordVerifier,
    phc::PasswordHash,
};
use lazy_static::lazy_static;
use rand_core::OsRng;
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
        let avg_len = std::cmp::max(21, avg_cycle_length) as i64;
        let current_day_in_cycle = days_since_start % avg_len;
        let ovulation_center = std::cmp::max(7, avg_len - 14); // 14 for 28-day cycle
        if current_day_in_cycle < 5 {
            "Menstrual".to_string()
        } else if current_day_in_cycle < ovulation_center - 1 {
            "Follicular".to_string()
        } else if current_day_in_cycle <= ovulation_center {
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
        .filter_map(|s| NaiveDate::parse_from_str(s.trim(), "%Y-%m-%d").ok())
        .collect();

    if dates.is_empty() {
        return serde_json::to_string(&default_result).unwrap_or_else(|_| "{}".to_string());
    }

    dates.sort();
    dates.dedup();
    
    let mut cycle_starts = Vec::new();
    cycle_starts.push(dates[0]);
    for i in 1..dates.len() {
        if (dates[i] - dates[i-1]).num_days() > 7 {
            cycle_starts.push(dates[i]);
        }
    }

    let mut intervals = Vec::new();
    for i in 1..cycle_starts.len() {
        let diff = (cycle_starts[i] - cycle_starts[i-1]).num_days();
        if diff >= 15 && diff <= 90 {
            intervals.push(diff as u32);
        }
    }

    let result = prediction_engine::predict_irregular_cycle(&intervals);
    serde_json::to_string(&result).unwrap_or_else(|_| "{}".to_string())
}

// Authentication Internal Logic
pub fn generate_pin_hash_internal(pin: &str) -> Vec<u8> {
    let params = argon2::Params::new(65536, 3, 4, Some(32)).unwrap_or_else(|_| argon2::Params::default());
    let argon2 = Argon2::new(argon2::Algorithm::Argon2id, argon2::Version::V0x13, params);
    match argon2.hash_password(pin.as_bytes()) {
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
    
    let cipher = XChaCha20Poly1305::new((&key_bytes).into());
    key_bytes.zeroize(); // VULN-08 FIX: Zeroize ephemeral key material
    
    let mut nonce_bytes = [0u8; 24];
    rand_core::RngCore::fill_bytes(&mut rng, &mut nonce_bytes);
    let nonce: &XNonce = (&nonce_bytes).into();

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
    
    let cipher = XChaCha20Poly1305::new((&key_bytes).into());
    key_bytes.zeroize(); // VULN-08 FIX: Zeroize ephemeral key material
    let nonce: &XNonce = nonce_bytes.try_into().ok()?;

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
            if !perms.share_pregnancy_tests {
                if let Some(ref n) = entry.notes {
                    let lower = n.to_lowercase();
                    if lower.contains("pregnan") || lower.contains("hcg") || lower.contains("positive") || lower.contains("negative") {
                        entry.notes = None;
                    }
                }
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

pub fn generate_random_key_internal() -> Zeroizing<Vec<u8>> {
    let mut key = [0u8; 32];
    let mut rng = OsRng;
    rand_core::RngCore::fill_bytes(&mut rng, &mut key);
    let vec = Zeroizing::new(key.to_vec());
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
        let key_str = Zeroizing::new(match env.get_string(&shared_key_base64) { Ok(s) => s.into(), Err(_) => "".to_string() });
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
        let key_str = Zeroizing::new(match env.get_string(&shared_key_base64) { Ok(s) => s.into(), Err(_) => "".to_string() });
        use base64::{Engine as _, engine::general_purpose};
        let secret_key = Zeroizing::new(general_purpose::STANDARD.decode(&*key_str).unwrap_or_default());
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



use serde_json::json;

pub fn calculate_next_cycle_native_internal(history_json: &str) -> String {
    let entries: Vec<SyncEntry> = serde_json::from_str(history_json).unwrap_or_default();
    let mut dates: Vec<NaiveDate> = entries
        .iter()
        .filter(|e| e.intensity > 0)
        .filter_map(|e| NaiveDate::parse_from_str(&e.date, "%Y-%m-%d").ok())
        .collect();

    dates.sort();
    dates.dedup();
    
    let empty_vec: Vec<String> = Vec::new();
    let default_response = json!({
        "expectedPeriodDays": empty_vec,
        "fertileWindow": empty_vec,
        "currentDayOfCycle": 1
    });

    if dates.is_empty() {
        return default_response.to_string();
    }

    // Group dates into distinct cycle starts (gap > 7 days = new cycle)
    // A typical period lasts 3-7 days, so any gap larger than 7 between
    // consecutive logged dates means a new menstrual cycle has started.
    let mut cycle_starts = Vec::new();
    cycle_starts.push(dates[0]);
    for i in 1..dates.len() {
        if (dates[i] - dates[i-1]).num_days() > 7 {
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

    let dates_csv = cycle_starts.iter().map(|d| d.format("%Y-%m-%d").to_string()).collect::<Vec<String>>().join(",");
    let analysis_json = analyze_cycle_history_internal(&dates_csv);
    
    let analysis: prediction_engine::CyclePrediction = serde_json::from_str(&analysis_json).unwrap_or(
        prediction_engine::CyclePrediction { estimated_length_days: 28, variance_days: 0, is_irregular: false }
    );
    

    // Calculate average period duration (consecutive dates with 1-3 day gaps)
    let mut period_durations: Vec<i32> = Vec::new();
    let mut current_duration: i32 = 1;
    for i in 1..dates.len() {
        let diff = (dates[i] - dates[i-1]).num_days() as i32;
        if diff >= 1 && diff <= 3 {
            current_duration += diff;
        } else if diff > 7 {
            period_durations.push(current_duration);
            current_duration = 1;
        }
    }
    period_durations.push(current_duration);
    
    let avg_period_duration = if !period_durations.is_empty() {
        let sum: i32 = period_durations.iter().sum();
        (sum / (period_durations.len() as i32)).clamp(3, 14)
    } else {
        5
    };

    let avg_length = analysis.estimated_length_days as i64;

    // Wrap the cycle day counter using the estimated cycle length so it
    // resets at the boundary instead of counting up forever (fixes "73 of 28").
    let raw_day = std::cmp::max(0, days_since_start) as i64;
    let current_day_of_cycle = if avg_length > 0 {
        ((raw_day % avg_length) + 1) as i32
    } else {
        1
    };

    
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
        let cycle_start = last_period_start + chrono::Duration::days(avg_length.saturating_mul(cycle_idx));
        
        for i in 0..avg_period_duration {
            expected_period_days.push((cycle_start + chrono::Duration::days(i as i64)).format("%Y-%m-%d").to_string());
        }

        let next_cycle_start = last_period_start + chrono::Duration::days(avg_length.saturating_mul(cycle_idx + 1));
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
    let key_vec = Zeroizing::new(general_purpose::STANDARD.decode(shared_key_base64).ok()?);
    if key_vec.len() != 32 { return None; }
    let mut key_arr = [0u8; 32];
    key_arr.copy_from_slice(&key_vec);
    
    let cipher = XChaCha20Poly1305::new((&key_arr).into());
    key_arr.zeroize();
    
    let mut nonce_bytes = [0u8; 24];
    let mut rng = rand_core::OsRng;
    rand_core::RngCore::fill_bytes(&mut rng, &mut nonce_bytes);
    let nonce: &XNonce = (&nonce_bytes).into();

    let mut ciphertext = cipher.encrypt(nonce, payload.as_bytes()).ok()?;
    
    let mut result = Vec::with_capacity(24 + ciphertext.len());
    result.extend_from_slice(&nonce_bytes);
    result.append(&mut ciphertext);
    
    Some(general_purpose::STANDARD.encode(&result))
}

pub fn decrypt_from_sync_internal(encrypted_base64: &str, shared_key_base64: &str) -> Option<String> {
    use base64::{Engine as _, engine::general_purpose};
    let key_vec = Zeroizing::new(general_purpose::STANDARD.decode(shared_key_base64).ok()?);
    if key_vec.len() != 32 { return None; }
    let mut key_arr = [0u8; 32];
    key_arr.copy_from_slice(&key_vec);
    
    let encrypted_data = general_purpose::STANDARD.decode(encrypted_base64).ok()?;
    if encrypted_data.len() < 24 { return None; }
    
    let nonce_bytes = &encrypted_data[0..24];
    let ciphertext = &encrypted_data[24..];
    
    let cipher = XChaCha20Poly1305::new((&key_arr).into());
    key_arr.zeroize();
    let nonce: &XNonce = nonce_bytes.try_into().ok()?;

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
        let key = Zeroizing::new(match env.get_string(&shared_key_base64) { Ok(s) => s.into(), Err(_) => "".to_string() });
        
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
        let key = Zeroizing::new(match env.get_string(&shared_key_base64) { Ok(s) => s.into(), Err(_) => "".to_string() });
        
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

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{Duration, Local, NaiveDate};
    use serde_json::Value;
    use base64::{Engine as _, engine::general_purpose};

    static TEST_MUTEX: Mutex<()> = Mutex::new(());

    // =========================================================================
    // BASELINE REGRESSION TESTS
    // =========================================================================

    #[test]
    fn test_phase_logic() {
        let today = NaiveDate::from_ymd_opt(2024, 1, 15).unwrap();
        assert_eq!(calculate_phase_internal("2024-01-15", 28, today), "Menstrual");
        assert_eq!(calculate_phase_internal("2024-01-10", 28, today), "Follicular");
        assert_eq!(calculate_phase_internal("2024-01-02", 28, today), "Ovulation");
        assert_eq!(calculate_phase_internal("2023-12-27", 28, today), "Luteal");
        assert_eq!(calculate_phase_internal("2024-01-20", 28, today), "Upcoming");
    }

    #[test]
    fn test_phase_logic_dynamic_cycles() {
        let today = NaiveDate::from_ymd_opt(2024, 2, 1).unwrap();
        // 35-day cycle: start = 2024-01-01 (day 31) -> Luteal
        assert_eq!(calculate_phase_internal("2024-01-01", 35, today), "Luteal");
        // Ovulation center for 35 days = 35 - 14 = 21. Day 20 or 21:
        // Today - 21 days = 2024-01-11
        assert_eq!(calculate_phase_internal("2024-01-11", 35, today), "Ovulation");
        // Follicular: Day 10 (today - 10 = 2024-01-22)
        assert_eq!(calculate_phase_internal("2024-01-22", 35, today), "Follicular");
        // Menstrual: Day 2 (today - 2 = 2024-01-30)
        assert_eq!(calculate_phase_internal("2024-01-30", 35, today), "Menstrual");
    }

    #[test]
    fn test_analyze_history_cycle_start_grouping() {
        let dates_csv = "2024-01-01,2024-01-02,2024-01-03,2024-01-29,2024-01-30,2024-01-31,2024-02-26,2024-02-27";
        let res_json = analyze_cycle_history_internal(dates_csv);
        let pred: prediction_engine::CyclePrediction = serde_json::from_str(&res_json).unwrap();
        assert_eq!(pred.estimated_length_days, 28);
        assert!(!pred.is_irregular);
    }

    #[test]
    fn test_responsive_1_2_cycle_history() {
        let one_cycle = vec![30];
        let p1 = prediction_engine::predict_irregular_cycle(&one_cycle);
        assert_eq!(p1.estimated_length_days, 30);
        assert!(!p1.is_irregular);

        let two_regular = vec![28, 30];
        let p2 = prediction_engine::predict_irregular_cycle(&two_regular);
        assert_eq!(p2.estimated_length_days, 29);
        assert!(!p2.is_irregular);

        let two_irregular = vec![24, 34];
        let p3 = prediction_engine::predict_irregular_cycle(&two_irregular);
        assert_eq!(p3.estimated_length_days, 29);
        assert!(p3.is_irregular);
    }

    #[test]
    fn test_dedup_and_gap_handling_in_next_cycle() {
        let history = serde_json::json!([
            {"date": "2024-01-01", "intensity": 2, "notes": null},
            {"date": "2024-01-01", "intensity": 3, "notes": null},
            {"date": "2024-01-03", "intensity": 2, "notes": null},
            {"date": "2024-01-05", "intensity": 1, "notes": null},
            {"date": "2024-01-29", "intensity": 3, "notes": null},
            {"date": "2024-01-30", "intensity": 2, "notes": null}
        ]).to_string();

        let result = calculate_next_cycle_native_internal(&history);
        assert!(result.contains("expectedPeriodDays"));
    }

    #[test]
    fn test_pregnancy_test_redaction() {
        let _lock = TEST_MUTEX.lock().unwrap();
        let perms = PartnerPermissions {
            share_cycle_phase: true,
            share_flow_intensity: true,
            share_symptoms_moods: true,
            share_birth_control: true,
            share_pregnancy_tests: false,
            share_sexual_activity: true,
        };
        save_partner_permissions_internal(perms);
        let entries_json = serde_json::json!([
            {"date": "2024-01-01", "intensity": 2, "notes": "Took a pregnancy test, positive hCG", "sexual_relations": null, "pain_level": null, "symptoms": null}
        ]).to_string();
        let key_bytes = [1u8; 32];
        let key_b64 = general_purpose::STANDARD.encode(key_bytes);
        let payload = generate_filtered_payload_internal(100, None, entries_json, "partner1", &key_b64);
        let decrypted = decrypt_payload_internal(&payload, "partner1", &key_bytes).unwrap();
        assert!(!decrypted.contains("pregnancy"));
        assert!(!decrypted.contains("positive"));
    }

    // =========================================================================
    // SCENARIO A: Regular Cycle User Simulation
    // =========================================================================

    #[test]
    fn test_scenario_a_regular_cycle_1_2_4_cycles() {
        // 1 cycle (2 periods logged 28 days apart, 5 days each)
        let p1 = "2026-01-01,2026-01-02,2026-01-03,2026-01-04,2026-01-05";
        let p2 = "2026-01-29,2026-01-30,2026-01-31,2026-02-01,2026-02-02";
        let csv_1_cycle = format!("{},{}", p1, p2);
        let res_1: prediction_engine::CyclePrediction =
            serde_json::from_str(&analyze_cycle_history_internal(&csv_1_cycle)).unwrap();
        assert_eq!(res_1.estimated_length_days, 28, "1 cycle should estimate 28 days");
        assert!(!res_1.is_irregular, "1 cycle 28 days should not be irregular");

        // 2 cycles (3 periods logged 28 days apart)
        let p3 = "2026-02-26,2026-02-27,2026-02-28,2026-03-01,2026-03-02";
        let csv_2_cycles = format!("{},{},{}", p1, p2, p3);
        let res_2: prediction_engine::CyclePrediction =
            serde_json::from_str(&analyze_cycle_history_internal(&csv_2_cycles)).unwrap();
        assert_eq!(res_2.estimated_length_days, 28, "2 cycles should estimate 28 days");
        assert!(!res_2.is_irregular, "2 cycles 28 days should not be irregular");

        // 4 cycles (5 periods logged 28 days apart, each 5 days long)
        let p4 = "2026-03-26,2026-03-27,2026-03-28,2026-03-29,2026-03-30";
        let p5 = "2026-04-23,2026-04-24,2026-04-25,2026-04-26,2026-04-27";
        let csv_4_cycles = format!("{},{},{},{},{}", p1, p2, p3, p4, p5);
        let res_4: prediction_engine::CyclePrediction =
            serde_json::from_str(&analyze_cycle_history_internal(&csv_4_cycles)).unwrap();
        assert_eq!(res_4.estimated_length_days, 28, "4 cycles should estimate 28 days");
        assert!(!res_4.is_irregular, "4 cycles 28 days should not be irregular");
        assert_eq!(res_4.variance_days, 0, "Variance for identical 28-day intervals should be 0");
    }

    #[test]
    fn test_scenario_a_calculate_next_cycle_regular() {
        let history = serde_json::json!([
            {"date": "2026-01-01", "intensity": 3, "notes": null},
            {"date": "2026-01-02", "intensity": 3, "notes": null},
            {"date": "2026-01-03", "intensity": 2, "notes": null},
            {"date": "2026-01-04", "intensity": 2, "notes": null},
            {"date": "2026-01-05", "intensity": 1, "notes": null},
            {"date": "2026-01-29", "intensity": 3, "notes": null},
            {"date": "2026-01-30", "intensity": 3, "notes": null},
            {"date": "2026-01-31", "intensity": 2, "notes": null},
            {"date": "2026-02-01", "intensity": 2, "notes": null},
            {"date": "2026-02-02", "intensity": 1, "notes": null}
        ]).to_string();

        let res_json = calculate_next_cycle_native_internal(&history);
        let v: Value = serde_json::from_str(&res_json).expect("Valid JSON response");

        let expected_period_days: Vec<String> = v["expectedPeriodDays"]
            .as_array()
            .unwrap()
            .iter()
            .map(|x| x.as_str().unwrap().to_string())
            .collect();
        let fertile_window: Vec<String> = v["fertileWindow"]
            .as_array()
            .unwrap()
            .iter()
            .map(|x| x.as_str().unwrap().to_string())
            .collect();
        let current_day = v["currentDayOfCycle"].as_i64().unwrap();

        // 1. Expected period days must include the past cycles:
        assert!(expected_period_days.contains(&"2026-01-01".to_string()));
        assert!(expected_period_days.contains(&"2026-01-29".to_string()));

        // 2. Expected period days must predict future cycles (28 days apart):
        // Future cycle 1: 2026-01-29 + 28 days = 2026-02-26
        assert!(expected_period_days.contains(&"2026-02-26".to_string()));
        // Future cycle 2: 2026-01-29 + 56 days = 2026-03-26
        assert!(expected_period_days.contains(&"2026-03-26".to_string()));
        // Future cycle 3: 2026-01-29 + 84 days = 2026-04-23
        assert!(expected_period_days.contains(&"2026-04-23".to_string()));

        // 3. Fertile window for 28-day cycle: ~14 days before next cycle start (-2..=+2)
        // For past cycle 1 (next start 2026-01-29): ovulation = 2026-01-15 -> window 2026-01-13..2026-01-17
        assert!(fertile_window.contains(&"2026-01-13".to_string()));
        assert!(fertile_window.contains(&"2026-01-14".to_string()));
        assert!(fertile_window.contains(&"2026-01-15".to_string()));
        assert!(fertile_window.contains(&"2026-01-16".to_string()));
        assert!(fertile_window.contains(&"2026-01-17".to_string()));

        // For future cycle 1 (starts 2026-02-26, next starts 2026-03-26):
        // Ovulation = 2026-03-26 - 14 = 2026-03-12 -> window 2026-03-10..2026-03-14
        assert!(fertile_window.contains(&"2026-03-12".to_string()));

        // 4. Current day of cycle must be in 1..=28
        assert!(current_day >= 1 && current_day <= 28, "Current day {} out of 1..=28", current_day);
    }

    #[test]
    fn test_scenario_a_calculate_phase_full_cycle() {
        let start = "2026-05-01";
        let start_date = NaiveDate::from_ymd_opt(2026, 5, 1).unwrap();
        let cycle_len = 28;

        // Days 0..4: Menstrual (Days 1 to 5 of cycle)
        for d in 0..5 {
            let today = start_date + Duration::days(d);
            assert_eq!(
                calculate_phase_internal(start, cycle_len, today),
                "Menstrual",
                "Day {} should be Menstrual", d
            );
        }

        // Days 5..12: Follicular (ovulation center = 14, current_day < 13)
        for d in 5..13 {
            let today = start_date + Duration::days(d);
            assert_eq!(
                calculate_phase_internal(start, cycle_len, today),
                "Follicular",
                "Day {} should be Follicular", d
            );
        }

        // Days 13..14: Ovulation
        for d in 13..=14 {
            let today = start_date + Duration::days(d);
            assert_eq!(
                calculate_phase_internal(start, cycle_len, today),
                "Ovulation",
                "Day {} should be Ovulation", d
            );
        }

        // Days 15..27: Luteal
        for d in 15..28 {
            let today = start_date + Duration::days(d);
            assert_eq!(
                calculate_phase_internal(start, cycle_len, today),
                "Luteal",
                "Day {} should be Luteal", d
            );
        }

        // Day 28: wraps around via modulo 28 -> Day 0 -> Menstrual
        assert_eq!(
            calculate_phase_internal(start, cycle_len, start_date + Duration::days(28)),
            "Menstrual"
        );

        // Negative days (before period start): Upcoming
        assert_eq!(
            calculate_phase_internal(start, cycle_len, start_date - Duration::days(1)),
            "Upcoming"
        );
        assert_eq!(
            calculate_phase_internal(start, cycle_len, start_date - Duration::days(10)),
            "Upcoming"
        );

        // Edge cases
        assert_eq!(calculate_phase_internal(start, 0, start_date), "Unknown");
        assert_eq!(calculate_phase_internal(start, -5, start_date), "Unknown");
        assert_eq!(calculate_phase_internal("invalid-date", cycle_len, start_date), "Invalid Date");
    }

    // =========================================================================
    // SCENARIO B: Irregular Cycle User & Real Bug Regression Simulation
    // =========================================================================

    #[test]
    fn test_scenario_b_exact_real_world_user_report() {
        // Exact real-world report:
        // Period 1: 2026-07-20 to 2026-07-23
        // Period 2: 2026-08-04 to 2026-08-07
        // Single-day entry: 2026-08-22 with intensity = 2, pain, no symptoms
        let history = serde_json::json!([
            {"date": "2026-07-20", "intensity": 2, "notes": null},
            {"date": "2026-07-21", "intensity": 2, "notes": null},
            {"date": "2026-07-22", "intensity": 2, "notes": null},
            {"date": "2026-07-23", "intensity": 1, "notes": null},
            {"date": "2026-08-04", "intensity": 3, "notes": null},
            {"date": "2026-08-05", "intensity": 2, "notes": null},
            {"date": "2026-08-06", "intensity": 2, "notes": null},
            {"date": "2026-08-07", "intensity": 1, "notes": null},
            {"date": "2026-08-22", "intensity": 2, "painLevel": 3, "symptoms": null, "notes": null}
        ]).to_string();

        let res_json = calculate_next_cycle_native_internal(&history);
        let v: Value = serde_json::from_str(&res_json).expect("Valid JSON response");
        let expected_period_days: Vec<String> = v["expectedPeriodDays"]
            .as_array()
            .unwrap()
            .iter()
            .map(|x| x.as_str().unwrap().to_string())
            .collect();

        // Verify that ALL 3 cycle starts (2026-07-20, 2026-08-04, 2026-08-22) are detected!
        assert!(
            expected_period_days.contains(&"2026-07-20".to_string()),
            "Cycle start 1 (2026-07-20) must be detected in expected periods"
        );
        assert!(
            expected_period_days.contains(&"2026-08-04".to_string()),
            "Cycle start 2 (2026-08-04) must be detected in expected periods"
        );
        assert!(
            expected_period_days.contains(&"2026-08-22".to_string()),
            "Cycle start 3 (2026-08-22) must be detected in expected periods"
        );

        // Also verify analyze_cycle_history_internal with dates_csv includes 15-day interval (July 20 to Aug 4)
        let dates_csv = "2026-07-20,2026-07-21,2026-07-22,2026-07-23,2026-08-04,2026-08-05,2026-08-06,2026-08-07,2026-08-22";
        let analysis_str = analyze_cycle_history_internal(dates_csv);
        let analysis: prediction_engine::CyclePrediction = serde_json::from_str(&analysis_str).unwrap();
        // Since intervals are [15, 18], mean is 16.5 -> 17, clamped to 21 for < 3 samples
        assert!(analysis.estimated_length_days >= 15 && analysis.estimated_length_days <= 45);
    }

    #[test]
    fn test_scenario_b_73_days_without_entry_and_modulo_wrapping() {
        let today = Local::now().date_naive();
        let last_start = today - Duration::days(73);

        let history = serde_json::json!([
            {"date": last_start.format("%Y-%m-%d").to_string(), "intensity": 3, "notes": null}
        ]).to_string();

        let res_json = calculate_next_cycle_native_internal(&history);
        let v: Value = serde_json::from_str(&res_json).expect("Valid JSON response");
        let current_day = v["currentDayOfCycle"].as_i64().unwrap() as i32;

        // Default 28 days estimated length.
        // 73 days without entry: (73 % 28) + 1 = 17 + 1 = 18.
        // It must NEVER output 73 of 28 or exceed estimated_length_days!
        assert_eq!(current_day, 18, "73 days since start should wrap to day 18 of 28");
        assert!(current_day <= 28 && current_day >= 1);

        // Test boundary wraps
        let test_offsets = [(0, 1), (27, 28), (28, 1), (56, 1), (84, 1), (100, 17)];
        for (offset, expected_day) in test_offsets {
            let date = today - Duration::days(offset);
            let h = serde_json::json!([
                {"date": date.format("%Y-%m-%d").to_string(), "intensity": 2, "notes": null}
            ]).to_string();
            let res: Value = serde_json::from_str(&calculate_next_cycle_native_internal(&h)).unwrap();
            let day = res["currentDayOfCycle"].as_i64().unwrap() as i32;
            assert_eq!(day, expected_day, "Offset {} should wrap to day {}", offset, expected_day);
        }
    }

    #[test]
    fn test_scenario_b_highly_irregular_cycles_and_dynamic_ovulation() {
        // Intervals of [22, 39, 24, 41, 27] days
        let irregular_intervals = vec![22, 39, 24, 41, 27];
        let pred = prediction_engine::predict_irregular_cycle(&irregular_intervals);
        assert!(pred.is_irregular, "Cycle with intervals [22, 39, 24, 41, 27] must be marked irregular");
        assert!(pred.estimated_length_days >= 25 && pred.estimated_length_days <= 35);
        assert!(pred.variance_days > 0);

        // Test MAD outlier clipping: extreme 85-day outlier
        let intervals_with_outlier = vec![22, 39, 24, 41, 27, 85];
        let pred_outlier = prediction_engine::predict_irregular_cycle(&intervals_with_outlier);
        assert!(pred_outlier.is_irregular);
        // Outlier 85 should be clipped by MAD threshold, preventing the estimated length from ballooning
        assert!(pred_outlier.estimated_length_days < 45, "Estimated length should be clipped by MAD, got {}", pred_outlier.estimated_length_days);

        // Dynamic Ovulation Scaling:
        // 1. Standard 28-day cycle: Ovulation center is 28 - 14 = 14
        let base_date = NaiveDate::from_ymd_opt(2026, 1, 1).unwrap();
        assert_eq!(calculate_phase_internal("2026-01-01", 28, base_date + Duration::days(14)), "Ovulation");

        // 2. 35-day cycle: Ovulation center is 35 - 14 = 21 (NOT day 14!)
        // Day 14 should be Follicular
        assert_eq!(
            calculate_phase_internal("2026-01-01", 35, base_date + Duration::days(14)),
            "Follicular",
            "Day 14 in a 35-day cycle must be Follicular, not Ovulation"
        );
        // Day 20 and Day 21 should be Ovulation
        assert_eq!(
            calculate_phase_internal("2026-01-01", 35, base_date + Duration::days(20)),
            "Ovulation",
            "Day 20 in a 35-day cycle must be Ovulation"
        );
        assert_eq!(
            calculate_phase_internal("2026-01-01", 35, base_date + Duration::days(21)),
            "Ovulation",
            "Day 21 in a 35-day cycle must be Ovulation"
        );
        // Day 25 should be Luteal
        assert_eq!(
            calculate_phase_internal("2026-01-01", 35, base_date + Duration::days(25)),
            "Luteal"
        );

        // 3. 21-day cycle: Ovulation center is max(7, 21 - 14) = 7
        assert_eq!(
            calculate_phase_internal("2026-01-01", 21, base_date + Duration::days(7)),
            "Ovulation"
        );

        // 4. 42-day cycle: Ovulation center is 42 - 14 = 28
        assert_eq!(
            calculate_phase_internal("2026-01-01", 42, base_date + Duration::days(14)),
            "Follicular"
        );
        assert_eq!(
            calculate_phase_internal("2026-01-01", 42, base_date + Duration::days(28)),
            "Ovulation"
        );
    }

    #[test]
    fn test_scenario_b_skipped_days_and_duplicate_entries_period_duration() {
        // Cycle 1: Day 1 (2026-01-01) logged twice, Day 3 (2026-01-03) logged, Day 2 skipped.
        // Cycle 2: Day 1 (2026-01-29) logged, Day 3 (2026-01-31) logged, Day 2 skipped.
        let history = serde_json::json!([
            {"date": "2026-01-01", "intensity": 2, "notes": null},
            {"date": "2026-01-01", "intensity": 3, "notes": null}, // Duplicate same-day entry
            {"date": "2026-01-03", "intensity": 2, "notes": null}, // Skipped Day 2 (gap of 2 days)
            {"date": "2026-01-29", "intensity": 2, "notes": null},
            {"date": "2026-01-31", "intensity": 2, "notes": null}  // Skipped Day 2 (gap of 2 days)
        ]).to_string();

        let res_json = calculate_next_cycle_native_internal(&history);
        let v: Value = serde_json::from_str(&res_json).expect("Valid JSON response");
        let expected_period_days: Vec<String> = v["expectedPeriodDays"]
            .as_array()
            .unwrap()
            .iter()
            .map(|x| x.as_str().unwrap().to_string())
            .collect();

        // Intra-period skipped days: duration for cycle 1 is 1 + 2 = 3 days.
        // Expected period for cycle 1 must include exactly 2026-01-01, 2026-01-02, 2026-01-03.
        assert!(expected_period_days.contains(&"2026-01-01".to_string()));
        assert!(expected_period_days.contains(&"2026-01-02".to_string()));
        assert!(expected_period_days.contains(&"2026-01-03".to_string()));
        assert!(!expected_period_days.contains(&"2026-01-04".to_string()), "Day 4 should not be in 3-day duration period");

        // Cycle 2: duration is also 3 days: 2026-01-29, 2026-01-30, 2026-01-31.
        assert!(expected_period_days.contains(&"2026-01-29".to_string()));
        assert!(expected_period_days.contains(&"2026-01-30".to_string()));
        assert!(expected_period_days.contains(&"2026-01-31".to_string()));
        assert!(!expected_period_days.contains(&"2026-02-01".to_string()));
    }

    #[test]
    fn test_scenario_b_mid_cycle_zero_flow_entries() {
        // Cycle 1: 2026-01-01 to 2026-01-04 (intensity > 0)
        // Mid-cycle: 2026-01-15 with intensity = 0 (cramps / ovulation symptom logging)
        // Cycle 2: 2026-01-29 to 2026-02-01 (intensity > 0)
        let history = serde_json::json!([
            {"date": "2026-01-01", "intensity": 2, "notes": null},
            {"date": "2026-01-02", "intensity": 2, "notes": null},
            {"date": "2026-01-03", "intensity": 2, "notes": null},
            {"date": "2026-01-04", "intensity": 1, "notes": null},
            {"date": "2026-01-15", "intensity": 0, "painLevel": 5, "symptoms": "Mid-cycle ovulation pain", "notes": "Cramps"},
            {"date": "2026-01-29", "intensity": 2, "notes": null},
            {"date": "2026-01-30", "intensity": 2, "notes": null},
            {"date": "2026-01-31", "intensity": 2, "notes": null},
            {"date": "2026-02-01", "intensity": 1, "notes": null}
        ]).to_string();

        let res_json = calculate_next_cycle_native_internal(&history);
        let v: Value = serde_json::from_str(&res_json).expect("Valid JSON response");
        let expected_period_days: Vec<String> = v["expectedPeriodDays"]
            .as_array()
            .unwrap()
            .iter()
            .map(|x| x.as_str().unwrap().to_string())
            .collect();

        // 2026-01-15 has intensity = 0, so it must NOT be counted as a period day or cycle start!
        assert!(!expected_period_days.contains(&"2026-01-15".to_string()), "Zero-flow entry must NOT be in expected period days");
        // Only 2026-01-01 and 2026-01-29 are cycle starts
        assert!(expected_period_days.contains(&"2026-01-01".to_string()));
        assert!(expected_period_days.contains(&"2026-01-29".to_string()));
    }

    // =========================================================================
    // SCENARIO C: Owner & Partner Cryptography, Granular Sharing & Redaction
    // =========================================================================

    #[test]
    fn test_scenario_c_granular_permissions_redaction() {
        let _lock = TEST_MUTEX.lock().unwrap();
        let key_bytes = [7u8; 32];
        let key_b64 = general_purpose::STANDARD.encode(key_bytes);
        let partner_id = "partner_qa_user_42";

        let sample_entries = serde_json::json!([
            {
                "date": "2026-03-01",
                "intensity": 3,
                "notes": "Took pregnancy test, positive result!",
                "sexualRelations": "Protected",
                "painLevel": 4,
                "symptoms": "Bloating and cramps"
            },
            {
                "date": "2026-03-02",
                "intensity": 0,
                "notes": "Normal day, feeling good",
                "sexualRelations": null,
                "painLevel": 0,
                "symptoms": null
            }
        ]).to_string();

        let sample_bc = serde_json::json!({
            "type": "IUD",
            "is_active": true
        }).to_string();

        // 1. All permissions TRUE: Everything remains intact
        save_partner_permissions_internal(PartnerPermissions {
            share_cycle_phase: true,
            share_flow_intensity: true,
            share_symptoms_moods: true,
            share_birth_control: true,
            share_pregnancy_tests: true,
            share_sexual_activity: true,
        });
        let payload_enc = generate_filtered_payload_internal(1000, Some(sample_bc.clone()), sample_entries.clone(), partner_id, &key_b64);
        let decrypted: SyncPayload = serde_json::from_str(&decrypt_payload_internal(&payload_enc, partner_id, &key_bytes).unwrap()).unwrap();
        assert_eq!(decrypted.last_updated, 1000);
        assert!(decrypted.birth_control.is_some());
        assert_eq!(decrypted.birth_control.unwrap().r#type, "IUD");
        assert_eq!(decrypted.entries[0].intensity, 3);
        assert_eq!(decrypted.entries[0].pain_level, Some(4));
        assert_eq!(decrypted.entries[0].symptoms, Some("Bloating and cramps".to_string()));
        assert_eq!(decrypted.entries[0].sexual_relations, Some("Protected".to_string()));
        assert!(decrypted.entries[0].notes.as_ref().unwrap().contains("positive"));

        // 2. share_flow_intensity == false: Intensities > 0 clamped to 1, 0 remains 0
        save_partner_permissions_internal(PartnerPermissions {
            share_cycle_phase: true,
            share_flow_intensity: false,
            share_symptoms_moods: true,
            share_birth_control: true,
            share_pregnancy_tests: true,
            share_sexual_activity: true,
        });
        let payload_enc = generate_filtered_payload_internal(1000, Some(sample_bc.clone()), sample_entries.clone(), partner_id, &key_b64);
        let decrypted: SyncPayload = serde_json::from_str(&decrypt_payload_internal(&payload_enc, partner_id, &key_bytes).unwrap()).unwrap();
        assert_eq!(decrypted.entries[0].intensity, 1, "Flow intensity > 0 must be clamped to 1");
        assert_eq!(decrypted.entries[1].intensity, 0, "Zero flow intensity must remain 0");

        // 3. share_symptoms_moods == false: notes, symptoms, and painLevel scrubbed
        save_partner_permissions_internal(PartnerPermissions {
            share_cycle_phase: true,
            share_flow_intensity: true,
            share_symptoms_moods: false,
            share_birth_control: true,
            share_pregnancy_tests: true,
            share_sexual_activity: true,
        });
        let payload_enc = generate_filtered_payload_internal(1000, Some(sample_bc.clone()), sample_entries.clone(), partner_id, &key_b64);
        let decrypted: SyncPayload = serde_json::from_str(&decrypt_payload_internal(&payload_enc, partner_id, &key_bytes).unwrap()).unwrap();
        assert!(decrypted.entries[0].notes.is_none(), "Notes must be scrubbed when share_symptoms_moods=false");
        assert!(decrypted.entries[0].symptoms.is_none(), "Symptoms must be scrubbed");
        assert!(decrypted.entries[0].pain_level.is_none(), "Pain level must be scrubbed");

        // 4. share_pregnancy_tests == false: Pregnancy test notes scrubbed, harmless notes kept
        save_partner_permissions_internal(PartnerPermissions {
            share_cycle_phase: true,
            share_flow_intensity: true,
            share_symptoms_moods: true,
            share_birth_control: true,
            share_pregnancy_tests: false,
            share_sexual_activity: true,
        });
        let payload_enc = generate_filtered_payload_internal(1000, Some(sample_bc.clone()), sample_entries.clone(), partner_id, &key_b64);
        let decrypted: SyncPayload = serde_json::from_str(&decrypt_payload_internal(&payload_enc, partner_id, &key_bytes).unwrap()).unwrap();
        assert!(decrypted.entries[0].notes.is_none(), "Pregnancy test note must be redacted");
        assert_eq!(decrypted.entries[1].notes, Some("Normal day, feeling good".to_string()), "Harmless note must remain");

        // 5. share_sexual_activity == false: sexualRelations scrubbed
        save_partner_permissions_internal(PartnerPermissions {
            share_cycle_phase: true,
            share_flow_intensity: true,
            share_symptoms_moods: true,
            share_birth_control: true,
            share_pregnancy_tests: true,
            share_sexual_activity: false,
        });
        let payload_enc = generate_filtered_payload_internal(1000, Some(sample_bc.clone()), sample_entries.clone(), partner_id, &key_b64);
        let decrypted: SyncPayload = serde_json::from_str(&decrypt_payload_internal(&payload_enc, partner_id, &key_bytes).unwrap()).unwrap();
        assert!(decrypted.entries[0].sexual_relations.is_none(), "Sexual activity must be redacted");

        // 6. share_birth_control == false: birth_control is None (Redacted)
        save_partner_permissions_internal(PartnerPermissions {
            share_cycle_phase: true,
            share_flow_intensity: true,
            share_symptoms_moods: true,
            share_birth_control: false,
            share_pregnancy_tests: true,
            share_sexual_activity: true,
        });
        let payload_enc = generate_filtered_payload_internal(1000, Some(sample_bc), sample_entries.clone(), partner_id, &key_b64);
        let decrypted: SyncPayload = serde_json::from_str(&decrypt_payload_internal(&payload_enc, partner_id, &key_bytes).unwrap()).unwrap();
        assert!(decrypted.birth_control.is_none(), "Birth control must be None (Redacted)");

        // 7. share_cycle_phase == false: entire entries list emptied, last_updated reset to 0
        save_partner_permissions_internal(PartnerPermissions {
            share_cycle_phase: false,
            share_flow_intensity: true,
            share_symptoms_moods: true,
            share_birth_control: true,
            share_pregnancy_tests: true,
            share_sexual_activity: true,
        });
        let payload_enc = generate_filtered_payload_internal(1000, None, sample_entries, partner_id, &key_b64);
        let decrypted: SyncPayload = serde_json::from_str(&decrypt_payload_internal(&payload_enc, partner_id, &key_bytes).unwrap()).unwrap();
        assert_eq!(decrypted.last_updated, 0);
        assert!(decrypted.entries.is_empty(), "Entries must be empty when share_cycle_phase=false");
    }

    #[test]
    fn test_scenario_c_sync_encryption_roundtrip_and_tampering() {
        let key_bytes = [0x5au8; 32];
        let key_b64 = general_purpose::STANDARD.encode(key_bytes);
        let payload = r#"{"test":"secret_cycle_data","version":1}"#;

        // 1. Valid Round-trip
        let encrypted = encrypt_for_sync_internal(payload, &key_b64).expect("Encryption should succeed");
        let decrypted = decrypt_from_sync_internal(&encrypted, &key_b64).expect("Decryption should succeed");
        assert_eq!(decrypted, payload);

        // 2. Wrong Key Failure
        let wrong_key_bytes = [0x5bu8; 32];
        let wrong_key_b64 = general_purpose::STANDARD.encode(wrong_key_bytes);
        let fail_wrong_key = decrypt_from_sync_internal(&encrypted, &wrong_key_b64);
        assert!(fail_wrong_key.is_none(), "Decryption with wrong key must fail");

        // 3. Corrupted Ciphertext (flip bits in body)
        let mut raw_bytes = general_purpose::STANDARD.decode(&encrypted).unwrap();
        let last_idx = raw_bytes.len() - 1;
        raw_bytes[last_idx] ^= 0xFF; // tamper Poly1305 MAC or ciphertext
        let tampered_b64 = general_purpose::STANDARD.encode(&raw_bytes);
        let fail_tampered = decrypt_from_sync_internal(&tampered_b64, &key_b64);
        assert!(fail_tampered.is_none(), "Decryption with tampered ciphertext must fail Poly1305 MAC");

        // 4. Truncated Ciphertext (< 24 bytes nonce)
        let truncated = general_purpose::STANDARD.encode(&raw_bytes[0..10]);
        assert!(decrypt_from_sync_internal(&truncated, &key_b64).is_none());

        // 5. Invalid Base64 strings
        assert!(decrypt_from_sync_internal("not-valid-base64!@#$", &key_b64).is_none());
        assert!(decrypt_from_sync_internal("", &key_b64).is_none());

        // 6. Invalid Key length (!= 32 bytes)
        let short_key_b64 = general_purpose::STANDARD.encode([1u8; 16]);
        assert!(encrypt_for_sync_internal(payload, &short_key_b64).is_none());
        assert!(decrypt_from_sync_internal(&encrypted, &short_key_b64).is_none());
    }

    #[test]
    fn test_scenario_c_pin_argon2id_and_biometric_auth() {
        let _lock = TEST_MUTEX.lock().unwrap();
        let pin = "1234";
        let wrong_pin = "4321";
        let salt = [0x11u8; 16];

        // 1. PIN Hashing with Argon2id
        let hash_bytes = generate_pin_hash_internal(pin);
        assert!(!hash_bytes.is_empty(), "Argon2id hash must not be empty");
        let hash_str = std::str::from_utf8(&hash_bytes).unwrap();
        assert!(hash_str.starts_with("$argon2id$"), "Hash must follow Argon2id PHC format");

        // 2. Verify and Unlock with correct PIN and derivation salt
        let success = verify_and_unlock_internal(&hash_bytes, pin, &salt);
        assert!(success, "Correct PIN must successfully unlock");
        {
            let guard = MASTER_KEY.lock().unwrap();
            assert!(guard.is_some(), "MASTER_KEY must be populated after successful unlock");
            assert_eq!(guard.as_ref().unwrap().len(), 32, "Derived key must be 32 bytes");
        }

        // 3. Verify and Unlock with WRONG PIN
        let fail = verify_and_unlock_internal(&hash_bytes, wrong_pin, &salt);
        assert!(!fail, "Wrong PIN must fail to unlock");

        // 4. Legacy derivation path (empty salt)
        let success_legacy = verify_and_unlock_internal(&hash_bytes, pin, &[]);
        assert!(success_legacy, "Empty salt must trigger backward-compatible legacy key derivation");

        // 5. Lock Database
        lock_database_internal();
        {
            let guard = MASTER_KEY.lock().unwrap();
            assert!(guard.is_none(), "MASTER_KEY must be None after lock_database_internal");
        }

        // 6. Unlock with Biometric
        let bio_key = [0xaau8; 32];
        let bio_success = unlock_with_biometric_internal(&bio_key);
        assert!(bio_success, "Valid 32-byte biometric key must succeed");
        {
            let guard = MASTER_KEY.lock().unwrap();
            assert_eq!(guard.as_ref().unwrap().as_slice(), &bio_key, "MASTER_KEY must match biometric key");
        }

        // Biometric unlock with invalid length (< 32 bytes)
        let invalid_bio_key = [0xaau8; 16];
        let bio_fail = unlock_with_biometric_internal(&invalid_bio_key);
        assert!(!bio_fail, "Invalid key length for biometric unlock must fail");

        // Lock again to clean up state
        lock_database_internal();
        assert!(MASTER_KEY.lock().unwrap().is_none());
    }
}
