# FGS Honest State Design

## Scope

Make foreground-service state fail honest: the live notification must not claim `on` until capture diagnostics prove the observer is actually ON; blocked or failed starts must surface needs-attention; self-heal must run through production wiring without the dead foreground-start-allowed seam.

No hosted CI/release behavior changes are in scope. `make ci-device` currently runs five instrumented modules: `:platform:persistence-room`, `:platform:pl-transport-conscrypt`, `:apps:watch`, `:apps:phone`, and `:apps:glasses`. It does not run `platform/fgs` android tests, and `platform/fgs` has no `androidTest` source set. Therefore every FGS honest-state choice must be represented by a pure JVM decision function where possible. The irreducible Android calls (`startForeground`, `NotificationManager.notify`, `getLaunchIntentForPackage`, `startForegroundService` / `startService`) remain thin wrappers with manual/on-device residual risk.

## Validation Summary

All lead decisions A-J are feasible against the current code. Two points require explicit treatment:

- `ReasonCode.NONE` is legitimate in the reducer for no-fault OFF/ON states at `core/diagnostics/Diagnostics.kt:45-46`; do not change the reducer.
- `HarnessController.reconcileOnce` already returns on `!desiredOn` before `startReadiness` at `harness/src/main/kotlin/app/solstone/observer/harness/HarnessController.kt:149`, so desired-off reconcile ticks already perform zero probes. The `!desiredOn` blocker in `startReadiness(Rehydrate)` is defense-in-depth for direct callers.

## Decisions

### A. Heartbeat Grace

Split service heartbeat truth from optimistic start intent.

- Keep `ObserverForegroundService.lastBeatNanos` as the real service heartbeat, stamped only by `refreshHeartbeat()` from `onCreate`, `onStartCommand`, and the heartbeat runnable.
- Add `lastStartRequestedNanos`, stamped by `markStartRequested()`.
- Replace `HeartbeatMonitor.isFresh(nowNanos, lastBeatNanos, staleAfterNanos)` with a pure JVM decision accepting `lastBeatNanos`, `lastStartRequestedNanos`, `staleAfterNanos`, and `startGraceNanos`.
- Fresh means either the real beat is within `staleAfterNanos`, or a start request is within a grace window and has not been superseded by a newer real beat/stale state.
- Pick `startGraceNanos <= staleAfterNanos`; recommended: 5 seconds, leaving the existing 15 second stale threshold intact.
- Rewrite `FgsLogicTest.startRequestOptimisticallyRefreshesHeartbeat`: recent request with no beat is fresh, old request with no beat is stale, real beat is fresh.

### B. Notification Re-Post Seam

Add a service-side notification update seam:

- `ObserverForegroundService` or `ObserverNotification` exposes a thin Android wrapper that posts id 101 using `ObserverNotification.ongoing(context, needsAttention, decorate = true)`.
- Extract pure JVM decision: `needsAttention = diagnostics.state != SourceState.ON`.
- Glasses calls this seam from the existing `GlassesAppContainer.emitStateTransition` diff path, using the container context. Do not add a second poller or signaling path.
- On API 33+ when `POST_NOTIFICATIONS` is denied and the notify call is suppressed, emit a lifecycle/diagnostic line with the reason.

### C. Initial Post And Sticky Restart

Unify honest start behavior through a pure `onStartCommandPlan(hasIntent, hasRehydrator)`:

- `onStartCommand` always begins with id 101 as needs-attention. At service start time, capture is not yet confirmed ON.
- The re-post seam in B is the only path that can flip id 101 to `on`, after diagnostics confirm ON. The accepted latency is the existing glasses poll cadence, up to 5 seconds.
- If sticky restart arrives with no rehydrator registered: call `startForeground(101, needs-attention)`, post id 102 needs-attention, then `stopSelf()`. Stopping removes id 101; id 102 survives as the non-observing attention signal.
- If a rehydrator exists: post id 101 needs-attention, dispatch rehydrate, and let diagnostics later flip id 101 to `on` only if capture is truly ON.

### D. Start-Failure Handling

Catch platform start failures and surface attention without crashing:

- Wrap `startForeground` in `onStartCommand`.
- Wrap requester calls in `startFromVisibleContext`: `startForegroundService` / `startService`.
- Catch `SecurityException` and, on API 31+, `ForegroundServiceStartNotAllowedException`.
- On catch, emit a lifecycle diagnostic containing the exception class name.
- Post id 102 needs-attention when notifications are permitted.
- Never rethrow from these wrappers.
- Extract pure JVM decision for exception classification: exception class name, diagnostic line, and whether to post attention.

