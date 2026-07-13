# Changelog

All notable changes to the sol Android app (part of solstone) are recorded here. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
