.PHONY: install test ci ci-device device-gate-report format brand-sync clean require-android-remote-host require-gate-source-commit sync-android-host android-host-ci android-host-ci-device android-host-assemble-validation-rogbid assemble-validation-rogbid validate-rogbid-adb validate-rogbid-media validate-rogbid-qr validate-rogbid-pl android-host-hitl-phone-frozen ci-device-experimental hitl-phone hitl-phone-frozen phone-version phone-bump changelog-cut changelog-notes pull-phone-apk pull-released-apk github-release publish-origin test-release apk-facts pull-phone-aab verify-bundle-binding aab-facts android-host-hitl-phone-bundle

GRADLE ?= ./gradlew
ROGBID_SERIAL ?= 46734915123233
ANDROID_REMOTE_HOST ?=
GATE_SOURCE_COMMIT ?= $(shell git rev-parse HEAD 2>/dev/null)
export GATE_SOURCE_COMMIT
# Dedicated remote build tree for `sync-android-host` (which rsyncs with --delete).
# Kept separate from any working clone at ~/projects/solstone-android so the
# destructive sync can never clobber a checkout in use on the build host.
ANDROID_REMOTE_PROJECT ?= ~/android-host/solstone-android
RSYNC_EXCLUDES := --exclude .git --exclude .gradle --exclude '**/build' --exclude artifacts --exclude captures

install:
	$(GRADLE) --version

test:
	$(GRADLE) test

test-release:
	python3 -m unittest discover -s tools/release/tests -p 'test_*.py' -v

# ⚠ The device-gate report is the last recipe line, where a session reading this gate's output
# will see it. ⛔ Do not put a `#` comment inside the recipe: make hands it to the shell and
# echoes it, which is how it ended up in the gate's own output.
ci: test-release
		$(GRADLE) check :core:push:test :core:model:test :core:sources:test :core:segment:test :core:spool:test :core:queue:test :core:diagnostics:test :core:crypto:test :core:pl:test :core:identity:test :core:observer:test :core:metadata:test :core:gate:test :testing:test :harness:test :formfactor:shared:testDebugUnitTest :formfactor:phone:assembleDebug :formfactor:phone:testDebugUnitTest :formfactor:phone:assembleDebugAndroidTest :platform:camera-still:test :platform:work:test :platform:persistence-room:assembleDebug :platform:persistence-room:assembleDebugAndroidTest :platform:pl-transport-conscrypt:assembleDebug :platform:pl-transport-conscrypt:testDebugUnitTest :platform:identity-file:assembleDebug :platform:identity-file:testDebugUnitTest :platform:identity-file:assembleDebugAndroidTest :platform:work:assembleDebug :platform:metadata:assembleDebug :platform:metadata:testDebugUnitTest :platform:audio:assembleDebug :platform:audio:testDebugUnitTest :platform:location:assembleDebug :platform:location:testDebugUnitTest :platform:camera-legacy:assembleDebug :platform:camera-legacy:testDebugUnitTest :platform:camera2:assembleDebug :platform:camera2:testDebugUnitTest :platform:fgs:assembleDebug :platform:fgs:test :platform:power:assembleDebug :apps:watch:checkRealDebugMicrophoneManifest :apps:phone:checkRealDebugMicrophoneManifest :apps:glasses:checkRealDebugMicrophoneManifest :apps:watch:checkRealDebugLauncherManifest :apps:phone:checkRealDebugLauncherManifest :apps:phone:checkPhoneLauncherCountManifest :apps:phone:checkRealDebugAppLinksManifest :apps:phone:checkRealReleaseAppLinksManifest :apps:phone:checkPhoneShellExportManifest :apps:phone:checkRealDebugOnBackInvokedCallbackManifest :apps:phone:checkRealReleasePushManifest :apps:phone:checkRealReleasePushDex :apps:phone:checkNoPushGateway :apps:glasses:checkRealDebugLauncherManifest :apps:watch:assembleMockDebug :apps:watch:assembleMockDebugAndroidTest :apps:watch:assembleRealDebug :apps:phone:assembleMockDebug :apps:phone:assembleMockDebugAndroidTest :apps:phone:assembleRealDebug :apps:phone:assembleRealDebugAndroidTest :apps:phone:verifySolstoneGateBuildReceipts :apps:glasses:assembleMockDebug :apps:glasses:assembleMockDebugAndroidTest :apps:glasses:assembleRealDebug :apps:validation-rogbid:testDebugUnitTest :apps:validation-rogbid:assembleDebug
	-@sh tools/gate/device-gate-owed.sh

