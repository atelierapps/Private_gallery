# Session log

A running record of what each working session built, what the owner has
confirmed on the phone, and what is still open. Newest session first.
`CLAUDE.md` describes the app as it stands; this file says how it got there
and where the next session should pick up.

---

## Session ending 2026-11-13

Branches: `claude/photo-video-app-specs-acawm0` (default) and
`claude/vault-android-continuation-ye2jnk`, both at the last commit below.
Schema went **9 → 12** this session; every migration is additive.

### Built, and confirmed working by the owner

- **Design pass.** Every chip drawn through `VaultChip`/`VaultActionChip`
  (brass outline, no M3 purple); no "vault" text on screen, starting with the
  fingerprint prompt; viewer control row restyled, with buttons white when
  off and brass when on and a 2-second notice for shuffle, slideshow and loop;
  tag sheet as a bottom sheet, later made half see-through with no scrim so
  the video shows through while tagging.
- **Notes and view history** (schema 9): Unwatched chip, "Not seen lately"
  sort, notes searchable and carried through backups.
- **Video trim and split**, original binned only after the copy verifies;
  **Set thumbnail** as its own mode, with keyframe seeks while dragging, an
  exact seek on release, and ±1 frame / ±1 s steps.
- **Trim failures fixed.** The cause was the Keystore's 300-second unlock
  window, not the app locking: a newly saved file's key wasn't cached, so
  verifying it needed an unwrap that failed. New keys now go straight into
  `DekCache`; trims hold `TransferWake` so the auto-lock waits.
- **Sort untagged** (one item at a time, tags open).
- **Foreground service** for long backups and restores (silent
  "Copying files" notification, owner-approved).
- **Back-to-top arrow** docked to the floating lock button.
- **Views row** (schema 10): Watch later, Continue watching, Most watched,
  plus saved views (+ Save view / Edit view lead the row).
  - Most watched: the 30 most viewed among items opened at least twice.
  - Continue watching: the 30 most recent, opened within the last 24 h.
- **Typo-tolerant search** (`filter/Search.kt`, unit-tested).
- **Watch later queue** (schema 11): clock button on the viewer's top bar,
  selection menu.
- **A–B repeat** (fixed via `PlayerMessage` after polling never fired on the
  phone) and **timeline bookmarks**.
- **Viewer gestures:** brightness/volume drags removed; swipe down closes,
  swipe up opens tags, hold for 2×; **low-light** button (system → low →
  ultra low) in the floating dock above the lock.
- **Watching stats** card in Settings → Storage.
- **Grid preview:** long-press, then slide down without lifting; 20 s max,
  stops silently, tap replays; home grid and albums.
- **Resume only within 24 h**; older videos start from the beginning.
- **Albums from tags**, and albums that **follow their tags** (schema 12):
  new matches offered on opening via Review; turned-down and removed items
  are never re-offered; Stop following tags in the album menu.
- **Backups** now carry albums (with tag rule and skip list), auto-tag rules,
  saved views, pins, video rotation, bookmarks and watch-later place.
  **Round trip confirmed at full scale:** the owner made a fresh full backup
  to a new folder, deleted every item, and restored all 803 (photos and
  videos) with albums intact; restoring again over the existing library
  created no duplicates.
- **Watching stats card** — "looks nice".

### Built but not yet tested by the owner

- Nothing outstanding.

### Decisions the owner made (don't undo without asking)

- Rotate stays in the viewer's control row (used often).
- Editors (trim, thumbnail, crop) get no swipe gestures.
- No picture-in-picture, background audio, cloud sync or playlist sharing
  (they break the no-internet / stay-hidden rules).
