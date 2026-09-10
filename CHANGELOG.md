# Changelog

All notable changes to the solstone app on android are recorded here. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- a source you haven't set up yet now says so. it reads `ready to set up`, takes nothing in, and isn't something needing your attention. a source you never set up takes nothing in until you allow its permission.

### Changed
- the solstone app now asks for one permission at a time, from the source you're setting up. the camera screen asks for the camera and nothing else, instead of one tap firing every prompt at once. allowing a source's permission is what turns that source on, so there's no second step to go find.
- if you're upgrading and you already had sources running, they keep running.
- notifications now get their own step, after the first source you turn on, and are asked for once. saying no costs you the ongoing notification, and the note when intake stops. intake itself keeps running, and you can turn notifications on later from settings.
- pairing now tells you what happened and what to do next. every failure names a step you can take, and says it the way the solstone app on your iphone does.
- the local storage screen now reads like the rest of the app: how much space is in use, how much your device has left, the limit you chose, and what the solstone app kept in place rather than remove, with the reason it kept it.

### Fixed
- in light mode, a source that was off could look like a source that was on. its switch drew in the same color the app uses for on, so the control and the words disagreed at a glance.
- `setting up` no longer says it's connecting to your journal when no journal is paired yet. it says what's actually true and points you at pairing.
- `intake was stopped by the system` no longer appears for intake that never started. on a fresh install, while you were still working through the permission prompts, the solstone app treated intake that had never started as intake the system had stopped.
- `start intake again` no longer appears in the middle of setting up, for the same reason.
- turning a source on now asks for its permission in the same tap. before, saying yes landed you on `needs attention: permissions needed` with no prompt in between.
- opening the solstone app no longer flashes a white screen first.
- backing out of `connect a journal` or `manage local storage` now returns you to the app instead of an internal diagnostics menu.
- the home-screen audio widget no longer uses attention colors for a source that is simply off, or one you haven't set up yet. it does use them when the solstone app can't read its own state, which it previously reported as fine.
- the app no longer calls itself `sol`, or its intake `observing`, anywhere in its own words.
- the pairing scanner now says `point your phone at the code`.

## [2.0.0] - 2026-09-08

### Added
- the solstone app now shows how much space its local copy of your memories uses on your phone and lets you choose a limit. when it needs room, it removes only what your journal has already confirmed; anything it cannot safely remove stays in place, and the app tells you what it kept.
- tapping a pairing link now opens the solstone app on android and starts pairing, whether the app was closed or already open. invalid links are identified, and expired links tell you to create a new one from your journal.
- a redesigned home screen brings together source controls, imports, sync status, settings, and your journal. wider screens keep the overview beside the detail you're working in.
- a new home-screen audio widget shows its current state and lets you turn audio on or off without opening the app.

### Changed
- the solstone app on android now asks only for approximate location and no longer repeats an unchanged position every second in your journal.

### Fixed
- fixed a case where matching material from two sources could be treated as already in your journal after only one source arrived.
- recovery buttons now work when permissions change, access to your journal is revoked, or local storage fills up, taking you directly to permissions, pairing, or local storage controls.

## [0.2.2] - 2026-07-16

### Fixed
- sol no longer re-sends audio your journal has already finished processing. syncing kept sending the same pieces again, quietly costing bandwidth and battery. sol now takes the journal's confirmation as proof and stops; when the journal can't confirm it holds something, sol keeps its local copy.
- the status screen now describes what's actually going on, in plain language, and only claims what sol can verify on your device. something that failed to start no longer shows a fresh start time.
- pairing failures now give a reason you can act on. sol tells you whether your phone was offline or your journal didn't answer, so you know what to check next.
- the permissions screen keeps itself current. status updates in place, the battery setting sol relies on shows its real state with steps for your specific device, and the settings shortcut opens the right page (on some Samsung and Rokid devices it went nowhere).
- the camera preview for scanning your pairing code is no longer distorted. it shows exactly the area sol can read, so lining up the code is predictable.

## [0.2.1] - 2026-07-13

### Fixed
- the app's menu is no longer hidden behind the bar at the top of the screen. on first open everything sol can do is reachable, including granting permissions and scanning the pairing code — both were sitting entirely behind that bar, and the menu was too short to scroll them into view, so there was no way to reach them at all. the screens that looked empty were showing their status in that same covered strip; they read normally now.
- "sync now" no longer does nothing without telling you. if your phone isn't paired with a journal yet, it says so and points you at the pairing code. if it is paired, it confirms the sync was queued — and it doesn't tell you your observations arrived until they actually have.
- the back gesture now goes back. from any screen it returns to the menu instead of closing sol; from the menu it exits, as you'd expect.

## [0.2.0] - 2026-07-04

### Added
- your phone now encrypts the credential and identity it uses to pair with your journal, wrapping them with a key held in the android keystore on your phone. if that file is ever read off your phone, there's no usable key sitting in it.
- your phone now recognizes a shared tailscale network as a direct way to reach your journal, not just the same wi-fi. if pairing failed with a "different networks" message while your phone and journal were both on tailscale, that's resolved.

### Changed
- the app is now sol. sol is the app on your phone, your journal is the memory it keeps, and solstone is the platform they're part of. the launcher name, the icon (now the sol mark), the ongoing notification, and the tips for keeping it running in the background all say sol now.

### Fixed
- two ways your phone's pairing could have been intercepted are now closed. pairing over a relay used to send the one-time pairing secret through a tunnel that didn't check the other end, so a malicious relay could have read it; pairing on a local network could have its certificate check sidestepped. your phone now pins and verifies the far end's certificate before it sends anything, and stops cold if it doesn't match.
- when your phone says your observations are synced, they now really are. it reports caught-up only once everything has actually landed in your journal, recovers uploads that a crash or a sleeping computer left stranded, and retries the failures worth retrying instead of stopping. the first sync after this update may push a backlog it can now tell was never confirmed.
- observations that used to be dropped before reaching your journal now make it in. location was the big one: nearly every session was quietly losing all of it. and a clock change, daylight saving included, can no longer collide in a way that overwrites observations already saved.
- sol runs more steadily on your phone now. it survives a screen rotation without interrupting, picks back up on its own after a restart without you reopening it, and syncs in the background without the app open. the ongoing notification flags when it needs attention instead of always reading as on, and pairing by QR no longer reports success when it didn't happen.

## [0.1.1] - 2026-06-29

### Added
- your phone now sends your journal a small health note each time it syncs, showing its name, version, how long it's been running, and whether syncing is keeping up. it's enough to see at a glance that your phone observer is healthy, and it carries none of what your phone observes with you: no voice, location, or photos.

### Fixed
- if the computer your journal lives on was asleep or offline, syncing from your phone could time out and fail. now your phone keeps its place, and your observations sync as soon as your journal is back.

## [0.1.0] - 2026-06-27

### Added

- solstone for Android, in beta — your phone as an observer for your journal. it
  adds what you say and where you are to your journal and syncs it to sol, the
  keeper that lives there.
- pair your phone to your journal and choose what it observes: voice, location, and
  photos — each yours to turn on or off.
- an ongoing notification whenever solstone is active, so it's always clear when
  your phone is observing.
- your phone talks only to your journal, never to a sol pbc server. your data is
  never sold, never shared.