# Whether the slow device gate is owed for this change, reported at the end of the fast one.
# ⛔ Never fails: see the header of the script for why a hard precondition here gets routed
# around. `-` keeps a missing script or a detached checkout from reddening an unrelated gate.
.PHONY: device-gate-report
device-gate-report:
	-@sh tools/gate/device-gate-owed.sh

# Slower device gate: GMD (pixel5api35) instrumented tests. Always host-GL — the
# default GMD GPU path segfaults on the headless build box. Kept separate from `ci`
# so `ci` stays fast.
#
# PHONE-ONLY, deliberately. The phone is the sole maintained quality target.
# Watch, glasses/Rokid, and Rogbid sources are parked historical/experimental
# evidence, not routine-maintenance or release targets. A red here must always mean
# "the shipping app is broken"; the retained manual hardware command is not a gate.
ci-device:
	$(GRADLE) -Pandroid.testoptions.manageddevices.emulator.gpu=host \
	  :platform:persistence-room:pixel5api35DebugAndroidTest \
	  :platform:pl-transport-conscrypt:pixel5api35DebugAndroidTest \
	  :platform:identity-file:pixel5api35DebugAndroidTest \
	  :formfactor:phone:pixel5api35DebugAndroidTest \
	  :apps:phone:pixel5api35MockDebugAndroidTest
	# AC5a real-flavor narrow gate. The class filter must match exactly one test;
	# device-gate operators must confirm the real run reports Tests run: 1.
	$(GRADLE) -Pandroid.testoptions.manageddevices.emulator.gpu=host \
	  -Pandroid.testInstrumentationRunnerArguments.class=app.solstone.observer.phone.RealFlavorOpportunisticSyncRuntimeTest \
	  :apps:phone:pixel5api35RealDebugAndroidTest
	# ⚠ Last, so it can only exist after both invocations above returned green. A receipt
	# written up front would tell the next `make ci` the gate passed when it had only started.
	# This write needs a git tree. The build host's synced tree has none (the sync excludes
	# .git), so there it does nothing; `android-host-ci-device` records the receipt in the
	# checkout that ran it, once the host's gate has returned green.
	@mkdir -p artifacts/ci-device && git rev-parse HEAD > artifacts/ci-device/$$(git rev-parse HEAD) 2>/dev/null || true

format:
	@echo "No formatter is configured yet."

# Re-vendor brand assets from the canonical brand source. CI verifies the
# committed output (it does not run brand-sync) — run this locally when the
# brand spec updates, then commit the diff.
#
# Every brand-derived asset in this repository is a raster EXCEPT the display
# face: formfactor/phone/src/main/res/font/comfortaa_bold.ttf is vendored from
# the brand asset source and carries its own licence. See
# docs/third-party-assets.md -- the OFL requires the licence to travel with the
# font, and the app surfaces the attribution in `about solstone > licenses`.
# The launcher ladder has no committed raster in the brand source at Android's
# densities; scripts/build-launcher-icons.sh renders all three families from the
# brand app-icon masters, and carries the geometry contract.
brand-sync:
	@test -n "$(BRAND_DIR)" || { echo "brand: BRAND_DIR is required — point it at your brand asset directory (BRAND_DIR=/path/to/brand make brand-sync)"; exit 1; }
	@test -d "$(BRAND_DIR)" || { echo "brand: BRAND_DIR=$(BRAND_DIR) not found"; exit 1; }
	@BRAND_DIR="$(BRAND_DIR)" sh scripts/build-launcher-icons.sh
	@echo "brand: synced from $(BRAND_DIR)"

clean:
	$(GRADLE) clean
	rm -rf artifacts captures

require-android-remote-host:
	@test -n "$(ANDROID_REMOTE_HOST)" || (echo "Set ANDROID_REMOTE_HOST=<host>" >&2; exit 2)