Residual risk: real Android throw/catch behavior around service starts is not covered by `make ci-device` because `platform/fgs` is not in that gate.

### E. Rehydrate Off Main Thread

Keep FGS dispatch synchronous and move the glasses work to its existing background executor.

- `ObserverForegroundService.dispatchRehydrate` stays synchronous/thread-agnostic.
- Add a method on `GlassesRuntimeContainer`, recommended `rehydrateInBackground()`.
- Implement it in `GlassesAppContainer` with `background.execute { ... }`, following the same route as `pollRunnable`.
- Move the body of `GlassesObserverRuntime.rehydrateFromForegroundServiceStart` into that background path: visible-owner check, `CaptureRefused` diag when needed, and `controller.reconcile(Rehydrate)`.
- Failures emit `DiagEvent.CaughtException(site = ..., type = exceptionClass)`.
- Update `GlassesRuntimeContainer` test doubles in glasses JVM tests.

This avoids `NetworkOnMainThreadException` from `RealPlStatusProbe` during FGS-triggered rehydrate.

### F. Reconcile Ordering And Desired-Off Reason

Reorder reconcile/readiness to avoid unnecessary probes:

- In `reconcileOnce`, keep the existing `!desiredOn` early return.
- Move the already-ON short-circuit before `startReadiness(mode)`, so healthy ON ticks perform zero PL status probes.
- In `startReadiness(Rehydrate)`, collect cheap blockers first: permissions, visible owner, desired-off, pairing, and any remaining cheap checks.
- Only call `probePlStatus()` if no cheap blocker exists. This makes unpaired readiness checks perform zero transport probes.
- No caller should depend on `startReadiness` always probing. Existing consumers use it for blockers/readiness; tests that implicitly expect probe side effects should be updated because the new behavior is more honest and cheaper.

Open gate decision for criterion 9:

Recommended: add `ReasonCode.DESIRED_OFF` to `core/model`. This is additive, self-documenting, and avoids misusing `NONE`, which the reducer legitimately uses for no-fault OFF/ON.

Alternatives:

- Remove the desired-off blocker line entirely. `desiredOn` plus the `reconcileOnce` guard already represents the state, and blockers remain fault-only. This is minimal but may not satisfy the literal acceptance criterion requiring a meaningful code.
- Reuse an existing code. None fits: permission, service killed, rebooted, unpaired, storage, provider, auth, exemption, transport, and foreground-start are all false causes for owner-desired off.

### G. Boot Gating

Make boot notification behavior depend on persisted desired state:

- Change `observerBootAction()` into a pure function taking `persistedDesiredOn`.
- Desired off: no notification.
- Desired on: post id 102 needs-attention, but do not start FGS or capture.
- Move prefs constants into `platform/fgs`, recommended `ObserverRuntimePrefs` with `PREFS_NAME = "solstone_observer_runtime"` and `KEY_DESIRED_ON = "desired_observing_on"`.
- `SharedPreferencesDesiredObservingStore` in `harness` imports those constants. This dependency direction is already allowed because harness depends on fgs.
- `ObserverBootReceiver` reads the persisted flag directly via those constants.
- Boot notification gets a content intent via `context.packageManager.getLaunchIntentForPackage(context.packageName)`, wrapped in `PendingIntent.getActivity`; handle a null launch intent by posting without content intent and emitting diag.
- Cancel id 102 when the service successfully starts, in `onCreate` or `onStartCommand`.
- On API 33+ when notification permission suppresses boot notification, emit a diag line.
- Update `platform/fgs/FGS_MATRIX.md` Boot behavior minimally if wording becomes stale.

Accepted temporary regression: phone/watch currently use `InMemoryDesiredObservingStore`, so their persisted desired flag is never true at boot and boot notification cannot fire for them until the sibling store consolidation lode. Glasses uses `SharedPreferencesDesiredObservingStore`.

### H. Remove Default-True Trust Values

Remove trust defaults and require explicit call-site choices:

- Remove `visibleCaptureAuthority: VisibleCaptureAuthority = AlwaysVisibleCaptureAuthority` from `HarnessController`.
- Remove `exemptionVerified: () -> Boolean = { true }` defaults from `PhoneHarnessFlavor`, `WatchHarnessFlavor`, and `GlassesHarnessFlavor`.
- Remove `isUsableNetworkPresent: () -> Boolean = { true }` default from `GlassesHarnessFlavor`.
- Phone/watch may explicitly pass `AlwaysVisibleCaptureAuthority` for this lode; the improvement is making the trust choice visible.
- Chase compile errors for direct data-class construction and tests.

