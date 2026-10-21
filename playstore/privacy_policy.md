# Privacy Policy

**Effective Date:** October 2026

At Bloom ("we", "us", "our"), your privacy is our highest priority. This Privacy Policy explains how Bloom (the "App", package `com.novarytm.android`) handles your personal information. We built Bloom from the ground up to ensure that your sensitive health data remains exclusively under your control.

## 1. Zero Cloud Architecture
Bloom operates on a **zero cloud** architecture. We do not own, rent, or operate any servers that store, process, or transmit your personal data, health data, or usage data. **Your data is never sent to us, nor is it accessible to us or any third parties.**

## 2. Information We Process (Locally)
To provide its core features (cycle tracking, symptom logging, and predictions), Bloom processes the following data directly on your device:
- **Health & Cycle Data:** Menstrual cycle dates, symptoms (pain, flow, etc.), sexual activity, and personal notes.
- **Biometric Data:** Fingerprint or facial recognition data, handled entirely by the Android OS (Android Keystore) for app unlocking.

## 3. End-to-End Encryption & Security
All data stored within Bloom is strongly encrypted:
- **Local Storage:** The local database is encrypted using SQLCipher.
- **Cryptography:** We use XChaCha20-Poly1305 for data encryption, Argon2id for PIN hashing, and BLAKE3 for key derivation. Your master encryption key is wrapped securely by the Android Keystore (AES-256-GCM).

## 4. Google Drive Sync & Partner Pairing
Bloom offers an optional backup and sync feature, including a "Partner Pairing" function to share specific cycle information with a partner.
- **Personal Cloud Only:** This sync relies entirely on your own personal Google Drive account.
- **End-to-End Encrypted Sync:** Data synced to Google Drive is end-to-end encrypted before it leaves your device. We cannot read, access, or decrypt any data stored in your Google Drive.
- **Network Permissions:** The App requires `INTERNET` and `ACCESS_NETWORK_STATE` permissions strictly for communicating with the Google Drive API.

## 5. Third-Party Tracking & Ads
Bloom contains **no advertising SDKs, no tracking SDKs (like Google Analytics), and no crash reporting software**. We do not monetize your data, sell it, or share it with anyone.

## 6. App Permissions
Bloom may request the following permissions on your Android device:
- **Notifications & Alarms (`POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`):** Used locally to send you timely reminders for periods, pills, or appointments.
- **Biometrics:** Used strictly to unlock the app if you enable this feature.

## 7. Data Deletion
Because your data is only stored on your device and your personal Google Drive, you have complete control over data deletion. You can delete all your data at any time by:
- Using the "Delete All Data" option within the App settings.
- Uninstalling the App.
- Deleting the Bloom backup file directly from your Google Drive.

## 8. GDPR & CCPA Compliance
Since we do not collect, process, or store your personal data on our servers, you inherently exercise full control over your data. Requests regarding access, rectification, or erasure of your data should be handled directly within the App, as we have no technical means to access your information.

## 9. Changes to this Policy
We may update this Privacy Policy from time to time. Any changes will be reflected in the App and on our store listing, with an updated "Effective Date".

## 10. Contact Us
If you have any questions or concerns about this Privacy Policy or Bloom's security practices, please contact us at:
**Email:** [INSERT_EMAIL]
