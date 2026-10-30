# Working on Vault

Read this before touching anything. [`vaultappspec.md`](vaultappspec.md) is the
original build spec; this file is what a session actually needs to know, including
the parts the spec has since been overtaken on.

## The app in one paragraph

A private, offline, single-user Android media vault for photos and videos the
owner downloads. It is **disguised** — it presents as a neutral utility called
"Link" (or Notes / Calculator / Weather / Files / Clock, switchable in Settings).
Everything is encrypted at rest. It is sideloaded, has one user, and has no
server, no account, and no network access of any kind.

## How we work

- The owner builds and tests on a real Xiaomi phone (Android 16 / HyperOS).
  **There is no Android SDK in the agent environment — nothing can be compiled
  or run here.** Every change is verified statically and then built by them.
- Every reply that changes code ends with the copy-pasteable build command:
  ```
  git pull && ./gradlew :app:installDebug
  ```
- All work goes on `claude/photo-video-app-specs-acawm0`, which is also the
  repo's default branch. There is no PR to open.
- Commit messages explain *why*, especially when a choice looks odd.

## Hard constraints — breaking any of these is a serious failure

1. **`applicationId` must stay `com.atelierapps.vault`.** It is what the Android
   Keystore keys are bound to. Changing it mints new keys and permanently
   destroys every encrypted file on the device. The user-facing name is changed
   with `activity-alias` entries instead (see `AppDisguise`).
2. **No `INTERNET` permission, ever.** It is stripped in the manifest with
   `tools:node="remove"`. Re-audit the merged manifest after any dependency
   bump — a library that merges one in must be caught.
3. **Stay inconspicuous.** No "vault"/padlock branding, no launcher name that
   gives it away, and think twice before adding anything that posts a visible
   notification. That includes **text on screen** and **system dialogs**: the
   fingerprint prompt once read "Unlock Vault — Your media is encrypted on this
   device". `BiometricAuth` now titles itself with the active disguise's name.
4. **Never lose the owner's media.** Anything that rewrites live data gets a
   verify-then-swap design and a "back up first" warning in the reply.

## Crypto: three keys, and why they differ

| Key | Where | Auth-gated? | Protects |
|---|---|---|---|
| `vault_wrap_v1` | Keystore RSA-2048 | **Yes**, 300s window | Per-file media DEKs |
| `vault_db_v1` | Keystore AES-256/GCM | **No** | The SQLCipher passphrase |
| backup passphrase | PBKDF2 from what you type | n/a | Exported backups |

- The media key gets away with auth-gating because RSA splits: **wrapping uses
  the public key and needs no auth** (so the share target saves while locked),
  **unwrapping needs a recent unlock**.
- The database key **cannot** be auth-gated — every read *and* write needs it,
  and the import worker and share target write with nobody present. Don't
  "harden" this without re-reading `DbKeyStore`'s comment.
- The backup key is passphrase-derived precisely *because* the Keystore key dies
  with the install. There is no recovery, deliberately.
- **The 300-second window is the cause of a whole family of bugs.** Anything
  long-running that unwraps media DEKs will fail partway. `MainActivity`
  prewarms both thumb and blob DEKs at unlock for this reason.
  It also bit anything that **reads a file straight after writing it**: a new
  file's key isn't in the cache, so verifying it means an unwrap, which fails
  once you've been unlocked over five minutes. That was the trim failures —
  the integrity check binned good output with "the app locked partway
  through", though nothing had locked. `EnvelopeCodec` now hands the fresh
  DEK to an `onKey` callback and `MediaSaver` / `ThumbnailSetter` put it in
  `DekCache` directly (only while unlocked, guarded by `DekCache.session`).
  `UserNotAuthenticatedException` now reads "the unlock had timed out".

## Database

- Room + **SQLCipher** (`net.zetetic:sqlcipher-android:4.9.0` — pinned, *not*
  latest: 4.10+ drags in androidx.sqlite 2.7.0 and kotlin-stdlib 2.2, both ahead
  of Room 2.6.1 / Kotlin 2.0.21 here).
