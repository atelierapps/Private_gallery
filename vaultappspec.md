# Vault — Private Media App (Android) — Build Spec v1.2

**Target:** single-user, sideloaded, offline. No cloud, no accounts, no analytics.
**Threat model:** someone picks up my unlocked phone, or browses my gallery. NOT: forensic extraction, nation-state, rooted-device adversary.

**Changed in v1.1:** crypto moved from a single symmetric master key to envelope encryption (§3) — required to make the share target work without a biometric prompt on every save. Adds share-target (§5), source attribution (§6), tags (§7).

**Changed in v1.2:** engineering-review pass. No architecture rewrites — the v1.1 design stands. Changes are: a read-path performance decision that must land before §8 is built (§3.1), crypto hardening nits (§3), an honest statement of what the plaintext metadata DB leaks (§2.1), a folder-import source (§4.1, new `FOLDER_IMPORT` source type), and the recovery reality stated once and loudly (§11). All v1.2 additions are marked with **`[v1.2]`** blocks; the original v1.1 text is unchanged. A step-1 crypto/perf test plan is in Appendix A.

---

## 1. Stack

| Layer | Choice |
|---|---|
| Language | Kotlin |
| UI | Compose, single Activity + transparent share Activity |
| minSdk / targetSdk | 30 / 35 |
| DB | Room (metadata only) |
| Images | Coil 3 + custom decrypting Fetcher |
| Video | Media3 ExoPlayer + AesCipherDataSource |
| Background | WorkManager (expedited) |
| Auth | androidx.biometric BiometricPrompt |
| Crypto | Android Keystore direct (NOT androidx.security.crypto — deprecated) |

---

## 2. Storage layout

All inside `context.filesDir` — app-private, never scanned by MediaStore, invisible to every other app. No `.nomedia` tricks needed.

```
filesDir/
  vault/<uuid>            # encrypted media blob, no extension
  thumbs/<uuid>           # encrypted 512px JPEG thumbnail
  tmp/<uuid>              # [v1.2] plaintext spool during share/import — swept on launch
  vault.db                # Room metadata
```

`getExternalFilesDir()` is NOT acceptable — it's world-readable via USB/MTP.

### `MediaItem`
```
id: String (UUID, = filename)
originalName: String
mimeType: String
sizeBytes: Long
dateTakenMillis: Long
durationMillis: Long?          // video only
widthPx: Int
heightPx: Int
importedAtMillis: Long
albumId: String?
contentHash: String?           // [v1.2] sha-256 of plaintext, for dedup — see §4.2

// source attribution — see §6
sourceType: SourceType         // SHARE | LOCAL_IMPORT | FOLDER_IMPORT | UNKNOWN
sourcePackage: String?         // com.android.chrome
sourceLabel: String?           // "Chrome" — cached, app may be uninstalled later
sourceDomain: String?          // indexed
sourceUrl: String?
```

### `Tag`
```
id: String (UUID)
name: String                   // unique, case-insensitive
colorHex: String
useCount: Int                  // drives the quick-tag chips in the share sheet
createdAtMillis: Long
```

### `MediaTagCrossRef`
```
mediaId: String                // composite PK, indexed both directions
tagId: String
```
Query via Room `@Transaction` + `@Relation`. Index `sourcePackage`, `sourceDomain`, and both cross-ref columns — the filter bar hits these constantly.

> ### `[v1.2]` 2.1 — The metadata DB is your weakest surface, not the blobs
> §14 puts SQLCipher out of scope. That's a defensible call for this threat model — but be clear-eyed about the consequence: **`vault.db` stores `originalName`, `sourceUrl`, `sourceDomain`, and tag names in plaintext.** The media blobs are encrypted; the row that describes each blob is not. Anyone who reaches `filesDir` (root, or the ADB/backup vectors §10 is closing) learns *exactly what every file is and where it came from* — often more revealing than a thumbnail (`IMG_nudes_2024.jpg`, a full source URL).
>
> This is invisible to the "someone browses my gallery through the app UI" threat, which is the primary one — so leaving SQLCipher out is fine. But given the DB is the soft spot, two cheap mitigations:
> - **Don't persist `sourceUrl` in full.** Host (`sourceDomain`) drives the filter bar; the full URL buys little and leaks the most. Store host only, or drop it.
> - **Consider truncating/normalizing `originalName`** (keep extension + a short hash) so the filename itself isn't a description. Trade-off: export (§11) restores less-original names — decide which you value more.
>
> Not a v1 blocker. Just stop calling the media "encrypted" without the asterisk that its label isn't.

