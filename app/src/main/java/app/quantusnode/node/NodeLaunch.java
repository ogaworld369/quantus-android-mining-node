package app.quantusnode.node;

import java.util.ArrayList;
import java.util.List;

/**
 * The node's launch command without mining (v4.2.4): the fixed,
 * CLI-verified argument set {@link NodeService} starts the bundled
 * node with, kept free of Android so the suites check the real list.
 * The mining arguments, when a launch has them, are appended by the
 * service ({@link MiningLogic.LaunchPlan#nodeArgs}).
 */
public final class NodeLaunch {

    static final String LINKER64 = "/system/bin/linker64";

    /**
     * The node's log filter, set as RUST_LOG at every launch (v4.2.4,
     * audits V4-3 and V4-10; pinned whole by the vision suite).
     *
     * - "info" is the node's usual level.
     * - "sync=debug,sc_network_sync=debug" (internal.30): the sync
     *   fork's failure paths log at debug only, and the 2026-11-11
     *   stall left nothing to diagnose without them. A target above
     *   info is also what makes sc-tracing write its DETAILED line
     *   header (millisecond time, level, thread, target), and that
     *   header is what {@link NodeLogLines} anchors on: the mining
     *   statistics, the miner's start gate, the WARN/ERROR counts and
     *   the RPC endpoint and peer identity the service adopts. Plain
     *   "info" would switch the node to its short format and silently
     *   stop all of them.
     * - "quantus_node::prometheus=error": with --no-prometheus the
     *   node's QPoW monitoring task, which runs while mining, logs
     *   "QPoW Monitoring: Prometheus registry not found" as a WARN for
     *   every imported block — about 300 lines an hour that would
     *   push the history out of node.log and dominate the shared
     *   WARN count. The module's other warnings run only with a
     *   registry, so nothing else is lost.
     */
    public static final String NODE_LOG_FILTER =
            "info,sync=debug,sc_network_sync=debug,"
                    + "quantus_node::prometheus=error";

    private NodeLaunch() {
    }

    /**
     * @param binaryPath the node executable
     * @param viaLinker  start it through /system/bin/linker64
     * @param chain      the one chain this app runs
     * @param basePath   the node's base path (app-private)
     * @param launchName the name to pass as --name
     *                   ({@link NodeSettings#launchNodeName})
     * @param telemetry  the user's telemetry opt-in
     */
    public static List<String> nodeCommand(String binaryPath, boolean viaLinker,
                                    String chain, String basePath,
                                    String launchName, boolean telemetry) {
        List<String> cmd = new ArrayList<>();
        if (viaLinker) {
            cmd.add(LINKER64);
        }
        cmd.add(binaryPath);
        // Fixed argument set (verified against the node's CLI). Pruning is
        // always passed; no --rpc-external / --ws-external, ever, so RPC
        // stays bound to loopback. The name travels as a single argv
        // element (no shell involved), and the only other variable is
        // --no-telemetry: present unless the user opted into telemetry,
        // in which case the node reports to the chain spec's built-in
        // official endpoint — the app never sets a telemetry URL itself.
        cmd.add("--chain");         cmd.add(chain);
        cmd.add("--base-path");     cmd.add(basePath);
        cmd.add("--name");          cmd.add(launchName);
        cmd.add("--state-pruning"); cmd.add("256");
        cmd.add("--blocks-pruning"); cmd.add("256");
        // Phone-sized tuning (2026-11-03 code audit): the default trie
        // cache ceiling is 1 GiB, which the node itself warns it cannot
        // initialize on this 8 GB device — cap it at 128 MiB so the
        // cache actually works and memory pressure (LMK kills) drops.
        // Blocks-per-request goes to the responder's protocol maximum
        // (128, was 64), halving request round-trips while syncing.
        cmd.add("--trie-cache-size"); cmd.add("134217728");
        cmd.add("--max-blocks-per-request"); cmd.add("128");
        cmd.add("--rpc-port");      cmd.add("9944");
        cmd.add("--rpc-methods");   cmd.add("safe");
        // v4.2.4 (audit L-5): no mDNS. Upstream enables it on every
        // non-dev chain: a UDP 5353 socket on all interfaces that
        // announces the node's addresses and peer ID to the local
        // network, answers any query, and dials whatever addresses
        // the LAN announces back. A phone node finds its peers
        // through the bootnodes and the DHT; it needs none of that.
        cmd.add("--no-mdns");
        // v4.2.4 (audit I-8): no Prometheus endpoint. Upstream opens
        // one on 127.0.0.1:9615 by default; nothing in the app reads
        // it, and any app on the device could (its labels carry the
        // node name and roles).
        cmd.add("--no-prometheus");
        // v4.2.4 (audit I-11): offchain workers never run. With
        // --validator (mining on) upstream runs them on every block
        // with HTTP requests enabled; the runtime implements none
        // today, so this changes nothing the node does — except that
        // a mining start no longer depends on loading the root CAs
        // (sc-offchain loads them when the workers are enabled), and
        // a future runtime cannot start making requests from the
        // phone unnoticed. Covered by the on-device mining check
        // before the release is finalised.
        cmd.add("--offchain-worker"); cmd.add("never");
        if (!telemetry) {
            cmd.add("--no-telemetry");
        }
        return cmd;
    }
}
