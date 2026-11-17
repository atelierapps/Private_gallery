# Handover — next phase: polish, then Google Play, then iOS

Written at the end of the long build session (Oct–Nov 2026) for the session
that picks up next. Read this, then `CLAUDE.md`, then the newest entry in
`SESSION_LOG.md`. `CLAUDE.md`'s rules all still hold — especially the four
hard constraints and "verify statically, the owner builds".

## Where things stand

- The Android app is feature-complete for the owner's own use and **stable on
  their phone** (Xiaomi, HyperOS / Android 16; it survived a system update with
  no issues). 803 items; full backup → delete → restore and a second restore
  over the library both confirmed, no duplicates, albums intact.
- Schema 12. Branch `claude/photo-video-app-specs-acawm0` (default), mirrored
  to `claude/vault-android-continuation-ye2jnk`. Nothing uncommitted.
- Untested: **importer Select all / Clear** and the **wording pass** (both 2026-11-17).
- The owner works on a **Mac** (zsh: no `#` comments in commands you hand
  them) and builds with `git pull && ./gradlew :app:installDebug`.
- The owner has a paid **Apple Developer account** and an iPhone 18 Pro Max.

## The owner's plan, in order

1. **Polish the in-app wording** (their explicit ask — do this first).
2. **Publish on Google Play**, accepting some sacrifices.
3. **Then an iOS version**, in a **new repository**.

---

## 1. Polish the in-app wording

The owner's words: the app "has full sentence instructions, so it looks a bit
unpolished". Screens explain themselves in paragraphs; they should read like a
finished product.

**Where the text is.** A scan for string literals of 55+ characters found ~95
(concatenated strings make the real count higher). Heaviest first:

```
19 ui/export/ExportScreen.kt        5 ui/viewer/VideoPlayer.kt
13 ui/settings/SettingsScreen.kt    5 ui/duplicates/DuplicatesScreen.kt
 9 ui/storage/StorageScreen.kt      3 ui/home/ViewsRow.kt
 9 ui/home/VaultHome.kt             3 ui/albums/AlbumFromTags.kt
 7 ui/restore/RestoreScreen.kt      3 ui/tags/TagsScreen.kt
 6 ui/trim/TrimScreen.kt            + ~15 more files with 1–2 each
```

Also check dialog bodies, empty states, toasts and snackbars, and notices.

**Style to aim for**

- Labels and short fragments, not instructions: "Back up now", "Last backup:
  3 days ago", not "You should back up regularly because…".
- One line of helper text at most, and only where a choice is genuinely
  unclear. Most explanations can simply go — the owner knows the app.
- No "you"/"your" lectures, no "This will…", no reasons in the UI. Reasons
  belong in code comments (which stay as they are).
- Numbers over words ("12 new", "3 of 803"). Consistent verbs across screens
  (Add / Remove / Delete / Restore / Back up).
- **Keep — but tighten — anything protecting the owner's media:** delete and
  restore confirmations, the backup-passphrase "no recovery" warning, the
  uninstall warning, "back up first" prompts, and errors that name a cause
  (e.g. "the unlock had timed out"). Short is fine; vague is not.
- **Never reintroduce "vault"** or anything revealing (hard constraint 3).
- Text only: don't change behaviour, layout logic or code comments in this
  pass. Commit per screen or per small group, with the usual "why" messages.

Deliver as one or two builds; the owner will check every screen.

---

## 2. Google Play — what it will take

Nothing here is started. Several items are **decisions for the owner**; ask
before acting on those. Facts marked *(verify)* are from memory and Google
changes them — check the current Play Console / policy pages first.

### A. Protect the owner's own library first
- A Play build is signed with a different key from the debug build, so it
  **cannot install over the owner's current app**. Uninstalling destroys the
  Keystore keys = every encrypted file (hard constraint 1).
