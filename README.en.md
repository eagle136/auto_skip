# auto_skip · Splash Text Auto-Tap

**The author does not recommend anyone use this project.**

It does exactly one thing: during the one or two seconds an app takes to launch, it looks for text
on screen that reads "Skip" or "Close", and taps it.

That buys the user nothing of real value, and it costs the app publisher a real impression that
would otherwise have happened. So what is published here is not a piece of software worth
installing — it is a technical sample: how far you can push **screen capture throughput, small-text
detection, and accessibility event timing** on Android. The value is in the method, not the
behavior.

If you came looking for a one-tap "remove the splash screen" tool, close this page.

Android 15+ (`minSdk 35`, `targetSdk 37`), arm64 only. Developed and verified on a realme GT8 Pro
(ColorOS / Android 16, 1080×2352).

---

## What it actually does

It finds short text at specific screen regions and taps it when it matches. That is the whole
feature. It does not intercept network traffic, modify other apps, read other apps' data, or upload
anything: capture, scoring, and decisions all happen in local memory and vanish when the process
ends.

Matching is not keyword-based. A 170 KB convolutional network scores each candidate box
(threshold 0.90). The text content itself is never an input — what decides is glyph shape, ink
density, aspect ratio, and local context. That is why it works across font sizes, languages, and
backgrounds.

## Two paths

The two are mutually exclusive: only one tap per frame is allowed, and whichever path fired holds
the other off for 1.5–2s so a single trigger cannot tap twice.

| Path | Trigger | Signal | Fits |
|---|---|---|---|
| **Node path** (accessibility) | `TYPE_WINDOW_STATE_CHANGED` | Walks `AccessibilityNodeInfo`, matches text/description | Native widgets; cheapest and most precise |
| **Vision path** (CornerTask) | same | Capture → banded proposals → SkipNet scoring → tap | Custom-drawn buttons, WebView content, text over artwork |

The vision path end to end:

```
event → open session → raw-frame capture (screencap, ~210ms) → half-resolution luma
      → banded tiles (8 hot tiles, full band every 6 frames)
      → proposals (connected components + hot-region top-hat)
      → SkipNet score (threshold 0.90)
      → BlobGate rejects solid blocks
      → title-bar guard (180ms budget, fail-open)
      → jitter → gestureTap
```

Cold start to tap lands at roughly **1.0–1.5s**, including ~520ms the system spends delivering the
event before any of this runs.

## What is actually worth reading

The engineering time went into throughput and robustness trade-offs, none of which are about the
skip behavior:

- **Capture uses `screencap` raw frames** — BGRA8888 piped straight out of stdout. `screencap -p`
  (PNG) measured 0.96–1.10s; the raw frame is 210ms. That 5× is where the whole time budget comes
  from.
- **Inference is a hand-written forward pass in pure Kotlin** (3 conv layers + pool), 170 KB of
  weights, no inference framework, no native dependency. First cold inference: 25–40ms.
- **Two complementary proposal passes.** Connected components handle buttons with a filled
  background. Hot-region top-hat (`lum − min7x7(lum)`) handles text printed directly onto artwork:
  any bright band wider than 7px is flattened by the min filter while 3–5px strokes survive intact,
  which incidentally makes it immune to border-touching components being discarded wholesale.
- **One tap per trigger**, then a 1.2s sleep, no verification and no retry. A retry lands on the
  next page — that is a measured conclusion, not a guess.
- **No persistent overlay.** `SYSTEM_ALERT_WINDOW` is only used for a full-screen debug canvas
  after the user explicitly enables it; otherwise no window exists. Some game anti-cheat treats an
  invisible overlay as cheating.
- **Small-box guards are tiered by screen position** (top-right 90×26 / rest of top half 140×46 /
  bottom half 60×24). The tiers split on height, not width: every real target measured falls in the
  3%–8.5% height band, the one false positive sat at 12.8%, and the width difference was 4px —
  unseparable.

## Permissions and modes

Scanning needs nothing beyond the accessibility service. Capture and self-healing need more, in one
of two tiers:

| Mode | Requires | Gets you |
|---|---|---|
| Accessibility | Service enabled in system settings | Node path + `takeScreenshot` |
| Root | su (Magisk / KernelSU / APatch) | Above + `screencap` raw frames + `WRITE_SECURE_SETTINGS` |
| Shizuku | Shizuku service | Same as Root, over `IRemoteProcess` |

## Signing and package name: different on every release

This project **does not maintain a fixed identity**.

- `signing/open-release.jks` in the repo is a **one-off random key** with its password written in
  plaintext in `signing/open-release.properties`. It is committed on purpose. It represents no
  identity; it exists so a fresh clone can build without generating its own key first.
