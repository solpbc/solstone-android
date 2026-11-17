# Android string catalog

Phone and Wear OS copy are authored in `catalog/source/en-US.ftl`, with translator context
and argument contracts in `catalog/meta/`. Only `en-US` ships. The phone's push messages remain a separate shared contract in `contracts/push-i18n/`.

## Edit and regenerate

Build the compiler revision in `compiler.json` using its locked dependencies.
The pin includes the locale registry digest. Then run:

```sh
I18N_COMPILER=/path/to/i18n-catalog make i18n
make check-i18n
make ci
```

Follow the compiler's `tools/i18n/README.md` for source locks, sidecar revisions,
history and retirement. Identities are permanent; a changed argument contract
needs a new identity. `messages.json` names the Wear identities in its generated Kotlin enum. The phone generator builds its message and native resource maps directly from the phone sidecars. These maps contain catalog identities and their argument contracts; generated English comes from the compiled catalog.

`make ci` checks hashes of the catalog, compiler pin, generator and generated
files. An edit to generated output alone fails. Regenerate with the pinned
compiler; never update `generated/snapshot.json` by hand. This is a generated
byte check, not a detector for English literals.

## Native resources

The compiler's Android target supplies complete `values/` fallback resources
and qualified resources, including `values-en-rUS`. Gradle includes those
committed resources directly. The generator also writes `locales_config.xml`
and the resource-ID enum. The installed app, tile and complication labels use
the generated app-name resource, so the name can be decided per language.
Google Play's listing name is separate store metadata.

The watch follows its effective Android configuration, normally the paired
phone's system language. Android selects the closest supported resource and
falls back to English. On supported Android versions, the declared locale list
also supports framework per-app language settings. A Wear device may not expose
that settings screen. The app implements no separate language picker.

The locale list and Gradle resource filters come from generated resources.
Automatic `generateLocaleConfig` is disabled so dependency translations cannot
advertise unapproved app languages. Approved translations need catalog and
registry changes, regeneration, review and a rebuild, without a new language
list in application code. Android 7+ matches `es-MX` to `values-b+es+419`.

Tiles and complications resolve text for each response. A locale receiver
requests fresh host content and updates an existing notification channel's name.
The running service rebuilds its notification and ongoing activity when its
configuration changes. Neither path starts audio.

## Formatting and shared code

Android selects quantity forms through `getQuantityString`. Numbers use the
configuration's locale without grouping; times retain the platform formatter.
Compact durations and relative-time thresholds retain their existing English
presentation. The portable profile allows one plural selector per message.
Spoken hours and minutes therefore use two quantity messages and a complete
joining message; a language that needs agreement across both will require a
wider profile. The two optional explanation paragraphs also have a catalog
joining message and a protected paragraph break.

Generated English resources omit interpolation isolation to preserve existing
English bytes. Other locales keep the compiler's isolation. Layout inherits
Android direction, uses start/end spacing, and retains `supportsRtl`. This does
not qualify a translated or right-to-left layout.

The `android-phone` and `android-wear` bundles share this repository catalog.
The phone's native resources and ID references live in `formfactor/shared`;
shared JVM modules use `core/i18n` without becoming Android libraries. The phone
installs the Android reader in its application context before UI rendering.
The catalog-derived English reader also supports JVM tests and other consumers
of those shared modules. Source and protocol identifiers stay separate from
translated display labels, including `watch`.

The phone follows the system language on Android 8 through 12. On Android 13
and later, Android's per-app language setting can override it. There is no
separate in-app language picker. Activities retain normal locale recreation;
Compose and Views resolve fresh text. Both notification channels are renamed
at startup and after a locale broadcast, and a running service refreshes its
notification. Existing channel settings and IDs stay intact.

A generated resource marker lets Android choose the best supported locale for
each bundle. The reader uses that locale for plural selection as well as text:
an unsupported French configuration cannot give English zero a singular form.
The independent push bundle uses its own marker, so Android can resolve
`es-MX` to an admitted `es-419` resource. Its contract and pin are unchanged.
APK resource filters retain the admitted phone and push locales.

`native-aliases.json` maps the current Compose accessibility resource names to
catalog identities. App-level aliases preserve their existing English and use
the selected catalog translation. Verify the names against the pinned Compose
resources when upgrading dependencies; the aliases keep existing semantics
and accessibility actions.

Numbers use the selected locale with the catalog's grouped or ungrouped style.
Byte sizes use Android's formatter. Whole date messages preserve existing
English presentation and allow another language to reorder dates. Optional
about-line clauses and their meaningful spaces remain catalog messages;
English is unchanged. Complete license texts and notices are legal messages,
verbatim in English. Templates too large for Android’s string pool use generated
UTF-8 raw resources and the same single formatting pass; this avoids AAPT
replacing a long license with `STRING_TOO_LARGE`. Permission sidecars call for review before translation.
Shared sentences name exact iPhone message identities in translator context.

Presenter unit tests use a generated English reader and retain their existing
English assertions. Instrumented tests exercise real Android resources. Test
readers and pseudolocales are excluded from production packaging.

## Tile preview images

The tile picker uses a bitmap preview. It is generated from the actual tile,
including its catalog-backed app name, prompt and button. Localized drawables
live in `previews/res/`; their manifest binds the selected text and tile source.
`make check-i18n` refuses stale preview inputs or changed image bytes.

After changing tile copy, layout or the bundle's languages, generate resources
first, then build and install the debug APK on an exclusively held development
watch. The capture recipe uses Android 13+ per-app locales, a 480×480 display,
density 340 and font scale 1. The capture command restores the prior app locale,
removes its temporary tile, and never starts audio.

```sh
I18N_COMPILER=/path/to/i18n-catalog python3 scripts/i18n.py --resources-only
./gradlew :apps:wear:assembleDebug
adb -s SERIAL install -r apps/wear/build/outputs/apk/debug/wear-debug.apk
python3 scripts/i18n_preview.py --serial SERIAL --frames /var/tmp/tile-frames
I18N_COMPILER=/path/to/i18n-catalog make i18n
```

Preview conversion requires Pillow. For a build host without Pillow, add
`--capture-only`, copy the frame directory to a host with Pillow, then run
`python3 scripts/i18n_preview.py --frames /var/tmp/tile-frames` there before
finishing regeneration. Captures must contain the catalog's words; a blank tile
or a system watch face is refused. Test-locale previews stay in scratch builds.
