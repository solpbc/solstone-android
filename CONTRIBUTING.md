# Contributing

`solstone-android` is early and private during bootstrap. Contributions are reviewed for correctness, safety, privacy, and fit with solstone's owner-controlled data model.

## Development

```bash
source ~/android-dev/env.sh
make install
make ci
```

Use focused commits. Run `make ci` before asking for review.

## Pull Requests

- Keep changes scoped to one concern.
- Include tests or validation evidence for behavior changes.
- Do not add analytics, telemetry, tracking, crash-reporting SDKs, or hosted release automation.
- Call out permission, foreground-service, battery, link-protocol, and data-path changes explicitly.

## License

By contributing, you agree that your contribution is licensed under AGPL-3.0-only.

