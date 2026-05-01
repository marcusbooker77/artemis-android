# Artemis Code Review Fixes — 2026-05-01

Branch: `moonlight-noir`
Scope: code review of the Artemis-only divergence on top of upstream Moonlight (SudoVDA adaptive features, reconnect overlay, stats overlay, WiFi monitor, controller combo, MediaCodec quirks).

The review surfaced 7 Critical / 9 High / 9 Medium / 12 Low issues. Fixes landed in 4 focused commits, applied via parallel subagents on non-overlapping file domains.

---

## TL;DR

| Commit | Scope | Headline fix |
|---|---|---|
| `c385abe0` | `Game.java` reconnect lifecycle | Replace unsafe `LiCleanupBridge` → `LiStartConnection` in-place rebuild with Activity relaunch; guard every `runOnUiThread` body against destroyed Activity; interrupt+null reconnect worker in `onDestroy()` |
| `8e9cb406` | `WifiMonitor.java` + `MoonBridge.java` | Move WiFi polling off the UI thread (HandlerThread); use `ConnectivityManager.NetworkCallback` on API 31+ to bypass `getConnectionInfo()` location-permission stub; tighten JNI payload size check from `>= 4` to `>= 6`; one-shot warn flag for unimplemented `sendWifiQuality` |
| `ae57266c` | `StatsOverlay.java`, `ControllerHandler.java`, `MediaCodecDecoderRenderer.java` | Eliminate per-frame `String.format` allocation pressure (per-stat dirty caches + custom `appendOneDecimal` helper); single point of truth for combo cancel; narrow `rttInfo >> 32` to `int` first to match wire format |
| `7cdebd7f` | `MediaCodecHelper.java`, `NvHTTP.java`, `ReconnectOverlay.java`, `proguard-rules.pro` | Hisense quirk narrowed to TV models on API 33+ (phones excluded); strict XML truthy parsing (`true` only, not `yes`/`1`); guard `startPulseAnimation()` on `isAttachedToWindow()`; ProGuard nested-class `**` keep + JNI member rules |

---

## Commit detail

### 1. `c385abe0` — fix(reconnect): Activity-relaunch lifecycle

**File:** `app/src/main/java/com/limelight/Game.java`

- **C1 (Critical)** Replaced the in-place reconnect sequence
  `MoonBridge.stopConnection()` → `MoonBridge.cleanupBridge()` → `conn.start()` with an Activity relaunch via `getIntent()` extras. `LiStartConnection` after `LiCleanupBridge` is undefined behavior in moonlight-common-c — the JNI callback table is torn down. Setting `quitOnStop = false` before `finish()` prevents quitApp during the relaunch.
- **C2 (Critical)** Added `volatile Thread reconnectWorker` field. Interrupt + null in `onDestroy()`. Every `runOnUiThread` body in the reconnect path early-returns on `isFinishing() || isDestroyed()`. Reconnect worker checks `Thread.isInterrupted()` before and after sleeps.
- **C3 (Critical)** Unconditional `MoonBridge.setServerStatsListener(null)` in `onDestroy()` — was previously skipped on some early-exit paths, leaving the native side holding a stale jobject.
- **C7 (High)** Removed `synchronized(MoonBridge.class)` block — JNI calls don't see the JVM monitor; this was theatre.

### 2. `8e9cb406` — fix(wifi/jni): off-UI-thread + NetworkCallback

**File:** `app/src/main/java/com/limelight/wifi/WifiMonitor.java`

- Replaced `Handler(Looper.getMainLooper())` polling with a dedicated `HandlerThread("WifiMonitor")`.
- API 31+ branch: register `ConnectivityManager.NetworkCallback` filtered on `TRANSPORT_WIFI`. Read RSSI via `caps.getSignalStrength()` and WifiInfo via `caps.getTransportInfo()` — both location-permission-free for the *current* network. Avoids the `getConnectionInfo()` stub (returns "02:00:00:00:00:00" placeholder on API 31+ without `ACCESS_FINE_LOCATION`).
- `lastQuality` now actually gates dispatch — previously stored but never checked, causing redundant JNI calls on every poll.
- `linkSpeed = Math.max(0, Math.min(65535, linkSpeed))` clamp before pack into payload.
- `buildWifiQualityPayload` early-returns `null` when `MoonBridge.sendWifiQualityImplemented == false`.

**File:** `app/src/main/java/com/limelight/nvstream/jni/MoonBridge.java`

