# Changelog

All notable changes to the sol Android app (part of solstone) are recorded here. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- smart-glasses hardware milestone: sol runs as the default HOME app on the glasses, sealing worn audio+camera segments and syncing them to your paired journal when Wi-Fi is available.

### Changed
- the app now calls itself sol — sol is the app, your journal is the memory, solstone is the platform.

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