- Recommended: Gradle **product flavors** — `personal` keeps
  `applicationId = com.atelierapps.vault` exactly as now (the owner's phone
  keeps working, untouched); `play` gets a new, neutral applicationId (also
  avoids "vault" showing in the public Play URL). The owner moves their
  library across, if they want, with an encrypted backup → restore (proven at
  803 items). Never change the `personal` id.
- Every reply that touches this: **"back up first"**.

### B. Build and signing
- `release` has `isMinifyEnabled = true` but has **never been built or run**.
  Expect R8 problems: keep rules for SQLCipher (`net.zetetic`), Room, Media3,
  Coil, WorkManager. Have the owner run `./gradlew :app:assemblePlayRelease`
  (or `bundle…`) and test the release build on the phone before uploading.
- Upload key + Play App Signing; `versionCode`/`versionName` (currently 1 /
  0.1.0). Ship an AAB.
- **Target API** must meet Play's current requirement *(verify — likely
  API 36 for new apps by now; currently 35)*. Re-audit the merged manifest
  after any bump (no INTERNET — hard constraint 2).

### C. Policy items likely to bite (owner decisions in **bold**)
- **Photo & video permissions.** The app uses `READ_MEDIA_IMAGES/VIDEO` for
  its own importer. Play restricts broad media access to apps where it's core
  and requires a declaration *(verify)*. A gallery/vault may qualify; if
  rejected, the fallback is the system Photo Picker — the likely
  **sacrifice**: no in-app folder browser / Select all, and "delete
  originals" may not work for picked items.
- **Launcher disguises** (Link, Notes, Calculator, Weather, Files, Clock via
  `activity-alias`). Play's deceptive-behaviour / impersonation rules may
  object to icons or names that mimic other apps *(verify)*. **Options: keep
  only neutral looks, keep all with plain disclosure in the listing, or drop
  the disguise in the `play` flavor.** Calculator vault apps exist on Play,
  so it's possible, but it's the most likely reason for rejection.
- **Foreground service `dataSync`** needs a declaration and a short video of
  the feature in use *(verify)*.
- **Camera + RECORD_AUDIO** (in-app capture): declare and justify.
- **Data safety form:** nothing collected or shared, no network — a strong
  story. **Privacy policy URL is still required**, so a page must be hosted
  somewhere (the app itself stays offline).
- **New personal developer accounts** must run a **closed test with at least
  12 testers for 14 days** before production *(verify)* — the owner needs to
  line up testers. One-time $25 account fee if they don't have one.
- **Store screenshots:** every screen sets `FLAG_SECURE`, so screenshots
  come out black. Add a debug-only switch (never in release) or capture with
  demo content.
- **Listing name/description** must say what the app does — a public listing
  is the opposite of inconspicuous. **Owner decides the public name.**

### D. Suggested order
Flavors → release build working on the phone → wording polish carried over →
policy decisions with the owner → listing assets → closed test → production.

---

## 3. iOS — later, new repository

Discussed with the owner; nothing started. Summary of what was agreed:
- Native **Swift/SwiftUI**, new repo; the Kotlin doesn't port, the design and
  decisions do. The owner has a Mac + Xcode and a paid developer account
  (install via Xcode for a year / TestFlight).
- Use the **same backup format** (`media/BackupCrypto.kt`: PBKDF2, framed
  AES-GCM, manifest) so backups move between Android and iPhone.
- Secure Enclave keys are P-256, not RSA: wrap per-file keys with ECIES under
  a `.userPresence` key; Keychain/LAContext reuse ≈ the 300 s window.
- Weaker on iOS: name under the icon is fixed at build time; alternate icons
  show a system alert; no FLAG_SECURE (blur on resign-active, hide when
  `UIScreen.isCaptured`); no long background work (backups need the app open);
  Share Extension memory limits for big videos; AVPlayer +
  `AVAssetResourceLoaderDelegate` for decrypt-while-playing.
- First version: unlock, import from Photos, encrypted grid + viewer, tags,
  backup/restore compatible with Android. Then features in batches.
