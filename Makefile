.PHONY: install test ci ci-device format clean require-android-remote-host sync-android-host android-host-ci android-host-ci-device android-host-assemble-validation-rogbid assemble-validation-rogbid validate-rogbid-adb validate-rogbid-media validate-rogbid-qr validate-rogbid-pl require-dist-env dist-phone android-host-dist-phone ci-device-experimental hitl-phone phone-version phone-bump changelog-cut changelog-notes pull-phone-apk github-release

GRADLE ?= ./gradlew
ROGBID_SERIAL ?= 46734915123233
ANDROID_REMOTE_HOST ?=
# Dedicated remote build tree for `sync-android-host` (which rsyncs with --delete).
# Kept separate from any working clone at ~/projects/solstone-android so the
# destructive sync can never clobber a checkout in use on the build host.
ANDROID_REMOTE_PROJECT ?= ~/android-host/solstone-android
RSYNC_EXCLUDES := --exclude .git --exclude .gradle --exclude '**/build' --exclude artifacts --exclude captures

install:
	$(GRADLE) --version

test:
	$(GRADLE) test

ci:
		$(GRADLE) check :core:model:test :core:sources:test :core:segment:test :core:spool:test :core:queue:test :core:diagnostics:test :core:crypto:test :core:pl:test :core:identity:test :core:observer:test :core:metadata:test :testing:test :harness:test :platform:camera-still:test :platform:work:test :platform:persistence-room:assembleDebug :platform:pl-transport-conscrypt:assembleDebug :platform:pl-transport-conscrypt:testDebugUnitTest :platform:identity-file:assembleDebug :platform:work:assembleDebug :platform:metadata:assembleDebug :platform:metadata:testDebugUnitTest :platform:audio:assembleDebug :platform:audio:testDebugUnitTest :platform:location:assembleDebug :platform:location:testDebugUnitTest :platform:camera-legacy:assembleDebug :platform:camera-legacy:testDebugUnitTest :platform:camera2:assembleDebug :platform:camera2:testDebugUnitTest :platform:fgs:assembleDebug :platform:power:assembleDebug :apps:watch:checkRealDebugMicrophoneManifest :apps:phone:checkRealDebugMicrophoneManifest :apps:glasses:checkRealDebugMicrophoneManifest :apps:watch:checkRealDebugLauncherManifest :apps:phone:checkRealDebugLauncherManifest :apps:phone:checkRealDebugAppLinksManifest :apps:glasses:checkRealDebugLauncherManifest :apps:watch:assembleMockDebug :apps:watch:assembleMockDebugAndroidTest :apps:watch:assembleRealDebug :apps:phone:assembleMockDebug :apps:phone:assembleMockDebugAndroidTest :apps:phone:assembleRealDebug :apps:glasses:assembleMockDebug :apps:glasses:assembleMockDebugAndroidTest :apps:glasses:assembleRealDebug :apps:validation-rogbid:assembleDebug

# Slower device gate: GMD (pixel5api35) instrumented tests. Always host-GL — the
# default GMD GPU path segfaults on the headless build box. Kept separate from `ci`
# so `ci` stays fast.
#
# PHONE-ONLY, deliberately. The phone is the primary quality target; watch and
# glasses are side experiments and are NOT release targets. A red here must always
# mean "the shipping app is broken" — a side-experiment flake that reddens this lane
# teaches everyone to ignore it. Watch/glasses live in `ci-device-experimental`.
ci-device:
	$(GRADLE) -Pandroid.testoptions.manageddevices.emulator.gpu=host \
	  :platform:persistence-room:pixel5api35DebugAndroidTest \
	  :platform:pl-transport-conscrypt:pixel5api35DebugAndroidTest \
	  :apps:phone:pixel5api35MockDebugAndroidTest
	# AC5a real-flavor narrow gate. The class filter must match exactly one test;
	# device-gate operators must confirm the real run reports Tests run: 1.
	$(GRADLE) -Pandroid.testoptions.manageddevices.emulator.gpu=host \
	  -Pandroid.testInstrumentationRunnerArguments.class=app.solstone.observer.phone.RealFlavorOpportunisticSyncRuntimeTest \
	  :apps:phone:pixel5api35RealDebugAndroidTest

