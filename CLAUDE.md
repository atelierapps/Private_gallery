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
   notification.
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

## Database

- Room + **SQLCipher** (`net.zetetic:sqlcipher-android:4.9.0` — pinned, *not*
  latest: 4.10+ drags in androidx.sqlite 2.7.0 and kotlin-stdlib 2.2, both ahead
  of Room 2.6.1 / Kotlin 2.0.21 here).
- **Schema version 8.** Migrations 1→2 … 7→8 all live in `VaultDatabase`.
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
  `VaultIconButton`, `SectionLabel`, `HeaderAction`, `FailureList`.

## Traps that have already cost time here

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
  overload, SQLCipher's factory signature). The network here allows it.

## What exists now

Everything in the spec, plus: recycle bin with 30-day purge, albums, tags +
manager + auto-tag rules, duplicate finder, search, six sort orders, date
sections, fast scroller, storage stats, camera capture, share-in target, durable
bulk import with batch tagging, template bulk rename, image rotate/crop,
per-video display rotation, resume playback, slideshow, shuffle, floating lock
button, app disguise + custom home-screen shortcut, encrypted (or plaintext)
backup with round-trip restore, and an uninstall warning.

## Open queue

1. **Video trim / split** — the one substantial feature not built. Media3
   Transformer, same library the player already uses. The argument is *storage*,
   not quality: keeping two good minutes of a 500 MB clip. The work isn't the
   trim, it's feeding Transformer from an encrypted blob through the same custom
   `DataSource` the player uses.
2. **Foreground service for long transfers.** Export/restore currently survive
   leaving the screen (app-scoped coroutine) and screen-off (wake lock), but not
   deep Doze or process death. Fixing that needs WorkManager + a visible
   notification — offered and not yet wanted, because of constraint 3.

Known, accepted, not bugs:

- Restoring from a cloud folder (Drive) is minutes per file. That is the SAF
  provider streaming over the network; decryption is microseconds. The advice is
  to copy the folder locally first.
- Import dedup checks **live** items only, so re-importing something that is in
  the recycle bin brings back a second copy. That is deliberate — the
  alternative is media silently vanishing into the bin — and the duplicate
  finder is the cleanup path.