### I. Production-Wiring Self-Heal Tests

Add harness JVM coverage that resembles glasses real wiring after seam removal:

- Construct `HarnessController` with a real `VisibleCaptureOwnerRegistry` and an acquired owner.
- Set desired on, paired identity/credential/endpoint facts, reachable transport, fresh heartbeat, and stopped runtime snapshot.
- Assert `reconcile(Rehydrate)` reaches `observerLifecycle.start()`.
- Separate no-visible-owner case: assert blocked, needs-attention surfaced via the new signal/diag, and no exception. Do not assert only absence of start.
- Replace/remove old fake foreground-start gate assertions, including `rehydrateRefusedWhenNoOwner` fake-gate setup and `startReadinessSurfacesCanonicalReasons` assertions for `NONE` and fake `FOREGROUND_START_NOT_ALLOWED`.

### J. Seam Removal

Delete `ForegroundStartAllowed.kt` and remove every reference in the same change. The start-allowed seam is dead production code because `AndroidForegroundStartAllowed.isForegroundStartAllowed()` is hardwired false.

## Seam-Removal Call-Site List

- `platform/fgs/src/main/kotlin/app/solstone/platform/fgs/ForegroundStartAllowed.kt:6`: `ForegroundStartAllowed` interface.
- `platform/fgs/src/main/kotlin/app/solstone/platform/fgs/ForegroundStartAllowed.kt:14`: `AndroidForegroundStartAllowed`.
- `platform/fgs/src/main/kotlin/app/solstone/platform/fgs/ForegroundStartAllowed.kt:15`: hardwired `false`.
- `harness/src/main/kotlin/app/solstone/observer/harness/HarnessController.kt:19`: import.
- `harness/src/main/kotlin/app/solstone/observer/harness/HarnessController.kt:33`: constructor parameter.
- `harness/src/main/kotlin/app/solstone/observer/harness/HarnessController.kt:106`: rehydrate gate call.
- `harness/src/main/kotlin/app/solstone/observer/harness/HarnessController.kt:107`: foreground-start blocker from the dead seam.
- `apps/phone/src/real/kotlin/app/solstone/observer/phone/HarnessFlavor.kt:19`: import.
- `apps/phone/src/real/kotlin/app/solstone/observer/phone/HarnessFlavor.kt:46`: production `AndroidForegroundStartAllowed()`.
- `apps/watch/src/real/kotlin/app/solstone/observer/watch/HarnessFlavor.kt:19`: import.
- `apps/watch/src/real/kotlin/app/solstone/observer/watch/HarnessFlavor.kt:46`: production `AndroidForegroundStartAllowed()`.
- `apps/glasses/src/real/kotlin/app/solstone/observer/glasses/HarnessFlavor.kt:22`: import.
- `apps/glasses/src/real/kotlin/app/solstone/observer/glasses/HarnessFlavor.kt:63`: production `AndroidForegroundStartAllowed()`.
- `apps/phone/src/mock/kotlin/app/solstone/observer/phone/HarnessFlavor.kt:29`: import.
- `apps/phone/src/mock/kotlin/app/solstone/observer/phone/HarnessFlavor.kt:51`: mock `ForegroundStartAllowed { true }`.
- `apps/watch/src/mock/kotlin/app/solstone/observer/watch/HarnessFlavor.kt:29`: import.
- `apps/watch/src/mock/kotlin/app/solstone/observer/watch/HarnessFlavor.kt:51`: mock `ForegroundStartAllowed { true }`.
- `apps/glasses/src/mock/kotlin/app/solstone/observer/glasses/HarnessFlavor.kt:30`: import.
- `apps/glasses/src/mock/kotlin/app/solstone/observer/glasses/HarnessFlavor.kt:53`: mock `ForegroundStartAllowed { true }`.
- `harness/src/test/kotlin/app/solstone/observer/harness/HarnessTestSupport.kt:15`: import.
- `harness/src/test/kotlin/app/solstone/observer/harness/HarnessTestSupport.kt:56`: `FakeForegroundStartAllowed`.
- `harness/src/test/kotlin/app/solstone/observer/harness/HarnessTestSupport.kt:57`: fake implementation method.
- `harness/src/test/kotlin/app/solstone/observer/harness/HarnessTestSupport.kt:182`: fixture field.
- `harness/src/test/kotlin/app/solstone/observer/harness/HarnessTestSupport.kt:218`: fixture parameter default.
- `harness/src/test/kotlin/app/solstone/observer/harness/HarnessTestSupport.kt:234`: pass into `HarnessController`.
- `harness/src/test/kotlin/app/solstone/observer/harness/HarnessTestSupport.kt:256`: fixture return field.
- `harness/src/test/kotlin/app/solstone/observer/harness/HarnessControllerRehydrateTest.kt:19`: fake allowed true.
- `harness/src/test/kotlin/app/solstone/observer/harness/HarnessControllerRehydrateTest.kt:165`: fake allowed false.
- `harness/src/test/kotlin/app/solstone/observer/harness/HarnessControllerVisibleStartTest.kt:46`: fake allowed false.
- `harness/src/test/kotlin/app/solstone/observer/harness/HarnessControllerVisibleStartTest.kt:89`: fake allowed false.
- `harness/src/test/kotlin/app/solstone/observer/harness/HarnessControllerVisibleStartTest.kt:105`: fake allowed false.
- `harness/src/test/kotlin/app/solstone/observer/harness/HarnessControllerVisibleStartTest.kt:124`: helper parameter default.
- `harness/src/test/kotlin/app/solstone/observer/harness/HarnessControllerVisibleStartTest.kt:127`: pass into fixture.
- `apps/glasses/src/test/kotlin/app/solstone/observer/glasses/GlassesDiagnosticPlumbingTest.kt:44`: import.
- `apps/glasses/src/test/kotlin/app/solstone/observer/glasses/GlassesDiagnosticPlumbingTest.kt:135`: direct `ForegroundStartAllowed { false }`.
- `apps/glasses/src/test/kotlin/app/solstone/observer/glasses/GlassesObserverRuntimeCommandTest.kt:35`: import.
- `apps/glasses/src/test/kotlin/app/solstone/observer/glasses/GlassesObserverRuntimeCommandTest.kt:328`: direct `ForegroundStartAllowed { true }`.