format:
	@echo "No formatter is configured yet."

clean:
	$(GRADLE) clean
	rm -rf artifacts captures

require-android-remote-host:
	@test -n "$(ANDROID_REMOTE_HOST)" || (echo "Set ANDROID_REMOTE_HOST=<host>" >&2; exit 2)

sync-android-host: require-android-remote-host
	ssh $(ANDROID_REMOTE_HOST) 'mkdir -p $(ANDROID_REMOTE_PROJECT)'
	rsync -az --delete $(RSYNC_EXCLUDES) ./ $(ANDROID_REMOTE_HOST):$(ANDROID_REMOTE_PROJECT)/

android-host-ci: sync-android-host
	ssh $(ANDROID_REMOTE_HOST) 'cd $(ANDROID_REMOTE_PROJECT) && source ~/android-dev/env.sh && make ci'

android-host-ci-device: sync-android-host
	ssh $(ANDROID_REMOTE_HOST) 'cd $(ANDROID_REMOTE_PROJECT) && source ~/android-dev/env.sh && make ci-device'

android-host-assemble-validation-rogbid: sync-android-host
	ssh $(ANDROID_REMOTE_HOST) 'cd $(ANDROID_REMOTE_PROJECT) && source ~/android-dev/env.sh && make assemble-validation-rogbid'

assemble-validation-rogbid:
	$(GRADLE) :apps:validation-rogbid:assembleDebug

validate-rogbid-adb:
	tools/rogbid/validate-rogbid-adb.sh $(ROGBID_SERIAL)

validate-rogbid-media:
	tools/rogbid/validate-rogbid-media.sh $(ROGBID_SERIAL)

validate-rogbid-qr:
	tools/rogbid/validate-rogbid-qr-preview.sh $(ROGBID_SERIAL)

validate-rogbid-pl:
	tools/rogbid/validate-rogbid-pl-link.sh $(ROGBID_SERIAL)

# --- Firebase App Distribution (beta channel) ---
# Build a signed release APK of the phone observer and push it to the
# trusted-testers group on Firebase App Distribution (the Android analog of
# TestFlight). The signed-APK path is browserless and needs no Play account.
# Authenticates non-interactively via ADC (the App Distribution service-account
# key), never an interactive login. The on-box environment supplies, via
# `source ~/android-dev/env.sh`:
#   ANDROID_UPLOAD_KEYSTORE, ANDROID_UPLOAD_KEYSTORE_PASS  (release signing)
#   GOOGLE_APPLICATION_CREDENTIALS                          (App Distribution SA)
#   FIREBASE_APP_ID                                         (Firebase Android App ID)
# Release notes carry the short git SHA. The remote build tree (sync-android-host)
# excludes .git, so the remote wrapper passes RELEASE_REV in from the caller's git.
# Side-experiment device gate: watch + glasses. Not release targets (founder call
# 2026-07-13). Run it when you touch them; never let it gate a phone release.
ci-device-experimental:
	$(GRADLE) -Pandroid.testoptions.manageddevices.emulator.gpu=host \
	  :apps:watch:pixel5api35MockDebugAndroidTest \
	  :apps:glasses:pixel5api35MockDebugAndroidTest

# --- HITL: the real-hardware human-usability gate (blocks the release) ---
#
# solstone-android 0.2.0 reached a paid contract tester with a harness screen he
# physically could not use: the menu rendered behind the status bar and a platform
# ActionBar, "Permissions" and "Scan pair QR" were entirely hidden, and the menu was
# shorter than the viewport so there was nothing to scroll. Every gate was green,
# because nothing ever looked at the screen. This is the gate that looks.
#
# It runs the RELEASE APK — the exact artifact `dist-phone` ships — on the Galaxy A36
# (API 36, one platform ahead of our targetSdk 35, so it also catches next-platform
# breakage before Google forces it on us). Gating the debug APK here would leave
# release signing and minification unexercised by the only human-usability check we
# have, which would hollow out the whole guarantee.
#
# `dist-phone` DEPENDS on this. There is deliberately no SKIP escape hatch: if the
# device is unhealthy, the device gets fixed. The guarantee we are buying is that a
# build a human cannot use physically cannot reach a tester, and an escape hatch is
# exactly how that guarantee gets spent on a deadline.
ANDROID_HITL_SERIAL ?= RZGL11XCS9D
HITL_FLOW := .maestro/phone-smoke.yaml
HITL_ARTIFACTS = $(ARTIFACTS)/hitl

