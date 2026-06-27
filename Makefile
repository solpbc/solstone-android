.PHONY: install test ci ci-device format clean require-android-remote-host sync-android-host android-host-ci android-host-ci-device android-host-assemble-validation-rogbid assemble-validation-rogbid validate-rogbid-adb validate-rogbid-media validate-rogbid-qr validate-rogbid-pl require-dist-env dist-phone android-host-dist-phone

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
		$(GRADLE) check :core:model:test :core:sources:test :core:segment:test :core:spool:test :core:queue:test :core:diagnostics:test :core:crypto:test :core:pl:test :core:identity:test :core:observer:test :core:metadata:test :testing:test :harness:test :platform:camera-still:test :platform:work:test :platform:persistence-room:assembleDebug :platform:pl-transport-conscrypt:assembleDebug :platform:pl-transport-conscrypt:testDebugUnitTest :platform:identity-file:assembleDebug :platform:work:assembleDebug :platform:metadata:assembleDebug :platform:audio:assembleDebug :platform:audio:testDebugUnitTest :platform:location:assembleDebug :platform:camera-legacy:assembleDebug :platform:camera2:assembleDebug :platform:fgs:assembleDebug :platform:power:assembleDebug :apps:watch:checkRealDebugMicrophoneManifest :apps:phone:checkRealDebugMicrophoneManifest :apps:glasses:checkRealDebugMicrophoneManifest :apps:watch:assembleMockDebug :apps:watch:assembleMockDebugAndroidTest :apps:watch:assembleRealDebug :apps:phone:assembleMockDebug :apps:phone:assembleMockDebugAndroidTest :apps:phone:assembleRealDebug :apps:glasses:assembleMockDebug :apps:glasses:assembleMockDebugAndroidTest :apps:glasses:assembleRealDebug :apps:validation-rogbid:assembleDebug

# Slower device gate: GMD (pixel5api35) instrumented tests for the modules that
# carry real androidTest coverage. Always host-GL — the default GMD GPU path
# segfaults on the headless build box. Kept separate from `ci` so `ci` stays fast.
ci-device:
	$(GRADLE) -Pandroid.testoptions.manageddevices.emulator.gpu=host \
	  :platform:persistence-room:pixel5api35DebugAndroidTest \
	  :platform:pl-transport-conscrypt:pixel5api35DebugAndroidTest \
	  :apps:watch:pixel5api35MockDebugAndroidTest \
	  :apps:phone:pixel5api35MockDebugAndroidTest \
	  :apps:glasses:pixel5api35MockDebugAndroidTest

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
RELEASE_REV ?= $(shell git rev-parse --short HEAD 2>/dev/null)
RELEASE_NOTES ?=

require-dist-env:
	@test -n "$(ANDROID_UPLOAD_KEYSTORE)" || (echo "Set ANDROID_UPLOAD_KEYSTORE (release signing keystore path)" >&2; exit 2)
	@test -n "$(GOOGLE_APPLICATION_CREDENTIALS)" || (echo "Set GOOGLE_APPLICATION_CREDENTIALS (App Distribution SA key)" >&2; exit 2)
	@test -n "$(FIREBASE_APP_ID)" || (echo "Set FIREBASE_APP_ID (Firebase Android App ID)" >&2; exit 2)
	@command -v firebase >/dev/null 2>&1 || (echo "firebase CLI not found on PATH" >&2; exit 2)

dist-phone: require-dist-env
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
