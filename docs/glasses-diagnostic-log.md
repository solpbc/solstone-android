# Glasses Diagnostic Log

The glasses app keeps a durable redacted diagnostic log under app-private storage at `filesDir/diag/diag.log`, with the previous rotated file at `filesDir/diag/diag.log.1`. The sink is process-wide, installed from `GlassesApplication.onCreate` before runtime/container construction, and writes newline-terminated `key=value` records. Each record is prefixed by the sink as `ts=<epochMillis> `. Rotation is a two-file ping-pong: readers reconstruct order as `diag.log.1` followed by `diag.log`, with total storage bounded to at most `2 * capBytes`.

Line grammar is ASCII `key=value` fields separated by single spaces. Event examples:

```text
ts=1782940000000 kind=fgs phase=create
ts=1782940000100 kind=fgs phase=start startId=1 flags=0
ts=1782940000200 kind=fgs phase=destroy
ts=1782940000300 kind=fgs phase=task-removed
ts=1782940000400 kind=mem trim=15
ts=1782940000500 kind=mem low
ts=1782940000600 kind=swipe key=24 action=EnsureObserving
ts=1782940000700 kind=state from=OFF to=ON reason=NONE
ts=1782940000800 kind=caught site=poll type=IllegalStateException
ts=1782940000900 kind=caught site=pipeline type=RuntimeException code=7
ts=1782940001000 kind=diag-dropped count=3
ts=1782940001100 relay-pair outcome=Linked mode=PAIRING
ts=1782940001200 reconcile mode=Rehydrate result=blocked blockers=UNPAIRED,TRANSPORT_UNAVAILABLE
ts=1782940001300 reconcile mode=VisibleStart result=started
ts=1782940001400 desired on=true
ts=1782940001500 capture event=segment-sealed count=2
ts=1782940001600 capture event=error source=camera type=CameraAccessException
```

All typed events are redacted by construction: fields are enums, ints, counts, exception simple names, short static site labels, or hash prefixes. There are no message, pair-link, ticket, token, certificate, payload, media, or extras fields. To inspect the active file on device:

```bash
adb shell run-as app.solstone.observer.glasses cat files/diag/diag.log
```