## Explicit Call Sites After Default Removal

Visible authority default removal requires explicit values at:

- `apps/phone/src/real/kotlin/app/solstone/observer/phone/HarnessFlavor.kt:43`: pass `AlwaysVisibleCaptureAuthority`.
- `apps/phone/src/mock/kotlin/app/solstone/observer/phone/HarnessFlavor.kt:48`: pass `AlwaysVisibleCaptureAuthority`.
- `apps/watch/src/real/kotlin/app/solstone/observer/watch/HarnessFlavor.kt:43`: pass `AlwaysVisibleCaptureAuthority`.
- `apps/watch/src/mock/kotlin/app/solstone/observer/watch/HarnessFlavor.kt:48`: pass `AlwaysVisibleCaptureAuthority`.
- `apps/glasses/src/test/kotlin/app/solstone/observer/glasses/GlassesDiagnosticPlumbingTest.kt:122`: pass an explicit fake or `AlwaysVisibleCaptureAuthority`.

Already explicit:

- `apps/glasses/src/real/kotlin/app/solstone/observer/glasses/HarnessFlavor.kt:78`: real registry.
- `apps/glasses/src/mock/kotlin/app/solstone/observer/glasses/HarnessFlavor.kt:106`: explicit parameter.
- `harness/src/test/kotlin/app/solstone/observer/harness/HarnessTestSupport.kt:249`: fixture parameter.
- `apps/glasses/src/test/kotlin/app/solstone/observer/glasses/GlassesObserverRuntimeCommandTest.kt:350`: explicit parameter.

Flavor trust defaults currently have no inherited production/mock construction sites because all factories pass explicit values:

- Phone real exemption verifier: `apps/phone/src/real/kotlin/app/solstone/observer/phone/HarnessFlavor.kt:62`.
- Phone mock true: `apps/phone/src/mock/kotlin/app/solstone/observer/phone/HarnessFlavor.kt:107`.
- Watch real exemption verifier: `apps/watch/src/real/kotlin/app/solstone/observer/watch/HarnessFlavor.kt:62`.
- Watch mock true: `apps/watch/src/mock/kotlin/app/solstone/observer/watch/HarnessFlavor.kt:107`.
- Glasses real exemption/network: `apps/glasses/src/real/kotlin/app/solstone/observer/glasses/HarnessFlavor.kt:88-89`.
- Glasses mock exemption/network true: `apps/glasses/src/mock/kotlin/app/solstone/observer/glasses/HarnessFlavor.kt:112-113`.

## Notification IDs