---

## 3. Crypto — envelope encryption

Two keys, because writing and reading have different auth requirements.

**`vault_wrap_v1`** — RSA-2048, `RSA/ECB/OAEPWithSHA-256AndMGF1Padding`, in Keystore:
- `setUserAuthenticationRequired(true)` — this constrains the **private** key only
- `setUserAuthenticationParameters(300, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL)`
- `setInvalidatedByBiometricEnrollment(false)` — so enrolling a new fingerprint doesn't destroy the vault

**Per-file DEK** — 32 fresh bytes from `SecureRandom`, ordinary JCE AES, never enters Keystore:
- Encrypt the media with the DEK
- Wrap the DEK with the RSA **public** key → 256-byte blob in the file header
- To read: unwrap with the **private** key (triggers auth), then decrypt

File format: `[2-byte version][256-byte wrapped DEK][16-byte IV][ciphertext]`

Modes: **GCM** for images and thumbnails (integrity for free). **CTR** for video — GCM is not seekable and ExoPlayer scrubbing will break on it.

> ### Why not one symmetric master key
> A user-auth-required Keystore AES key means a biometric prompt *just to save a photo from the share sheet*. That destroys the feature. Envelope encryption splits the paths: public key writes with no auth, private key reads with auth.
>
> Side benefit: the DEK is a plain in-memory AES key, so none of AndroidKeyStore's block-mode or IV restrictions apply. Caller-supplied IVs and CTR seeking just work. **This supersedes the `setRandomizedEncryptionRequired` warning**, which only applied to the symmetric-master design.
>
> Cost: the DEK sits in app memory while a file is open, and a fresh DEK per file means 256 bytes of overhead per file. Both fine here.

> ### `[v1.2]` 3.1 — The read path pays an RSA-in-TEE op per file. This is the one real risk.
> The write path is now beautiful: public-key wrap, no auth to save. But look at what the same design does to reads. Unwrapping a per-file DEK is an **RSA-2048 private-key decrypt inside the TEE**, and §3 gives *every file its own DEK — including every thumbnail*.
>
> So painting a grid of 200 thumbnails (§8) = **200 serialized TEE RSA-OAEP decrypts**. Those run tens of ms each, serialized through the Keystore daemon → multiple seconds of jank on a cold grid, and it repeats after **every auto-lock** (§9 zeroes the DEK cache). Nothing in v1.1 addresses this; it's the failure most likely to make v1 feel broken.
>
> **Decision to make before building §8 (not during):**
> 1. The DEK cache §9 implies must live for the **whole unlocked session**, and you should **background-prewarm it right after unlock**, not lazily per-thumbnail.
> 2. Stronger, and what I'd do: after the first RSA unwrap, **re-wrap each DEK under a fast in-memory symmetric session key**. Then a lock/unlock cycle re-pays RSA only for media you actually open, never per-thumbnail.
> 3. If measurement (Appendix A) still shows the cold grid is unacceptable, the fallback is a symmetric **library key** for thumbnails (one RSA unwrap per unlock, then AES-unwrap per thumb) while keeping RSA solely for the no-auth save path. That's a bigger change — which is exactly why you measure in step 1, before step 4 locks the format.
>
> Do **not** reach for `setIsStrongBoxBacked(true)` to "harden" the RSA key — StrongBox RSA is *slower* and makes this worse.

> ### `[v1.2]` 3.2 — Crypto hardening nits (all cheap, do them in step 1)
> - **GCM IV = 12 bytes, not 16.** The header reserves `[16-byte IV]`; GCM's standard/optimal nonce is 96-bit. A 16-byte IV forces the non-standard GHASH-derived path (slower, off the well-analyzed track). Use 12 for GCM, keep 16 for CTR — or document the field as 12+pad for GCM.
> - **Authenticate the header.** GCM covers the payload but not the `[version][wrapped DEK]` prefix. Feed that prefix as **AAD** to the GCM cipher so a file can't be silently spliced/downgraded. Nearly free.
> - **CTR video has zero integrity — state it.** GCM gives integrity "for free"; the corollary is that CTR video files are unauthenticated and malleable. Acceptable for a passive-snooping threat model, but it should be a written decision, not an accident.
> - **Time-based auth is the right call — make it explicit.** `setUserAuthenticationParameters(300, …)` means a plain `BiometricPrompt` success authorizes the private key for 300s with **no `CryptoObject`**. That's what makes §9 work. The auth-*per-use* form (`duration = 0`) *requires* a `CryptoObject` and would change the auth code — so name which one you're building.
> - **`setInvalidatedByBiometricEnrollment(false)` is a security trade, not just convenience.** It means a *newly enrolled* fingerprint (someone who knows your device PIN adds their print) can unlock the vault. You want it for robustness against your own re-enrollment; just log that you accepted the cost.