- **Schema version 9.** Migrations 1→2 … 8→9 all live in `VaultDatabase`.
  9 added `note`, `lastViewedAtMillis` and `viewCount` (DEFAULT 0) to `media`.
  Bump the version *and* add the migration; there is no destructive fallback,
  by design.
- `DbCipher` migrates a legacy plaintext database once, verifying table by table
  before swapping. `VaultDatabase.open()` tries the mode it believes the file is
  in and **falls back to the other**, so a wrong guess can't brick the app.

## Where things live

```
crypto/      envelope format, Keystore wrappers, DEK cache
data/        Room entities, DAOs, VaultRepository (single data entry point)
media/       save pipeline, export/restore, backup crypto, image editor, name templates
imports/     durable import queue + worker
session/     process-scoped state: locks, prefs, disguise, tile anchor
ui/          one package per screen; ui/theme is the design system
```

- `VaultGraph` is the whole dependency graph. `VaultRepository` is the only way
  to touch data.
- `ui/theme/VaultTheme.kt` is the single source of visual truth — palette,
  type scale, spacing, and a **mapped `darkColorScheme`** so Material components
  don't draw in default purple. Use tokens; never a raw hex.
- Shared components live in `ui/theme/VaultComponents.kt`: `ScreenHeader`,
  `VaultIconButton`, `SectionLabel`, `HeaderAction`, `FailureList`. Also
  `ui/theme/TagPicker.kt` — **every** tag-chip picker goes through it. Four
  dialogs had each hand-rolled the same row and two had capped it at twelve.
  It owns the find-or-add field, the Recent / Most used / A–Z order, the
  per-chip live counts and the "on this item" group. Recent tags are kept in
  `TagPrefs` as **ids only** — prefs are plaintext, and a list of tag names
  would be a summary of the library sitting outside the encrypted database.
- **Every chip is `VaultChip` or `VaultActionChip`** (`ui/theme/VaultChips.kt`).
  Material's selected chip fills with `secondaryContainer`, which was never
  mapped, so every selected chip drew in stock M3 purple-grey. Selected is now a
  brass outline + brass label + check on `SurfaceHigh`; never call `FilterChip`
  / `InputChip` / `AssistChip` directly.
