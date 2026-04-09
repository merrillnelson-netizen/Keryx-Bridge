# Keryx Bridge — Installation Guide

## What this app does
Keryx Bridge runs silently in the background and relays your Google Messages to Keryx in real time. Incoming messages are captured via Android's Notification Listener service (works for SMS, MMS, and RCS). Sent SMS/MMS are captured via the SMS content provider.

---

## Step 1 — Get the APK

**Option A: GitHub Actions (recommended)**
1. Push this repo to GitHub — the workflow at `.github/workflows/android-bridge.yml` will automatically build and attach the APK to a GitHub Release.
2. Go to **Releases** in your GitHub repo and download `KeryxBridge-debug.apk`.

**Option B: Build locally with Android Studio**
1. Open the `android-bridge/` folder in Android Studio.
2. Run **Build → Build APK(s)**.
3. APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

**Option C: Build via Gradle (requires Android SDK)**
```bash
cd android-bridge
gradle wrapper --gradle-version=8.11.1   # first time only
./gradlew assembleDebug
```

---

## Step 2 — Transfer to your phone

- **USB**: Copy the APK file to your phone's Downloads folder.
- **Cloud**: Upload to Google Drive and open it on your phone.
- **Keryx**: If a build exists, download directly from your Keryx Settings page under "Android Bridge".

---

## Step 3 — Enable Unknown Sources

Your phone needs to allow apps from outside the Play Store:

1. Open the APK file on your phone.
2. If prompted, tap **Settings** → find the app you used to open the APK (Chrome, Files, etc.) → enable **Install unknown apps**.
3. Go back and tap **Install**.

On Samsung: **Settings → Apps → Special app access → Install unknown apps**

---

## Step 4 — Set up the app

1. Open **Keryx Bridge**.
2. Enter your **Keryx Server URL** (e.g., `https://your-app.replit.app`).
3. Enter your **Relay API Key** (find it in Keryx Settings → Android Bridge).
4. Tap **Save** then **Test** to verify the connection.
5. Enable the **Enable Bridge** toggle.

---

## Step 5 — Grant Notification Access (required)

1. Tap **Open Notification Access Settings** in the app (or go to **Settings → Special app access → Notification access**).
2. Find **Keryx Bridge** and enable it.
3. Confirm the prompt.

---

## Step 6 — Disable Battery Restriction (critical on Samsung)

Without this step, Samsung's aggressive battery manager will kill the bridge after a few minutes.

1. Tap **Disable Battery Restriction** in the app — this takes you directly to the battery settings.
2. Select **Unrestricted** (not "Optimized" or "Restricted").

**Manual path**: Settings → Apps → Keryx Bridge → Battery → Unrestricted

---

## What to expect after setup

- Incoming messages relay within a few seconds of the notification appearing.
- Sent SMS/MMS relay after you tap Send.
- Sent RCS is best-effort — it depends on which version of Google Messages you have.
- The bridge auto-starts after reboot.
- If your network drops, failed messages queue locally and retry automatically when connectivity returns (up to 6 attempts with exponential backoff).

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| Nothing relays | Check notification access is enabled and battery is unrestricted |
| "4xx error" in logs | Wrong API key — copy it fresh from Keryx Settings |
| Stops relaying after a few hours | Battery restriction still enabled — set to Unrestricted |
| Sent messages not relaying | READ_SMS permission may be blocked — check App permissions |
| "Network error" | Normal when offline — messages will retry when connection resumes |
