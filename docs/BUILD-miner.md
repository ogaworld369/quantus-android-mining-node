# Building the bundled miner for Android (aarch64-linux-android)

The miner bundled since v4.0.0 as `app/native/libquantus_miner.so` is the
official `quantus-miner` **v4.2.1** (Quantus-Network/quantus-miner),
cross-built for Android with **one** source change against the release
tag, recorded below. Everything else — sources and release profile —
is the upstream tag unchanged; the lockfile carries the security
updates listed in the measured-results table (2026-11-17 row).

## The one change: the metrics HTTP exporter is not compiled in

Upstream `miner-cli` enables the metrics crate's `http-exporter`
feature, which serves Prometheus metrics over unauthenticated HTTP
(0.0.0.0:9900). A phone miner has no use for it and it must not ship.
The feature is opt-in (the metrics crate's default features are
empty), so removing it is a one-line dependency change:

- File: `crates/miner-cli/Cargo.toml`
- Before: `metrics = { path = "../metrics", features = ["http-exporter"] }`
- After: `metrics = { path = "../metrics" }`

With the feature off, the exporter's entry point compiles to a stub
that logs `'http-exporter' feature is disabled` and does nothing; the
HTTP stack (warp/tokio server side) is not linked at all.

## Build

```sh
source ~/workspace/toolchain/android-env.sh
cargo build --release -p miner-cli --target aarch64-linux-android
# then strip with the NDK llvm-strip
```

## Measured results

| Build | Unstripped | Stripped | SHA-256 (stripped) |
| --- | ---: | ---: | --- |
| Upstream v4.2.1, unmodified (2026-11-07) | 10,099,424 B | 8,276,048 B | `eaa10f9cf7eb69b23301614370ee2bf7ffa075aa59016b82cc1758792332a053` |
| v4.2.1 with the exporter removed (2026-11-15) | 8,953,056 B | **7,374,088 B** | `7d175091460f05cb8052af36c687d1c129a3dafbe791565db9a6b3b666e2ae8e` |
| v4.2.1 with the exporter removed and the 2026-11-17 lockfile updates (`rustls` 0.23.46, `rustls-webpki` 0.103.16, `quinn-proto` 0.11.19, `quinn-udp` 0.5.16, `anyhow` 1.0.104, `crossbeam-epoch` 0.9.20) | 8,959,856 B | **7,379,272 B** | `2b746be4b6febc0547c47f487f1944b03e20a4c82072dda3431d64282acd687c` |

## Verification of the shipped binary

- `strings` finds **no** `warp` (HTTP framework) strings in the
  exporter-free build; the unmodified build has them.
- The exporter-free build contains the stub line
  `start_http_exporter called but 'http-exporter' feature is disabled`,
  which is what `logs/miner.log` must show on device when the miner
  starts (checked in the on-device end-to-end run).
- ELF 64-bit LSB pie executable, ARM aarch64, interpreter
  `/system/bin/linker64`, stripped — the same packaging shape as the
  bundled node binary.
