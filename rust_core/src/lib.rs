pub mod prediction_engine;
pub mod crypto;
pub mod domain;
pub mod jni_bridge;

pub use crypto::*;
pub use domain::*;
pub use jni_bridge::*;

#[cfg(test)]
mod tests {
    use super::*;
    use base64::{Engine as _, engine::general_purpose};
    use std::sync::Mutex;

    lazy_static::lazy_static! {
        static ref TEST_MUTEX: Mutex<()> = Mutex::new(());
    }

    #[test]
    fn test_argon2id_key_derivation_and_pin_hash() {
        let _lock = TEST_MUTEX.lock().unwrap_or_else(|e| e.into_inner());
        let pin = "123456";
        let hash = generate_pin_hash_internal(pin);
        assert!(!hash.is_empty(), "PIN hash must not be empty");

        let salt = b"test_salt_12345678";
        let derived1 = derive_key_argon2(pin, salt);
        assert_eq!(derived1.len(), 32, "Derived key must be 32 bytes");

        let derived2 = derive_key_argon2(pin, salt);
        assert_eq!(derived1, derived2, "Key derivation must be deterministic");

        let wrong_pin_derived = derive_key_argon2("654321", salt);
        assert_ne!(derived1, wrong_pin_derived, "Different PIN must yield different key");

        assert!(verify_and_unlock_internal(&hash, pin, salt), "Verify and unlock must succeed with correct PIN");
        
        {
            let guard = MASTER_KEY.lock().unwrap_or_else(|e| e.into_inner());
            assert!(guard.is_some(), "Master key must be populated after successful unlock");
        }

        lock_database_internal();
        {
            let guard = MASTER_KEY.lock().unwrap_or_else(|e| e.into_inner());
            assert!(guard.is_none(), "Master key must be None after locking database");
        }

        assert!(!verify_and_unlock_internal(&hash, "000000", salt), "Verify must fail with incorrect PIN");
    }

    #[test]
    fn test_ae_encryption_and_sync_domain_separation() {
        let _lock = TEST_MUTEX.lock().unwrap_or_else(|e| e.into_inner());
        let key = generate_random_key_internal();
        let key_b64 = general_purpose::STANDARD.encode(&*key);

        let payload = r#"{"lastUpdated":123456789,"entries":[]}"#;
        let encrypted = encrypt_for_sync_internal(payload, &key_b64);
        assert!(encrypted.is_some(), "Sync encryption must succeed");

        let decrypted = decrypt_from_sync_internal(&encrypted.unwrap(), &key_b64);
        assert_eq!(decrypted, Some(payload.to_string()), "Sync decryption must recover original payload");

        // Test domain separation context string "bloom_sync_payload_v1"
        let mut hasher1 = blake3::Hasher::new_derive_key("bloom_sync_payload_v1");
        hasher1.update(payload.as_bytes());
        let hash1 = hasher1.finalize();

        let mut hasher2 = blake3::Hasher::new_derive_key("bloom_sync_legacy_v1");
        hasher2.update(payload.as_bytes());
        let hash2 = hasher2.finalize();

        assert_ne!(hash1.as_bytes(), hash2.as_bytes(), "Domain separation string change must produce distinct hash");
    }

    #[test]
    fn test_partner_permissions_filtering() {
        let _lock = TEST_MUTEX.lock().unwrap_or_else(|e| e.into_inner());
        let perms = PartnerPermissions {
            share_cycle_phase: true,
            share_flow_intensity: false,
            share_symptoms_moods: false,
            share_birth_control: true,
            share_pregnancy_tests: false,
            share_sexual_activity: false,
        };
        save_partner_permissions_internal(perms);

        let key = generate_random_key_internal();
        let key_b64 = general_purpose::STANDARD.encode(&*key);

        let bc_json = r#"{"type":"Pill","isActive":true}"#;
        let entries_json = r#"[{"date":"2026-01-01","intensity":5,"notes":"secret notes","symptoms":"cramps","painLevel":4,"sexualRelations":"yes"}]"#;

        let filtered_bytes = generate_filtered_payload_with_extras_internal(
            100,
            Some(bc_json.to_string()),
            entries_json.to_string(),
            None,
            None,
            "partner1",
            &key_b64,
        );
        assert!(!filtered_bytes.is_empty());

        let decrypted_json = decrypt_payload_internal(&filtered_bytes, "partner1", &key).unwrap();
        let payload: SyncPayload = serde_json::from_str(&decrypted_json).unwrap();

        assert!(payload.birth_control.is_some());
        assert_eq!(payload.entries.len(), 1);
        // Flow intensity should be zeroed out
        assert_eq!(payload.entries[0].intensity, 0);
        // Symptoms, notes, pain level, sexual relations should be filtered out
        assert!(payload.entries[0].notes.is_none());
        assert!(payload.entries[0].symptoms.is_none());
        assert!(payload.entries[0].pain_level.is_none());
        assert!(payload.entries[0].sexual_relations.is_none());
    }

    #[test]
    fn test_oversized_and_tampered_inputs_fail_closed() {
        let _lock = TEST_MUTEX.lock().unwrap_or_else(|e| e.into_inner());
        let valid_key = generate_random_key_internal();
        let valid_key_b64 = general_purpose::STANDARD.encode(&*valid_key);
        let oversized_str = "A".repeat(MAX_JNI_INPUT_BYTES + 100);
        let oversized_bytes = vec![0u8; MAX_JNI_INPUT_BYTES + 100];

        assert!(encrypt_data(&oversized_str, "partner", &valid_key).is_empty());
        assert!(decrypt_payload_internal(&oversized_bytes, "partner", &valid_key).is_none());
        assert!(encrypt_for_sync_internal(&oversized_str, &valid_key_b64).is_none());
        assert!(decrypt_from_sync_internal(&oversized_str, &valid_key_b64).is_none());
    }
}
