# Updates

CapCraft has no store behind it, so it carries its own updater: Settings → **Check for updates**
reads `version.json` from this repository over HTTPS and compares it with the build that is
running.

## What the app does

1. **Check** — fetches `version.json` (the constant lives in `Updates.MANIFEST_URL`).
2. **Compare** — a build is offered only when its `versionCode` is higher than the installed one.
3. **Show** — the version, its release date, and every line of `notes` (the changelog).
4. **Download** — streams the APK to `cacheDir/updates/`, showing bytes done against bytes
   promised, a percentage, and a **Cancel** that stops it and leaves nothing behind.
5. **Verify** — the finished file must match `sizeBytes` and `sha256`; a mismatch is deleted and
   reported, never installed.
6. **Install** — hands the file to the system's installer through the app's `FileProvider`. The
   phone may ask the user to allow installing unknown apps the first time.

Nothing is uploaded, and nothing is installed without the user tapping Install.

## `version.json`

Written by `scripts/release/version-json.py` at release time, never by hand:

```sh
python3 scripts/release/version-json.py \
  --version 0.0.9-beta \
  --version-code 901 \
  --apk build/capcraft-android-0.0.9-beta-aosp.apk \
  --url https://github.com/dwicao/capcraft-android/releases/download/v0.0.9-beta/capcraft-android-0.0.9-beta-aosp.apk \
  --note "Thumbnail langsung muncul saat project dibuka" \
  --note "Settings → Check for updates"
```

| field | meaning |
| --- | --- |
| `version` | the version string the build carries |
| `versionCode` | the build's **base** code — the number in `gradle.properties`, not the packaged one (the build adds the ABI digit: packaged = base × 10 + abi; the app compares against its own code ÷ 10) |
| `apkUrl` | where the signed APK is published — must be `https` |
| `sizeBytes` | the file's size, measured by the script |
| `sha256` | the file's digest, measured by the script |
| `releasedAt` | release date |
| `notes` | the changelog lines the app shows |

## Release checklist

1. Bump `capcraft.version` and `capcraft.versionCode` in
   `src/crates/capcraft-android/android/gradle.properties`.
2. Build and sign the APK (CI's `release-apk` artifact, signed with the release key).
3. Publish the GitHub release with the APK attached.
4. Run `scripts/release/version-json.py` against that APK and its released URL, then commit
   `version.json` — this is the moment every installed copy starts seeing the update.

Step 4 is last on purpose: commit it too early and users are offered a download that does not
exist yet.