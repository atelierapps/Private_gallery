# Vault

A private, offline, single-user Android media vault. Encrypted at rest, no cloud,
no accounts, no analytics, and **no `INTERNET` permission at all**. See
[`vaultappspec.md`](vaultappspec.md) for the full build spec (v1.2) and
[`ui-mockup.html`](ui-mockup.html) for the UI decisions.

## Status

Build-order steps 1–3 of the spec (§13) are implemented: the verified crypto
envelope, the Room metadata layer, and the primary daily flow — the share
target with its save sheet, source capture, and encrypt-on-save pipeline.

| Area | State |
|---|---|
| 1 · Crypto envelope (RSA-wrap + per-file DEK, GCM/CTR, CTR seek) | ✅ implemented + verified |
| 1 · Keystore key + biometric read gate | ✅ implemented (device-only) |
| 2 · Room schema (media + tags + cross-ref) + repository | ✅ implemented |
| 3 · Share target + save sheet + source capture + encrypt pipeline | ✅ implemented |
| 4 · Vault grid + decrypting Coil fetcher (DEK-cache read path, §3.1) | ✅ implemented |
| 4 · Biometric unlock gate + DEK prewarm (partial §9/§10) | ✅ implemented |
| 5 · Filter bar (source + tag + date chips, multi-select) | ✅ implemented |
| 6 · MediaStore picker grid + permissions (incl. partial grant) | ✅ implemented |
| 7 · Import pipeline + batched createDeleteRequest (verify-first) | ✅ implemented |
| §4.1 · Folder import (MediaStore buckets) + SAF file import (cloud) | ✅ implemented |
| 8 · Full-screen viewer (swipe, pinch-zoom, metadata, delete) | ✅ implemented |
| 9 · Encrypted video playback (ExoPlayer + CTR data source) | ✅ implemented |
| 10 · Auto-lock on background (configurable delay) + lock now | ✅ implemented |
| 11 · Export/backup to a folder + manifest.json (biometric-gated) | ✅ implemented |
| Multi-select (delete + move back to gallery) | ✅ implemented |
| Restore from backup (re-import manifest.json) | ✅ implemented |
| Organization: search, bulk/retroactive tagging, select-all | ✅ implemented |
| In-app camera (capture photos/videos straight into the vault) | ✅ implemented |
| Recycle bin (soft delete, restore, 30-day auto-purge) | ✅ implemented |
| Share-out (decrypt one item to another app via FileProvider) | ✅ implemented |
| Auto-tag rules (tag on save by source/capture type) | ✅ implemented |
| Albums (curated collections, chosen cover, add/remove, export) | ✅ implemented |
| Tag manager (rename / merge / delete, live counts) | ✅ implemented |
| Durable bulk import (survives leaving the app) + duplicate report | ✅ implemented |
| Pin-to-top, immersive viewer, playback/slideshow | ✅ implemented |
| Video: zoom, drag-seek, speed, vol/brightness, resume, skip, autoplay | ✅ implemented |
| Grid date section headers (Today / This week / …) | ✅ implemented |
| Settings screen (playback, display, security, about) | ✅ implemented |
| Storage & stats (encrypted size breakdown, library counts) | ✅ implemented |
| Rename an item · add to album from the viewer | ✅ implemented |
| Global mute (persisted; disables the swipe volume gesture) | ✅ implemented |
| Disguised as "Link" (neutral name + icon) | ✅ implemented |
| Project scaffold, manifest, anti-leak config, launcher icon | ✅ |

All of build-order steps 1–11 are implemented. Verified end-to-end on device
through step 9 (import, view, video, delete, move); steps 10–11 pending a
device pass.

Verified on device (Xiaomi, Android 16): build, unit tests, install, biometric
unlock, import, **encrypted images render end-to-end** (the OAEP fix). RSA
unwrap measured at 18.3 ms median — see spec §3.1.

Everything except the crypto is written but **unbuilt** here (no Android SDK in
this environment). The crypto and the pure-logic pieces (envelope, CTR seek,
source-host parsing) are verified on the JDK; the Android-framework code compiles
and runs once built with an SDK.

## Crypto verification

The riskiest piece — the on-disk format and the AES-CTR **seek-to-offset** math
that ExoPlayer scrubbing depends on — is proven two ways:

**1. Pure-JDK harness (no Android needed, runs anywhere):**

```bash
cd tools/crypto-verify
javac EnvelopeVerify.java && java EnvelopeVerify
```

Exercises GCM round-trips, header-as-AAD authentication, tamper rejection, a
100 MB CTR round-trip, 200 random-offset seeks, and every block boundary.
Last run: **224/224 checks passed**.

**2. Gradle JVM unit tests** (same assertions, against the real Kotlin codec;
needs the Android SDK + dependency access):

```bash
./gradlew :app:test
```

**3. On-device latency benchmark** (the §3.1 go/no-go for the grid design):

```bash
./gradlew :app:connectedCheck   # reads TEE RSA unwrap latency; see logcat tag VaultRsaBench
```

## Building the app

Requires the **Android SDK** (set `ANDROID_HOME` / `local.properties`) and network
access to `google`/`mavenCentral` for dependencies.

```bash
./gradlew :app:assembleDebug
```

Signing for real data: create and **back up** a release keystore off-device — a
different signing key on reinstall means a new Keystore and permanently
inaccessible data (spec §12).

## Layout

```
app/src/main/java/com/atelierapps/vault/
  crypto/         # step 1 — EnvelopeCodec, KeyWrapper, KeystoreKeyWrapper, CtrReader/CtrCounter
                  #          + DekCache / MediaCrypto (step 4 §3.1 read path)
  data/           # step 2 — Room entities, DAOs, VaultDatabase, VaultRepository
  storage/        # app-private vault/thumbs/tmp layout
  media/          # save pipeline — MediaSaver, Thumbnailer, MediaProbe
  share/          # step 3 — ShareReceiverActivity, SaveSheet, SaveMediaWorker, SourceAttribution
  auth/           # step 4 — BiometricAuth (unlock gate)
  session/        # step 4 — VaultSession lock state
  filter/         # step 5 — MediaFilter + DateBucket (AND/OR filter model)
  imports/        # steps 6-7 + §4.1 — MediaStore/SAF picker, ImportViewModel/Activity, delete
  media/          # + reused by the importer (MediaSaver pipeline)
  ui/grid/        # step 4 — VaultGridScreen (3-col decrypting grid)
  ui/lock/        # step 4 — LockScreen
  ui/image/       # step 4 — Coil decrypting fetcher + ImageLoader (disk cache off)
  ui/home/        # step 5 — VaultHome, FilterBar, GridViewModel
  ui/viewer/      # step 8 — ViewerActivity/Screen/ViewModel (swipe, zoom, delete)
  ui/             # MainActivity — lock/grid host
app/src/test/     # EnvelopeCodecTest + SourceAttributionTest (JVM) + SoftwareKeyWrapper
app/src/androidTest/  # KeystoreRsaLatencyTest (device)
tools/crypto-verify/  # standalone JDK proof of the envelope + CTR seek
```