- Tag groups and hidden albums/decoy dropped as unnecessary.
- Grid preview gesture is long-press then slide down, not a separate button.
- **Auto-tag rules retired.** They matched only source app/domain, and
  nearly everything arrives as a gallery download, so they never tagged by
  content; on-device recognition was ruled out (generic labels, no identity,
  and Google's on-device ML pulls in network transport). Removed from the UI,
  from saving and from backups; the table stays so the schema doesn't
  change. Tags rules had already applied stay on their items.
- Continue watching and resume both use the 24-hour cut-off.

### Corrections made this session

- An earlier reply claimed Compose dialogs and sheets don't inherit
  FLAG_SECURE. That was wrong; they do (`SecureFlagPolicy.Inherit`, checked
  in the Compose source). Recorded as a trap in `CLAUDE.md`.

### Open / possible next steps

- Run the backup → restore test above.
- Optional: record watch *time* if the owner wants it in stats (needs a new
  column).
- Optional, owner's own words "nerding out": more detail in the Watching
  card on which tags get watched (e.g. per-tag views over time, or recently
  watched tags). Not requested as work.
- Optional: grid preview from search results or views (already works there,
  since it's the home grid), and inside the Sort untagged screen (not yet).
- The September audit's "deliberately left" items in `CLAUDE.md` still stand.

### Commits this session (oldest → newest)

```
7b3308f Draw every chip through one component, in brass outline instead of M3 purple
64b59fc Stop saying "vault" on screen, starting with the fingerprint prompt
1b5f8ae Viewer: shuffle joins the control row, lighter bars, tags in a bottom sheet
3c4cd00 Viewer buttons: white when off, brass when on; shuffle is a real switch
40ced38 Backup: "Retry these" finishes the run instead of starting a new one
609cb70 Settings: say how many items the last backup is missing
618d193 Unlock shows the disguise's icon; sort moves over the grid; import bar restyle
59c5d2e Notes and view history (schema 9): Unwatched filter, "Not seen lately" sort
bb322d9 Video trim: keep one stretch of a clip, original binned only after it verifies
949caff Pick a video's thumbnail from the trim screen
78e85ca Set thumbnail is its own mode, not a side effect of the trim handles
39c4106 Fix two audit leftovers: endless share retries, never-released file grants
f350a6c Sort untagged: the untagged pile one item at a time, tags already open
015502e Split a video into several parts, each kept as its own item
9574008 Keep long backups and restores alive through deep Doze
58ebe8d CLAUDE.md: move the two fixed audit items out of "deliberately left"
ad5f5e6 Make the viewer tag sheet half see-through
e80a32e Fix trims failing after five minutes unlocked
b2a19e0 Make thumbnail scrubbing keep up, and add frame steps
f67f387 Add a back-to-top arrow docked to the floating lock
ffa24ba CLAUDE.md: the read-after-write face of the 300 s window, and new features
adac6f0 Add the saved_view table (schema 10)
9c5b74c Add views: Continue watching, Most watched, and your own saved ones
eb7550a CLAUDE.md: schema 10 and the views row
74bfa33 Carry albums, rules, saved views, pins and rotation in backups
067b629 Add bookmark and watch-later columns (schema 11)
1dfd7cb Add a watch-later queue, and make search forgive typos
02d024a Add A-B repeat and timeline bookmarks to the player
899dde2 Carry bookmarks and watch-later places in backups
fe18e4d CLAUDE.md: schema 11, watch later, search, A-B and bookmarks
e62f888 Fix A-B repeat playing straight past B
7a4242a Make Most watched a shortlist, and the view actions findable
a6b199a Put Watch later on the viewer's top bar
af2ac30 CLAUDE.md: Most watched, Watch later button, A-B via PlayerMessage
f923cf7 Swap the viewer's brightness/volume drags for close, tags and 2x hold
01d1f24 Add a low-light button to the floating dock in the viewer
3e82d4e Add a Watching card to the storage stats
dfa636d CLAUDE.md: viewer gestures, low light, watching stats
32358bb Preview an item from the grid: long-press, then flick down
e082724 Preview: stop silently and replay on tap; add it to albums
9f78720 Cap Continue watching at 30; resume only within a day
038b987 Fill an album from tags
034bf25 Continue watching lists only what would resume
3e63b46 Let albums remember the tags they were filled from (schema 12)
1ea1522 Albums follow their tags: new matches are offered on opening
```
