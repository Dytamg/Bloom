use argon2::{
    Argon2, PasswordHasher, PasswordVerifier,
    password_hash::phc::PasswordHash,
};
use lazy_static::lazy_static;
use rand_core::{OsRng, RngCore};
use std::sync::Mutex;
use zeroize::{Zeroize, Zeroizing};
use chacha20poly1305::{
    aead::{Aead, KeyInit},
    XChaCha20Poly1305, XNonce,
};
use base64::{Engine as _, engine::general_purpose};

pub const MAX_JNI_INPUT_BYTES: usize = 1024 * 1024; // 1MB upper bound

pub struct CoreVault {
    pub master_key: Zeroizing<Vec<u8>>,
}

lazy_static! {
    pub static ref MASTER_KEY: Mutex<Option<Zeroizing<Vec<u8>>>> = Mutex::new(None);
}

pub fn generate_pin_hash_internal(pin: &str) -> Vec<u8> {
    if pin.len() > 1024 {
        return vec![];
    }
    let argon2 = Argon2::default();
    match argon2.hash_password(pin.as_bytes()) {
        Ok(hash) => hash.to_string().into_bytes(),
        Err(_) => vec![],
    }
}

pub fn verify_and_unlock_internal(pin_hash: &[u8], input_pin: &str, derivation_salt: &[u8]) -> bool {
    if pin_hash.len() > MAX_JNI_INPUT_BYTES || input_pin.len() > 1024 || derivation_salt.len() > 256 {
        return false;
    }
    let hash_str = match std::str::from_utf8(pin_hash) {
        Ok(s) => s,
        Err(_) => return false,
    };
    let parsed_hash = match PasswordHash::new(hash_str) {
        Ok(h) => h,
        Err(_) => return false,
    };
    let argon2 = Argon2::default();
    if argon2.verify_password(input_pin.as_bytes(), &parsed_hash).is_ok() {
        let derived = derive_key_argon2(input_pin, derivation_salt);
        if !derived.is_empty() {
            let mut guard = match MASTER_KEY.lock() {
                Ok(g) => g,
                Err(e) => e.into_inner(),
            };
            *guard = Some(Zeroizing::new(derived));
            return true;
        }
    }
    false
}

pub fn derive_key_argon2(pin: &str, salt: &[u8]) -> Vec<u8> {
    if pin.len() > 1024 || salt.is_empty() || salt.len() > 256 {
        return vec![];
    }
    let mut output = vec![0u8; 32];
    let argon2 = Argon2::default();
    if argon2.hash_password_into(pin.as_bytes(), salt, &mut output).is_ok() {
        output
    } else {
        vec![]
    }
}

pub fn unlock_with_biometric_internal(key_bytes: &[u8]) -> bool {
    if key_bytes.len() != 32 {
        return false;
    }
    let mut guard = match MASTER_KEY.lock() {
        Ok(g) => g,
        Err(e) => e.into_inner(),
    };
    *guard = Some(Zeroizing::new(key_bytes.to_vec()));
    true
}

pub fn lock_database_internal() {
    let mut guard = match MASTER_KEY.lock() {
        Ok(g) => g,
        Err(e) => e.into_inner(),
    };
    if let Some(mut key) = guard.take() {
        key.zeroize();
    }
    *guard = None;
}

pub fn encrypt_data(data: &str, id: &str, secret_key: &[u8]) -> Vec<u8> {
    if data.len() > MAX_JNI_INPUT_BYTES || id.len() > 256 || secret_key.len() != 32 {
        return vec![];
    }
    let cipher = match XChaCha20Poly1305::new_from_slice(secret_key) {
        Ok(c) => c,
        Err(_) => return vec![],
    };
    let mut nonce_bytes = [0u8; 24];
    OsRng.fill_bytes(&mut nonce_bytes);
    let nonce = XNonce::try_from(nonce_bytes.as_slice()).unwrap_or_else(|_| XNonce::default());

    let mut combined_plaintext = Vec::with_capacity(id.len() + 1 + data.len());
    combined_plaintext.extend_from_slice(id.as_bytes());
    combined_plaintext.push(b':');
    combined_plaintext.extend_from_slice(data.as_bytes());

    match cipher.encrypt(&nonce, combined_plaintext.as_ref()) {
        Ok(ciphertext) => {
            let mut result = Vec::with_capacity(24 + ciphertext.len());
            result.extend_from_slice(&nonce_bytes);
            result.extend_from_slice(&ciphertext);
            result
        }
        Err(_) => vec![],
    }
}

