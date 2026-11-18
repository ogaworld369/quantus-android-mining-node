# Quantus Android Node

Android-only, open-source app that runs a Quantus Network (QTC) full node on a phone.

**Purpose: decentralization, not profit.** The app itself earns nothing. It syncs, verifies every block itself, and relays data to peers — so the network depends a little less on a small number of servers, and a little more on ordinary people with hardware they already own. Since v4.0.0 it can also mine — optionally, off by default — for the user's own rewards address.

The design concept is to make the node's pulse visible: live block/peer visualization with a quiet "presence" (KEHAI) design, and a local record of your contribution.

## At a glance

| | |
|---|---|
| Current release | **v4.2.5** (versionCode 71), published 2026-11-18 — [release page](https://github.com/ogaworld369/quantus-android-mining-node/releases/tag/v4.2.5) |
| First public release | v0.1.0, published 2026-11-04 |
| Download | Signed APK from [GitHub Releases](https://github.com/ogaworld369/quantus-android-mining-node/releases) |
| Requirements | Android 12 or later, arm64 device |
| Verified device | Pixel 9a running GrapheneOS |
| Languages | 9 (447 strings per language; see [docs/I18N.md](docs/I18N.md); native-speaker improvements are very welcome) |
| Telemetry listing | Opt-in only (off by default) |
| Licence | Apache License 2.0 |

## What this app is — and is not

- **It is** a full node for Quantus mainnet: it syncs, verifies every block itself, and relays data to peers. With mining switched on (v4.0.0, opt-in and off by default), the same node also validates and mines for the user's own rewards address.
- **It is not** a wallet. It never handles wallet seed phrases and cannot spend funds. The one mining value it can hold is the rewards inner hash the user pastes in — a preimage that is published in block headers when mining and controls no assets by itself (see "Security first" below).
- **New in v3.0 — activity recording (DePIN), built in.** Opt-in and off by default. With it on, the app keeps a signed record of the node's uptime and relay work, which you can export as a proof bundle file whenever you choose.

## What's in v4.2.5

- **Mining is faster: the bundled miner now hashes in batches.** The miner's proof-of-work engine processes four nonces at a time in a transposed layout that reuses one hashing setup across the batch — measured on a Pixel 9a at **436,642 hashes/s per thread against 357,195 for the previous engine (about 1.22x)**. The batched engine is plain Rust with no unsafe code, is differentially tested against the previous engine and the reference implementation, and returns exactly the same results; SIMD variants (NEON, SVE2) and profile-guided builds were measured on the same device and were not adopted. The bundled node binary is unchanged, there are no feature or permission changes, and mining stays opt-in and off by default. Details: [CHANGELOG.md](CHANGELOG.md), [docs/BUILD-miner.md](docs/BUILD-miner.md).

## What's in v4.2.4

- **The security fix release for the external audit of v4.2.3** (no critical or high findings; one medium, eleven low, twelve informational — every item fixed, or accepted with the reason recorded in [SECURITY.md](SECURITY.md)). An audit of the changes themselves found no critical or high findings (one medium, three low), and every verified finding was fixed in this release. No feature changes, no new permissions, and the bundled binaries are unchanged.
- **The app now verifies it is talking to its own node.** The node silently moves its RPC to a random port when the usual one is taken, so the app adopts the address and peer identity from the node's own startup output, checks the identity on every poll, and keeps a record of them that stays valid only while that node process lives — the activity recording and the identity transfer read through the same check. Statistics and diagnostics derived from the node's log now count only lines in the node's own format, and shared diagnostics mask every non-loopback address.
- **Less of the node is visible by default.** The display name is passed to the node only while telemetry is on (peers otherwise see the default name), mDNS no longer advertises the node on the local network, and the metrics endpoint and offchain workers are off.
- **The handover confirmation number is derived from the handover's own secret**, not from the transfer code; handover packages are capped at 16 MiB and imports stream instead of buffering the whole file. Both devices need v4.2.4 for a handover. Details: [CHANGELOG.md](CHANGELOG.md).

## What's in v4.2.3

- **The bundled binaries are rebuilt** — the first binary change since v3.0.2. The node's miner endpoint now binds to **loopback only** (127.0.0.1:9833) instead of all interfaces: the only miner it ever serves is the one bundled in the same app, and the auth token and TLS pin remain in place as further layers. The mining confirmation text now says the port is reachable only from within the device, in all nine languages.
- **Dependency security updates in both binaries** (node: `quinn-udp` 0.5.16, `hickory-net` / `hickory-proto` 0.26.2; miner: `rustls` 0.23.46, `rustls-webpki` 0.103.16, `quinn-proto` 0.11.19, `quinn-udp` 0.5.16, `anyhow` 1.0.104, `crossbeam-epoch` 0.9.20), the dependency audit re-run, and the bundled CA roots refreshed. No feature, permission, launch-argument or data changes. Details: [CHANGELOG.md](CHANGELOG.md).

## What's in v4.2.2

- **A polish release: the remaining low findings of the v4.2.0 audit, its follow-up checks and the gap inventory, fixed — except the notification bidi item (phase-1 L-7) and FC-3, which remain as accepted items.** The member card's streak is labelled as what it is — the longest consecutive run; the Storage page also shows the device's free space; the donation sheet gains a copy button, the sheets' close buttons are named for screen readers, and the content behind an open sheet is hidden from accessibility services; the settings lock also covers the price switch in the price sheet, and the External information page says why it is disabled; value rows and the home today row share the stacking row layout, so long values in verbose languages no longer crush their labels; price freshness is judged on the monotonic clock and failed fetches retry after 15, then 30, then 60 minutes; the daily record is written at most every 15 minutes; mining time is counted across delayed polls but never across a suspend, a miner restart button is disabled while its thermal stop is latched, and a thermal stop is requested before its log line is written, so an Error escaping the log cannot leave the miner running. Details: [CHANGELOG.md](CHANGELOG.md).

## What's in v4.2.1

- **Fixes from the external audit of v4.2.0** (two high, five medium; no critical): the Storage page measures its sizes again — including right after a resync; the price and donation sheets no longer slide under the bottom navigation, so the donation address is fully visible and copies on tap; after a failed start, Node details opens with the diagnostics expanded; the Settings hub's Node row no longer shows the telemetry switch as the node's state; a stale price dims on the home row too; and the first-run paragraph no longer pushes the Start button toward the fold. Details: [CHANGELOG.md](CHANGELOG.md).

## What's in v4.2.0

- **The home screen is a list now.** Under the status card and the start/stop button, everything is a row: today's record (with a progress bar), mining, the QTC price, node details, the activity track and donations. The price card is gone — the QTC row opens a price sheet with the larger figure, the 24-hour change and the source, and the donation address has its own sheet. Until the node has run for the first time, the status card shows a short paragraph on what the app does.
- **Price fetching can be switched off, and says what it does.** A switch on the price sheet and in Settings (on by default) turns price fetching off; a note there says what a fetch connects to and what it never sends. The refresh interval is now one hour instead of five minutes.
- **Settings is a hub of pages** — Node, Mining, DePIN records, Storage, Device transfer, External information, Language, About — and every row shows its current value. The sections themselves work as before.
- **The member tab scrolls, and the card has its declared ratio** (1.42:1, reached by tightening margins only). A standing note below the card says the records live only on this device, are never converted into money and carry no ranking. A long press on the member number copies it.
- **Motion and touch.** Screen changes fade or slide briefly, sheets rise from the bottom, the main button answers a press immediately, toggles and the resync confirmation have haptic feedback, and screen changes are announced to accessibility services. All motion follows the system's animation setting.
- Verified on a real device before release: an in-place update over v4.1.0 with settings and records preserved, the node synced to the chain tip, mining running with jobs received, the price fetched, and a shared diagnostic summary with no warnings or errors. Details: [CHANGELOG.md](CHANGELOG.md).

## What's in v4.1.0

- **Storage management in Settings:** per-area sizes (the chain database, the node as a whole, the logs, the DePIN records) and a "delete the chain database and resync" action — available only while the node is stopped, behind a confirmation that spells out what is deleted and what is kept. Only the database directories are removed; the node's network key, the mining settings, the DePIN identity and records, the logs and the app settings all stay.
- **A daily activity record** is now kept on the device (uptime, blocks observed, peer counts, mining jobs and time) as the foundation for a later activity-track view. Nothing is displayed yet and nothing leaves the device.
- Readability and polish fixes, and two robustness fixes carried over from the v4.0.0 audit's follow-up list. Details: [CHANGELOG.md](CHANGELOG.md).

## What's in v4.0.0

- **On-device mining, as an opt-in (off by default).** With mining on and a rewards inner hash saved, the bundled node starts as a validator and the bundled official miner (`quantus-miner` v4.2.1, rebuilt without its HTTP metrics exporter) runs as a second process, fed jobs by the node. Rewards go to the wormhole address of the user's own inner hash — the address the node reports in the app can be compared with the official key tool's output before trusting it.
- **Thermal protection.** Mining defaults to 2 CPU workers; at the platform's "moderate" heat level the worker count is halved, and at "severe" the miner stops for the rest of the session. The phone is never pushed to its limit.
- **A support footer** at the bottom of the Home screen shows the developer's QTC donation address (the same address as in "Support" below); tapping it copies it. Donations are voluntary, and the app never sends anything to it.
- Verified on a real device before release: mining on, jobs received from the bundled node, the reported rewards address matching the maintainer's wallet, the metrics exporter inactive, and the inner hash never appearing in shared diagnostics. A found block was not observed on a device — at the current difficulty a phone's expected wait is far longer than a test session — so the submission path rests on tests over the official miner's real log lines. Details: [CHANGELOG.md](CHANGELOG.md).

## What's in v3.0.2

- **A hotfix release: the bundled node binary is rebuilt** (the first binary change since versionCode 10). No feature changes, no new permissions, and no changes to the app code, the node launch arguments or the database.
- On some devices (reported on a Xiaomi phone) a node could stall permanently — stuck far behind the tip with peers dropping to zero — because the node's Wasm runtime reserved its memory pool with a single 258 GiB virtual-memory mapping that the device refused; after that, every block verification failed. The rebuilt binary creates runtime instances on demand instead of reserving the pool. Block validation itself is unchanged.
- Details: [CHANGELOG.md](CHANGELOG.md).

## What's in v3.0.1

- **A maintenance release with audit fixes only** — no feature changes, no new permissions, and no changes to the node launch arguments, the database or the bundled node binary.
- A fourth round of external audit and an internal review of the v3.0 tree fixed a set of defects in the activity-recording and identity-transfer machinery — among them: turning recording off right after turning it on could leave recording running while the display showed off; a journal filled to exactly the maximum could no longer be exported; and exporting a proof bundle could create a fresh identity seed as a side effect after the home identity had been sent out.
- Every verified finding is fixed; one display item that needs new UI strings in all nine languages is left for a later release. Details: [CHANGELOG.md](CHANGELOG.md).

## What's in v3.0

- **Activity recording (DePIN) is built into the node app** — the standalone companion app's role is complete. Recording writes a signed checkpoint every 10 minutes to an on-device journal; the identity seed is kept in an encrypted vault; an existing identity — key and full history — moves between installs in a single one-file exchange.
- **The main screens are redesigned** around a three-tab bottom navigation (home, connection, membership card). Home shows the live QTC price next to the pulse and the node state; the connection tab draws the node's network field in place of the previous radar-style figure; the membership card carries your member number (device ID) and three headline figures from your local record — no rankings, and the record is never converted into money.
- **Sync stall recovery.** The node service detects a stalled sync — the network tip advancing while the best block stays frozen — and restarts the node on its own (at most three times in 24 hours), with sync debug logging enabled so this class of stall leaves evidence.
- **Three rounds of pre-release final check**, with no critical findings in any round and every verified finding fixed before release.

Details and the full version history: [CHANGELOG.md](CHANGELOG.md).

## Download

Get the signed APK from [GitHub Releases](https://github.com/ogaworld369/quantus-android-mining-node/releases). The current release is [v4.2.5](https://github.com/ogaworld369/quantus-android-mining-node/releases/tag/v4.2.5); its APK size and SHA-256 are listed on the release page (and in the release records in [docs/RELEASE.md](docs/RELEASE.md)), so you can verify your download. v4.2.5 installs over v4.2.4 keeping settings and records.

## Usage

New to the app? Start with the Usage Guide — available in all 9 languages the app speaks: [English](docs/USAGE.md), [日本語](docs/USAGE.ja.md), [简体中文](docs/USAGE.zh-Hans.md), [Español](docs/USAGE.es.md), [हिन्दी](docs/USAGE.hi.md), [العربية](docs/USAGE.ar.md), [한국어](docs/USAGE.ko.md), [Русский](docs/USAGE.ru.md), [Français](docs/USAGE.fr.md). Requirements, installation, the first sync, settings, mining, and troubleshooting.

## Status

- Publicly released since 2026-11-04; the current release is v4.2.5 (above). Per-version details live in the [CHANGELOG](CHANGELOG.md), not here.
- Still being watched on the reference device: the remaining on-device checks — where the pruned database size plateaus, and battery/heat over long uptimes.
- Release gates: [docs/RELEASE.md](docs/RELEASE.md). Design: [docs/DESIGN.md](docs/DESIGN.md).

## Scope

- A full node for Quantus mainnet: sync, verify, relay, local status — and, since v4.0.0, optional mining (off by default) for the user's own rewards address. No wallet. The app never handles seed phrases — by design, there is no asset secret inside the app worth stealing.
- Devices: not Pixel-only. Requirements: Android 12+ on arm64 — the implementation has no Google-services or Pixel-specific dependency (it runs on GrapheneOS, which ships without Google services). Verified so far: Pixel 9a running GrapheneOS. Other devices are expected to work but are unverified; OEM battery savers (Samsung, Xiaomi, …) may stop the resident service. Device reports via GitHub Issues are welcome — the verified list grows from them.

## Known limitations

- The node prunes its database to the latest 256 blocks: old block history cannot be queried from this device. (The Storage page can delete the chain database and resync from the start.)
- Only Android 12 or later on arm64 is supported, and only the Pixel 9a / GrapheneOS combination is verified so far; on other makers' devices, aggressive battery management may stop the resident node service.
- Mining is participation, not income: at a phone's hashrate the expected wait for a block is very long, and the phone gets warm while it runs (the app eases off and stops the miner on heat, by design).

## What we know so far

- Upstream: `quantus-node` v1.0.5 (Quantus-Network/chain), Substrate-based, QPoW / Poseidon2, ML-DSA signatures.
- The node cross-builds for `aarch64-linux-android`. Four small patches are needed, all in [patches/](patches/):
  - one makes explicit pruning values take effect (next bullet);
  - one stops DNS resolution from panicking when the node runs as a plain process outside an Android app runtime (vendored `ndk-context` / `hickory-resolver` with a fallback);
  - one makes the Wasm runtime create instances on demand instead of reserving its whole memory pool up front;
  - one binds the node's miner endpoint to loopback (`127.0.0.1`) instead of all interfaces (since v4.2.3).

  The build recipe is fixed in [docs/BUILD-android.md](docs/BUILD-android.md). Release-profile binary: ~53 MB stripped (debug: ~123 MB).
- Upstream forces archive storage on all nodes; a minimal patch (respect explicit numeric pruning) enables a phone-sized database. Details in the design doc.
- Official minimum specs list 100 GB storage (validator-oriented); a pruned non-validator node is far smaller in practice. Measured on a Pixel 9a after a full sync (2026-11-05): 1.30 GB in total (96 MB app + 1.20 GB of node data). Where it plateaus over months is still being watched.

## Roadmap (summary)

- Next features: a home-screen widget; the activity-track graph, drawn from the daily record the app has kept on-device since v4.1.0; a QR presentation mode for the member card; and a QTC price / network-nodes visualization.
- The DePIN aggregation pilot (collecting proof bundles through the DePIN repository) has not started yet.

## Security first

The secrets on the device are identity keys: the node's P2P identity key, and — only when DePIN activity recording is turned on — the participation identity seed, stored encrypted in app-private storage. Neither controls any assets. Mining (v4.0.0) adds one more value, only when the user turns it on: the rewards inner hash, a preimage that cannot spend rewards and is published in block headers by protocol design; it is kept in app-private settings and scrubbed from logs, screens and shared diagnostics. All are excluded from cloud backup (`allowBackup=false`). RPC stays on loopback only. No analytics, no ads, no remote code loading — native code ships inside the signed APK only. Distribution is signed APKs on GitHub Releases, gated by [docs/RELEASE.md](docs/RELEASE.md); an F-Droid listing is planned for a later phase.

## License

Apache License 2.0 — see [LICENSE](LICENSE). That licence covers this app's own code.

The app bundles two prebuilt Rust binaries, whose licensing is mixed:

- `libquantus_node.so` (the node) is built from the upstream Quantus chain. Most of its crates are MIT and/or Apache-2.0, but the Substrate client crates are GPL-3.0-or-later WITH Classpath-exception-2.0, two crates (`qp-rusty-crystals-dilithium`, `qp-rusty-crystals-hdwallet`) are GPL-3.0, and one crate (`option-ext`) is MPL-2.0. This project patches one of the GPL crates (`client/executor-wasmtime`); the patch is in [patches/](patches/), and the corresponding source is the upstream repository at the pinned revision plus that patch, built exactly as [docs/BUILD-android.md](docs/BUILD-android.md) describes.
- `libquantus_miner.so` (the miner, bundled since v4.0.0) is Apache-2.0, with MIT/Apache-2.0 dependencies.

The full per-crate lists for both binaries are in [docs/THIRD-PARTY-LICENSES.md](docs/THIRD-PARTY-LICENSES.md) and inside the app under Settings → About → Open-source licences, together with the licence texts. The bundled fonts are under the SIL Open Font License (texts in `app/src/main/assets/fonts/`).

## Support

Quantus Node for Android is a personal open-source project, developed for the love of decentralization. If it is useful to you, donations in QTC are gratefully received:

`qzoqy3pcLsBcPpGFqj9BTv3UyvRB9o8ScnKqcQfSbQTR6odnu`
