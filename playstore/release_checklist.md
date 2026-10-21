# Google Play Console Open Beta Release Checklist

Follow this step-by-step checklist to successfully publish Bloom to the Open Testing track on Google Play.

## 1. Initial Setup
- [ ] Create a Google Play Developer account and pay the registration fee.
- [ ] Verify your identity and developer account details.
- [ ] In Play Console, click **Create App**.
  - App Name: `Bloom - Period Tracker`
  - Default Language: English (US)
  - App or Game: App
  - Free or Paid: Free
  - Accept the Developer Program Policies and US export laws.

## 2. Store Listing (Main)
- [ ] Navigate to **Store presence > Main store listing**.
- [ ] Add the **App Title**, **Short Description**, and **Full Description** from `store_listing.md`.
- [ ] Upload High-Res Icon (512x512px, PNG/JPEG).
- [ ] Upload Feature Graphic (1024x500px, PNG/JPEG).
- [ ] Upload Phone Screenshots (At least 2, highlighting privacy, tracking, and predictions).

## 3. App Content & Declarations
Navigate to **Policy > App content** and complete the following:
- [ ] **Privacy Policy:** Add the URL to where your privacy policy is hosted (you can host `privacy_policy.md` on a GitHub Pages site or personal domain).
- [ ] **Ads:** Select "No, my app does not contain ads".
- [ ] **App Access:** Select "All functionality is available without special access" (or provide dummy test credentials if reviewers need a PIN to test the app).
- [ ] **Content Rating:** Fill out the IARC questionnaire using `content_rating.md`.
- [ ] **Target Audience and Content:** Select ages 13+ (do not select under 13 to avoid COPPA/Families policy restrictions).
- [ ] **News Apps:** Select "No".
- [ ] **COVID-19:** Select "My app is not a publicly available COVID-19 contact tracing or status app".
- [ ] **Data Safety:** Complete the form using the guide in `data_safety.md`.
- [ ] **Financial Features:** Declare that the app does not provide financial features.
- [ ] **Health Apps:** Declare the app as a Health App and confirm it complies with health app policies.

## 4. App Setup & Pricing
- [ ] Navigate to **Store presence > Store settings**.
  - Category: Health & Fitness
  - Tags: Period Tracker, Women's Health, etc.
  - Contact Details: Add support email (`[INSERT_EMAIL]`).
- [ ] Navigate to **Monetization > App pricing**.
  - Ensure the app is set to "Free".

## 5. Release Track Selection
- [ ] Navigate to **Testing > Open testing**.
- [ ] Click **Create new release**.
- [ ] Opt-in to Play App Signing (highly recommended).
- [ ] Upload your signed AAB (Android App Bundle).
- [ ] Add Release Notes:
  ```text
  Welcome to the Bloom Open Beta!
  Features included:
  - Private, E2E encrypted cycle tracking
  - AI predictions & symptom logging
  - Local reminders and biometric lock
  - Google Drive backup sync
  ```
- [ ] Save the release.

## 6. Review and Rollout
- [ ] Click **Review release**.
- [ ] Address any errors or warnings shown by the console.
- [ ] Click **Start rollout to Open testing**.
- [ ] Wait for Google's review. Initial review for new accounts/apps can take up to 7 days. Subsequent updates are usually faster (under 24 hours).
