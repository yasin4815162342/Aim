# LineDebugger

Debug/tweak harness for the pool-guideline line detector. This is **not** the final
minimal app — it's the "see everything, tweak everything" version, for dialing in
thresholds live against real screen-recorded games before you ever shrink it down.

## What it does

1. A foreground service captures the screen via `MediaProjection`.
2. Drag the small circle (default 100px, itself tweakable) onto a guideline in your
   pool app.
3. Every captured frame, the crop under the circle runs: green-felt threshold →
   ball removal (erode thin-vs-thick, then subtract) → robust line-angle fit
   (PCA + outlier trim, refit twice) → an extended ray drawn back over the whole
   screen, color- and width-matched to what was actually detected.
4. A floating panel exposes every tunable as a slider, plus a live debug preview:
   **magenta** = counted as line, **blue** = rejected as ball, **yellow** =
   non-green but uncategorized, **dim green** = felt. You see exactly what the
   algorithm is doing while you drag sliders.

## First run

1. Install the debug APK (build locally or grab it from the Actions artifact — see below).
2. Open the app → **1. Grant overlay permission** → allow "display over other apps".
3. Tap **2. Start** → Android asks for notification permission, then to confirm
   screen capture → allow.
4. Switch to your pool app. The circle and tweak panel float on top of it.

## Build

CI builds a debug APK on every push to `main` via `.github/workflows/build.yml` —
no local Android Studio needed. Grab the APK from that workflow run's Artifacts tab.

To build locally with just a JDK 17 + Gradle installed (no wrapper is checked in,
so use a system `gradle` the first time):

    gradle assembleDebug

Run `gradle wrapper --gradle-version 9.5.1` once afterward if you want a local
`./gradlew` for repeat builds.

## Assumptions baked in — change these if wrong

- **Target device:** Samsung Galaxy A32 / Android 13 → `minSdk 26`, `compileSdk`/
  `targetSdk 36` (comfortably covers it; 26 is also the floor for
  `TYPE_APPLICATION_OVERLAY` and the `Notification.Builder(ctx, channelId)`
  constructor this code uses directly).
- **Package name:** `com.yas.linedebugger` — rename `namespace` / `applicationId`
  in `app/build.gradle.kts` if you want something else.
- **AGP 9.2.1 with built-in Kotlin** — AGP 9 removed the need for (and the
  compatibility with) the separate `org.jetbrains.kotlin.android` plugin, so it's
  deliberately absent here. If you've seen older Android/Kotlin tutorials that add
  that plugin, don't port that step in — it'll conflict with this build.

## Known rough edges

- The morphology (erode/dilate) is a naive O(size²·radius²) loop, not a fast
  distance transform. Push "ball erode r" and "circle diam" to their max at the
  same time and you'll visibly feel frame lag — that's expected, not a bug.
- Samsung's One UI is sometimes more aggressive than stock Android about killing
  foreground services to save battery. If the overlay vanishes after a while,
  check Settings → Apps → LineDebugger → Battery, and allow unrestricted
  background use.
- The debug preview shows the *last processed* crop, which can lag a frame or two
  behind the live circle position while you're actively dragging it.
- This has been written carefully but not compiled locally (no Android SDK in the
  environment that generated it) — the GitHub Actions run is the first real
  compile. If it breaks, the error log will point at the exact line.