---

## 4. Gallery import (the part everyone gets wrong)

**Do NOT use the Android Photo Picker.** It returns `content://media/picker/...` URIs which cannot be passed to `MediaStore.createDeleteRequest()` — so you can import but never delete the original, which defeats the entire point.

Build your own grid picker:

1. Request `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO` (API 33+) / `READ_EXTERNAL_STORAGE` (≤32). Handle `READ_MEDIA_VISUAL_USER_SELECTED` partial grant on API 34+.
2. Query `MediaStore.Files` with `MEDIA_TYPE IN (image, video)`, sort by `DATE_TAKEN DESC`. Paginate.
3. Multi-select → per item: stream → encrypt → write `vault/<uuid>` → `fsync` → verify byte length → encrypt thumbnail → insert Room row with `sourceType = LOCAL_IMPORT`.
4. **Only after every write is verified**, batch all original MediaStore URIs into one `MediaStore.createDeleteRequest()` → launch via `IntentSenderRequest`. One system dialog for N files.
5. On cancel: keep vault copies, leave originals. Show "N imported, originals kept."

**Hard rule: never delete an original before the encrypted copy is confirmed on disk.**

> ### `[v1.2]` 4.1 — Folder import (the "render from a folder on my phone" idea, done safely)
> The instinct — "point the app at a folder and have it show that media" — is good, but *where the folder lives* decides whether it breaks the whole app:
> - A normal folder in **shared storage** (`/sdcard/…`) is **scanned by MediaStore and appears in the stock Gallery.** That's precisely the exposure this app exists to remove. A "render plaintext from a shared folder" mode silently defeats §2 and the threat model. **Don't build that.**
> - The safe version is a **folder-import source**, mechanically identical to §4 but sourced from a directory instead of the MediaStore grid:
>   1. User picks a directory once via `ACTION_OPEN_DOCUMENT_TREE` (SAF). Persist the grant with `takePersistableUriPermission()` — SAF tree URIs *do* support this (unlike the `ACTION_SEND` grants in §5).
>   2. Enumerate children via `DocumentFile` / `DocumentsContract`, filter to image/video MIME types.
>   3. Run the **same** encrypt → fsync → verify → thumbnail → Room-insert pipeline as §4, with `sourceType = FOLDER_IMPORT` and `sourceLabel` = the folder's display name.
>   4. Originals: SAF lets you delete them (`DocumentsContract.deleteDocument`) after every write is verified — same hard rule as §4.5. Offer keep-or-delete; default keep.
>   5. Optional (v2): remember the tree URI and offer a manual "scan folder for new files" action. Do **not** auto-watch — no `INTERNET` and no background scraping; this stays a user-initiated import.
>
> Net effect is exactly what was asked ("render media from my folder") — but the rendered copies are the encrypted vault ones, and no plaintext is left sitting in a MediaStore-scanned directory. If you truly want a plaintext folder the system gallery shows, that's a *different, non-private* app; it can't coexist with this threat model.

