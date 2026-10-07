# Changelog

All notable changes to the solstone app on android are recorded here. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- the camera's tile and its page now say that while it's on, the solstone app takes a photo from your rear camera every minute and adds it to your journal. its page tells you this before you turn it on.

## [2.1.20] - 2026-10-07

### Added

- after you pair your phone with a journal and confirm its mark, the app asks whether this phone is replacing one of your devices. you can keep both, or choose the device it replaces: your phone then carries on that device's name and history in your journal, and the device you chose can no longer reach your journal. if you close the question without answering, settings › this device shows "device choice" until you do.

## [2.1.19] - 2026-10-05

### Fixed

- pairing your phone with your journal on windows could fail after the journal added the device. the app now keeps the pairing and lets you confirm your journal's mark.
- forgetting your journal or unpairing this device in the middle of a sync could let your phone keep sending to the journal you just left. it now stops there once what was already on its way finishes, and anything still waiting goes to the journal you pair next.
- a moment on its way to your journal when you unpaired could stay on your phone for good, and the waiting count never got back to zero, even after you paired again. it now goes to the journal you're paired with, or the one you pair next, once you've confirmed that journal's mark.
- moments your old journal had trouble taking could wait hours before your phone sent them to the journal you paired next. they now go to it right after you confirm its mark.

## [2.1.18] - 2026-10-04

### Changed

- settings › your journal no longer shows a label. your journal's mark is its name, and TalkBack reads it when you open your journal.

## [2.1.17] - 2026-10-03

### Fixed

- updates and unexpected stops could leave up to five minutes of audio out of your journal. unfinished audio now stays on your phone for recovery when the app next starts.
- old interrupted audio still on your phone that cannot be recovered now shows "audio was interrupted" instead of "all caught up".

## [2.1.16] - 2026-10-03

### Added

- settings › about solstone now shows two lines you can copy with one tap: this app's version and build with your android version, and your journal's version. if copying doesn't work, you can select the text and copy it yourself.

### Changed

- when you report a problem from the app, the support page opens with those same two lines filled in, and you can check or change them before you send. before, a report didn't say which version of your journal you use.
- when you open your journal in the app, links to other websites now open in your phone's browser. before, the app said the link wasn't available in the app.

## [2.1.15] - 2026-10-02

### Fixed

- while your journal's mark is still loading, the pairing prompt shows that it's still connecting to your journal instead of a placeholder mark. if the mark can't be checked, it asks whether to continue or cancel pairing, instead of asking you to say it matches.
- if your journal's mark couldn't be checked earlier, for example while your phone was offline, the pairing prompt checks again when you open it. before, it kept showing "mark unavailable" even once your phone was back online.
- while a pairing waits for you to confirm your journal's mark, the app's status, the status screen and the widget say so, and the status screen can ask you again. before, they could read as connected or syncing while nothing was going to your journal.

## [2.1.14] - 2026-09-30

### Fixed

- this is a security fix: when you pair your phone with a journal, nothing you've kept on it goes to that journal until you confirm it's your journal. before, what you'd kept could start going to it as soon as pairing finished, even to a journal you then said wasn't yours. if you paired before this update, you won't be asked.
- moments you kept with version 2.1.13 didn't reach your journal. they stayed on your phone, and they go into your journal once you install this update.
- leaving the app while another app was using the microphone, such as a screen recording with sound, could mark every source as needing attention, saying intake couldn't start from the background. it stayed that way until you stopped intake and started it again, even though intake kept running. now your audio source says why it's paused, instead of saying you paused it, and the other sources stay on. audio picks up again on its own once the microphone is free again or access is back on.
- the pairing scanner now reads your journal's code from farther away. before, you might have had to hold your phone much closer than the on-screen guides suggested, and try more than once.
- if you stop and start intake again within a few minutes, moments from that stretch are no longer lost before they reach your journal.
- after you change time zones, each new moment goes into your journal under your new local time. before, the app kept the time zone it had when intake started.
- if a saved moment on your phone is damaged, the app now leaves it where it is and starts intake anyway. before, one damaged moment could keep intake from starting.

## [2.1.13] - 2026-09-29

### Changed
- your phone's back gesture or back button now takes you one page back in your journal. on the first page, it closes your journal.

### Fixed

- with Arabic or Persian as your phone’s language, moments now reach your journal.

## [2.1.12] - 2026-09-27

### Changed
- the confirmations for unpairing and forgetting your journal now say where anything still waiting goes: to the journal you pair next.

### Fixed
- copying apps and data to a new phone no longer brings the solstone app's data along. before, that included anything still waiting, a second copy of what your old phone still had. now that stays only on your old phone, so let it reach your journal before you reset that phone, and pair the new phone the usual way.