require-gate-source-commit:
	@test -n "$$GATE_SOURCE_COMMIT" || (echo "Set GATE_SOURCE_COMMIT to a full commit SHA or run from a git checkout" >&2; exit 2)
	@test "$$GATE_SOURCE_COMMIT" = "$$(git rev-parse HEAD)" || { echo "GATE_SOURCE_COMMIT must match the caller's HEAD" >&2; exit 2; }
	@status="$$(git status --porcelain --untracked-files=normal)" || { echo "Could not inspect the caller's source tree" >&2; exit 2; }; \
		test -z "$$status" || { echo "Refusing to attest a dirty source tree" >&2; printf '%s\n' "$$status" >&2; exit 2; }

sync-android-host: require-android-remote-host
	ssh $(ANDROID_REMOTE_HOST) 'mkdir -p $(ANDROID_REMOTE_PROJECT)'
	rsync -az --delete $(RSYNC_EXCLUDES) ./ $(ANDROID_REMOTE_HOST):$(ANDROID_REMOTE_PROJECT)/

android-host-ci: require-gate-source-commit sync-android-host
	ssh $(ANDROID_REMOTE_HOST) 'cd $(ANDROID_REMOTE_PROJECT) && source ~/android-dev/env.sh && GATE_SOURCE_COMMIT=$(GATE_SOURCE_COMMIT) make ci'

android-host-ci-device: require-gate-source-commit sync-android-host
	ssh $(ANDROID_REMOTE_HOST) 'cd $(ANDROID_REMOTE_PROJECT) && source ~/android-dev/env.sh && GATE_SOURCE_COMMIT=$(GATE_SOURCE_COMMIT) make ci-device'
	@mkdir -p artifacts/ci-device && printf '%s\n' "$(GATE_SOURCE_COMMIT)" > "artifacts/ci-device/$(GATE_SOURCE_COMMIT)"

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

# Parked manual device command: retained watch + glasses source evidence. It is
# neither routine maintenance nor a release gate; do not let it gate a phone release.
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
# It runs the RELEASE APK — the exact artifact that ships — on the Galaxy A36
# (API 36, matching the phone targetSdk 36 baseline). Gating the debug APK here would leave
# release signing and minification unexercised by the only human-usability check we
# have, which would hollow out the whole guarantee.
#
# There is deliberately no SKIP escape hatch: if the device is unhealthy, the
# device gets fixed. The guarantee we are buying is that a build a human cannot
# use physically cannot reach a tester, and an escape hatch is exactly how that
# guarantee gets spent on a deadline.
ANDROID_HITL_SERIAL ?= RZGL11XCS9D
HITL_FLOW := .maestro/phone-smoke.yaml
# 🔴 The second leg, installed with NOTHING granted. `HITL_FLOW` runs after `install -g`, so it
# has never seen the app a new owner sees — which is how A1 (the main call to action opening a
# blank screen with a raw camera exception on it) shipped twice with every gate green.
HITL_FRESH_FLOW := .maestro/phone-fresh-install.yaml
HITL_ARTIFACTS = $(ARTIFACTS)/hitl
FROZEN_PHONE_APK ?=
# The exact local artifact the real-hardware gate installs. It defaults to the
# direct channel's signed APK and the direct channel never changes. The bundle
# rail overrides it with the universal APK derived from the .aab that gets
# uploaded, so one gate serves both channels and neither can gate bytes it did
# not freeze. See "the bundle rail" below.
HITL_FROZEN_APK_LOCAL ?= $(PHONE_RELEASE_APK_LOCAL)
FROZEN_REMOTE_APK = $(ANDROID_REMOTE_PROJECT)/artifacts/distribution/$(notdir $(HITL_FROZEN_APK_LOCAL))

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