- **The design lives on two Artifacts:** the Link Design System
  (https://claude.ai/artifact/TZhopxithumxZJzLbFZbdp — tokens, components,
  brand rules) and the screen canvas (https://claude.ai/artifact/Mbv6vHi5s9qFUWtDTRWs2L).
  Screens are rebuilt from the canvas in batches.

## Traps that have already cost time here

- **An immersive screen's `statusBarsPadding()` is zero.** `ViewerActivity`
  hides the system bars, so that inset collapses and anything relying on it
  sits flush against the top edge — which is under the punch-hole camera on
  the owner's phone. `displayCutoutPadding()` is the inset that survives
  immersive mode; pair the two, as the viewer's top bar does.
- **Material's `AlertDialog` does not scroll its body.** The text slot gets
  `weight(1f, fill = false)` and nothing else, so tall content is silently
  clipped at the dialog's edge rather than becoming scrollable. Anything
  list-shaped in a dialog needs its own bounded `verticalScroll`.
- **`MediaSaver`'s catch block deletes the blob it just wrote.** Everything
  after the encrypt-and-verify step runs inside that try, so anything cosmetic
  that throws there discards verified media. Three did: the metadata probe,
  the thumbnail frame, and — worst — tagging inside `saveMedia`, which runs
  *after* the row insert. That one left a live row with no file (a "ghost"):
  a blank tile that never plays, which a retry then deduped against and threw
  the spool away. All three are non-throwing now. Keep anything added below
  step 2 that way, and **anything that dedups by hash must also check the blob
  exists** — MediaSaver, the restore skip and the duplicate finder's keeper all
  needed that.
- **AES/GCM on Android does not stream.** Conscrypt implements it over
  BoringSSL's one-shot AEAD: `Cipher.update()` emits *nothing*, copying input
  into a buffer it doubles as it grows, and `doFinal()` seals everything at
  once. `CipherOutputStream`/`CipherInputStream` don't change that. So GCM over
  a large file is the whole file in memory — this is what made every big
  video fail backup with OutOfMemoryError. Backup items are therefore framed
  (`BackupCrypto`, 1 MiB frames, frame index as AAD). AES/CTR is a plain
  stream cipher and is fine; it is why video blobs use it.
- **Stale captures in gesture handlers.** This has bitten three times. A
  `pointerInput` block captures values from the composition that created it; if
  the value changes every drag delta, the handler keeps using the old one. Use
  `rememberUpdatedState`, or read live state at gesture start. Related: don't
  key `pointerInput` on anything freshly allocated each recomposition —
  `asImageBitmap()` returns a new wrapper every call and tore down a gesture
  mid-drag.
- **`VaultIconButton(icon, description, onClick, ...)` takes `onClick` third,
  not last.** A trailing lambda binds to `size: Int` and fails confusingly.
- **Don't invent Material icon names.** `DriveFileMoveOutline` doesn't exist;
  that cost a build. Prefer one already used in the codebase, or accept the
  round trip knowingly.
- **`MediaSaver.save` runs on the caller's thread.** It encrypts the whole
  file synchronously, so every caller wraps it in `Dispatchers.IO` (the image
  editor, the trimmer). From a `viewModelScope` without that, a big video
  freezes the app.
- **A trailing lambda binds to the *last* parameter.** Adding a parameter
  after a callback that callers pass as a trailing lambda breaks every one of
  them; keep callbacks last (`VideoTrimmer.trim`'s `part` nearly did this).
- **Media3 has its own `ExportResult`**, which clashes with the backup one in
  `media/`; import it aliased (`as TransformerResult`).
- **Verify call sites, not diffs.** A partly-applied edit once shipped a commit
  that couldn't compile because three call-site lines were missed. After
  changing a signature, check every caller programmatically.
- **Compose-side checks that catch most of it without a compiler:** brace/paren
  balance, duplicate imports, symbols used but not imported, icon imports, and
  no stray control characters in written files.
- **Gradle failures that aren't code:** a corrupt configuration cache
  (`./gradlew --stop && rm -rf .gradle/configuration-cache`) and an unauthorised
  device (`adb kill-server`).
- **Verify third-party APIs rather than guessing.** Downloading an AAR and
  running `javap` on it has settled two questions cleanly (Coil's `onSuccess`
  overload, SQLCipher's factory signature). **The AAR route is now blocked** —
  the agent proxy refuses `dl.google.com` / `maven.google.com`, so Gradle
  artifacts can't be fetched. `raw.githubusercontent.com` *does* work, so read
  the library's source at its release tag instead:
  `https://raw.githubusercontent.com/androidx/media/1.5.1/libraries/.../Foo.java`.
  That settled the Transformer questions in the open queue below.

## What exists now

Everything in the spec, plus: recycle bin with 30-day purge, albums, tags +
manager + auto-tag rules, duplicate finder, search, six sort orders, date
sections, fast scroller, one-tap Favourites / Untagged / Short filter chips
(Source was dropped from the bar — it only ever said "Download"; sources now
sit at the bottom of the Type menu), play a selection, every video loops by
default unless a slideshow is running (Settings → Playback), shuffle in the
viewer's control row (not the home bar) — an on/off switch that shuffles
around the current item and restores the order around it when turned off.
Viewer buttons are white when off and brass when on, and shuffle, slideshow
and loop confirm with a 2-second notice (rotate, speed, mute and tag don't),
notes on any item (viewer ⋮ → Note…, searchable, carried through backups and
image edits), video trim (keeps one stretch; original to Recently deleted only
after the copy verifies) and a chosen video thumbnail (⋮ → Set thumbnail…, the
same screen in its own Thumbnail mode — a single scrubber, no trim handles)
(captured from the preview's TextureView, so no plaintext file; swapped in
by write-beside, length check, rename — `media/ThumbnailSetter.kt`; the
scrubber seeks to keyframes while dragging and exactly on release, with
±1 frame / ±1 s steps), the viewer's tag sheet is half see-through with no
scrim so the clip shows through while tagging, Sort untagged (home ⋮ → one untagged item at a time with
the tag row open: Tag & next / Skip / Delete with undo — `ui/triage/`), view history (counted after 2 s on screen; Unwatched chip and a
"Not seen lately" sort — history starts at schema 9, so everything older begins
as unwatched), storage stats, camera capture, share-in target, durable
bulk import with batch tagging, template bulk rename, image rotate/crop,
per-video display rotation, resume playback, slideshow, shuffle, floating lock
button (with a "back to top" arrow docked to it once the home grid is a
quarter scrolled — one draggable pair, so they can't overlap), app disguise + custom home-screen shortcut, encrypted (or plaintext)
backup with round-trip restore, and an uninstall warning.

Diagnostics, because "won't play" and "is damaged" look identical on screen:
the video player names the cause when playback fails (with ExoPlayer's error
code), and **Settings → Storage → Check library** decrypts every item and
compares it against the SHA-256 recorded at import — the only thing that can
say whether data at rest has actually changed. Start there for any report of
corruption.

## Open queue

1. **Video trim — built** (viewer ⋮ → Trim…, `media/VideoTrimmer.kt`, `ui/trim/`).
   The owner accepted the one exception to "no plaintext on disk": Transformer
   only writes to a file path (`Mp4Muxer` seeks via `getChannel()` to backfill
   the moov atom), so the kept part is muxed to `tmp/trim-<uuid>.mp4`, encrypted
   in through `MediaSaver`, and deleted in `finally`; the launch sweep removes
   any `trim-` file regardless of age. No overwrite-before-delete: on flash it
   erases nothing. Input is read through `VaultCtrDataSource` via
   `ExoPlayerAssetLoader.Factory`. No effects, so it transmuxes (no re-encode,
   cuts on keyframes). The original is binned only after the copy is probed,
   saved and passes `MediaIntegrity`. **Split** is the same screen: "Add as a
   part" collects ranges, and each is cut as its own item ("clip (part 2).mp4");
   the original is binned only after *every* part verifies, and a failure stops
   the run with the original kept and the landed parts left as new items.
   A run holds `TransferWake`, so the auto-lock waits for it to finish.
2. **Foreground service for long transfers — built** (`media/TransferService.kt`).
   Started and stopped by `TransferWake` (first acquire / last release), so
   export, retry, restore and the library check all get it. It does no work; it
   only keeps the process at foreground priority through deep Doze. Owner-approved
   notification: silent, low importance, disguise name as title, "Copying files
   120 / 482". **The app never requests POST_NOTIFICATIONS** — on 13+ the
   notification only shows if allowed in system settings; the protection works
   regardless. dataSync type, START_NOT_STICKY, stops on Android 15's 6-hour
   `onTimeout`. HyperOS battery saver can still kill it; the owner can exempt
   the app in battery settings.

### Found by the September audit and deliberately left

Read before "fixing" one of these; each was weighed.

- **`addTags` / `setTagsForMedia` are not `@Transaction`.** A failure partway
  leaves some tags applied and some not. Harmless for these two. (`saveMedia`
  was on this list too, wrongly — see the MediaSaver trap; it is fixed.)
- **`DbCipher` has a one-instruction window** between renaming the plaintext
  database aside and moving the encrypted one in. A crash exactly there leaves
  no `vault.db`, and the next launch builds an empty one — blobs intact,
  metadata gone. Only reachable on a first upgrade from a plaintext database,
  which this install is long past.
- **The unlock pre-warm is O(library).** Two RSA unwraps per item at ~18 ms
  across four workers, and nothing cancels it on lock. Fine at a few thousand
  items; revisit if it ever feels slow at unlock.

Fixed since the audit: SAF persistable grants (released per entry by
`ImportWorker`, plus a sweep of old ones when the queue drains, skipping grants
under ten minutes old and all tree grants) and `SaveMediaWorker`'s endless
retries (a missing spool fails at once; five attempts otherwise).

Known, accepted, not bugs:

- Restoring from a cloud folder (Drive) is minutes per file. That is the SAF
  provider streaming over the network; decryption is microseconds. The advice is
  to copy the folder locally first.
- Import dedup checks **live** items only, so re-importing something that is in
  the recycle bin brings back a second copy. That is deliberate — the
  alternative is media silently vanishing into the bin — and the duplicate
  finder is the cleanup path.