hitl-phone:
	@command -v maestro >/dev/null 2>&1 || { echo "maestro not on PATH (expected ~/.maestro/bin/maestro)" >&2; exit 2; }
	@adb -s $(ANDROID_HITL_SERIAL) get-state >/dev/null 2>&1 || { \
	  echo "" >&2; \
	  echo "HITL GATE FAILED: device $(ANDROID_HITL_SERIAL) is not attached." >&2; \
	  echo "This gate is required before a phone release can be distributed." >&2; \
	  echo "Plug the Galaxy A36 back in, or set ANDROID_HITL_SERIAL to the phone under test." >&2; \
	  echo "adb devices:" >&2; adb devices -l >&2; \
	  echo "" >&2; \
	  exit 1; }
	$(GRADLE) :apps:phone:assembleRealRelease
	@apk=$$(ls -t apps/phone/build/outputs/apk/real/release/*.apk 2>/dev/null | head -1); \
	test -n "$$apk" || { echo "No signed release APK to gate — is the keystore env set?" >&2; exit 1; }; \
	echo "HITL: gating $$apk on $(ANDROID_HITL_SERIAL)"; \
	adb -s $(ANDROID_HITL_SERIAL) uninstall app.solstone.observer.phone >/dev/null 2>&1 || true; \
	adb -s $(ANDROID_HITL_SERIAL) install -r -g "$$apk" || exit 1
	mkdir -p $(HITL_ARTIFACTS)
	@echo "HITL: driving $(HITL_FLOW) on $(ANDROID_HITL_SERIAL)"
	MAESTRO_DRIVER_STARTUP_TIMEOUT=120000 maestro --device $(ANDROID_HITL_SERIAL) test $(HITL_FLOW)
	@echo "HITL GATE PASSED — every control on every harness screen was reachable on real hardware."

RELEASE_REV ?= $(shell git rev-parse --short HEAD 2>/dev/null)
RELEASE_NOTES ?=

require-dist-env:
	@test -n "$(ANDROID_UPLOAD_KEYSTORE)" || (echo "Set ANDROID_UPLOAD_KEYSTORE (release signing keystore path)" >&2; exit 2)
	@test -n "$(GOOGLE_APPLICATION_CREDENTIALS)" || (echo "Set GOOGLE_APPLICATION_CREDENTIALS (App Distribution SA key)" >&2; exit 2)
	@test -n "$(FIREBASE_APP_ID)" || (echo "Set FIREBASE_APP_ID (Firebase Android App ID)" >&2; exit 2)
	@command -v firebase >/dev/null 2>&1 || (echo "firebase CLI not found on PATH" >&2; exit 2)

# NOTE: hitl-phone is a HARD prerequisite. A phone build that a human cannot use
# must not be able to reach a tester. Do not add a bypass.
dist-phone: require-dist-env hitl-phone
	$(GRADLE) :apps:phone:assembleRealRelease
	@apk=$$(ls -t apps/phone/build/outputs/apk/real/release/*.apk 2>/dev/null | head -1); \
	test -n "$$apk" || { echo "No signed release APK found under apps/phone/build/outputs/apk/real/release/" >&2; exit 1; }; \
	notes="$(RELEASE_NOTES)"; \
	if [ -z "$$notes" ]; then \
	  if [ -n "$(RELEASE_REV)" ]; then notes="solstone-android beta $(RELEASE_REV)"; else notes="solstone-android beta build"; fi; \
	fi; \
	echo "Distributing $$apk  (notes: $$notes)"; \
	firebase appdistribution:distribute "$$apk" --app "$(FIREBASE_APP_ID)" --groups trusted-testers --release-notes "$$notes"

android-host-dist-phone: sync-android-host
	ssh $(ANDROID_REMOTE_HOST) 'cd $(ANDROID_REMOTE_PROJECT) && source ~/android-dev/env.sh && make dist-phone RELEASE_REV=$(RELEASE_REV) RELEASE_NOTES="$(RELEASE_NOTES)"'

# --- Versioning + changelog + GitHub release (the release-notes spine) ---
# Version lives in apps/phone/build.gradle.kts (versionName = semver, versionCode =
# monotonic int). CHANGELOG.md is Keep-a-Changelog; each release cuts the
# [Unreleased] section to [VERSION] and that section becomes the GitHub release body
# (which solstone.app/releases/android renders). Full sequence:
# vpe/playbooks/solstone-android-release.md (in the extro Org).
PHONE_GRADLE := apps/phone/build.gradle.kts
ARTIFACTS := artifacts
PHONE_RELEASE_APK_LOCAL := $(ARTIFACTS)/phone-real-release.apk

phone-version:
	@grep -E 'versionName = ' $(PHONE_GRADLE) | sed -E 's/.*versionName = "([^"]+)".*/\1/'

# Set phone versionName=<VERSION> and auto-increment versionCode. Usage: make phone-bump VERSION=0.1.1
phone-bump:
	@test -n "$(VERSION)" || { echo "Set VERSION=x.y.z" >&2; exit 2; }
	@code=$$(grep -E 'versionCode = ' $(PHONE_GRADLE) | sed -E 's/.*versionCode = ([0-9]+).*/\1/'); \
	newcode=$$((code + 1)); \
	sed -i -E "s/versionCode = [0-9]+/versionCode = $$newcode/" $(PHONE_GRADLE); \
	sed -i -E "s/versionName = \"[^\"]+\"/versionName = \"$(VERSION)\"/" $(PHONE_GRADLE); \
	echo "phone -> versionName $(VERSION), versionCode $$newcode"

# Cut CHANGELOG [Unreleased] -> [VERSION] - <today>. Usage: make changelog-cut VERSION=0.1.1
changelog-cut:
	@test -n "$(VERSION)" || { echo "Set VERSION=x.y.z" >&2; exit 2; }
	tools/release/changelog-cut.sh $(VERSION)

# Print the CHANGELOG section for VERSION (the GitHub release body).
changelog-notes:
	@test -n "$(VERSION)" || { echo "Set VERSION=x.y.z" >&2; exit 2; }
	@tools/release/changelog-notes.sh $(VERSION)

# Build the signed phone APK on the remote host and pull it back to ./artifacts/.
# (The release/sign toolchain lives on the build host; gh + git live on the caller.)
pull-phone-apk: sync-android-host
	ssh $(ANDROID_REMOTE_HOST) 'cd $(ANDROID_REMOTE_PROJECT) && source ~/android-dev/env.sh && ./gradlew :apps:phone:assembleRealRelease'
	mkdir -p $(ARTIFACTS)
	scp $(ANDROID_REMOTE_HOST):$(ANDROID_REMOTE_PROJECT)/apps/phone/build/outputs/apk/real/release/phone-real-release.apk $(PHONE_RELEASE_APK_LOCAL)

# Cut the GitHub release: tag v<VERSION> at HEAD, body = the CHANGELOG section,
# attach the signed APK. Run from the caller (gh-authed); requires the version-bump
# + changelog commit to be pushed first (the tag points at HEAD). Usage:
#   make github-release VERSION=0.1.1   (after `make pull-phone-apk ANDROID_REMOTE_HOST=...`)
github-release:
	@test -n "$(VERSION)" || { echo "Set VERSION=x.y.z" >&2; exit 2; }
	@test -f $(PHONE_RELEASE_APK_LOCAL) || { echo "No $(PHONE_RELEASE_APK_LOCAL) — run 'make pull-phone-apk ANDROID_REMOTE_HOST=<host>' first" >&2; exit 2; }
	@command -v gh >/dev/null 2>&1 || { echo "gh CLI not found / not authenticated" >&2; exit 2; }
	mkdir -p $(ARTIFACTS)
	tools/release/changelog-notes.sh $(VERSION) > $(ARTIFACTS)/notes-$(VERSION).md
	gh release create v$(VERSION) \
	  --repo solpbc/solstone-android \
	  --title "solstone for Android v$(VERSION)" \
	  --notes-file $(ARTIFACTS)/notes-$(VERSION).md \
	  $(PHONE_RELEASE_APK_LOCAL)