pub fn decrypt_payload_internal(encrypted_data: &[u8], id: &str, secret_key: &[u8]) -> Option<String> {
    if encrypted_data.len() < 24 || encrypted_data.len() > MAX_JNI_INPUT_BYTES + 24 || id.len() > 256 || secret_key.len() != 32 {
        return None;
    }
    let cipher = XChaCha20Poly1305::new_from_slice(secret_key).ok()?;
    let nonce = XNonce::try_from(&encrypted_data[0..24]).ok()?;
    let ciphertext = &encrypted_data[24..];

    let decrypted = cipher.decrypt(&nonce, ciphertext).ok()?;
    let expected_prefix = format!("{}:", id);
    if !decrypted.starts_with(expected_prefix.as_bytes()) {
        return None;
    }
    let payload_bytes = &decrypted[expected_prefix.len()..];
    String::from_utf8(payload_bytes.to_vec()).ok()
}

pub fn generate_random_key_internal() -> Zeroizing<Vec<u8>> {
    let mut key = vec![0u8; 32];
    OsRng.fill_bytes(&mut key);
    Zeroizing::new(key)
}

pub fn encrypt_for_sync_internal(payload: &str, shared_key_base64: &str) -> Option<String> {
    if payload.len() > MAX_JNI_INPUT_BYTES || shared_key_base64.len() > 256 {
        return None;
    }
    let key_vec = Zeroizing::new(general_purpose::STANDARD.decode(shared_key_base64).ok()?);
    if key_vec.len() != 32 {
        return None;
    }
    let mut key_arr = [0u8; 32];
    key_arr.copy_from_slice(&key_vec);

    let cipher = XChaCha20Poly1305::new((&key_arr).into());
    key_arr.zeroize();

    let mut nonce_bytes = [0u8; 24];
    OsRng.fill_bytes(&mut nonce_bytes);
    let nonce = XNonce::try_from(nonce_bytes.as_slice()).ok()?;

    let mut hasher = blake3::Hasher::new_derive_key("bloom_sync_payload_v1");
    hasher.update(payload.as_bytes());
    let _domain_hash = hasher.finalize();

    let ciphertext = cipher.encrypt(&nonce, payload.as_bytes()).ok()?;

    let mut result = Vec::with_capacity(24 + ciphertext.len());
    result.extend_from_slice(&nonce_bytes);
    result.extend_from_slice(&ciphertext);
    Some(general_purpose::STANDARD.encode(&result))
}

pub fn decrypt_from_sync_internal(encrypted_base64: &str, shared_key_base64: &str) -> Option<String> {
    if encrypted_base64.len() > MAX_JNI_INPUT_BYTES || shared_key_base64.len() > 256 {
        return None;
    }
    let decoded = general_purpose::STANDARD.decode(encrypted_base64).ok()?;
    if decoded.len() < 24 {
        return None;
    }
    let key_vec = Zeroizing::new(general_purpose::STANDARD.decode(shared_key_base64).ok()?);
    if key_vec.len() != 32 {
        return None;
    }
    let mut key_arr = [0u8; 32];
    key_arr.copy_from_slice(&key_vec);

    let cipher = XChaCha20Poly1305::new((&key_arr).into());
    key_arr.zeroize();

    let nonce = XNonce::try_from(&decoded[0..24]).ok()?;
    let ciphertext = &decoded[24..];

    let decrypted = cipher.decrypt(&nonce, ciphertext).ok()?;
    String::from_utf8(decrypted).ok()
}