- Id 101: live foreground-service notification only.
- Id 102: non-currently-observing needs-attention signal.
- Id 102 is used by boot, start failure, and sticky restart without rehydrator.
- Service successful start cancels id 102.
- No new channel or notification id is needed.

## Test-Tier Plan

| Criterion | Coverage tier | Planned coverage |
|---|---|---|
| 1. Initial FGS notification must not claim ON before capture is confirmed | Pure JVM decision function plus existing FGS JVM tests | `onStartCommandPlan(hasIntent, hasRehydrator)` asserts initial id 101 text is needs-attention. Android `startForeground` wrapper remains uncovered. |
| 2. Sticky/null-intent restart without rehydrator surfaces needs-attention and stops | Pure JVM decision function | `onStartCommandPlan(false, false)` asserts dispatch false, post id 102 true, stopSelf true. |
| 3. Notification text flips from needs-attention to ON only from diagnostics | Pure JVM decision plus glasses JVM/harness test | Test `needsAttentionForDiagnostics(state)` and a glasses transition/repost seam fake. Actual `NotificationManager.notify` uncovered. |
| 4. Start failure is caught and surfaced | Pure JVM exception decision plus FGS JVM test | Classify `SecurityException` and API-gated FGS-start-not-allowed exception by class name. Real Android throws are manual/on-device residual. |
| 5. Heartbeat request grace expires honestly | Pure JVM decision | New `HeartbeatMonitor.isFresh` cases for recent request, expired request, real beat. |
| 6. Production-wiring rehydrate self-heals | Harness JVM test | Controller constructed without foreground-start seam, real visible owner acquired, paired/reachable, reconcile starts lifecycle. |
| 7. Rehydrate with no visible owner is surfaced | Harness JVM/glasses JVM test | Assert blocked, attention/diag emitted, no exception, no lifecycle start. |
| 8. Healthy already-ON tick performs zero PL probes | Harness JVM test | Reorder `reconcileOnce`; count PL probe invocations. |
| 9. Desired-off does not report `NONE` as blocker | Harness/core JVM test | Gate decision: recommended `DESIRED_OFF`; assert direct `startReadiness(Rehydrate)` uses meaningful code or, if alternative chosen, no blocker. Reducer remains unchanged. |
| 10. Boot notification gated by persisted desired state | Pure JVM FGS test | `observerBootAction(false)` no post; `observerBootAction(true)` post only, no service/capture/sync. Android prefs read/notify/contentIntent uncovered. |
| 11. Default-true trust values removed | Compile/JVM tests | No default args; all call sites explicit. Compile catches misses. |
| 12. Seam removed cleanly | Compile/JVM tests | `ForegroundStartAllowed` deleted, all imports/call sites gone, tests updated. |
| 13. FGS rehydrate does not run network probe on main thread | Glasses JVM test | Test runtime delegates to container background rehydrate method. Actual Android main-looper behavior manual residual. |

## Implementation Order

1. Add pure decision functions and JVM tests in `platform/fgs`: heartbeat grace, start command plan, notification attention decision, exception handling decision, boot action with persisted desired.
2. Add notification id 102 wrapper behavior, id 101 repost seam, permission-denied diagnostics, launch intent handling, and id 102 cancellation.
3. Move prefs constants to fgs and update `SharedPreferencesDesiredObservingStore`.
4. Rework glasses rehydrate to container background executor and update interfaces/test doubles.
5. Reorder `HarnessController.reconcileOnce` and `startReadiness`; resolve the `DESIRED_OFF` open decision.
6. Remove default-true trust defaults and make all call sites explicit.
7. Remove `ForegroundStartAllowed` seam and update all real/mock/test call sites.
8. Add/replace harness production-wiring self-heal tests.
9. Update `platform/fgs/FGS_MATRIX.md` Boot behavior if needed.
10. Run `make ci`; because this lode touches on-device app surfaces and platform adapters, also run `make ci-device` even though `platform/fgs` itself is not directly instrumented.

## Risks And Open Questions

- Open gate decision: choose `ReasonCode.DESIRED_OFF` versus removing the desired-off blocker entirely. Recommendation is `DESIRED_OFF`.
- `NotificationManager.notify`, `startForeground`, service-start exceptions, and launch-intent construction cannot be fully automated in this repo's current test tiers.
- Phone/watch boot attention remains temporarily disabled by their in-memory desired store until the sibling store consolidation lode.
- Adding a `ReasonCode` is source-compatible in Kotlin but can affect serialized/externally interpreted enum names if any downstream code assumes a closed set. Grep did not show such serialization in this lode's target path, but release notes should mention the additive enum if exposed.
