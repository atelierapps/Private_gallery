# Vault

A private, offline, single-user Android media vault. Encrypted at rest, no cloud,
no accounts, no analytics, and **no `INTERNET` permission at all**. See
[`vaultappspec.md`](vaultappspec.md) for the full build spec (v1.2) and
[`ui-mockup.html`](ui-mockup.html) for the UI decisions.

## Status

Build-order step 1 of the spec (§13) — the crypto envelope — is implemented and
**verified**. The rest of the app is scaffolded (project skeleton, manifest with
the anti-leak config, Room/Compose/Media3 wiring) with placeholder UI.

| Area | State |
|---|---|
| Crypto envelope (RSA-wrap + per-file DEK, GCM/CTR, CTR seek) | ✅ implemented + verified |
| Keystore key + biometric read gate | ✅ implemented (device-only, untested off-device) |
| Project scaffold, manifest, anti-leak config | ✅ |
| Share target, grid, viewer, import, lock, export | ⬜ stubs / not started |

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
  share/          # share target (stub)
  ui/             # main activity (stub)
app/src/test/     # EnvelopeCodecTest (JVM) + SoftwareKeyWrapper
app/src/androidTest/  # KeystoreRsaLatencyTest (device)
tools/crypto-verify/  # standalone JDK proof of the envelope + CTR seek
```
