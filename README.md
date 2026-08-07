# XCSoar Compet

Android app that fetches a competition day from **SoaringSpot** and installs it
into **XCSoar**: the task, the organiser's waypoints, and the airspace file with
the day's *inactive* airspaces removed.

Written in Kotlin, no third-party runtime dependencies. MIT licensed.

## Why

SoaringSpot publishes no downloadable task file, and its API needs a key that
the organiser generates for their own competition only. The task therefore has
to be rebuilt from the competition's *task* page. Existing tools stop at the
turnpoints; this one also carries over what actually matters in flight:

| | XCSoar Compet | GlideAndSeek-based tools |
|---|---|---|
| Turnpoints and observation zones | yes | yes |
| AAT detection and minimum time | **yes** | no |
| Start/finish rules (max start speed, min finish height) | **yes** | no |
| Inactive airspaces removed | **yes** | no |

Coordinates come from the organiser's `.cup`. When it is missing or empty,
they are fetched from the GlideAndSeek API instead, and a waypoint file is
written from them — so the task can always be built. Start and finish rules
are read from task notes in French, English and German.

## What it writes

Into `Android/media/<xcsoar-package>/`:

| File | Content |
|---|---|
| `compet_task.tsk` | the task |
| `Default.tsk` | same task, loaded automatically at XCSoar startup (optional) |
| the organiser's `.cup` | official waypoints, under their published name |
| `compet_airspace.txt` | organiser's airspace minus the day's inactive ones |

With the last option ticked, the profile's `AirspaceFileList` is pointed at
`compet_airspace.txt`, so nothing has to be selected by hand.

**Restart XCSoar** after installing: the task file is read at startup.

## Requirements

- Android 8.0 or later
- **All-files access**, granted on first run — this is the only way to write
  into another app's `Android/media` folder
- **XCSoar 7.43 or later**, installed fresh or already storing its data in
  `Android/media`

### If XCSoar still uses its private folder

XCSoar keeps using `Android/data/<package>/files/` — which no other app may
touch — as long as an `xcsoar.log` sits there. That is deliberate: it protects
the configuration of users upgrading from 7.42 or earlier. In that case XCSoar
does not even look at `Android/media`, and anything written there is ignored.

The app detects this (no `xcsoar.log` in the media folder means XCSoar has
never written there) and warns instead of pretending to succeed. Tap the
warning for the migration options.

## Installing

Grab `app-release.apk` from the [latest release](https://github.com/F-CPJV/xcsoar-compet/releases/latest),
or build it yourself. Android will ask you to allow installation from this
source; the APK is signed with the project's own key.

## Building

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # parser tests
./gradlew assembleRelease        # signed, needs keystore.properties (not in git)
```

Requires JDK 17+ and an Android SDK with platform 35.

## Tests

The unit tests run against **real SoaringSpot pages** kept in
`app/src/test/resources` (French Nationals 2026, Villefranche-Tarare): a racing
task, an AAT, the downloads page and a search result page. If SoaringSpot
changes its template, these fail before pilots do.

The generated task file has been verified byte-for-byte against an independent
implementation, and loaded in XCSoar 7.44 on a Fossibot F101P — including the
AAT type and its 2 h minimum time.

## Design notes

- The page is parsed through its CSS classes (`task-duration`, `task-version`,
  `task-excluded-airspaces`, `table.task`), not the displayed text, which is
  translated and reworded.
- Observation zones map to XCSoar's named zones when the published parameters
  match them exactly — `90° / 20 km / 500 m` is a **BGA Fixed Course**,
  `90° / 10 km / 500 m` a **DAeC Keyhole**, `180° / 10 km / 500 m` a **BGA
  Enhanced Option**. Anything else stays a `CustomKeyhole`, which keeps the
  organiser's own numbers: the named zones carry a hard-coded 500 m cylinder
  and would silently replace a different one. A `360°` sector becomes a plain
  cylinder, which is what an AAT area of that shape means.
- Airspace names are matched in full, whitespace-normalised and case-folded.
  The activation suffixes (`(MON-FRI)`, `(MON-FRI HX)`) are part of the name,
  so `TMA LYON 3` is never removed when `TMA LYON 3 DES R3201B` is inactive.
- Anything unrecognised is reported in the log rather than silently guessed.

## Safety

This is a convenience tool, not a source of truth. Check the task against the
official briefing before flying it. Airspace filtering only removes what the
organiser listed as inactive for the day.