# Full versioned releases use this target after `pull-phone-apk` has frozen the
# candidate bytes. It never invokes Gradle: the exact APK passed here is the one
# installed on the A36 and later distributed to origin/GitHub.
hitl-phone-frozen:
	@set -eu; \
	test -n "$(FROZEN_PHONE_APK)" || { echo "Set FROZEN_PHONE_APK=<exact signed APK>" >&2; exit 2; }; \
	test -f "$(FROZEN_PHONE_APK)" || { echo "Frozen release APK not found: $(FROZEN_PHONE_APK)" >&2; exit 2; }; \
	command -v maestro >/dev/null 2>&1 || { echo "maestro not on PATH (expected ~/.maestro/bin/maestro)" >&2; exit 2; }; \
	adb -s "$(ANDROID_HITL_SERIAL)" get-state >/dev/null 2>&1 || { \
	  echo "HITL GATE FAILED: device $(ANDROID_HITL_SERIAL) is not attached." >&2; \
	  adb devices -l >&2; \
	  exit 1; \
	}; \
	digest_line=$$(sha256sum "$(FROZEN_PHONE_APK)"); before=$${digest_line%% *}; \
	echo "HITL: gating frozen APK $(FROZEN_PHONE_APK) ($$before) on $(ANDROID_HITL_SERIAL)"; \
	adb -s "$(ANDROID_HITL_SERIAL)" uninstall app.solstone.observer.phone >/dev/null 2>&1 || true; \
	adb -s "$(ANDROID_HITL_SERIAL)" install -r -g "$(FROZEN_PHONE_APK)"; \
	mkdir -p "$(HITL_ARTIFACTS)"; \
	if ! MAESTRO_DRIVER_STARTUP_TIMEOUT=120000 maestro --device "$(ANDROID_HITL_SERIAL)" test "$(HITL_FLOW)"; then \
	  if [ "$$(sh tools/gate/hitl-foreign-dialog.sh "$(ANDROID_HITL_SERIAL)")" != none ]; then \
	    echo "" >&2; \
	    echo "HITL INCONCLUSIVE — a dialog that is not ours was on screen." >&2; \
	    echo "⛔ This is NOT a verdict on the build. Clear the dialog and re-run the gate." >&2; \
	    exit 2; \
	  fi; \
	  echo "HITL GATE FAILED on $(HITL_FLOW)" >&2; exit 1; \
	fi; \
	echo "HITL: un-granted leg — reinstalling $(FROZEN_PHONE_APK) with nothing granted"; \
	adb -s "$(ANDROID_HITL_SERIAL)" uninstall app.solstone.observer.phone >/dev/null 2>&1 || true; \
	adb -s "$(ANDROID_HITL_SERIAL)" install "$(FROZEN_PHONE_APK)"; \
	MAESTRO_DRIVER_STARTUP_TIMEOUT=120000 maestro --device "$(ANDROID_HITL_SERIAL)" test "$(HITL_FRESH_FLOW)"; \
	digest_line=$$(sha256sum "$(FROZEN_PHONE_APK)"); after=$${digest_line%% *}; \
	test "$$after" = "$$before" || { echo "Frozen release APK changed during HITL" >&2; exit 1; }; \
	echo "HITL FROZEN-APK GATE PASSED, BOTH LEGS — $$after"

# Versioned release wrapper. Copies and proves the frozen local artifact before
# HITL; cannot reach Gradle.
android-host-hitl-phone-frozen: require-android-remote-host sync-android-host
	@test -f "$(HITL_FROZEN_APK_LOCAL)" || { echo "No frozen $(HITL_FROZEN_APK_LOCAL) — run 'make pull-phone-apk ANDROID_REMOTE_HOST=<host>' (direct channel) or 'make pull-phone-aab ANDROID_REMOTE_HOST=<host>' (bundle rail) first" >&2; exit 2; }
	ssh $(ANDROID_REMOTE_HOST) 'mkdir -p $(ANDROID_REMOTE_PROJECT)/artifacts/distribution'
	scp "$(HITL_FROZEN_APK_LOCAL)" $(ANDROID_REMOTE_HOST):$(FROZEN_REMOTE_APK)
	@set -eu; \
	digest_line=$$(sha256sum "$(HITL_FROZEN_APK_LOCAL)"); local_sha=$${digest_line%% *}; \
	remote_line=$$(ssh $(ANDROID_REMOTE_HOST) 'sha256sum $(FROZEN_REMOTE_APK)'); remote_sha=$${remote_line%% *}; \
	test "$$remote_sha" = "$$local_sha" || { echo "Frozen APK transfer digest mismatch: $$remote_sha != $$local_sha" >&2; exit 1; }; \
	echo "Frozen APK transfer verified for HITL — $$local_sha"
	ssh $(ANDROID_REMOTE_HOST) 'cd $(ANDROID_REMOTE_PROJECT) && source ~/android-dev/env.sh && make hitl-phone-frozen FROZEN_PHONE_APK=artifacts/distribution/$(notdir $(HITL_FROZEN_APK_LOCAL))'

# --- Versioning + changelog + GitHub release (the release-notes spine) ---
# Version lives in apps/phone/build.gradle.kts (versionName = semver, versionCode =
# monotonic int). CHANGELOG.md is Keep-a-Changelog; each release cuts the
# [Unreleased] section to [VERSION] and that section becomes the GitHub release body
# (which solstone.app/releases/android renders). Full sequence:
# the release runbook.
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