- `bridgeClServerStats` payload size check tightened from `>= 4` to `>= 6` (matches the docstring's documented packet shape). Rate-limited `LimeLog.warning` on shorts so a misbehaving server can't flood the log.
- Added `public static volatile boolean sendWifiQualityImplemented = false;` with one-shot warning on first stub call. Lets `WifiMonitor` skip work cleanly on hosts/builds where the JNI stub isn't wired up.
- Doc comment now references Apollo C++ as the ABI source of truth (was unspecified — readers had no way to know which fork's protocol was canonical).

### 3. `ae57266c` — fix(stats/controller)

**File:** `app/src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java`

- `float networkLatencyMs = (int)(rttInfo >> 32)` — narrow to int first. The two existing perf-overlay sites at lines 1811 and 1869 already do this; the third site was casting straight to float, sign-extending the upper 32 bits incorrectly when interpreted as the wire pack format.

**File:** `app/src/main/java/com/limelight/overlay/StatsOverlay.java`

- Removed all `String.format("%.1f ms", v)` (≈12 sites). Replaced with `appendOneDecimal(StringBuilder, float, String suffix)` helper: half-up rounding, NaN guard, no allocation.
- Per-stat dirty caches via `Float.floatToRawIntBits` (NaN-safe equality). `updateClientStats / updateServerStats / updateWifiStats` skip `invalidate()` when nothing changed *or* `mode == OFF`. `clearDirtyCaches()` is called on `toggle()` / `setMode()` so the next visible frame is forced to render.
- StringBuilder reuse honesty: kept the builder, but `.toString()` still allocates per call — that's the irreducible cost, no longer compounded by `String.format`'s internal Formatter machinery.

**File:** `app/src/main/java/com/limelight/binding/input/ControllerHandler.java`

- New `cancelStatsOverlayHoldIfComboBroken(int currentInputMap)` helper — single point of truth, runs *after* `inputMap` is fully resolved. Called from both `handleButtonUp` and `handleButtonDown` combo-breaking paths so a partial press transition doesn't desync the held-combo state.
- `DEFAULT_STATS_OVERLAY_COMBO = "select_l1"` constant + `statsOverlayCombo` field with TODO for future user-preference plumbing.

### 4. `7cdebd7f` — chore(audit): MediaCodec quirks, XML strict, overlay race, R8 keep

**File:** `app/src/main/java/com/limelight/binding/video/MediaCodecHelper.java`

- Hisense quirk narrowed:
  ```java
  Build.MANUFACTURER.equalsIgnoreCase("Hisense")
    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    && (Build.MODEL.contains("U7K") || ... "U8K"|"U9K"|"U7N"|"U8N"|"U9N"|"ULED"|"PENTONIC")
  ```
  Phones excluded — was previously catching all Hisense devices indiscriminately.
- Added `omx.mtk` to `whitelistedHevcDecoders` (covers MediaTek-based TV SoCs that ship Hisense's TV stack).

**File:** `app/src/main/java/com/limelight/nvstream/http/NvHTTP.java`

- `isTruthyXmlBoolean` now strict: only `"true"` (case-insensitive). Previously accepted `"true" | "yes" | "1"`. The Apollo/Sunshine wire protocol only emits `true`/`false`; the loose parser was silently masking truncated or upstream-changed responses.

**File:** `app/src/main/java/com/limelight/overlay/ReconnectOverlay.java`

- `show()` gates `startPulseAnimation()` on `isAttachedToWindow()`. If not attached, defers via `post(this::startPulseAnimation)`. Was racing on rapid show/hide where the `ValueAnimator` started before the view's `Choreographer` was wired up.
- Added comment confirming `currentAttempt` reset on `show()` is intentional (per-session counter, not lifetime).

**File:** `app/proguard-rules.pro`

- `nvstream.jni.*` → `nvstream.jni.**` so nested types (`MoonBridge$ServerStatsListener`, `MoonBridge$PerfOverlayListener`) survive R8.
- `-keepclassmembers` for `MoonBridge` static fields/methods touched from JNI callbacks (`bridgeClServerStats`, `sendWifiQualityImplemented`, etc.).
- `-keep interface MoonBridge$ServerStatsListener` and `PerfOverlayListener` + their implementations. R8 was minifying the listener method names, breaking the JNI string lookup.

---

## Confirmed-good (independently verified)

- `Apollo` C++ side (`apollo-src-2/`) emits the 6-field server-stats payload that the tightened `bridgeClServerStats >= 6` check expects — verified against Apollo's `nvhttp.cpp` server-stats serializer.
- `getSignalStrength()` is API 29+, `getTransportInfo()` is API 31+. The HandlerThread + `NetworkCallback` path is gated correctly behind the `Build.VERSION.SDK_INT >= 31` branch; pre-31 still uses the polling fallback. (Artemis `minSdk` is 24 per `app/build.gradle`.)
- `quitOnStop = false` before `finish()` is consistent with how the existing app-quit path uses the same flag — no double-quit regression.
- `Float.floatToRawIntBits` for NaN-safe equality matches the rest of the perf-overlay codebase's float-compare convention (used in `MediaCodecDecoderRenderer` for `lastFrameRate` cache).
- The Hisense MODEL containment list was cross-referenced against Hisense's 2023–2025 TV product line — U7/U8/U9 K-series and N-series, ULED branding, MediaTek Pentonic SoC family.

---

## Outstanding (empirical / next session)

These need real-device validation that can't be done from source review alone:

1. **Build verify.** `./gradlew assembleDebug` clean from a fresh checkout — confirms ProGuard rules + R8 keeps don't strip anything required at runtime, and that the API-31 `NetworkCallback` path compiles against `compileSdk`.
2. **Pixel 7 / Android 14 stream session.** Trigger a reconnect via host-side network blip; verify Activity relaunch is visually seamless and reconnect overlay pulse animates from the first frame. Watch logcat for `ServerStatsListener` not-null-after-destroy warnings.
3. **Hisense U7-series TV session.** Confirm the narrowed quirk still triggers on actual U7K hardware (SDK 33+, MediaTek decoder). Confirm a Hisense *phone* (e.g. older Android 11 model) no longer hits the quirk path.
4. **Android 9 phone.** Confirm the polling-fallback `WifiMonitor` branch still works correctly on pre-API-31 devices (no `NetworkCallback` regression bleeding into the legacy branch).
5. **Combo cancel.** Manually walk Select+L1 in/out across both directions, with a third button transitioning mid-combo, to confirm the new single-point-of-truth helper doesn't desync.

---

## Stash retained for comparison

`stash@{0}` = "WIP MediaCodecHelper Hisense block — match too broad; rebuild after review"

Kept intentionally. Diff against the new narrowed block in commit `7cdebd7f` shows the difference between the original over-broad match and the TV-only variant. Drop with `git stash drop stash@{0}` once a Hisense-TV device confirms the narrowed match still fires.
