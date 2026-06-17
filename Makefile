.PHONY: install test ci format clean require-android-remote-host sync-android-host android-host-ci android-host-assemble-validation-rogbid assemble-validation-rogbid validate-rogbid-adb validate-rogbid-media validate-rogbid-qr validate-rogbid-pl

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
	$(GRADLE) check :core:model:test :core:sources:test :core:segment:test :core:spool:test :core:queue:test :core:diagnostics:test :core:crypto:test :core:pl:test :core:identity:test :core:observer:test :testing:test :platform:camera-still:test :platform:persistence-room:assembleDebug :platform:pl-transport-conscrypt:assembleDebug :platform:identity-file:assembleDebug :platform:audio:assembleDebug :platform:location:assembleDebug :platform:camera-legacy:assembleDebug :platform:camera2:assembleDebug :platform:fgs:assembleDebug :platform:power:assembleDebug :apps:watch:checkRealDebugMicrophoneManifest :apps:phone:checkRealDebugMicrophoneManifest :apps:watch:assembleMockDebug :apps:watch:assembleRealDebug :apps:phone:assembleMockDebug :apps:phone:assembleRealDebug :apps:validation-rogbid:assembleDebug

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