> ### `[v1.2]` 4.2 — Dedup (optional, cheap to leave a hook for)
> Saving the same image from two apps (or re-importing a folder) writes two blobs. Compute `contentHash` = SHA-256 of the plaintext during the encrypt stream (you're reading every byte anyway) and store it (§2). Even if v1 doesn't act on it, an indexed hash column now makes "skip if already present" a one-line query later instead of a migration.

---

## 5. Save-from-anywhere (share target)

This is the primary daily flow. Build it early.

### Manifest
```xml
<activity
    android:name=".share.ShareReceiverActivity"
    android:theme="@style/Theme.Vault.Transparent"
    android:excludeFromRecents="true"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="image/*" />
        <data android:mimeType="video/*" />
    </intent-filter>
    <intent-filter>
        <action android:name="android.intent.action.SEND_MULTIPLE" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="image/*" />
        <data android:mimeType="video/*" />
    </intent-filter>
</activity>
```

### Behaviour
- Transparent activity renders a bottom sheet: thumbnail preview, detected source label, quick-tag chips, Save. Auto-dismisses.
- **No biometric prompt** — public-key wrapping means saving needs no auth (§3).
- Hand the work to an expedited `WorkManager` job, not the Activity scope. Large videos outlive the sheet.

### ⚠️ The URI expiry trap
`ACTION_SEND` grants **temporary** read permission scoped to the receiving Activity's lifetime. You **cannot** call `takePersistableUriPermission()` on it — that only works for `ACTION_OPEN_DOCUMENT`. Queue the bare URI for later processing and the worker dies with `SecurityException`.

Fix: open the `InputStream` while the Activity is still alive, and either finish the encrypt inline for small files or spool to an app-private temp file the worker owns.

> ### `[v1.2]` 5.1 — "Auto-dismisses" is a half-truth for large videos; temp files need sweeping
> Two operational cracks in the §5 flow:
> - **The read grant dies when the Activity finishes.** For a large video you must finish reading the stream into `tmp/<uuid>` *before* `finish()` — you cannot truly fire-and-forget to the worker and dismiss instantly. In practice: show a brief "Saving…" state on the sheet while the **copy** completes, hand the owned temp file to WorkManager for the **encrypt**, then dismiss. Small files can encrypt inline and dismiss immediately. So "auto-dismiss" is real for photos, deferred-by-a-copy for big video.
> - **`tmp/<uuid>` holds plaintext until the encrypt finishes.** A process death mid-encrypt leaves plaintext in `filesDir`, quietly breaking the no-plaintext-on-disk invariant (§8). **Sweep `tmp/` on every app + worker launch** (delete anything with no completed Room row). Put this in the startup path in step 3.

### Sharing Shortcuts — get into the top row
Without this you're buried in the alphabetical app list, which makes the whole feature feel slow.
- `res/xml/shortcuts.xml` with a `<share-target>` pointing at `ShareReceiverActivity`, declaring `image/*` and `video/*` and a custom category
- Publish a long-lived dynamic shortcut (`ShortcutManagerCompat.pushDynamicShortcut()`, `setLongLived(true)`) carrying the same category

### Reality check on downloads
Chrome does not offer a share target at download time. There is no way to make Vault a download destination. The two real flows:
- **Long-press image → Share image → Vault.** Never touches Downloads. This is the one you actually want.
- Already downloaded → share from the download notification or Files → Vault.

Don't chase a "download straight into Vault" hook. It doesn't exist on Android.

> ### `[v1.2]` 5.2 — This is also the honest home for the "downloaded folder" idea
> If the daily reality is "stuff lands in Downloads and I want it in Vault," the §4.1 folder-import pointed at the Downloads tree is the clean answer: import-and-clear on demand, no plaintext left in a scanned folder. Pair it with the share-from-notification flow above; between them there's no gap that a (non-existent) download hook would fill.

---

## 6. Source attribution

Captured automatically. Never typed.

- `activity.referrer` (`Intent.EXTRA_REFERRER`) → `android-app://com.whatsapp` → package name. Primary signal.
- Resolve display name via `PackageManager.getApplicationLabel()` → "WhatsApp". **Cache the label at save time** — the source app may be uninstalled later and the lookup will fail.
- Browser shares usually put the page URL in `Intent.EXTRA_TEXT` → parse host → `sourceDomain` + `sourceUrl`.
- Gallery imports → `sourceType = LOCAL_IMPORT`.

`referrer` is null when the sender shares via `PendingIntent`. Record `UNKNOWN` and let him tag manually. **Do not guess** — a wrong source is worse than an absent one.

> ### `[v1.2]` 6.1 — Per §2.1, store `sourceDomain` (host) but reconsider persisting full `sourceUrl`. The host drives the filter bar; the full URL is the biggest single leak in the plaintext DB.

---

## 7. Tags & filtering

### Filter bar
Horizontal chip row above the grid: **All · By source · By tag · Date**. Source chips are auto-generated from distinct `sourcePackage` / `sourceDomain` values with counts. Tag chips come from `Tag` ordered by `useCount DESC`. Multi-select chips = AND.

### Tag at save time, or it never happens
The share sheet shows the 6 highest-`useCount` tags as toggle chips plus a "+ new" field. One tap and the tag is written with the file.

Retroactive tagging is a feature everyone builds and nobody uses. Build the capture-time path first; the bulk re-tag screen is v2.

### v2
Auto-tag rules (source package → tag), smart albums saved as stored filter queries.

---

## 8. Viewing

- **Grid:** Coil `Fetcher` reading `thumbs/<uuid>`, decrypting in memory. Enable Coil's memory cache; **disable its disk cache** — it would write plaintext.
- **Full image:** decrypt to an in-memory stream. Never to a temp file.
- **Video:** `ExoPlayer` with `AesCipherDataSource` wrapping `FileDataSource`. No plaintext ever hits disk.

> ### `[v1.2]` 8.1 — This section is gated by the §3.1 decision. Build the DEK cache / session-key strategy first, or the grid will stutter. Also cap full-image decode with a downsample ceiling (`BitmapFactory.Options.inSampleSize` / Coil `size`) so a huge source can't OOM the in-memory path.

---

## 9. Lock behaviour

- `FLAG_SECURE` on both Activities — kills screenshots, screen recording, and the recents thumbnail.
- `ProcessLifecycleOwner`: on `ON_STOP`, start lock timer. Configurable: immediate / 15s / 60s.
- On lock: clear Coil memory cache, zero out cached DEKs, release the private-key Cipher, route to auth.
- `BiometricPrompt` with `DEVICE_CREDENTIAL` fallback. No custom PIN — device credential is stronger and already Keystore-backed.
- The share sheet is exempt from lock. Saving never requires auth.

> ### `[v1.2]` 9.1 — "Zero out cached DEKs" means `ByteArray` you can actually overwrite (`Arrays.fill(dek, 0)`), not `String`/`SecretKeySpec` copies the JVM may have duplicated. Keep DEKs in mutable byte arrays end-to-end so the wipe is real. If §3.1's session-key approach is used, wipe that key on lock too — it's the thing that re-derives everything.

---

## 10. Anti-leak manifest config

```xml
android:allowBackup="false"
android:fullBackupContent="@xml/empty_backup_rules"
android:dataExtractionRules="@xml/empty_extraction_rules"
```
Both rule files exclude everything. Otherwise ADB backup or cloud-to-cloud transfer can pull `filesDir`.

Also: **no `INTERNET` permission at all.** Makes exfiltration structurally impossible and proves it to yourself.

> ### `[v1.2]` 10.1 — Confirm no transitive dependency assumes `INTERNET`. Coil 3, Media3, Room, WorkManager all function locally without it, but audit the merged manifest (`app/build/.../AndroidManifest.xml`) after each dependency bump — a stray `<uses-permission android:name="android.permission.INTERNET"/>` merged in from a library silently reopens the exfil surface. `tools:node="remove"` it explicitly if one appears.

---

## 11. Export — build this in v1, not v2

Uninstall, "Clear data", factory reset, or Keystore invalidation = **permanent, unrecoverable loss**. No recovery path exists.

- `ACTION_OPEN_DOCUMENT_TREE` → user picks a destination (SD card, USB-OTG, whatever)
- Decrypt everything back to original filename + extension
- Write a `manifest.json` alongside it carrying tags and source metadata, so a re-import can restore them
- Progress UI, resumable, verify counts at the end

Do not move irreplaceable media in until export is tested end-to-end.

> ### `[v1.2]` 11.1 — Say it once, loudly: export is the ONLY backup that exists.
> The scattered warnings (§11, §12) understate the sharpest fact: **Android Keystore private keys never leave the device and do not transfer to a new phone** — same signing key or not, same Google account or not. New phone, dead battery-on-a-broken-screen, factory reset = the vault is gone, and there is no unlock-from-elsewhere. `setInvalidatedByBiometricEnrollment(false)` protects against re-enrollment, nothing more.
>
> Consequences that should drive the build:
> - **Export is not a "nice to have in v1" — it is the disaster-recovery story, and it lands as plaintext.** Treat it as load-bearing. Arguably build a minimal export *before* step 4 if you intend to put anything irreplaceable in early.
> - Export output is plaintext on removable media — brief the user that the safety copy is *unencrypted by design*, and where they put it matters.
> - Verify counts + re-import round-trip (`manifest.json` restores tags/source) must pass before "move irreplaceable media in" (§11) is honored.

---

## 12. Build & install

```bash
# One-time: create a REAL keystore. Back it up off-device.
keytool -genkey -v -keystore vault-release.jks -keyalg RSA \
  -keysize 4096 -validity 10000 -alias vault

./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

**Signing key = your data.** A different signing key on reinstall means a new UID and a new Keystore — old data permanently inaccessible. Debug builds use a rotating debug keystore: fine for testing, fatal for real data. Back `vault-release.jks` up wherever you'd back up a password.

---

## 13. Build order

1. RSA wrap key + DEK envelope round-trip test — GCM, CTR, and a **seek-to-offset** test on CTR
   - **`[v1.2]`** …and a **TEE-RSA-unwrap latency benchmark** on the real target device (Appendix A). This is the go/no-go for the §3.1 read-path design; measure before step 4.
2. Room schema (media + tags + cross-ref) + repository
3. **Share target + save sheet + source capture** ← primary daily flow, build it before the gallery importer
   - **`[v1.2]`** include `tmp/` sweep-on-launch (§5.1) here.
4. Vault grid with decrypting Coil fetcher
   - **`[v1.2]`** implement the §3.1 DEK-cache / session-key strategy as part of this step, not after.
5. Filter bar (source + tag chips)
6. MediaStore picker grid + permissions
7. Gallery import pipeline + `createDeleteRequest`
   - **`[v1.2]`** folder-import source (§4.1) is the same pipeline with a SAF tree source — build it alongside, not as a separate epic.
8. Full-screen image viewer
9. ExoPlayer video playback
10. Biometric lock + FLAG_SECURE + auto-lock
11. **Export**
12. Albums, bulk re-tag, auto-tag rules

Steps 1–11 = a genuinely usable v1.

---

## 14. Explicitly out of scope for v1

Decoy PIN, break-in photo capture, cloud sync, filename obfuscation beyond UUIDs, SQLCipher on the metadata DB, hidden app icon, retroactive bulk tagging. All scope creep.

> ### `[v1.2]` 14.1 — Also explicitly out of scope: a plaintext folder the system gallery renders (§4.1). It cannot coexist with the threat model; the folder-*import* source is the supported shape of that idea.

---

## Appendix A — `[v1.2]` Step-1 crypto & performance test plan

Step 1 is not "get encryption working" — it's "prove the format is correct *and* the read path is fast enough," because both are baked into the file layout and are expensive to change after step 4. Write these as instrumented (on-device) tests; the RSA-latency numbers only mean anything on real TEE hardware, not the emulator.

### A.1 Correctness — envelope round-trip
- **GCM image:** random plaintext (1 B, 1 KB, 5 MB) → wrap DEK with RSA public → GCM-encrypt with **12-byte** IV → write `[ver][wrapped][iv][ct]` → read back → RSA-private unwrap → decrypt → assert byte-equal.
- **Header-as-AAD:** decrypt must **fail** if any byte of `[ver][wrapped DEK]` is flipped (proves §3.2 AAD binding). Also assert GCM tag catches a flipped ciphertext byte.
- **CTR video:** same round-trip with 16-byte IV, AES-CTR, assert byte-equal on a ~100 MB blob.
- **CTR seek-to-offset (the one that bites):** for random offsets, compute the counter block for that offset, decrypt a 64 KB window, and assert it equals the same slice of the plaintext. This is the exact path `AesCipherDataSource` drives during ExoPlayer scrubbing — get it green here, not in step 9.
- **fsync + length verify:** write → fsync → reopen → assert on-disk length matches expected header+ciphertext, before any "delete original" logic trusts it.

### A.2 Performance — the go/no-go benchmark for §3.1
- **Single TEE RSA-OAEP unwrap latency:** median + p90 over 100 iterations, warm. Record it. This × grid-size ≈ your cold-grid stall.
- **Cold-grid simulation:** unwrap N DEKs serially for N ∈ {50, 200, 500} with the DEK cache **cold** (post-unlock). If p90 total > ~300 ms at N=200, the naive per-thumbnail path is not shippable → adopt §3.1 option 2 (session-key re-wrap) or option 3 (library key).
- **Warm-cache grid:** same N with DEKs cached → should be sub-millisecond AES. Confirms the cache actually removes RSA from the scroll path.
- **Lock/unlock re-warm cost:** measure the background prewarm after unlock at N=500 — this is what the user feels each time auto-lock (§9) fires. If it's ugly, that argues for the library-key fallback.

**Exit criteria for step 1:** all A.1 assertions pass on device; A.2 numbers recorded and a read-path option (naive cache / session-key / library-key) chosen *in writing* before step 4 begins.
