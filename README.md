# XCSoar Compet

Android app that fetches a competition day from **SoaringSpot** and installs it
into **XCSoar**: the task, the organiser's waypoints, and the airspace file with
the day's *inactive* airspaces removed.

Written in Kotlin, no third-party runtime dependencies. MIT licensed.

## Status

Working and flown-tested on the bench, not yet in competition. Version 0.9,
sideloaded — not on any store.

Verified against **29 competitions in 16 countries**: 47 tasks parsed, 26 of
them AAT, all built, no failure. The full report, with the version and time
that produced it, is in [`docs/live-survey.md`](docs/live-survey.md).

What is known to work: task and AAT minimum time, observation zones, start and
finish rules, inactive airspace filtering, competition search, the profile
being updated so XCSoar actually loads the files, and installing on Android 15
and later. Each of those cost a bug fix along the way; the release notes tell
the story.

Known limits, all measured rather than guessed:

- **Start and finish rules are often absent** — 29 of 47 tasks in the survey.
  Almost never a language problem: most organisers publish no task notes at all.
- **A "next" observation zone cannot be represented.** SoaringSpot publishes a
  sector oriented on the following leg, which XCSoar cannot store. It is
  approximated by a cylinder of the announced radius and reported in the log.
- **An altitude limit carried by a zone is lost.** XCSoar stores a maximum
  start height and a minimum finish height, but no ceiling on a zone. Reported
  in the log.
- **Some organisers publish broken files** — a 0-byte `.cup` in Romania, a
  turnpoint file from 2024 for a 2026 event in Austria. The GlideAndSeek
  fallback covers the first case; nothing can be done about the second.

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
| `compet_waypoints.cup` | only when the organiser published no usable file |
| `compet_airspace.txt` | organiser's airspace minus the day's inactive ones |

With the last option ticked, the profile lists the airspace and waypoint files
so nothing has to be selected by hand. Both **replace** what was there: keeping
your national airspace file would put back the airspaces the organiser
deactivated, and the organiser's waypoint file is the reference during an event
— overlaying your own base would show duplicate points at slightly different
coordinates. The previous values are printed in the log so you can restore them
afterwards.

**Close XCSoar before installing** — it rewrites `default.prf` when it exits,
so a running instance undoes the profile changes. **Restart it afterwards**:
the task file is read at startup.

## Why the airspace file is filtered, rather than acknowledged in XCSoar

XCSoar can silence an airspace for the day: `AcknowledgeDay()`, reachable from
the map-item dialog as **"ACK day"**. It is clean — an acknowledged airspace
drops out of `IsActive()`, so no warning, and every calculation treats it as
inactive. It is nonetheless the wrong tool here, for three reasons:

- **It does not survive a restart.** The state is a plain `ack_day` flag on the
  in-memory warning; nothing in the profile persists it. Since installing a
  task requires restarting XCSoar, any acknowledgement made beforehand would be
  gone by take-off.
- **It cannot be prepared from outside.** No file and no profile key lets
  another application say "these are fine today". They would have to be
  acknowledged one by one — 12 for the French Nationals, 144 for the Romanian
  championship.
- **It only silences warnings.** The airspace stays drawn on the map, cluttering
  the very area the day has opened up.

Removing the blocks from the OpenAir file is therefore the only mechanism that
is persistent, preparable offline and complete: the airspace leaves both the
warnings and the map.

A third route exists in theory — OpenAir carries activation periods and XCSoar
honours `AbstractAirspace::IsActive()` — but no organiser uses it: all 29
competitions surveyed publish unconditional zones and list the inactive ones as
free text on the task page.

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

Grab the APK from the [latest release](https://github.com/F-CPJV/xcsoar-compet/releases/latest),
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
task, an AAT, the downloads page and a search result page. They also patch a
real pilot profile. If SoaringSpot changes its template, they fail before
pilots do.

A second, network-bound harness parses live competitions and is skipped in CI:

```bash
LIVE_SURVEY=1 ./gradlew testDebugUnitTest --tests '*LiveSurveyTest*'
```

It writes `app/build/live-survey.md`: one row per task, the observation zone
forms met in the wild with an example competition for each, and a summary.
Re-run it at the start of a season to check SoaringSpot has not moved.

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
- Inactive airspaces are matched the other way round: SoaringSpot joins their
  names with `", "` without escaping, and names do contain commas — Romania
  publishes `LRR206 - Active H24, except SAR, Police, Medevac`. So every name
  read from the OpenAir file is looked up inside that list, delimited by
  commas, which also stops `TMA LYON 3` from swallowing `TMA LYON 3 DES
  R3201B`.
- Downloads and task pages are retried up to three times. SoaringSpot has been
  seen serving an incomplete page under HTTP 200; at a briefing a one-off
  glitch must not leave a pilot without a task.
- Anything unrecognised is reported in the log rather than silently guessed.

## Safety

This is a convenience tool, not a source of truth. Check the task against the
official briefing before flying it. Airspace filtering only removes what the
organiser listed as inactive for the day.
