# Contributing

Thank you for helping decentralize the Quantus Network.

## Ground rules
- This app is a full node: by default it runs as a non-validator, and since v4.0.0 it can also mine on-device as an opt-in (off by default). There are no wallet features. PRs that add asset handling will be declined by design.
- Security first: no analytics/ads SDKs, no remote code loading, RPC stays on loopback.
- Be honest in benchmarks and tests — a passing build is not proof of correctness. Node changes must be verified against an upstream `quantus-node` (matching block hashes at the same height).

## Getting started
- Read `docs/DESIGN.md` first — it records the architecture, threat model, and what is still unverified.
- Look for issues labeled `good first issue`.
- For larger changes, open an issue to discuss the approach before writing code.

## Building and testing
- The build is manual: `app/build-manual.sh`, with `JAVA_HOME` and `ANDROID_HOME` pointing at a JDK 21 and an Android SDK (the script defaults to paths under `~/workspace/toolchain/…`). The Gradle build is not used.
- The three native libraries the app bundles are not in git; building them is covered in `docs/BUILD-android.md`.
- Tests are plain-JVM runners (no emulator needed): `core/tests/run-tests.sh`, `core/tests/run-transfer-tests.sh` and `app/tests/run-mining-tests.sh`.

## Pull requests
- Keep PRs focused; describe how you tested (see the PR template).
- By contributing you agree your work is licensed under the project's license, Apache-2.0 (see `LICENSE`).