- Real releases go through `tools/make_release.py`. Every run generates a new RSA 3072 key and a new
  `applicationId` (shaped like `com.<8 chars>.<6 chars>`), builds, and drops the APK into
  `releases/`, keeping only the newest one.
- Consequently **every APK in `releases/` has a different package name and a different signing
  fingerprint**, and none of them can overwrite another.

That is deliberate: package name and signing fingerprint are an app's identity on a device, and a
fixed identity is an identity that can be recognized and traced.

**The cost, stated plainly:** a random package name means every release is a brand-new app.
Installing a newer version means re-enabling the accessibility service, re-granting root, and
starting rule configuration from scratch. The old version will not upgrade or uninstall itself.
That is a direct consequence of the design, not a bug. If you want to keep one long-lived install,
pin both with `-PappId=`.

## Building

Requires JDK 17+ and the Android SDK (`compileSdk 37`). The C sources under `native/` are compiled
by hand; a normal build does not need the NDK.

```bash
git clone <repo>
cd auto_skip

# SDK location
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# Build with a fresh random identity
python tools/make_release.py
# → releases/auto_skip-v1.0-com.xxxxxxxx.yyyyyy.apk

# Or pin the identity and use your own key
cp keystore.properties.example keystore.properties
keytool -genkeypair -v -keystore keystore/release_key.jks \
  -alias mykey -keyalg RSA -keysize 3072 -validity 10000
# fill in the password in keystore.properties, then
./gradlew assembleRelease -PappId=com.example.myapp
```

If Gradle picks the wrong JDK, add this to `~/.gradle/gradle.properties`:

```properties
org.gradle.java.home=/path/to/jdk-17-or-newer
```

### Rebuilding the watchdog ELFs (optional)

`fgwatch-arm64` / `watchdog-arm64` under `app/src/main/assets/elf/` are cross-compiled from
`tools/skp_fgwatch.c` and `native/watchdog.c`. Sources are in the repo; build them with the NDK.
Prebuilt binaries ship with the repo, so a normal build does not need this.

### Training the model (optional)

`tools/` holds the full pipeline: corpus synthesis (`glyph_synth.py` / `gen_skip_corpus.py`) →
training (`train_skip_torch.py`, needs PyTorch) → export to `skipnet_f32.bin` +
`skipnet_model.json` → copy into `app/src/main/assets/`. Evaluation scripts: `eval_gen.py`,
`eval_skip_real.py`.

## Configuration

Runtime config lives in the app's private directory and is editable from the rules screen:

- `shared_prefs/skip.global.xml` — global switches (recognition duration, delay, debug overlay, run mode)
- `shared_prefs/skip.rules.xml` — per-package rules (node-path text, whether to use the vision path)
- `app/src/main/assets/default_rules.json` — factory defaults
- `app/src/main/java/com/v5yn133n3/skp/K.kt` — every constant (thresholds, jitter, timeouts, guard sizes)

With a randomized package name, replace `com.v5yn133n3.skp` in those paths with the actual one.

## Layout

```
app/src/main/java/com/v5yn133n3/skp/
  engine/    SkipService (accessibility), CornerTask (vision pipeline), CornerScan (proposals),
             SkipNet (forward pass), BlobGate (solid-block rejection), BandScan, PopupTask, Matcher
  priv/      Root/Shizuku channel: Shell, Capture (raw frames), Daemon, FgWatch, SelfHeal
  keep/      BootReceiver, KeepAlive
  data/      Rule storage
  ui/        MainActivity (Compose), RuleScreen, ImageTestActivity (offline screenshot test bench)
  K.kt       All constants
app/src/main/assets/
  skipnet_f32.bin / skipnet_model.json   weights and architecture
  elf/                                   watchdog binaries
signing/     one-off public key, committed on purpose
native/      watchdog.c
tools/       corpus synthesis, training, evaluation, release script, watchdog C source
plan/        design notes
releases/    build output, newest only
```

## Known limitations

- Scope is **launch screens only**. Feeds, dialogs, and video pre-rolls are out of scope
  (`PopupTask` is only a fallback).
- When text sits on a **bright band of the same luminance**, plain connected components fuse the two
  into one block and discard it. Only the hot-region top-hat rescues that case.
- The lower band (bottom 42% of the screen) occasionally proposes page imagery as a target; the
  60×24 height guard suppresses it but does not eliminate it.
- Only arm64 and portrait coordinates are supported. Landscape is unverified.
- Accessibility behavior differs sharply across ROMs (on ColorOS, `am force-stop` silently drops the
  app from `enabled_accessibility_services`). Troubleshoot with `adb logcat -s SkipEngine:I`.

## License

MIT — see [LICENSE](LICENSE). A permissive license is not an endorsement.