# Stage the exact bytes an already-published GitHub release carries, verifying
# the digest GitHub records for the asset. For an origin publish of a release
# cut before the origin existed, and as the recovery path when an origin
# publish failed after the mirror already went out — a rebuild is different
# bytes and must never stand in for what testers are running. Usage:
#   make pull-released-apk VERSION=2.1.0
pull-released-apk:
	@test -n "$(VERSION)" || { echo "Set VERSION=x.y.z" >&2; exit 2; }
	@command -v gh >/dev/null 2>&1 || { echo "gh CLI not found / not authenticated" >&2; exit 2; }
	tools/release/pull-released-apk.sh $(VERSION) $(PHONE_RELEASE_APK_LOCAL)

# Publish the signed APK to the release origin, updates.solstone.app. THIS is
# the release publish; the GitHub release below is the mirror, and on a new cut
# this target runs first. It never contacts GitHub, so a GitHub outage cannot
# stop or delay it.
#
# Unlike github-release it does not require HEAD == CANDIDATE: it mints no ref,
# and instead requires the remote tag vVERSION to already peel to CANDIDATE.
# It does require a clean tree, ancestry on the remote release branch, an APK
# whose versionName/versionCode match what the candidate commit declares, and
# an APK signed by the pinned release key — then it reads every published
# object back and re-hashes it before advancing latest. Usage:
#   make publish-origin VERSION=2.1.0 CANDIDATE=<full 40-hex sha>
#   (after `make pull-phone-apk ...` or `make pull-released-apk VERSION=...`)
ORIGIN_LANE ?= release

publish-origin:
	@test -n "$(VERSION)" || { echo "Set VERSION=x.y.z" >&2; exit 2; }
	@test -n "$(CANDIDATE)" || { echo "Set CANDIDATE=<full 40-hex commit>; publish-origin never infers a candidate from HEAD or the remote default branch" >&2; exit 2; }
	@test -f $(PHONE_RELEASE_APK_LOCAL) || { echo "No $(PHONE_RELEASE_APK_LOCAL) — run 'make pull-phone-apk ANDROID_REMOTE_HOST=<host>' or 'make pull-released-apk VERSION=$(VERSION)' first" >&2; exit 2; }
	tools/release/publish-origin.sh \
	  --lane $(ORIGIN_LANE) \
	  --version $(VERSION) \
	  --candidate $(CANDIDATE) \
	  --apk $(PHONE_RELEASE_APK_LOCAL)

# Read release-binding facts (package, version, signer certificate, the MERGED
# permission set) straight out of an APK, with no Android SDK. Usage:
#   make apk-facts APK=artifacts/phone-real-release.apk
apk-facts:
	@test -n "$(APK)" || { echo "Set APK=<path to an apk>" >&2; exit 2; }
	@python3 tools/release/apk_facts.py $(APK)

# --- the bundle rail (Play) -------------------------------------------------
#
# Play takes an Android App Bundle and nobody can install one, so the bundle
# rail freezes TWO artifacts from one build: the .aab that gets uploaded, and
# the universal APK bundletool derives from it, which is the only installable
# form of those exact bytes. The real-hardware gate runs against the derived
# APK, and `verify-bundle-binding.py` proves it is that bundle rather than a
# second assemble — the same frozen-bytes rule the direct channel has, where
# `hitl-phone` rebuilds and `hitl-phone-frozen` does not.
#
# ⛔ The direct signed-APK channel is untouched: updates.solstone.app and
# solstone.app/beta keep shipping `pull-phone-apk` bytes. The universal APK is a
# GATE artifact and is never published anywhere.
PHONE_RELEASE_AAB_LOCAL := $(ARTIFACTS)/phone-real-release.aab
PHONE_UNIVERSAL_APK_LOCAL := $(ARTIFACTS)/phone-real-release-universal.apk

