# URL Opener (Android)

A tiny utility app that opens **long URLs** and **shorturl.at short URLs** inside the app of your choice.

## Features
- **Step 1 – App picker**: a spinner at the top lists every app that can open links (Chrome, Firefox, Foodora, ...). Pick one once — it is remembered. "Ask every time" uses the standard Android chooser.
- **Step 2 – Long URL field**: paste any long URL (with `?`/`&` parameters, like your foodora verify-email link). "Paste" button, "Clear" button, and a **File picker** that loads a URL (or a multi-line list of URLs) from a `.txt` file.
- **Short URL tab**: paste just the value after `https://shorturl.at/` (e.g. `6UcjW`) — the app builds `https://shorturl.at/6UcjW` and opens it. A full short URL pasted in also works.
- **History**: last 50 opened links, exportable to a `.txt` file, with on/off toggle.
- **Extras**: the app also appears in the Android **Share** sheet (share a link from any app → URL Opener), and can be set as default browser so tapped links open in it.

## Build the APK — two options

### Option A – GitHub Actions (easiest, no software to install)
1. Create a free GitHub account / repo and upload this whole folder (keep the folder structure).
2. The included workflow `.github/workflows/build-apk.yml` runs automatically.
3. Open the repo → **Actions** tab → latest run → download the **urlopener-apk** artifact → inside is `app-debug.apk`.
4. Copy to your phone, enable "Install unknown apps" for your file manager, install.

### Option B – Android Studio (on your PC)
1. Install Android Studio (free).
2. File → Open → select this `UrlOpener` folder.
3. Let it sync/gradle download, then menu **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
4. The APK appears in `app/build/outputs/apk/debug/app-debug.apk`. Transfer to phone and install.

Requires Java 17 (Android Studio bundles it).

## Notes
- Package name: `com.example.urlopener` (change in `app/build.gradle` if you want).
- Min Android: 7.0 (API 24). Target: Android 14 (API 34).
- Debug APK is installable as-is; for Play Store you would need a signed release build.