## [2.1.11] - 2026-09-25

### Fixed
- turning off your last source now stops intake and removes its ongoing notification. if another source is still on, it keeps running.
- if android no longer asks for a source's permission after repeated denials, the app opens android settings for the app so you can allow that source there. when you return, it starts without another tap.

## [2.1.10] - 2026-09-25

### Added
- you can now turn on notifications from your journal, under notifications. your journal encrypts each one to your phone's own key, and turning them off tells your journal to stop sending them.

### Changed
- what the solstone app takes in now goes into your journal right after each five-minute part is finished, while your phone stays in reach of your journal. before, it waited for the app's next regular sync, which could be 15 minutes or more away.

### Fixed
- stopping intake from the notification now asks your phone to send anything waiting to your journal right away. before, it could wait until the next regular sync.
- rarely, after you stopped intake, the ongoing notification stayed and still read as on. now it goes away when you stop intake.

## [2.1.9] - 2026-09-24

### Changed
- the solstone app now removes what it took in from your phone as soon as your journal confirms it has all of it, or that you removed it from your journal, instead of keeping it up to your local storage limit (4 GB unless you changed it). the local storage screen is gone; when your phone is full, "manage local storage" opens your phone's own storage settings. anything an earlier version kept goes from your phone after this update, once your journal has confirmed it.
- the solstone app checks its connection to your journal far less often while it runs, so it uses less battery and data.

### Fixed
- fixed cases where a failed pairing change made your phone ask to pair again after a restart.
- rarely, your phone could lose its pairing and need to be paired with your journal again. it stays paired now.
- the syncing count on your phone could stay up, because larger things waiting to go into your journal kept retrying and didn't catch up. they land in your journal now.
- with the app open, the syncing count could stay at an old number until you left and came back. it now changes as things land in your journal.
- after you forgot a journal the app couldn't reach and paired another, it could keep showing `your journal still lists this device`. that note now goes away once the new journal is paired.

## [2.1.8] - 2026-09-24

### Fixed
- what the solstone app took in could be removed from your phone without a check that all of it had landed in your journal intact. for anything that goes into your journal from this version on, the app removes it from your phone only after every file has landed in your journal exactly as your phone saved it.

## [2.1.7] - 2026-09-23

### Changed
- a relay pairing link whose address text is malformed is now refused when you paste or scan it.

## [2.1.6] - 2026-09-22

### Changed
- the pairing scanner now shows four corner marks in the middle of the screen, so you can see where to point the camera.
- the screen that asks you to confirm your journal's mark now uses the app's own colors and type, with a line explaining where to compare it, and clearer yes and no buttons.

### Fixed
- a pairing link that can't reach your journal now tells you so with a way to try again, instead of leaving you on a screen with no way forward.

## [2.1.5] - 2026-09-21

### Changed
- opening your journal shows its mark as a title line rather than a full card, leaving more room for the journal itself.
- your journal's fingerprint now appears only under technical details, instead of being listed twice.
- settings › your journal no longer repeats how the phone connects, and now shows your journal's label.
- pairing now asks you to confirm that your journal's mark matches before it calls the connection done. if it doesn't match, the phone tries to remove the connection and tells you if the journal may still have this phone listed.
- the screens for connecting a journal and managing local storage now use the app's own buttons and text instead of android's defaults.

### Fixed
- the camera now shuts off as soon as pairing finishes. it was left running behind the screen where you confirm your journal, so on phones that show a camera indicator yours stayed lit. nothing it saw went to your journal.
- when a journal is on a public IPv6 address, a pairing failure no longer tells you to check your wi-fi.
- an IPv6 address no longer runs straight into its port number, so you can tell where the address ends.
- the status card on the phone now spans the full width between the screen's margins, so the greeting behind it no longer shows beside its edge.
- an off switch draws a lighter outline than it did, so it sits back from the things around it.
- the pairing scanner now fills the screen, with what to do written across the bottom, instead of showing a small window part way down the screen.
- scanning a pairing code no longer replaces a journal this phone is already connected to. the app tells you to unpair first, or to get the current journal reachable.

## [2.1.4] - 2026-09-20

### Changed
- connection details now say how your phone connects to your journal, and name a relay as run by sol pbc only when it is.

## [2.1.3] - 2026-09-20

### Added
- forgetting a journal or unpairing this device now asks your journal to drop this device before the pairing goes away on the phone, and tells you if your journal kept its record. before, the journal kept it either way and you had to remove the device there yourself.
- `check connection` now shows what it found: `checking…`, then `reached your journal` or `couldn't reach your journal`. it ran the same probe before and showed you nothing.

