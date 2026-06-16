.PHONY: install test ci format clean assemble-validation-rogbid validate-rogbid-adb validate-rogbid-media validate-rogbid-qr validate-rogbid-pl

GRADLE ?= ./gradlew
ROGBID_SERIAL ?= 46734915123233

install:
	$(GRADLE) --version

test:
	$(GRADLE) test

ci:
	$(GRADLE) test :apps:validation-rogbid:assembleDebug

format:
	@echo "No formatter is configured yet."

clean:
	$(GRADLE) clean
	rm -rf artifacts captures

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
