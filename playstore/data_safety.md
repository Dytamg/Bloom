# Data Safety Form Answers

*Use this guide when filling out the Data Safety section in the Google Play Console for Bloom.*

### 1. Data Collection and Security
**Q: Does your app collect or share any of the required user data types?**
**A:** No.
*Note: Because Bloom sends data ONLY to the user's personal Google Drive using End-to-End Encryption, and the developer has NO access to this data, Google Play policies generally classify this as "not collecting" user data. If Play Console review explicitly forces a "Yes", specify that data is collected but NEVER shared, and fully encrypted.*

**Q: Is all of the user data collected by your app encrypted in transit?**
**A:** Yes. (All Google Drive traffic is over HTTPS, plus the payload is E2E encrypted).

**Q: Do you provide a way for users to request that their data be deleted?**
**A:** Yes. (Users can delete all app data locally and via their personal Google Drive).

### 2. Data Types
*(If forced to declare data types due to the app's functionality or if you opt to be hyper-transparent despite E2E encryption)*

**Health & Fitness**
- **Health info:** Not collected by developer (Processed locally / User's GDrive).
- **Fitness info:** Not collected.

**Personal Info**
- **Name/Email:** Not collected.

**Files and Docs**
- **Files or docs:** Not collected by developer.

**App Activity**
- **Interactions, Crash logs, Diagnostics:** Not collected. (No analytics SDKs).

### 3. Data Usage and Handling
If declaring data types, for each data type:
- **Is this data collected, shared, or both?** Collected (if strictly required by Google's interpretation of cloud sync), never shared.
- **Is this data processed ephemerally?** No.
- **Is this data required for your app, or can users choose whether it's collected?** Users can choose (Google Drive sync is optional).
- **Why is this user data collected?** App Functionality.

### 4. Security Practices
- **Security practices:** Check the boxes for:
  - Data is encrypted in transit.
  - You provide a way for users to request that their data be deleted.
- **Independent Security Review:** Unchecked (unless you have undergone a third-party MASVS audit).
