# TV Recorder

Android TV app that records the TV screen (and the TV's sound) to MP4, with a built-in web page so you can watch, download and delete recordings from your phone.

## Use it

1. Open **TV Recorder** on the TV → **● Start recording** → allow the screen-capture prompt.
2. The app steps aside; go to whatever you want to record.
3. To stop: open TV Recorder again, or press **Stop** on the phone page.
4. On your phone, scan the QR code on the TV (same Wi-Fi). You can play, download or delete recordings there. If you type the address instead, enter the code shown under the QR.

Options on the TV: quality (1080p / 720p), TV sound on/off, auto-stop timer (15 min – 3 h).

## Good to know

- **Protected apps record black.** Netflix, Prime Video, Disney+ and similar block screen capture (DRM). Normal apps, live TV apps, menus and games record fine.
- **Sound** needs Android 10+, and an app can opt out of having its audio captured — then you'll get video with no sound.
- Recordings are stored inside the app (`Android/data/com.ahusan.tvrecorder/files/Movies`). **Uninstalling the app deletes them** — download anything you want to keep first.
- ~1080p uses about 75 MB per minute; 720p about 38 MB per minute.
- Some TV models don't allow screen-capture apps at all; the app will say so.

## Build

Push to `main` → GitHub Actions builds `TVRecorder.apk` and publishes it to Releases.