# Build the signed bundle and its universal APK on the build host in ONE Gradle
# invocation, pull both back, and prove the binding before either is usable.
pull-phone-aab: sync-android-host
	ssh $(ANDROID_REMOTE_HOST) 'cd $(ANDROID_REMOTE_PROJECT) && source ~/android-dev/env.sh && ./gradlew :apps:phone:bundleRealRelease :apps:phone:packageRealReleaseUniversalApk'
	mkdir -p $(ARTIFACTS)
	scp $(ANDROID_REMOTE_HOST):$(ANDROID_REMOTE_PROJECT)/apps/phone/build/outputs/bundle/realRelease/phone-real-release.aab $(PHONE_RELEASE_AAB_LOCAL)
	scp $(ANDROID_REMOTE_HOST):$(ANDROID_REMOTE_PROJECT)/apps/phone/build/outputs/apk_from_bundle/realRelease/phone-real-release-universal.apk $(PHONE_UNIVERSAL_APK_LOCAL)
	@$(MAKE) verify-bundle-binding

# Prove the frozen universal APK is the frozen bundle: every dex, native
# library, asset, packaged java resource and baseline-profile entry
# byte-identical, the manifest facts equal, the signer the pinned release key,
# and that same certificate carried in the bundle's own JAR signature.
verify-bundle-binding:
	@test -f $(PHONE_RELEASE_AAB_LOCAL) || { echo "No $(PHONE_RELEASE_AAB_LOCAL) — run 'make pull-phone-aab ANDROID_REMOTE_HOST=<host>' first" >&2; exit 2; }
	@test -f $(PHONE_UNIVERSAL_APK_LOCAL) || { echo "No $(PHONE_UNIVERSAL_APK_LOCAL) — run 'make pull-phone-aab ANDROID_REMOTE_HOST=<host>' first" >&2; exit 2; }
	python3 tools/release/verify-bundle-binding.py \
	  --aab $(PHONE_RELEASE_AAB_LOCAL) \
	  --apk $(PHONE_UNIVERSAL_APK_LOCAL)

# Read release-binding facts out of a bundle, with no Android SDK. The AAB twin
# of apk-facts; an AAB keeps its manifest as protobuf, not binary AXML. Usage:
#   make aab-facts AAB=artifacts/phone-real-release.aab
aab-facts:
	@test -n "$(AAB)" || { echo "Set AAB=<path to an aab>" >&2; exit 2; }
	@python3 tools/release/aab_facts.py $(AAB)

# The real-hardware gate for the bundle rail. Re-proves the binding, then runs
# the identical frozen HITL gate the direct channel runs, against the universal
# APK. Usage: make android-host-hitl-phone-bundle ANDROID_REMOTE_HOST=<host>
android-host-hitl-phone-bundle: verify-bundle-binding
	@$(MAKE) android-host-hitl-phone-frozen HITL_FROZEN_APK_LOCAL=$(PHONE_UNIVERSAL_APK_LOCAL)

# Cut the GitHub release for an explicit candidate commit. The tag is never
# inferred from HEAD or from the remote default branch. An annotated tag is
# pushed first, peeled from the remote, and proven to name CANDIDATE before
# publication; GitHub is not allowed to invent the tag. Usage:
#   make github-release VERSION=0.1.1 CANDIDATE=<full 40-hex sha>
#   (after `make pull-phone-apk ANDROID_REMOTE_HOST=...`)
github-release:
	@test -n "$(VERSION)" || { echo "Set VERSION=x.y.z" >&2; exit 2; }
	@test -n "$(CANDIDATE)" || { echo "Set CANDIDATE=<full 40-hex commit>; github-release never infers a candidate from HEAD or the remote default branch" >&2; exit 2; }
	@$(MAKE) require-gate-source-commit GATE_SOURCE_COMMIT=$(CANDIDATE)
	@test -f $(PHONE_RELEASE_APK_LOCAL) || { echo "No $(PHONE_RELEASE_APK_LOCAL) — run 'make pull-phone-apk ANDROID_REMOTE_HOST=<host>' first" >&2; exit 2; }
	@command -v gh >/dev/null 2>&1 || { echo "gh CLI not found / not authenticated" >&2; exit 2; }
	mkdir -p $(ARTIFACTS)
	tools/release/changelog-notes.sh $(VERSION) > $(ARTIFACTS)/notes-$(VERSION).md
	cp $(PHONE_RELEASE_APK_LOCAL) $(ARTIFACTS)/solstone-android-$(VERSION).apk
	tools/release/github-release.sh \
	  --version $(VERSION) \
	  --candidate $(CANDIDATE) \
	  --apk $(ARTIFACTS)/solstone-android-$(VERSION).apk \
	  --notes $(ARTIFACTS)/notes-$(VERSION).md