### Changed
- the row that says how the app reaches your journal now says `straight to your journal` or `through the relay sol pbc runs`, and names both when both are open. it used to say `direct`, `relay`, or a dash. it is also called `how it connects` now instead of `where it lives`, on the home screen and in `settings › your journal`.
- the confirm for forgetting a journal or unpairing this device now covers what the solstone app has taken in on this device and hasn't gone into your journal yet.

### Fixed
- `intake: running` showed on a fresh install over a screen where every source said `ready to set up`. it now reads `on` only when intake is actually running.
- the journal you open inside the app now keeps up to date. a card saying the connection was lost sat over the journal's home, and its live sections never updated.
- the journal's own tabs inside the app sat under the system navigation bar and couldn't be tapped. tapping search hit the system bar instead.
- the event log held nothing you did. what you do in the app reaches it now, and the empty state says what the log is for instead of showing a dash.

## [2.1.2] - 2026-09-19

### Added
- once you're paired, tap your journal's mark at the bottom of the home screen and your journal opens inside the app, through the same paired connection the app already uses.
- a welcome card on the home screen of a fresh install, with one button to connect a journal.
- settings now has: your journal's details with check connection, pair a new journal and forget this journal; this device's storage and haptics; notifications, with a test notification; an event log and problem reports. settings › your journal no longer says `not paired` when you are paired.

### Changed
- a pairing link can now point at any address you can reach your journal at, not only one on your local network. pairing over the internet works the same way as pairing at home: the link still carries your journal's fingerprint, and your phone still checks it before trusting anything. if pairing can't reach your journal at a public ip address, the message no longer suggests it's a wi-fi problem.
- the status at the top of the home screen now also shows your journal's version and, when one route is set up, how the app reaches it, with technical details one tap further.

### Fixed
- if you tapped `connect a journal` before the app had ever asked for your camera, you got an error instead of the question. the app now asks for the camera first, and if you say no it tells you how to pair without it. allowing the camera there doesn't turn the camera source on.
- if the app quit on its own right after a successful pairing, while it showed your journal's mark, this resolves it.
- after pairing finished, by link or by code, the screen had no button to move on. it now has a `done` button.

## [2.1.1] - 2026-09-18

### Added
- pairing now shows your journal's mark when it succeeds, so you can check that your phone connected to the journal you meant.

### Changed
- you get the app from solstone.app/download/android now, and the file itself comes from updates.solstone.app rather than GitHub. that page also shows how to check the file is ours before you install it. the same bytes stay attached to the GitHub release, under a different filename.

## [2.1.0] - 2026-09-10

### Added
- a source you haven't set up yet now says so. it reads `ready to set up`, takes nothing in, and isn't something needing your attention.

### Changed
- the solstone app now asks for one permission at a time, from the source you're setting up. the camera screen asks for the camera and nothing else, instead of one tap firing every prompt at once. allowing a source's permission is what turns that source on, so there's no second step to go find.
- notifications now get their own step, after the first source you turn on, and are asked for once. saying no costs you the ongoing notification and every notice about intake, including the one after a restart. intake itself keeps running, and you can turn notifications on later from settings.
- pairing now tells you what happened and what to do next. every failure names a step you can take, and says it the way the solstone app on your iphone does.
- the local storage screen now reads like the rest of the app: how much space is in use, how much your device has left, the limit in force and whether you chose it, and what the solstone app kept in place rather than remove, with the reason it kept it.

### Fixed
- in light mode, a source that was off could look like a source that was on. its switch drew in the same color the app uses for on, so the control and the words disagreed at a glance.
- `setting up` no longer says it's connecting to your journal when no journal is paired yet. it says what's actually true and points you at pairing.
- `intake was stopped by the system` no longer appears for intake that never started. on a fresh install, while you were still working through the permission prompts, the solstone app treated intake that had never started as intake the system had stopped.
- `start intake again` no longer appears in the middle of setting up, for the same reason.
- flipping a source's switch now asks for that source's permission, in the same tap. before, the switch only recorded your preference: the screen went to `needs attention: permissions needed`, and granting was a separate button that then asked for everything at once.
- `stop intake` in the notification now stops it. before, opening the app again started it back up without asking you.
- after you press `stop intake`, the sources that were on read `paused` and offer `resume intake`.
- opening the solstone app no longer flashes a white screen first.
- backing out of `connect a journal` or `manage local storage` now returns you to the app instead of an internal diagnostics menu. those two screens no longer draw their own back button; your phone's back gesture is what leaves them.
- the home-screen audio widget no longer uses attention colors for a source that is simply off, or one you haven't set up yet.
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
