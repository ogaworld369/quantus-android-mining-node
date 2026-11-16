# Usage Guide

**Languages:** English · [日本語](USAGE.ja.md) · [简体中文](USAGE.zh-Hans.md) · [Español](USAGE.es.md) · [हिन्दी](USAGE.hi.md) · [العربية](USAGE.ar.md) · [한국어](USAGE.ko.md) · [Русский](USAGE.ru.md) · [Français](USAGE.fr.md)

This app runs a **full node** for Quantus Network (QTC mainnet) on an Android phone. It syncs the chain, verifies every block itself, and relays data to peers. By default it runs as a non-validator; it can also mine, as an option that is off by default (see **Mining** below). It has **no wallet** and never touches seed phrases — the one secret it can hold is the mining rewards Inner Hash you paste in yourself.

> Status: v4.2.1 released (2026-11-16), the current release — a fix release for the external audit of v4.2.0: the Storage page measures its sizes again, including right after a resync; the price and donation sheets no longer slide under the bottom navigation, so the donation address is fully visible and copies on tap; after a failed start, Node details opens with the diagnostics expanded; the Settings hub's Node row no longer shows the telemetry switch as the node's own state; a stale price now dims on the home row too; and the first-run paragraph no longer pushes the Start button down (v4.2.0, also released 2026-11-16, made the home screen a list under the status card, Settings a hub of pages and the member tab a scrolling column). The signed APK is published on [GitHub Releases](https://github.com/ogaworld369/quantus-android-mining-node/releases) with a SHA-256 checksum. Earlier test builds were shared directly by the maintainer.

## Requirements

- Android 12 or later, arm64 device
- Free storage: the node database is pruned (only the latest 256 blocks are kept), so it stays small. Measured on a Pixel 9a after a full sync (2026-11-05): 1.30 GB in total (96 MB app + 1.20 GB of node data). As a guide, keep at least about 3 GB free
- An internet connection. Wi-Fi is strongly recommended for the first sync (see below)

Verified so far: **Pixel 9a running GrapheneOS**. Other devices are expected to work but are unverified. If you try it on another model, please report the result (working or not) as a GitHub Issue — the verified list grows from those reports.

## Install

1. Get the APK file from [GitHub Releases](https://github.com/ogaworld369/quantus-android-mining-node/releases) — verify the SHA-256 checksum published with the release before installing.
2. Open the APK with your Files app and allow installation from that source when Android asks.
3. Install, then open **Quantus Node**.

## First run and the initial sync

1. (Optional) Set a **display name** and the **telemetry** option first (see Settings). Setting changes need a node restart (Stop → Start) to apply.
2. Tap **Start node**.
3. The status shows **Syncing** with the best block climbing toward the highest block. On a Pixel 9a the first sync from genesis runs at roughly 50 blocks/second — expect on the order of **2 hours**. One new block arrives about every 12 seconds after you are caught up, and the status turns to a synced state.
4. During the first sync, keep the phone **charging and on Wi-Fi** if you can. Syncing over mobile data works, but it downloads a lot of data and uses noticeably more battery (about 11% in 30 minutes measured during sync on mobile data). After the sync completes, the ongoing load is small.

You do not need to keep the app open. The node runs as a foreground service: it keeps running with the screen off or while you use other apps, and shows a persistent notification while it runs. Syncing resumes from where it stopped if you stop and start it again — the downloaded chain is kept on the device.

## What the screens show

The app has three main tabs along the bottom — Home, Connection, and Membership card. Node details and Settings are reached from Home.

- **Home** — the node's best block and state (Stopped / Starting / Syncing / Synced / Failed) and the one main button. While the node runs, it also shows the connected peer count, this session's uptime, and the current QTC price. If a start fails, Home shows it as a failure with a retry button instead of a plain stop; if a setting you changed has not taken effect yet, Home shows a link to review it
- **Connection** — a living picture of your node's network field: the peers it is connected to and the echoes of its own recorded checkpoints. It shows relationships, not numbers to compete with — there is no ranking
- **Node details** — best block / highest block (where your node is, versus the network tip it knows), the sync gap, peers and uptime, and the **Diagnostics** section: exit code, service notes, the record of each start attempt, and **Share log** (see below). While the node is stopped, this screen shows your cumulative track record instead of the previous session's values
- **Membership card** — your member card: your member number (your device ID) and three figures from your local record — your first checkpoint's date, your checkpoint count, and your longest day streak. The record is stored only on this device; it is never uploaded, and it is worth no money — it is simply your own trail of contribution, yours to show
- **Settings** — the settings below, arranged as a hub of pages. The app version is shown on the **About this app** page, which also lists the **Open-source licences** of the bundled node and miner binaries

## Settings

- **Appear in telemetry** (off by default) — when on, your node name and live status become publicly visible in the official telemetry at telemetry.quantus.com. Turn it on only if you want to be listed. Applies from the next node start (Stop → Start).
- **Display name** — the name your node uses (also shown in telemetry if enabled), edited in a dialog. Leave blank to use the default name `quantus-android-node`. Applies from the next node start.
- **Language** — follow the device setting, or pick one of 9 languages (Japanese, English, Simplified Chinese, Spanish, Hindi, Arabic, Korean, Russian, French). Applies immediately.
- **Record my activity (DePIN)** (off by default) — when on, the app keeps a signed record of the node's uptime and relay work on this device (a checkpoint every 10 minutes). Nothing is sent anywhere by itself; from Settings you can export the record as a proof bundle file and share it yourself. Turning it off stops the recording; the record stays on the device
- **Lock settings** — locks the settings above (telemetry, display name, language, and activity recording) and the mining settings (the mining switch and the Inner Hash) so they cannot be changed by accident while the phone sits there running the node. It is a mis-tap guard only: there is no PIN, and starting or stopping the node is never locked. Turn **Lock settings** off when you want to change settings again.
- **Mining** (off by default) — the node can also mine on this device, with the bundled official miner. It needs a rewards Inner Hash first; see **Mining (optional)** below.

Settings never restart the node by themselves. If you change telemetry or the display name while the node is running, the changed item is marked in Settings as not yet applied, and Home shows a link to review the pending settings; the change takes effect at the next node start (Stop → Start).

**Share log** lives under **Node details → Diagnostics**: it shares the node log as text, and is what to attach when you report a problem. Before posting a log in a public place, check its contents first: it can include your node's public IP address and peer information. The share dialog opens with a summary of the session, and its masking (on by default) covers the display name, this device's own interface addresses and app-private paths; the outward address the node learns through its peers is not masked.

## Storage

**Settings → Storage** shows how much space the app uses, in five figures: the **chain database**, the rest of the **node data**, **logs**, the **activity record** (DePIN) and the **total**. While the node is running the figures keep changing, so treat them as estimates.

Its one action, **Delete the chain database and resync**, deletes only the chain database — the block data this device has synced so far. The node's network key, the mining settings and the saved Inner Hash, your DePIN identity and its record, the track record, the logs and the app's settings are all kept. The action works only while the node is stopped, and it cannot be undone: the next time you start the node, syncing begins from the start again, which takes time and data (about 2 hours on a Pixel 9a).

## Price

Home shows the current QTC price, which the app fetches from **CoinGecko** (api.coingecko.com) about once an hour. Fetching is **on by default**; the switch is in the price sheet (tap the price on Home) and under **Settings → Information from outside**. A fetch sends only the price query — no node information, member number or records are ever sent — but CoinGecko can see this device's IP address, as any server you connect to can. With the switch off, nothing is fetched: the price row keeps showing the last known value, with the time it was fetched and dimmed once it is old — or shows nothing if no price has ever been fetched.

## Mining (optional)

The app can also mine, on this phone, with the official miner bundled in the app. Mining is **off by default**, and the node works exactly the same with it off. Turning it on makes the node start as a validator, and the rewards go to you — not to the app.

**Before you can turn mining on, you need a rewards Inner Hash.** Rewards are not paid to an ordinary address: the protocol pays them to a **wormhole address** derived from a secret called the Inner Hash.

To get an Inner Hash:

- On a PC, run the official node's command `quantus-node key quantus --scheme wormhole`. It prints three values: the **Address** (where the rewards will go), the **Inner Hash** (the value this app needs), and a **Secret phrase** (your backup — store it safely, offline).
- If you already use the official wallet, you can derive the same values from its seed phrase instead: add `--words` to the same command and enter the phrase when asked — on the PC, never in this app. With the same seed phrase, the rewards appear in that wallet automatically.

Then, in the app:

1. Open **Settings → Mining** and paste the **Inner Hash** value (it looks like `0x` followed by 64 hex digits). Enter only the Inner Hash — never a seed phrase or a secret key: the Inner Hash is written into every block this device produces and becomes public.
2. The app shows the rewards address the node reports for your Inner Hash. Compare it with the **Address** from the tool before you rely on it.
3. Turn mining on, then restart the node (Stop → Start). Mining settings take effect from the next node start.

**Check the Inner Hash carefully before saving.** If it is wrong, the rewards are paid to that wrong destination and cannot be recovered. The saved value is stored only on this device; the app never shows it on screen, in logs or in shared text.

**Workers and heat.** You can choose 1, 2, 4 or auto CPU workers (the default is 2; one core is always left for the node itself). More workers mean more heat and battery use. If the phone gets hot, mining automatically eases off at the platform's "moderate" heat level and stops for the rest of the session at "severe" — the phone is never pushed to its limit.

**An honest expectation:** at a phone's hashrate, the expected wait for a found block is very long — far longer than any test session. Mining here is a way to take part, not a way to earn.

## Moving to a new device

Your DePIN identity and its record can move to a new phone. Open **Settings → Device transfer** on both devices; the simple way takes one file:

1. On the **old device**, tap **Create a simple handover file**. The app creates the file and shows a **transfer code** once — write it down. Recording on the old device stops at this moment.
2. Get the file to the **new device** (any way you like). There, tap **Import a transfer package**, pick the file and enter the transfer code.
3. The new device verifies the package and shows the identity it is about to take over: the device ID, the checkpoint count, and the first and last record dates. Check the values, then confirm with **Confirm with simple handover**. The identity is now this device's own, and recording can start here.
4. The new device then shows an 8-digit **confirmation number**. On the old device, tap **Enter the confirmation number: erase this device's key** and enter it — the old key is erased only when the number matches. Afterwards, delete the handover file wherever it still sits.

Until step 4 is done, two copies of the identity exist. **Treat the handover file like a key**: it holds the identity seed encrypted with the transfer code, so anyone who has both the file and the code can take over the identity and its whole history. Keep the two apart where you can, and never post either in a public place. If the file never reached the new device, **Cancel the simple handover** on the old device lets recording resume there — but never cancel once the new device has imported it.

Under **Other actions**, the same page also offers the stricter step-by-step transfer: instead of the single confirmation number, the two devices exchange a staging notice, a confirmation permit, a receipt and a completion acknowledgement, one file at a time.

## Running it day to day

- The node stops only when you tap **Stop node**, force-stop the app, or reboot the phone.
- After a reboot, open the app and tap **Start node** again — automatic start after boot is not implemented yet.
- On some manufacturers' Android versions (Samsung, Xiaomi, etc.), aggressive battery saving may stop background services. If your node keeps stopping, exempt this app from battery optimization in the system settings.
- Leaving a spare phone plugged in 24/7? Check the battery from time to time — old lithium batteries can swell. Keep the phone somewhere ventilated, ideally with a battery charge limit if your device offers one.

## If something goes wrong

- **The node exits right away** — open **Node details → Diagnostics → Share log** and send the log with a GitHub Issue (bug report template). The log shows each start attempt and why it ended.
- **Peers stay at 0** — check the connection, then Stop → Start once.
- **Syncing stalls (your best block stops while the network moves on)** — the app detects this shape and restarts the node by itself (at most 3 times in 24 hours). If it keeps recurring, a stable Wi-Fi connection helps; then Stop → Start once
- **Your node does not appear in telemetry** — make sure the telemetry option is on and the node was restarted after turning it on. (Listing is verified working: the maintainer's own node appears in the official list.)
- **Storage questions** — the database is pruned to the latest 256 blocks by design. Uninstalling the app deletes the database and the node's identity key — and also the saved Inner Hash and your DePIN identity with its whole record. A reinstall starts a fresh sync with a new identity, so if the record should survive, move it to the new device first (see **Moving to a new device** above).

Questions, device reports, and ideas are welcome as GitHub Issues. Security issues follow [SECURITY.md](../SECURITY.md) instead of a public issue.
