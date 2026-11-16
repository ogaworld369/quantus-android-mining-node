package app.quantusnode.node;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Plain-JVM harness for the mining feature (v4.0.0), in the shape
 * of the core harness (core/tests): no JUnit, one check() per
 * pinned behaviour, a passed/failed total and a non-zero exit on
 * any failure. Everything exercised here is the real, Android-free
 * production code of the app module:
 *
 *  - MiningLogic: inner-hash validation against the official
 *    node's own rules, the fixed argument lists (node side and
 *    miner side), log redaction and the secret scrubber, the miner
 *    file checks, log-line classification for the bundled miner
 *    (v4.2.1) and node (v1.0.5), the restart policy, the state
 *    presentation and the share-text helpers;
 *  - BoundedLogFile: the size-capped miner log;
 *  - MinerSupervisor: the second child process, driven here with
 *    real child processes (shell scripts standing in for the miner
 *    binary), so spawn, drain, exit handling, restart limits and
 *    the bounded stop run exactly as they do on a device;
 *  - StorageLogic (v4.1.0): which paths the resync may delete and
 *    which it may never, the deletion itself on real directory
 *    trees, and the usage figures;
 *  - DailyTrackStore (v4.1.0): the daily record's line format,
 *    retention, atomic save and the sampler that feeds it;
 *  - StringsTable: every key in every language, placeholders equal
 *    to the Japanese source.
 *
 * Expected values are literals written from the specification and
 * from the upstream sources, never computed by the code under test.
 * Run with app/tests/run-mining-tests.sh.
 */
public final class MiningHarnessMain {
    private static int passed = 0;
    private static int failed = 0;
    private static int skipped = 0;

    /** The official node's own wormhole test vector
     *  (node/src/tests/data/quantus_key_test_data.rs, v1.0.5):
     *  public test data, not anyone's value. */
    static final String VALID =
            "0xe4be02a913727c01c1a155fd6e807b7c1a4a13abf37a352b7c9ed4412d127fc3";
    static final String VALID_CORE =
            "e4be02a913727c01c1a155fd6e807b7c1a4a13abf37a352b7c9ed4412d127fc3";
    static final String VALID_ADDRESS =
            "qzpWh4AEtsgCyEbv4WBgFWnB9bcdF2L2jVDuyjXP9mSTyBaeU";
    /** A second well-formed address: not the launched one. */
    static final String OTHER_ADDRESS =
            "qzmTAz3UUw1WGUuVh8nbFmPwcftomduwy6twq6NDR6y9qqtEs";
    /** A second well-formed value (first limb = p - 1). */
    static final String OTHER =
            "0x00000000ffffffff000000000000000000000000000000000000000000000000";
    /** Shaped like the node's generated token: 64 lowercase hex. */
    static final String TOKEN =
            "5f2c8a1e9b3d4c6f7a8091b2c3d4e5f60718293a4b5c6d7e8f9a0b1c2d3e4f50";

    public static void main(String[] args) throws Exception {
        testInnerHash();
        testRewardsTag();
        testWorkers();
        testNodeArgsAndLaunchPlan();
        testMinerCommand();
        testRedaction();
        testScrubber();
        testMinerFiles();
        testMinerLines();
        testNodeLines();
        testStats();
        testStatsAddress();
        testRestartPolicy();
        testThermalGovernor();
        testThermalApply();
        testPresentation();
        testPending();
        testShareHelpers();
        testBoundedLogFile();
        testDrainLines();
        testStrayScan();
        testSupervisor();
        testStorageRule();
        testStorageReset();
        testStorageUsage();
        testDailyRecords();
        testDailySampler();
        testDailySamplerClock();
        testStorageResetState();
        testInnerHashEntry();
        testShareAssembly();
        testStrings();
        testUiText();
        System.out.println("----");
        System.out.println("passed=" + passed + " failed=" + failed
                + " skipped=" + skipped);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------- inner hash ----------------

    private static void testInnerHashEntry() {
        check("entry: nothing typed yet",
                MiningLogic.innerHashEntryState("") == MiningLogic.EntryState.EMPTY
                        && MiningLogic.innerHashEntryState("   ")
                        == MiningLogic.EntryState.EMPTY
                        && MiningLogic.innerHashEntryState(null)
                        == MiningLogic.EntryState.EMPTY);
        check("entry: a value still being typed is not an error",
                MiningLogic.innerHashEntryState("0")
                        == MiningLogic.EntryState.INCOMPLETE
                        && MiningLogic.innerHashEntryState("0x")
                        == MiningLogic.EntryState.INCOMPLETE
                        && MiningLogic.innerHashEntryState("0xe4be")
                        == MiningLogic.EntryState.INCOMPLETE
                        && MiningLogic.innerHashEntryState(
                        VALID.substring(0, 65))
                        == MiningLogic.EntryState.INCOMPLETE
                        && MiningLogic.innerHashEntryState(
                        VALID_CORE.substring(0, 63))
                        == MiningLogic.EntryState.INCOMPLETE);
        check("entry: a complete valid value",
                MiningLogic.innerHashEntryState(VALID)
                        == MiningLogic.EntryState.OK
                        && MiningLogic.innerHashEntryState(" " + VALID_CORE + "\n")
                        == MiningLogic.EntryState.OK);
        check("entry: a character that cannot be part of the value is an"
                        + " error at once",
                MiningLogic.innerHashEntryState("0xe4bg")
                        == MiningLogic.EntryState.BAD_FORMAT
                        && MiningLogic.innerHashEntryState("e4 be")
                        == MiningLogic.EntryState.BAD_FORMAT
                        && MiningLogic.innerHashEntryState("x12")
                        == MiningLogic.EntryState.BAD_FORMAT);
        check("entry: too long is an error",
                MiningLogic.innerHashEntryState(VALID + "0")
                        == MiningLogic.EntryState.BAD_FORMAT);
        check("entry: 64 zeros are complete and refused",
                MiningLogic.innerHashEntryState("0x" + repeat("0", 64))
                        == MiningLogic.EntryState.BAD_FORMAT);
        check("entry: complete but not a value the node accepts",
                MiningLogic.innerHashEntryState("0x" + repeat("f", 64))
                        == MiningLogic.EntryState.NOT_CANONICAL);
        boolean agrees = true;
        for (String v : new String[] {"", "0x", VALID, VALID_CORE, OTHER,
                "0x" + repeat("f", 64), VALID + "0", "zz", "0x" + repeat("0", 64)}) {
            boolean ok = MiningLogic.checkInnerHash(v)
                    == MiningLogic.InnerHashStatus.OK;
            if (ok != (MiningLogic.innerHashEntryState(v)
                    == MiningLogic.EntryState.OK)) {
                agrees = false;
            }
        }
        check("entry: OK here means exactly OK for the stored check", agrees);
    }

    private static void testInnerHash() {
        check("inner hash: the official test vector is accepted",
                MiningLogic.checkInnerHash(VALID)
                        == MiningLogic.InnerHashStatus.OK);
        check("inner hash: an accepted value normalizes to itself",
                VALID.equals(MiningLogic.normalizeInnerHash(VALID)));
        check("inner hash: surrounding whitespace, 0X and upper case normalize",
                VALID.equals(MiningLogic.normalizeInnerHash(
                        "  0XE4BE02A913727C01C1A155FD6E807B7C"
                                + "1A4A13ABF37A352B7C9ED4412D127FC3\n")));
        check("inner hash: a bare 64-hex value gains the 0x the node requires",
                VALID.equals(MiningLogic.normalizeInnerHash(VALID_CORE)));
        check("inner hash: null is EMPTY",
                MiningLogic.checkInnerHash(null)
                        == MiningLogic.InnerHashStatus.EMPTY);
        check("inner hash: empty is EMPTY",
                MiningLogic.checkInnerHash("")
                        == MiningLogic.InnerHashStatus.EMPTY);
        check("inner hash: whitespace only is EMPTY",
                MiningLogic.checkInnerHash("  \n\t ")
                        == MiningLogic.InnerHashStatus.EMPTY);
        check("inner hash: a bare 0x is BAD_FORMAT",
                MiningLogic.checkInnerHash("0x")
                        == MiningLogic.InnerHashStatus.BAD_FORMAT);
        check("inner hash: 63 hex digits are BAD_FORMAT",
                MiningLogic.checkInnerHash("0x" + VALID_CORE.substring(1))
                        == MiningLogic.InnerHashStatus.BAD_FORMAT);
        check("inner hash: 65 hex digits are BAD_FORMAT",
                MiningLogic.checkInnerHash(VALID + "a")
                        == MiningLogic.InnerHashStatus.BAD_FORMAT);
        check("inner hash: a non-hex character is BAD_FORMAT",
                MiningLogic.checkInnerHash("0xg" + VALID_CORE.substring(1))
                        == MiningLogic.InnerHashStatus.BAD_FORMAT);
        check("inner hash: whitespace inside the value is BAD_FORMAT",
                MiningLogic.checkInnerHash("0x" + VALID_CORE.substring(0, 32)
                        + " " + VALID_CORE.substring(32))
                        == MiningLogic.InnerHashStatus.BAD_FORMAT);
        check("inner hash: the tool's whole output line is BAD_FORMAT",
                MiningLogic.checkInnerHash("Inner Hash: " + VALID)
                        == MiningLogic.InnerHashStatus.BAD_FORMAT);
        check("inner hash: an address is BAD_FORMAT",
                MiningLogic.checkInnerHash(VALID_ADDRESS)
                        == MiningLogic.InnerHashStatus.BAD_FORMAT);
        check("inner hash: a seed phrase is BAD_FORMAT",
                MiningLogic.checkInnerHash("orchard answer curve patient"
                        + " visual flower maze noise retreat penalty cage small")
                        == MiningLogic.InnerHashStatus.BAD_FORMAT);
        check("inner hash: the all-zero placeholder is BAD_FORMAT",
                MiningLogic.checkInnerHash("0x" + repeat("00", 32))
                        == MiningLogic.InnerHashStatus.BAD_FORMAT);
        // The node decodes the value as four little-endian u64
        // limbs, each of which must be below the Goldilocks
        // modulus p = 0xFFFFFFFF00000001 (qp-poseidon-core 3.1,
        // serialization::bytes_to_digest).
        check("inner hash: first limb equal to p is NOT_CANONICAL",
                MiningLogic.checkInnerHash("0x01000000ffffffff" + repeat("00", 24))
                        == MiningLogic.InnerHashStatus.NOT_CANONICAL);
        check("inner hash: first limb equal to p - 1 is accepted",
                MiningLogic.checkInnerHash(OTHER)
                        == MiningLogic.InnerHashStatus.OK);
        check("inner hash: limbs are little-endian (p read big-endian is fine)",
                MiningLogic.checkInnerHash("0xffffffff00000001" + repeat("00", 24))
                        == MiningLogic.InnerHashStatus.OK);
        check("inner hash: second limb equal to p is NOT_CANONICAL",
                MiningLogic.checkInnerHash("0x" + repeat("00", 8)
                        + "01000000ffffffff" + repeat("00", 16))
                        == MiningLogic.InnerHashStatus.NOT_CANONICAL);
        check("inner hash: third limb equal to p is NOT_CANONICAL",
                MiningLogic.checkInnerHash("0x" + repeat("00", 16)
                        + "01000000ffffffff" + repeat("00", 8))
                        == MiningLogic.InnerHashStatus.NOT_CANONICAL);
        check("inner hash: fourth limb of all ones is NOT_CANONICAL",
                MiningLogic.checkInnerHash("0x01" + repeat("00", 23)
                        + repeat("ff", 8))
                        == MiningLogic.InnerHashStatus.NOT_CANONICAL);
        check("inner hash: a rejected value has no normal form",
                MiningLogic.normalizeInnerHash("0x01000000ffffffff"
                        + repeat("00", 24)) == null
                        && MiningLogic.normalizeInnerHash("nope") == null
                        && MiningLogic.normalizeInnerHash(null) == null);
    }

    private static void testRewardsTag() {
        // Independent expectation: sha256sum of
        // "quantus-node-android/rewards-tag/v1:" + value, first 16 hex.
        check("rewards tag: matches the independently computed digest",
                "7190a2df8cecef3b".equals(MiningLogic.rewardsTag(VALID)));
        check("rewards tag: a different value gives a different tag",
                "eaa5f1bb79e4c145".equals(MiningLogic.rewardsTag(OTHER)));
        check("rewards tag: carries no part of the value itself",
                MiningLogic.rewardsTag(VALID) != null
                        && !VALID_CORE.contains(MiningLogic.rewardsTag(VALID)));
        check("rewards tag: nothing to tag gives null",
                MiningLogic.rewardsTag(null) == null);
    }

    // ---------------- CPU workers ----------------

    private static void testWorkers() {
        check("workers: the offered choices are 1, 2, 4 and auto",
                Arrays.equals(new int[] {1, 2, 4, 0},
                        MiningLogic.workerChoices()));
        // 2 since fix2: the owner's decision of 2026-11-15 ("leave the
        // phone headroom") replaced the specification's default of 4.
        // The choices, and what "auto" resolves to, did not change.
        check("workers: the default is 2",
                MiningLogic.WORKERS_DEFAULT == 2
                        && MiningLogic.normalizeWorkerSetting(2) == 2);
        check("workers: stored choices are kept",
                MiningLogic.normalizeWorkerSetting(1) == 1
                        && MiningLogic.normalizeWorkerSetting(4) == 4
                        && MiningLogic.normalizeWorkerSetting(0) == 0);
        check("workers: a value that is not a choice falls back to the default",
                MiningLogic.normalizeWorkerSetting(3) == 2
                        && MiningLogic.normalizeWorkerSetting(-1) == 2
                        && MiningLogic.normalizeWorkerSetting(8) == 2
                        && MiningLogic.normalizeWorkerSetting(100) == 2);
        check("workers: a fresh install runs 2 workers on a phone-sized CPU",
                MiningLogic.effectiveCpuWorkers(MiningLogic.WORKERS_DEFAULT, 8)
                        == 2
                        && MiningLogic.effectiveCpuWorkers(
                        MiningLogic.WORKERS_DEFAULT, 4) == 2);
        int[][] table = {
                // setting, cores, expected
                {4, 8, 4}, {2, 8, 2}, {1, 8, 1}, {0, 8, 4},
                {4, 4, 3}, {0, 4, 2}, {2, 4, 2},
                {4, 2, 1}, {2, 2, 1}, {0, 2, 1},
                {4, 1, 1}, {0, 1, 1},
                {0, 6, 3}, {4, 6, 4},
                {0, 16, 4}, {4, 16, 4},
                {4, 0, 1}, {0, -3, 1},
                {3, 8, 2},
        };
        for (int[] row : table) {
            check("workers: setting " + row[0] + " on " + row[1]
                            + " cores runs " + row[2],
                    MiningLogic.effectiveCpuWorkers(row[0], row[1]) == row[2]);
        }
    }

    // ---------------- node arguments ----------------

    private static void testNodeArgsAndLaunchPlan() throws IOException {
        List<String> want = Arrays.asList("--validator",
                "--miner-listen-port", "9833",
                "--rewards-inner-hash", VALID);
        check("node args: the three opt-in arguments, in the fixed order",
                want.equals(MiningLogic.nodeMiningArgs(VALID)));

        MiningLogic.LaunchPlan off =
                MiningLogic.planLaunch(false, VALID, true, true);
        check("launch plan: mining off adds nothing to the node launch",
                off != null && !off.active && off.nodeArgs.isEmpty()
                        && off.skip == MiningLogic.SkipReason.NONE
                        && off.innerHash == null && off.rewardsTag == null);
        MiningLogic.LaunchPlan offGarbage =
                MiningLogic.planLaunch(false, "garbage", false, false);
        check("launch plan: mining off ignores every other input",
                offGarbage != null && !offGarbage.active
                        && offGarbage.nodeArgs.isEmpty()
                        && offGarbage.skip == MiningLogic.SkipReason.NONE);

        MiningLogic.LaunchPlan on = MiningLogic.planLaunch(true,
                " " + VALID_CORE.toUpperCase(java.util.Locale.ROOT) + " ",
                true, true);
        check("launch plan: mining on appends exactly the opt-in arguments",
                on != null && on.active && want.equals(on.nodeArgs)
                        && on.skip == MiningLogic.SkipReason.NONE);
        check("launch plan: the plan carries the normalized value and its tag",
                on != null && VALID.equals(on.innerHash)
                        && "7190a2df8cecef3b".equals(on.rewardsTag));

        MiningLogic.LaunchPlan noHash =
                MiningLogic.planLaunch(true, "", true, true);
        check("launch plan: no saved inner hash -> plain launch, reason kept",
                noHash != null && !noHash.active && noHash.nodeArgs.isEmpty()
                        && noHash.skip == MiningLogic.SkipReason.REWARDS_INVALID);
        MiningLogic.LaunchPlan badHash = MiningLogic.planLaunch(true,
                "0x01000000ffffffff" + repeat("00", 24), true, true);
        check("launch plan: a value the node would refuse -> plain launch",
                badHash != null && !badHash.active && badHash.nodeArgs.isEmpty()
                        && badHash.skip == MiningLogic.SkipReason.REWARDS_INVALID);
        MiningLogic.LaunchPlan noBin =
                MiningLogic.planLaunch(true, VALID, false, true);
        check("launch plan: no miner binary -> plain launch, no open endpoint",
                noBin != null && !noBin.active && noBin.nodeArgs.isEmpty()
                        && noBin.skip
                        == MiningLogic.SkipReason.MINER_BINARY_MISSING);

        // The node's network key. Started with --validator, the node
        // (v1.0.5, client/cli/src/params/node_key_params.rs) refuses
        // to run when <base-path>/chains/<chain>/network/
        // secret_dilithium does not exist; started without, it
        // creates the file. A launch that finds no key therefore
        // runs without mining — the case of a fresh install on
        // which mining was switched on before the node ever ran.
        MiningLogic.LaunchPlan noKey =
                MiningLogic.planLaunch(true, VALID, true, false);
        check("launch plan: no network key yet -> plain launch, reason kept",
                noKey != null && !noKey.active && noKey.nodeArgs.isEmpty()
                        && noKey.skip
                        == MiningLogic.SkipReason.NETWORK_KEY_MISSING
                        && noKey.innerHash == null && noKey.rewardsTag == null);
        check("launch plan: what a restart will not cure outranks the"
                        + " missing key",
                MiningLogic.planLaunch(true, "", true, false).skip
                        == MiningLogic.SkipReason.REWARDS_INVALID
                        && MiningLogic.planLaunch(true, VALID, false, false).skip
                        == MiningLogic.SkipReason.MINER_BINARY_MISSING);

        File base = tempDir("first-run");
        check("network key: a base path the node never ran in has none",
                !MiningLogic.networkKeyExists(base, "mainnet"));
        MiningLogic.LaunchPlan firstStart = MiningLogic.planLaunch(true, VALID,
                true, MiningLogic.networkKeyExists(base, "mainnet"));
        check("launch plan: a fresh install's first start runs without mining",
                firstStart != null && !firstStart.active
                        && firstStart.nodeArgs.isEmpty());
        File network = new File(base, "chains/mainnet/network");
        if (!network.mkdirs()) {
            throw new IOException("cannot create " + network);
        }
        check("network key: the network directory alone is not the key",
                !MiningLogic.networkKeyExists(base, "mainnet"));
        write(new File(network, "secret_dilithium"), "written by the node");
        check("network key: the file the node's first start leaves is found,"
                        + " under its own chain only",
                MiningLogic.networkKeyExists(base, "mainnet")
                        && !MiningLogic.networkKeyExists(base, "dev"));
        MiningLogic.LaunchPlan nextStart = MiningLogic.planLaunch(true, VALID,
                true, MiningLogic.networkKeyExists(base, "mainnet"));
        check("launch plan: with the key in place the next start mines as"
                        + " before",
                nextStart != null && nextStart.active
                        && want.equals(nextStart.nodeArgs)
                        && nextStart.skip == MiningLogic.SkipReason.NONE);
    }

    // ---------------- miner command ----------------

    private static void testMinerCommand() throws IOException {
        List<String> direct = MiningLogic.minerCommand(
                "/n/libquantus_miner.so", false,
                "/b/chains/mainnet/miner-auth-token",
                "/b/chains/mainnet/miner-tls-cert-sha256", 4);
        check("miner command: the fixed serve form",
                Arrays.asList("/n/libquantus_miner.so", "serve",
                        "--node-addr", "127.0.0.1:9833",
                        "--auth-token-file",
                        "/b/chains/mainnet/miner-auth-token",
                        "--tls-cert-sha256-file",
                        "/b/chains/mainnet/miner-tls-cert-sha256",
                        "--cpu-workers", "4",
                        "--gpu-devices", "0").equals(direct));
        List<String> linker = MiningLogic.minerCommand(
                "/n/libquantus_miner.so", true, "/t", "/f", 2);
        check("miner command: the linker64 form only prepends the linker",
                Arrays.asList("/system/bin/linker64",
                        "/n/libquantus_miner.so", "serve",
                        "--node-addr", "127.0.0.1:9833",
                        "--auth-token-file", "/t",
                        "--tls-cert-sha256-file", "/f",
                        "--cpu-workers", "2",
                        "--gpu-devices", "0").equals(linker));
        check("miner command: the token travels as a file, never as a value",
                direct != null && !direct.contains("--auth-token")
                        && !direct.contains("--tls-cert-sha256")
                        && direct.indexOf("--auth-token-file") >= 0);
        check("miner command: GPU is always switched off explicitly",
                direct != null
                        && direct.indexOf("--gpu-devices") == direct.size() - 2
                        && "0".equals(direct.get(direct.size() - 1)));
        check("miner command: nothing about the metrics port is passed",
                direct != null && !direct.contains("--metrics-port"));

        // With a real token on disk: the command built from the
        // paths cannot carry the token's value.
        File dir = tempDir("cmd");
        File tokenFile = new File(dir, MiningLogic.TOKEN_FILE_NAME);
        write(tokenFile, TOKEN + "\n");
        File fpFile = new File(dir, MiningLogic.FINGERPRINT_FILE_NAME);
        write(fpFile, repeat("ab", 32) + "\n");
        List<String> real = MiningLogic.minerCommand("/n/libquantus_miner.so",
                false, tokenFile.getPath(), fpFile.getPath(), 4);
        boolean tokenInArgs = false;
        if (real != null) {
            for (String a : real) {
                if (a.contains(TOKEN)) {
                    tokenInArgs = true;
                }
            }
        }
        check("miner command: the token's value appears in no argument",
                real != null && !tokenInArgs);
    }

    // ---------------- redaction and scrubbing ----------------

    private static void testRedaction() {
        List<String> nodeCmd = Arrays.asList("/n/libquantus_node.so",
                "--chain", "mainnet", "--no-telemetry", "--validator",
                "--miner-listen-port", "9833",
                "--rewards-inner-hash", VALID);
        check("redaction: the inner hash value is replaced in the launch line",
                ("/n/libquantus_node.so --chain mainnet --no-telemetry"
                        + " --validator --miner-listen-port 9833"
                        + " --rewards-inner-hash <redacted>")
                        .equals(MiningLogic.redactCommandForLog(nodeCmd)));
        check("redaction: the --flag=value spelling is covered",
                "x --rewards-inner-hash=<redacted>".equals(
                        MiningLogic.redactCommandForLog(Arrays.asList(
                                "x", "--rewards-inner-hash=" + VALID))));
        check("redaction: a stray element that is an inner hash is covered",
                "x <redacted> y".equals(MiningLogic.redactCommandForLog(
                        Arrays.asList("x", VALID, "y"))));
        check("redaction: an inline token value is covered",
                "m serve --auth-token <redacted> --cpu-workers 4".equals(
                        MiningLogic.redactCommandForLog(Arrays.asList(
                                "m", "serve", "--auth-token", TOKEN,
                                "--cpu-workers", "4"))));
        List<String> plain = Arrays.asList("/n/libquantus_node.so",
                "--chain", "mainnet", "--name", "my node", "--no-telemetry");
        check("redaction: a command without secrets is the plain join",
                "/n/libquantus_node.so --chain mainnet --name my node --no-telemetry"
                        .equals(MiningLogic.redactCommandForLog(plain)));
        String out = MiningLogic.redactCommandForLog(nodeCmd);
        check("redaction: the value is gone from the result",
                out != null && !out.contains(VALID_CORE));
    }

    private static void testScrubber() {
        MiningLogic.SecretScrubber s =
                MiningLogic.SecretScrubber.of(VALID, TOKEN);
        check("scrubber: holding secrets it is not empty", !s.isEmpty());
        check("scrubber: the node's own 'Provided:' echo is scrubbed",
                "  Provided: <redacted>".equals(
                        s.apply("  Provided: " + VALID)));
        check("scrubber: the value without 0x is scrubbed",
                "x <redacted> y".equals(s.apply("x " + VALID_CORE + " y")));
        check("scrubber: upper case and 0X are scrubbed",
                "v=<redacted>;".equals(s.apply("v=0X"
                        + VALID_CORE.toUpperCase(java.util.Locale.ROOT) + ";")));
        check("scrubber: the token is scrubbed",
                "a<redacted>b".equals(s.apply("a" + TOKEN + "b")));
        check("scrubber: the token in upper case is scrubbed",
                "t <redacted>".equals(s.apply("t "
                        + TOKEN.toUpperCase(java.util.Locale.ROOT))));
        check("scrubber: every occurrence in a line is scrubbed",
                "<redacted> and <redacted> and <redacted>".equals(
                        s.apply(VALID + " and " + TOKEN + " and " + VALID_CORE)));
        String clean = "2026-11-14 18:00:00 Imported #123 (0xabcd…ef01)";
        check("scrubber: a line without secrets comes back untouched",
                s.apply(clean) == clean);
        check("scrubber: null stays null", s.apply(null) == null);

        MiningLogic.SecretScrubber none = MiningLogic.SecretScrubber.of(null, null);
        check("scrubber: without secrets it is empty and changes nothing",
                none.isEmpty() && none.apply(VALID) == VALID);
        MiningLogic.SecretScrubber hashOnly =
                MiningLogic.SecretScrubber.of(VALID, null);
        check("scrubber: only the secrets it was given are scrubbed",
                ("<redacted> " + TOKEN).equals(
                        hashOnly.apply(VALID + " " + TOKEN)));
        MiningLogic.SecretScrubber shortToken =
                MiningLogic.SecretScrubber.of(null, "abc");
        check("scrubber: a token too short to be a secret is not used",
                shortToken.isEmpty()
                        && "abc abc".equals(shortToken.apply("abc abc")));
        MiningLogic.SecretScrubber grown = hashOnly.withToken(TOKEN);
        check("scrubber: a token learned later joins the same scrubber",
                "<redacted> <redacted>".equals(grown.apply(VALID + " " + TOKEN))
                        && ("<redacted> " + TOKEN).equals(
                        hashOnly.apply(VALID + " " + TOKEN)));
        check("scrubber: learning no token leaves the scrubber as it was",
                hashOnly.withToken(null) == hashOnly
                        && hashOnly.withToken("abc") == hashOnly);
        check("scrubber: a token cut off by the line cap is removed as far"
                        + " as it shows",
                "abc <redacted>".equals(
                        grown.apply("abc " + TOKEN.substring(0, 20), true)));
        check("scrubber: the same text in a complete line is left alone",
                ("abc " + TOKEN.substring(0, 20)).equals(
                        grown.apply("abc " + TOKEN.substring(0, 20), false)));
        check("scrubber: an inner hash cut off by the line cap goes with its 0x",
                "x <redacted>".equals(grown.apply("x 0X"
                        + VALID_CORE.substring(0, 30).toUpperCase(
                        java.util.Locale.ROOT), true)));
        check("scrubber: fewer than 8 leading characters at the cut are not"
                        + " taken for a secret",
                ("abc " + TOKEN.substring(0, 7)).equals(
                        grown.apply("abc " + TOKEN.substring(0, 7), true)));
        check("scrubber: a cut line holding a whole secret is scrubbed as usual",
                "t=<redacted> tail".equals(
                        grown.apply("t=" + TOKEN + " tail", true)));
        check("scrubber: a cut line with nothing of a secret comes back as is",
                grown.apply("plain text", true).equals("plain text"));
        MiningLogic.SecretScrubber badHash =
                MiningLogic.SecretScrubber.of("not-a-hash", TOKEN);
        check("scrubber: an invalid inner hash is not used, the token still is",
                "not-a-hash <redacted>".equals(
                        badHash.apply("not-a-hash " + TOKEN)));
    }

    // ---------------- miner files ----------------

    private static void testMinerFiles() throws IOException {
        check("files: token path under the node's chain directory",
                "/data/x/files/node/chains/mainnet/miner-auth-token".equals(
                        path(MiningLogic.minerTokenFile(
                                new File("/data/x/files/node"), "mainnet"))));
        check("files: fingerprint path under the node's chain directory",
                "/data/x/files/node/chains/mainnet/miner-tls-cert-sha256".equals(
                        path(MiningLogic.minerFingerprintFile(
                                new File("/data/x/files/node"), "mainnet"))));

        File dir = tempDir("files");
        File token = new File(dir, "miner-auth-token");
        File fp = new File(dir, "miner-tls-cert-sha256");
        check("files: nothing there yet -> token missing",
                MiningLogic.checkMinerFiles(token, fp)
                        == MiningLogic.FilesStatus.TOKEN_MISSING);
        write(token, "");
        check("files: an empty token file is not ready",
                MiningLogic.checkMinerFiles(token, fp)
                        == MiningLogic.FilesStatus.TOKEN_EMPTY);
        write(token, TOKEN + "\n");
        check("files: token ready, fingerprint missing",
                MiningLogic.checkMinerFiles(token, fp)
                        == MiningLogic.FilesStatus.FINGERPRINT_MISSING);
        write(fp, "");
        check("files: an empty fingerprint file is not ready",
                MiningLogic.checkMinerFiles(token, fp)
                        == MiningLogic.FilesStatus.FINGERPRINT_EMPTY);
        write(fp, repeat("ab", 32) + "\n");
        check("files: both present and non-empty -> ready",
                MiningLogic.checkMinerFiles(token, fp)
                        == MiningLogic.FilesStatus.READY);
        File asDir = new File(dir, "token-as-dir");
        asDir.mkdirs();
        check("files: a directory in the token's place is not a token",
                MiningLogic.checkMinerFiles(asDir, fp)
                        == MiningLogic.FilesStatus.TOKEN_MISSING);
        if (token.setReadable(false, false) && !token.canRead()) {
            check("files: an unreadable token file is reported as such",
                    MiningLogic.checkMinerFiles(token, fp)
                            == MiningLogic.FilesStatus.TOKEN_UNREADABLE);
            token.setReadable(true, true);
        } else {
            skip("files: unreadable token (this user can read any file)");
            token.setReadable(true, true);
        }
        if (fp.setReadable(false, false) && !fp.canRead()) {
            check("files: an unreadable fingerprint file is reported as such",
                    MiningLogic.checkMinerFiles(token, fp)
                            == MiningLogic.FilesStatus.FINGERPRINT_UNREADABLE);
            fp.setReadable(true, true);
        } else {
            skip("files: unreadable fingerprint (this user can read any file)");
            fp.setReadable(true, true);
        }

        check("files: the blocking file is named for the status line",
                "miner-auth-token".equals(MiningLogic.blockingFileName(
                        MiningLogic.FilesStatus.TOKEN_MISSING))
                        && "miner-auth-token".equals(MiningLogic.blockingFileName(
                        MiningLogic.FilesStatus.TOKEN_EMPTY))
                        && "miner-auth-token".equals(MiningLogic.blockingFileName(
                        MiningLogic.FilesStatus.TOKEN_UNREADABLE))
                        && "miner-tls-cert-sha256".equals(
                        MiningLogic.blockingFileName(
                                MiningLogic.FilesStatus.FINGERPRINT_MISSING))
                        && "miner-tls-cert-sha256".equals(
                        MiningLogic.blockingFileName(
                                MiningLogic.FilesStatus.FINGERPRINT_EMPTY))
                        && "miner-tls-cert-sha256".equals(
                        MiningLogic.blockingFileName(
                                MiningLogic.FilesStatus.FINGERPRINT_UNREADABLE))
                        && MiningLogic.blockingFileName(
                        MiningLogic.FilesStatus.READY) == null);

        check("files: a small text file is read trimmed",
                TOKEN.equals(MiningLogic.readSmallTextFile(token, 4096)));
        check("files: a missing file reads as null",
                MiningLogic.readSmallTextFile(new File(dir, "absent"), 4096)
                        == null);
        File big = new File(dir, "big");
        write(big, repeat("x", 5000));
        check("files: a file over the bound reads as null, not as a prefix",
                MiningLogic.readSmallTextFile(big, 4096) == null);
    }

    // ---------------- log lines ----------------

    // Lines as the bundled miner (v4.2.1, env_logger default
    // format) prints them; the message texts are the format
    // strings of miner-service/src/{lib,quic}.rs and miner-cli.
    static final String M_JOB = "[2026-11-14T09:00:12Z INFO  miner_service::quic]"
            + " 🧱 New block to mine: job 27 (header 0x1f2e3d4c5b6a7988"
            + "1f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c5b6a7988)";
    static final String M_SOLVED = "[2026-11-14T09:00:24Z INFO  miner_service::quic]"
            + " ✅ Job 27 solved: 1234567 hashes in 11.52s (107.17 KH/s)"
            + " - submitting solution to node";
    static final String M_WORKER = "[2026-11-14T09:00:24Z INFO  miner_service]"
            + " CPU worker 2 stopped (new block received):"
            + " 301245 hashes in 12.01s (25.08 KH/s)";
    static final String M_METRICS = "[2026-11-14T09:00:00Z INFO  quantus_miner]"
            + " Metrics available at http://0.0.0.0:9900/metrics";
    static final String M_METRICS_OFF = "[2026-11-14T09:00:00Z WARN  metrics]"
            + " metrics::start_http_exporter called but 'http-exporter'"
            + " feature is disabled; ignoring";
    // Lines as the bundled node (v1.0.5) prints them.
    static final String N_ACCEPTED = "2026-11-14 18:00:07 🥇 Successfully mined"
            + " and submitted a new block via external miner 1"
            + " (mining time: 43s)";
    static final String N_REJECTED = "2026-11-14 18:00:07 ⛏️ Failed to submit"
            + " seal from miner 1, continuing to wait (job 27)";
    static final String N_LISTEN =
            "2026-11-14 18:00:01 ⛏️ Miner server listening on port 9833";
    static final String N_ADDRESS = "2026-11-14 18:00:00 ⛏️ Rewards wormhole"
            + " address: " + VALID_ADDRESS;

    private static void testMinerLines() {
        check("miner line: a new job",
                MiningLogic.classifyMinerLine(M_JOB)
                        == MiningLogic.MinerEvent.JOB_RECEIVED);
        check("miner line: a submitted solution",
                MiningLogic.classifyMinerLine(M_SOLVED)
                        == MiningLogic.MinerEvent.SOLUTION_SUBMITTED);
        check("miner line: a worker's hash report",
                MiningLogic.classifyMinerLine(M_WORKER)
                        == MiningLogic.MinerEvent.WORKER_REPORT);
        MiningLogic.WorkerReport r = MiningLogic.parseWorkerReport(M_WORKER);
        check("miner line: the worker report's numbers",
                r != null && "CPU 2".equals(r.worker) && r.hashes == 301245L
                        && r.seconds == 12.01);
        MiningLogic.WorkerReport r0 = MiningLogic.parseWorkerReport(
                "[x INFO  miner_service] CPU worker 0 finished (nonce range"
                        + " exhausted): 10 hashes in 0.00s (0.00 H/s)");
        check("miner line: a zero-second report still parses",
                r0 != null && "CPU 0".equals(r0.worker) && r0.hashes == 10L
                        && r0.seconds == 0.0);
        check("miner line: 'worker started' is not a report",
                MiningLogic.classifyMinerLine("[x INFO  miner_service]"
                        + " CPU worker 1 started (persistent)")
                        == MiningLogic.MinerEvent.NONE
                        && MiningLogic.parseWorkerReport("[x INFO  miner_service]"
                        + " CPU worker 1 started (persistent)") == null);
        check("miner line: 'found solution' alone is not a submission",
                MiningLogic.classifyMinerLine("[x INFO  miner_service]"
                        + " 🎯 CPU worker 1 found solution! Nonce: 12ab,"
                        + " Hash: 00ff (job 5)")
                        == MiningLogic.MinerEvent.NONE);
        check("miner line: the metrics announcement",
                MiningLogic.classifyMinerLine(M_METRICS)
                        == MiningLogic.MinerEvent.METRICS_ANNOUNCED);
        check("miner line: the exporter-disabled note",
                MiningLogic.classifyMinerLine(M_METRICS_OFF)
                        == MiningLogic.MinerEvent.METRICS_DISABLED_NOTE);
        check("miner line: anything else, empty and null are NONE",
                MiningLogic.classifyMinerLine("[x INFO  miner_service::quic]"
                        + " ⛏️ Waiting for mining jobs from node...")
                        == MiningLogic.MinerEvent.NONE
                        && MiningLogic.classifyMinerLine("")
                        == MiningLogic.MinerEvent.NONE
                        && MiningLogic.classifyMinerLine(null)
                        == MiningLogic.MinerEvent.NONE);
    }

    private static void testNodeLines() {
        check("node line: a block accepted from the external miner",
                MiningLogic.classifyNodeLine(N_ACCEPTED)
                        == MiningLogic.NodeEvent.BLOCK_ACCEPTED);
        check("node line: a rejected seal",
                MiningLogic.classifyNodeLine(N_REJECTED)
                        == MiningLogic.NodeEvent.SEAL_REJECTED);
        check("node line: the miner endpoint is listening",
                MiningLogic.classifyNodeLine(N_LISTEN)
                        == MiningLogic.NodeEvent.ENDPOINT_LISTENING);
        check("node line: the rewards address report",
                MiningLogic.classifyNodeLine(N_ADDRESS)
                        == MiningLogic.NodeEvent.REWARDS_ADDRESS);
        check("node line: the reported address is extracted",
                VALID_ADDRESS.equals(MiningLogic.parseRewardsAddress(N_ADDRESS)));
        check("node line: text after the address is not part of it",
                VALID_ADDRESS.equals(MiningLogic.parseRewardsAddress(
                        N_ADDRESS + " (extra)")));
        check("node line: an empty report yields no address",
                MiningLogic.parseRewardsAddress("2026-11-14 18:00:00 ⛏️"
                        + " Rewards wormhole address: ") == null);
        check("node line: something that is not an address is not adopted",
                MiningLogic.parseRewardsAddress("2026-11-14 18:00:00 ⛏️"
                        + " Rewards wormhole address: <b>not-an-address</b>")
                        == null
                        && MiningLogic.parseRewardsAddress("2026-11-14 18:00:00"
                        + " ⛏️ Rewards wormhole address: short") == null);
        check("node line: ordinary lines, empty and null are NONE",
                MiningLogic.classifyNodeLine("2026-11-14 18:00:09 ✨ Imported"
                        + " #123 (0xabcd…ef01)") == MiningLogic.NodeEvent.NONE
                        && MiningLogic.classifyNodeLine("")
                        == MiningLogic.NodeEvent.NONE
                        && MiningLogic.classifyNodeLine(null)
                        == MiningLogic.NodeEvent.NONE
                        && MiningLogic.parseRewardsAddress(null) == null);
    }

    private static void testStats() {
        MiningLogic.MinerStats st = new MiningLogic.MinerStats();
        st.onMinerProcessStarted(0);
        MiningLogic.StatsSnapshot s0 = st.snapshot(0);
        check("stats: a fresh session knows nothing",
                s0.jobsReceived == 0 && s0.solutionsSubmitted == 0
                        && s0.blocksAccepted == 0 && s0.sealsRejected == 0
                        && s0.lastJobElapsedMs == -1 && s0.hashrateHps < 0
                        && s0.rewardsAddress == null && !s0.endpointListening
                        && !s0.metricsExporterActive);
        st.onMinerLine(M_JOB, 1000);
        st.onMinerLine(M_SOLVED, 2000);
        st.onNodeLine(N_ACCEPTED, 2500);
        st.onNodeLine(N_REJECTED, 2600);
        st.onNodeLine(N_ADDRESS, 2700);
        st.onNodeLine(N_LISTEN, 2800);
        MiningLogic.StatsSnapshot s1 = st.snapshot(3000);
        check("stats: job, submission, acceptance and rejection are counted",
                s1.jobsReceived == 1 && s1.solutionsSubmitted == 1
                        && s1.blocksAccepted == 1 && s1.sealsRejected == 1);
        check("stats: the last job's time is kept", s1.lastJobElapsedMs == 1000);
        check("stats: the node's reported address is kept",
                VALID_ADDRESS.equals(s1.rewardsAddress));
        check("stats: the endpoint is known to listen", s1.endpointListening);

        st.onMinerLine("[x INFO  miner_service] CPU worker 0 stopped (new block"
                + " received): 120000 hashes in 12.00s (10.00 KH/s)", 13000);
        st.onMinerLine("[x INFO  miner_service] CPU worker 1 stopped (new block"
                + " received): 60000 hashes in 12.00s (5.00 KH/s)", 13000);
        check("stats: the hashrate is the sum of the workers' latest rates",
                st.snapshot(13000).hashrateHps == 15000.0);
        st.onMinerLine("[x INFO  miner_service] CPU worker 0 stopped (new block"
                + " received): 240000 hashes in 12.00s (20.00 KH/s)", 25000);
        check("stats: a worker's newer report replaces its older one",
                st.snapshot(25000).hashrateHps == 25000.0);
        check("stats: a worker silent for over two minutes stops counting",
                st.snapshot(13000 + 120_001).hashrateHps == 20000.0);
        check("stats: with every report stale the hashrate is unknown again",
                st.snapshot(25000 + 120_001).hashrateHps < 0);
        st.onMinerLine("[x INFO  miner_service] CPU worker 3 finished (nonce"
                + " range exhausted): 10 hashes in 0.00s (0.00 H/s)", 26000);
        check("stats: a zero-second report contributes nothing",
                st.snapshot(26000).hashrateHps == 25000.0);

        st.onMinerProcessStarted(30000);
        MiningLogic.StatsSnapshot s2 = st.snapshot(30000);
        check("stats: a new miner process starts with no hashrate",
                s2.hashrateHps < 0);
        check("stats: the session's counters survive a miner restart",
                s2.jobsReceived == 1 && s2.solutionsSubmitted == 1
                        && s2.blocksAccepted == 1
                        && VALID_ADDRESS.equals(s2.rewardsAddress));

        MiningLogic.MinerStats a = new MiningLogic.MinerStats();
        a.onMinerProcessStarted(0);
        a.onMinerLine(M_METRICS, 10);
        check("stats: metrics announced with no disabled note -> exporter live",
                a.snapshot(10).metricsExporterActive);
        MiningLogic.MinerStats b = new MiningLogic.MinerStats();
        b.onMinerProcessStarted(0);
        b.onMinerLine(M_METRICS_OFF, 9);
        b.onMinerLine(M_METRICS, 10);
        check("stats: the disabled note first -> exporter not live",
                !b.snapshot(10).metricsExporterActive);
        a.onMinerProcessStarted(20);
        check("stats: the exporter flag belongs to one miner process",
                !a.snapshot(20).metricsExporterActive);
    }

    // Not every line of the node's output is the node's own
    // statement. Until a peer on the miner port has authenticated,
    // the node (v1.0.5, node/src/miner_server.rs) logs what that
    // peer sent: a wrong first message as its Debug text, an
    // unknown one inside serde's error text — line breaks and all.
    // These are lines such a peer can make the node print.
    static final String N_FORGED_QUOTED = "2026-11-14 18:00:20 ⛏️ Expected"
            + " Ready from miner 192.168.1.23:50000, got NewJob(MiningRequest {"
            + " job_id: \"Rewards wormhole address: " + OTHER_ADDRESS + "\","
            + " mining_hash: \"\", difficulty: \"\" })";
    static final String N_FORGED_LINE = "2026-11-14 18:00:21 ⛏️ Rewards"
            + " wormhole address: " + OTHER_ADDRESS;

    private static void testStatsAddress() {
        check("stats: (premise) a peer's text reads like the node's report",
                OTHER_ADDRESS.equals(
                        MiningLogic.parseRewardsAddress(N_FORGED_QUOTED))
                        && OTHER_ADDRESS.equals(
                        MiningLogic.parseRewardsAddress(N_FORGED_LINE)));

        MiningLogic.MinerStats first = new MiningLogic.MinerStats();
        first.onNodeLine(N_ADDRESS, 100);
        first.onNodeLine(N_FORGED_QUOTED, 200);
        check("stats: a second report, inside a peer's message, does not"
                        + " replace the node's address",
                VALID_ADDRESS.equals(first.snapshot(200).rewardsAddress));
        first.onNodeLine(N_FORGED_LINE, 300);
        check("stats: a second report on a line of its own does not either",
                VALID_ADDRESS.equals(first.snapshot(300).rewardsAddress));
        first.onMinerProcessStarted(400);
        first.onNodeLine(N_FORGED_LINE, 500);
        check("stats: a miner restart does not reopen the address",
                VALID_ADDRESS.equals(first.snapshot(500).rewardsAddress));

        MiningLogic.MinerStats late = new MiningLogic.MinerStats();
        late.onNodeLine(N_LISTEN, 100);
        late.onNodeLine(N_FORGED_LINE, 200);
        check("stats: no address is taken once the miner endpoint listens",
                late.snapshot(200).rewardsAddress == null
                        && late.snapshot(200).endpointListening);

        MiningLogic.MinerStats blank = new MiningLogic.MinerStats();
        blank.onNodeLine("2026-11-14 18:00:00 ⛏️ Rewards wormhole address: ",
                100);
        blank.onNodeLine(N_ADDRESS, 200);
        check("stats: the first address counts, not the first marker - a"
                        + " report without one does not use up the place",
                VALID_ADDRESS.equals(blank.snapshot(200).rewardsAddress));
    }

    // ---------------- restart policy ----------------

    private static void testRestartPolicy() {
        MiningLogic.RestartPolicy p = new MiningLogic.RestartPolicy();
        check("restart: first failure restarts after 10 s",
                p.onAbnormalExit(100) == 10_000L && p.consecutiveFailures() == 1);
        check("restart: second failure restarts after 30 s",
                p.onAbnormalExit(100) == 30_000L);
        check("restart: third failure restarts after 60 s",
                p.onAbnormalExit(100) == 60_000L && p.restartsTotal() == 3);
        check("restart: the fourth failure in a row gives up",
                p.onAbnormalExit(100) == MiningLogic.GIVE_UP);
        check("restart: giving up schedules no further restart",
                p.onAbnormalExit(100) == MiningLogic.GIVE_UP
                        && p.restartsTotal() == 3);
        p.reset();
        check("restart: a manual restart clears the streak",
                p.onAbnormalExit(100) == 10_000L && p.consecutiveFailures() == 1);

        MiningLogic.RestartPolicy q = new MiningLogic.RestartPolicy();
        q.onAbnormalExit(100);
        q.onAbnormalExit(100);
        check("restart: a run just under ten minutes continues the streak",
                q.onAbnormalExit(599_999L) == 60_000L);
        MiningLogic.RestartPolicy r = new MiningLogic.RestartPolicy();
        r.onAbnormalExit(100);
        r.onAbnormalExit(100);
        check("restart: a ten-minute run counts as healthy and resets it",
                r.onAbnormalExit(600_000L) == 10_000L
                        && r.consecutiveFailures() == 1
                        && r.restartsTotal() == 3);
    }

    // ---------------- thermal protection ----------------

    private static boolean decided(MiningLogic.ThermalDecision d,
                                   MiningLogic.ThermalAction action, int cap) {
        return d != null && d.action == action && d.cap == cap;
    }

    // The rules as the fix2 request states them (statuses are
    // PowerManager's: 0 none, 1 light, 2 moderate, 3 severe,
    // 4 critical, 5 emergency, 6 shutdown):
    //   <= 1  nothing, and a cap once lowered is never raised;
    //   == 2  cap = max(1, floor(resolved workers / 2)), never above
    //         the cap already held; a miner running above the new cap
    //         is restarted with fewer workers (REDUCE);
    //   >= 3  STOP, latched: no status brings the miner back in the
    //         same session.
    private static void testThermalGovernor() {
        final MiningLogic.ThermalAction none = MiningLogic.ThermalAction.NONE;
        final MiningLogic.ThermalAction reduce =
                MiningLogic.ThermalAction.REDUCE;
        final MiningLogic.ThermalAction stop = MiningLogic.ThermalAction.STOP;
        final int noCap = Integer.MAX_VALUE;

        check("thermal: the status numbers are the platform's",
                MiningLogic.THERMAL_NONE == 0 && MiningLogic.THERMAL_LIGHT == 1
                        && MiningLogic.THERMAL_MODERATE == 2
                        && MiningLogic.THERMAL_SEVERE == 3
                        && MiningLogic.THERMAL_CRITICAL == 4
                        && MiningLogic.THERMAL_EMERGENCY == 5
                        && MiningLogic.THERMAL_SHUTDOWN == 6);
        MiningLogic.ThermalGovernor fresh = new MiningLogic.ThermalGovernor();
        check("thermal: a new session has no cap and is not stopped",
                MiningLogic.THERMAL_NO_CAP == noCap && fresh.cap() == noCap
                        && !fresh.stopped());
        boolean passThrough = true;
        for (int w = 1; w <= 8; w++) {
            if (fresh.capWorkers(w) != w) {
                passThrough = false;
            }
        }
        check("thermal: without a cap the resolved worker count passes"
                + " unchanged, for 1 to 8 workers", passThrough);

        boolean coolQuiet = true;
        for (int status = 0; status <= 1; status++) {
            for (int w = 1; w <= 8; w++) {
                MiningLogic.ThermalGovernor g = new MiningLogic.ThermalGovernor();
                if (!decided(g.onStatus(status, w, w), none, noCap)
                        || g.cap() != noCap || g.stopped()
                        || g.capWorkers(w) != w) {
                    coolQuiet = false;
                }
            }
        }
        check("thermal: status 0 and 1 do nothing, for 1 to 8 workers",
                coolQuiet);

        final int[] half = {1, 1, 1, 2, 2, 3, 3, 4};
        final boolean[] reduces = {false, true, true, true, true, true, true,
                true};
        for (int w = 1; w <= 8; w++) {
            MiningLogic.ThermalGovernor g = new MiningLogic.ThermalGovernor();
            MiningLogic.ThermalDecision d = g.onStatus(2, w, w);
            check("thermal: status 2 with " + w + " worker(s) running caps at "
                            + half[w - 1] + (reduces[w - 1]
                            ? " and reduces the miner"
                            : " and leaves the one-worker miner alone"),
                    decided(d, reduces[w - 1] ? reduce : none, half[w - 1])
                            && g.cap() == half[w - 1] && !g.stopped()
                            && g.capWorkers(w) == half[w - 1]);
        }

        boolean hotStops = true;
        for (int status = 3; status <= 6; status++) {
            for (int w = 1; w <= 8; w++) {
                MiningLogic.ThermalGovernor g = new MiningLogic.ThermalGovernor();
                if (!decided(g.onStatus(status, w, w), stop, noCap)
                        || !g.stopped() || g.cap() != noCap) {
                    hotStops = false;
                }
            }
        }
        check("thermal: status 3 to 6 stop the miner, for 1 to 8 workers",
                hotStops);
        check("thermal: the stop needs no running miner (a reading taken"
                        + " before the miner is started)",
                decided(new MiningLogic.ThermalGovernor().onStatus(3, 2, 0),
                        stop, noCap));
        MiningLogic.ThermalGovernor odd = new MiningLogic.ThermalGovernor();
        check("thermal: a status below 0 is not heat",
                decided(odd.onStatus(-1, 4, 4), none, noCap) && !odd.stopped());
        check("thermal: a status above 6 is",
                decided(odd.onStatus(7, 4, 4), stop, noCap) && odd.stopped());

        MiningLogic.ThermalGovernor r = new MiningLogic.ThermalGovernor();
        check("thermal: with no miner running the cap is set and nothing is"
                        + " reduced",
                decided(r.onStatus(2, 4, 0), none, 2) && r.cap() == 2);
        check("thermal: a miner already at the cap is left alone",
                decided(r.onStatus(2, 4, 2), none, 2));
        check("thermal: a miner below the cap is left alone",
                decided(r.onStatus(2, 4, 1), none, 2));
        check("thermal: a miner above the cap is reduced, each time it is"
                        + " seen there",
                decided(r.onStatus(2, 4, 4), reduce, 2)
                        && decided(r.onStatus(2, 4, 3), reduce, 2));
        check("thermal: the cap bounds the worker count of every later start",
                r.capWorkers(8) == 2 && r.capWorkers(4) == 2
                        && r.capWorkers(2) == 2 && r.capWorkers(1) == 1);

        MiningLogic.ThermalGovernor k = new MiningLogic.ThermalGovernor();
        k.onStatus(2, 4, 4);
        check("thermal: cooling down does not lift the cap",
                decided(k.onStatus(1, 4, 2), none, 2)
                        && decided(k.onStatus(0, 4, 2), none, 2)
                        && k.cap() == 2 && k.capWorkers(4) == 2);
        check("thermal: a cool status reduces nothing, whatever is running",
                decided(k.onStatus(1, 4, 4), none, 2)
                        && decided(k.onStatus(0, 8, 8), none, 2));
        check("thermal: a later status 2 never raises the cap (8 workers"
                        + " alone would give 4)",
                decided(k.onStatus(2, 8, 2), none, 2) && k.cap() == 2);
        check("thermal: a later status 2 can lower it (2 workers give 1)",
                decided(k.onStatus(2, 2, 2), reduce, 1) && k.cap() == 1);
        check("thermal: and it stays at 1 from then on",
                decided(k.onStatus(2, 4, 1), none, 1)
                        && decided(k.onStatus(0, 4, 1), none, 1)
                        && k.capWorkers(4) == 1);
        check("thermal: at a cap of 1 a one-worker miner is left alone",
                decided(k.onStatus(2, 1, 1), none, 1));

        MiningLogic.ThermalGovernor s = new MiningLogic.ThermalGovernor();
        s.onStatus(2, 4, 4);
        check("thermal: status 3 after a cap stops the miner; the cap is"
                        + " reported as it stood",
                decided(s.onStatus(3, 4, 2), stop, 2) && s.stopped()
                        && s.cap() == 2);
        boolean latched = true;
        for (int status = 0; status <= 6; status++) {
            if (!decided(s.onStatus(status, 2, 0), stop, 2) || !s.stopped()
                    || s.cap() != 2) {
                latched = false;
            }
        }
        check("thermal: once stopped, every status 0 to 6 still says stop -"
                + " cooling down resumes nothing, and nothing else moves",
                latched);

        check("thermal: status names for the app log",
                "none (0)".equals(MiningLogic.thermalStatusText(0))
                        && "light (1)".equals(MiningLogic.thermalStatusText(1))
                        && "moderate (2)".equals(
                        MiningLogic.thermalStatusText(2))
                        && "severe (3)".equals(MiningLogic.thermalStatusText(3))
                        && "critical (4)".equals(
                        MiningLogic.thermalStatusText(4))
                        && "emergency (5)".equals(
                        MiningLogic.thermalStatusText(5))
                        && "shutdown (6)".equals(
                        MiningLogic.thermalStatusText(6))
                        && "unknown (7)".equals(MiningLogic.thermalStatusText(7))
                        && "unknown (-1)".equals(
                        MiningLogic.thermalStatusText(-1)));
    }

    /** The supervisor as {@link MiningLogic.ThermalGovernor#apply} sees
     *  it. It notes what the governor held at the moment it was asked
     *  for its worker count. */
    static final class FakeMiner implements MiningLogic.ThermalControl {
        final MiningLogic.ThermalGovernor governor;
        int running;
        int asked;
        int capWhenAsked = -1;
        int restarts;
        int thermalStops;

        FakeMiner(MiningLogic.ThermalGovernor governor, int running) {
            this.governor = governor;
            this.running = running;
        }

        @Override
        public int runningWorkers() {
            asked++;
            capWhenAsked = governor.cap();
            return running;
        }

        @Override
        public void requestRestart() {
            restarts++;
        }

        @Override
        public void requestThermalStop() {
            thermalStops++;
        }
    }

    private static void testThermalApply() {
        final MiningLogic.ThermalAction none = MiningLogic.ThermalAction.NONE;
        final MiningLogic.ThermalAction reduce =
                MiningLogic.ThermalAction.REDUCE;
        final MiningLogic.ThermalAction stop = MiningLogic.ThermalAction.STOP;

        MiningLogic.ThermalGovernor g = new MiningLogic.ThermalGovernor();
        FakeMiner m = new FakeMiner(g, 4);
        check("thermal apply: status 2 restarts a miner that runs above the"
                        + " new cap",
                decided(g.apply(2, 4, m), reduce, 2) && m.restarts == 1
                        && m.thermalStops == 0);
        // The order is the point: a miner start that read its worker
        // count before the cap came down is only caught if the miner
        // is looked at AFTER the cap is in place.
        check("thermal apply: the miner is looked at only after the cap is"
                + " in place", m.asked == 1 && m.capWhenAsked == 2);
        m.running = 2;
        check("thermal apply: once the miner runs at the cap, status 2 leaves"
                        + " it alone",
                decided(g.apply(2, 4, m), none, 2) && m.restarts == 1);
        check("thermal apply: cool statuses touch nothing",
                decided(g.apply(1, 4, m), none, 2)
                        && decided(g.apply(0, 4, m), none, 2)
                        && m.restarts == 1 && m.thermalStops == 0);
        check("thermal apply: status 3 stops the miner and restarts nothing",
                decided(g.apply(3, 4, m), stop, 2) && m.thermalStops == 1
                        && m.restarts == 1 && g.stopped());
        m.running = 0;
        check("thermal apply: after the stop a cool status asks for the stop"
                        + " again, never for a restart",
                decided(g.apply(0, 4, m), stop, 2) && m.thermalStops == 2
                        && m.restarts == 1);

        MiningLogic.ThermalGovernor h = new MiningLogic.ThermalGovernor();
        FakeMiner idle = new FakeMiner(h, 0);
        check("thermal apply: with no miner running, status 2 only sets the"
                        + " cap for the next start",
                decided(h.apply(2, 2, idle), none, 1) && idle.restarts == 0
                        && idle.thermalStops == 0 && h.capWorkers(2) == 1);
        check("thermal apply: status 3 before any miner ran stops the session",
                decided(h.apply(4, 2, idle), stop, 1) && idle.thermalStops == 1
                        && idle.restarts == 0);
    }

    // ---------------- presentation ----------------

    private static MiningLogic.MinerSnapshot snap(MiningLogic.Phase phase,
            MiningLogic.FilesStatus files, Integer exit, long lastJobMs) {
        MiningLogic.StatsSnapshot stats = new MiningLogic.StatsSnapshot(
                lastJobMs >= 0 ? 1 : 0, 0, 0, 0, lastJobMs, -1.0, null,
                false, false);
        return new MiningLogic.MinerSnapshot(phase, files, exit, 0, 0, 4,
                false, stats, Collections.<String>emptyList());
    }

    /** A snapshot with the thermal facts of its session (fix2). */
    private static MiningLogic.MinerSnapshot thermalSnap(
            MiningLogic.Phase phase, Integer exit, long lastJobMs,
            int cpuWorkers, int thermalCap, boolean thermalStopped) {
        MiningLogic.StatsSnapshot stats = new MiningLogic.StatsSnapshot(
                lastJobMs >= 0 ? 1 : 0, 0, 0, 0, lastJobMs, -1.0, null,
                false, false);
        return new MiningLogic.MinerSnapshot(phase,
                MiningLogic.FilesStatus.READY, exit, 0, 0, cpuWorkers, false,
                stats, Collections.<String>emptyList(), thermalCap,
                thermalStopped);
    }

    private static boolean is(MiningLogic.Presentation p,
                              MiningLogic.UiState state,
                              MiningLogic.UiReason reason) {
        return p != null && p.state == state && p.reason == reason;
    }

    private static void testPresentation() {
        final MiningLogic.SkipReason none = MiningLogic.SkipReason.NONE;
        check("state: off and stopped is OFF",
                is(MiningLogic.present(false, false, false, null, none,
                        false, -1, null, 0),
                        MiningLogic.UiState.OFF, MiningLogic.UiReason.NONE));
        check("state: a saved inner hash alone does not leave OFF",
                is(MiningLogic.present(false, true, true, Boolean.FALSE, none,
                        true, 9, null, 0),
                        MiningLogic.UiState.OFF, MiningLogic.UiReason.NONE));
        check("state: enabled with the node stopped",
                is(MiningLogic.present(true, true, false, null, none,
                        false, -1, null, 0),
                        MiningLogic.UiState.NODE_STOPPED,
                        MiningLogic.UiReason.NONE));
        check("state: enabled, node stopped, stored value unusable",
                is(MiningLogic.present(true, false, false, null, none,
                        false, -1, null, 0),
                        MiningLogic.UiState.NODE_STOPPED,
                        MiningLogic.UiReason.REWARDS_INVALID));
        check("state: enabled while the node has not been launched yet",
                is(MiningLogic.present(true, true, true, null, none,
                        false, -1, null, 0),
                        MiningLogic.UiState.PREPARING,
                        MiningLogic.UiReason.NODE_STARTING));
        check("state: enabled after the node was launched without mining",
                is(MiningLogic.present(true, true, true, Boolean.FALSE, none,
                        true, 9, null, 0),
                        MiningLogic.UiState.PREPARING,
                        MiningLogic.UiReason.RESTART_TO_ENABLE));
        check("state: launch skipped mining over the stored value",
                is(MiningLogic.present(true, false, true, Boolean.FALSE,
                        MiningLogic.SkipReason.REWARDS_INVALID,
                        true, 9, null, 0),
                        MiningLogic.UiState.STOPPED,
                        MiningLogic.UiReason.REWARDS_INVALID));
        check("state: launch skipped mining over the missing binary",
                is(MiningLogic.present(true, true, true, Boolean.FALSE,
                        MiningLogic.SkipReason.MINER_BINARY_MISSING,
                        true, 9, null, 0),
                        MiningLogic.UiState.STOPPED,
                        MiningLogic.UiReason.BINARY_MISSING));
        // A launch that skipped mining for want of the network key:
        // the node writes the key as its network comes up, so a
        // restart begins mining only from then on. Until the node
        // has answered (no peer count yet, not synced), the display
        // waits for it instead of asking for a restart that would
        // only repeat the launch.
        check("state: no network key at launch, node not answering yet ->"
                        + " waiting for the node, no restart asked for",
                is(MiningLogic.present(true, true, true, Boolean.FALSE,
                        MiningLogic.SkipReason.NETWORK_KEY_MISSING,
                        false, -1, null, 0),
                        MiningLogic.UiState.PREPARING,
                        MiningLogic.UiReason.NODE_STARTING));
        check("state: no network key at launch, node up -> a restart is all"
                        + " it takes",
                is(MiningLogic.present(true, true, true, Boolean.FALSE,
                        MiningLogic.SkipReason.NETWORK_KEY_MISSING,
                        false, 0, null, 0),
                        MiningLogic.UiState.PREPARING,
                        MiningLogic.UiReason.RESTART_TO_ENABLE)
                        && is(MiningLogic.present(true, true, true,
                        Boolean.FALSE,
                        MiningLogic.SkipReason.NETWORK_KEY_MISSING,
                        true, -1, null, 0),
                        MiningLogic.UiState.PREPARING,
                        MiningLogic.UiReason.RESTART_TO_ENABLE));
        check("state: (as before) switched on while a node launched with"
                        + " mining off is still coming up -> restart sentence",
                is(MiningLogic.present(true, true, true, Boolean.FALSE, none,
                        false, -1, null, 0),
                        MiningLogic.UiState.PREPARING,
                        MiningLogic.UiReason.RESTART_TO_ENABLE));
        check("state: launched with mining, supervisor not visible yet",
                is(MiningLogic.present(true, true, true, Boolean.TRUE, none,
                        false, -1, null, 0),
                        MiningLogic.UiState.PREPARING,
                        MiningLogic.UiReason.MINER_STARTING));
        check("state: waiting for the node to come up",
                is(MiningLogic.present(true, true, true, Boolean.TRUE, none,
                        false, -1, snap(MiningLogic.Phase.WAITING_NODE,
                                MiningLogic.FilesStatus.READY, null, -1), 0),
                        MiningLogic.UiState.PREPARING,
                        MiningLogic.UiReason.NODE_STARTING));
        MiningLogic.Presentation tokenWait = MiningLogic.present(true, true,
                true, Boolean.TRUE, none, false, -1,
                snap(MiningLogic.Phase.WAITING_FILES,
                        MiningLogic.FilesStatus.TOKEN_MISSING, null, -1), 0);
        check("state: waiting for the token file names that file",
                is(tokenWait, MiningLogic.UiState.PREPARING,
                        MiningLogic.UiReason.FILES)
                        && "miner-auth-token".equals(tokenWait.fileName));
        MiningLogic.Presentation fpWait = MiningLogic.present(true, true,
                true, Boolean.TRUE, none, false, -1,
                snap(MiningLogic.Phase.WAITING_FILES,
                        MiningLogic.FilesStatus.FINGERPRINT_EMPTY, null, -1), 0);
        check("state: waiting for the fingerprint file names that file",
                is(fpWait, MiningLogic.UiState.PREPARING,
                        MiningLogic.UiReason.FILES)
                        && "miner-tls-cert-sha256".equals(fpWait.fileName));
        check("state: the miner process is being started",
                is(MiningLogic.present(true, true, true, Boolean.TRUE, none,
                        true, 9, snap(MiningLogic.Phase.STARTING,
                                MiningLogic.FilesStatus.READY, null, -1), 0),
                        MiningLogic.UiState.PREPARING,
                        MiningLogic.UiReason.MINER_STARTING));
        check("state: running without a job while the node syncs",
                is(MiningLogic.present(true, true, true, Boolean.TRUE, none,
                        false, 9, snap(MiningLogic.Phase.RUNNING,
                                MiningLogic.FilesStatus.READY, null, -1), 5000),
                        MiningLogic.UiState.WAITING,
                        MiningLogic.UiReason.WAIT_SYNC));
        check("state: running without a job, synced, zero peers",
                is(MiningLogic.present(true, true, true, Boolean.TRUE, none,
                        true, 0, snap(MiningLogic.Phase.RUNNING,
                                MiningLogic.FilesStatus.READY, null, -1), 5000),
                        MiningLogic.UiState.WAITING,
                        MiningLogic.UiReason.WAIT_PEERS));
        check("state: running without a job, synced, peers connected",
                is(MiningLogic.present(true, true, true, Boolean.TRUE, none,
                        true, 5, snap(MiningLogic.Phase.RUNNING,
                                MiningLogic.FilesStatus.READY, null, -1), 5000),
                        MiningLogic.UiState.WAITING,
                        MiningLogic.UiReason.WAIT_JOB));
        check("state: an unknown peer count is not a measured zero",
                is(MiningLogic.present(true, true, true, Boolean.TRUE, none,
                        true, -1, snap(MiningLogic.Phase.RUNNING,
                                MiningLogic.FilesStatus.READY, null, -1), 5000),
                        MiningLogic.UiState.WAITING,
                        MiningLogic.UiReason.WAIT_JOB));
        check("state: a job within the last two minutes is MINING",
                is(MiningLogic.present(true, true, true, Boolean.TRUE, none,
                        true, 5, snap(MiningLogic.Phase.RUNNING,
                                MiningLogic.FilesStatus.READY, null, 1000),
                        1000 + 120_000),
                        MiningLogic.UiState.MINING, MiningLogic.UiReason.NONE));
        check("state: a job older than two minutes is WAITING again",
                is(MiningLogic.present(true, true, true, Boolean.TRUE, none,
                        true, 5, snap(MiningLogic.Phase.RUNNING,
                                MiningLogic.FilesStatus.READY, null, 1000),
                        1000 + 120_001),
                        MiningLogic.UiState.WAITING,
                        MiningLogic.UiReason.WAIT_JOB));
        check("state: job evidence outranks the node's sync flag",
                is(MiningLogic.present(true, true, true, Boolean.TRUE, none,
                        false, 0, snap(MiningLogic.Phase.RUNNING,
                                MiningLogic.FilesStatus.READY, null, 4000), 5000),
                        MiningLogic.UiState.MINING, MiningLogic.UiReason.NONE));
        MiningLogic.Presentation retry = MiningLogic.present(true, true, true,
                Boolean.TRUE, none, true, 5,
                snap(MiningLogic.Phase.BACKOFF,
                        MiningLogic.FilesStatus.READY, 1, -1), 5000);
        check("state: an abnormal exit shows STOPPED with its exit code",
                is(retry, MiningLogic.UiState.STOPPED,
                        MiningLogic.UiReason.RETRYING)
                        && Integer.valueOf(1).equals(retry.exitCode));
        MiningLogic.Presentation gaveUp = MiningLogic.present(true, true, true,
                Boolean.TRUE, none, true, 5,
                snap(MiningLogic.Phase.GAVE_UP,
                        MiningLogic.FilesStatus.READY, 101, -1), 5000);
        check("state: after the restarts ran out, STOPPED with the last code",
                is(gaveUp, MiningLogic.UiState.STOPPED,
                        MiningLogic.UiReason.GAVE_UP)
                        && Integer.valueOf(101).equals(gaveUp.exitCode));
        check("state: the miner binary vanished after launch",
                is(MiningLogic.present(true, true, true, Boolean.TRUE, none,
                        true, 5, snap(MiningLogic.Phase.BINARY_MISSING,
                                MiningLogic.FilesStatus.READY, null, -1), 5000),
                        MiningLogic.UiState.STOPPED,
                        MiningLogic.UiReason.BINARY_MISSING));
        check("state: the miner could not be spawned",
                is(MiningLogic.present(true, true, true, Boolean.TRUE, none,
                        true, 5, snap(MiningLogic.Phase.START_FAILED,
                                MiningLogic.FilesStatus.READY, null, -1), 5000),
                        MiningLogic.UiState.STOPPED,
                        MiningLogic.UiReason.START_FAILED));
        check("state: switched off in settings, the running miner still shows",
                is(MiningLogic.present(false, true, true, Boolean.TRUE, none,
                        true, 5, snap(MiningLogic.Phase.RUNNING,
                                MiningLogic.FilesStatus.READY, null, 4000), 5000),
                        MiningLogic.UiState.MINING, MiningLogic.UiReason.NONE));
        check("state: an idle supervisor under a mining launch is preparing",
                is(MiningLogic.present(true, true, true, Boolean.TRUE, none,
                        true, 5, snap(MiningLogic.Phase.IDLE,
                                MiningLogic.FilesStatus.READY, null, -1), 5000),
                        MiningLogic.UiState.PREPARING,
                        MiningLogic.UiReason.MINER_STARTING));
        check("state: a stopped node outranks a leftover miner snapshot",
                is(MiningLogic.present(true, true, false, Boolean.TRUE, none,
                        true, 5, snap(MiningLogic.Phase.RUNNING,
                                MiningLogic.FilesStatus.READY, null, 4000), 5000),
                        MiningLogic.UiState.NODE_STOPPED,
                        MiningLogic.UiReason.NONE));
        check("state: off, stopped, leftover snapshot is plain OFF",
                is(MiningLogic.present(false, true, false, Boolean.TRUE, none,
                        true, 5, snap(MiningLogic.Phase.GAVE_UP,
                                MiningLogic.FilesStatus.READY, 1, -1), 5000),
                        MiningLogic.UiState.OFF, MiningLogic.UiReason.NONE));

        // fix2: the thermal protection. A miner it stopped is STOPPED
        // for that reason, and the reason outranks whatever the
        // supervisor's phase says: the miner is not coming back in
        // this session, whatever it was doing when the stop came.
        final int noCap = Integer.MAX_VALUE;
        check("state: a snapshot built without thermal facts has no cap and"
                        + " no thermal stop",
                snap(MiningLogic.Phase.RUNNING, MiningLogic.FilesStatus.READY,
                        null, -1).thermalCap == noCap
                        && !snap(MiningLogic.Phase.RUNNING,
                        MiningLogic.FilesStatus.READY, null, -1).thermalStopped);
        MiningLogic.Presentation heat = MiningLogic.present(true, true, true,
                Boolean.TRUE, none, true, 5,
                thermalSnap(MiningLogic.Phase.IDLE, null, -1, 2, noCap, true),
                5000);
        check("state: stopped by the thermal protection -> STOPPED with the"
                        + " thermal reason, and no exit code",
                is(heat, MiningLogic.UiState.STOPPED,
                        MiningLogic.UiReason.THERMAL_STOP)
                        && heat.exitCode == null && heat.fileName == null);
        MiningLogic.Presentation heatAfterGiveUp = MiningLogic.present(true,
                true, true, Boolean.TRUE, none, true, 5,
                thermalSnap(MiningLogic.Phase.GAVE_UP, 101, -1, 2, 1, true),
                5000);
        check("state: the thermal stop outranks 'gave up', and the old exit"
                        + " code is not shown as its cause",
                is(heatAfterGiveUp, MiningLogic.UiState.STOPPED,
                        MiningLogic.UiReason.THERMAL_STOP)
                        && heatAfterGiveUp.exitCode == null);
        boolean outranksAll = true;
        for (MiningLogic.Phase ph : MiningLogic.Phase.values()) {
            // A recent job as well, so that RUNNING alone would read
            // MINING.
            MiningLogic.Presentation p = MiningLogic.present(true, true, true,
                    Boolean.TRUE, none, true, 5,
                    thermalSnap(ph, 3, 4000, 2, 1, true), 5000);
            if (!is(p, MiningLogic.UiState.STOPPED,
                    MiningLogic.UiReason.THERMAL_STOP) || p.exitCode != null) {
                outranksAll = false;
            }
        }
        check("state: the thermal stop outranks every phase of the supervisor",
                outranksAll);
        check("state: a stopped node still outranks a thermally stopped miner",
                is(MiningLogic.present(true, true, false, Boolean.TRUE, none,
                        true, 5, thermalSnap(MiningLogic.Phase.IDLE, null, -1,
                                2, noCap, true), 5000),
                        MiningLogic.UiState.NODE_STOPPED,
                        MiningLogic.UiReason.NONE)
                        && is(MiningLogic.present(false, true, false,
                        Boolean.TRUE, none, true, 5,
                        thermalSnap(MiningLogic.Phase.IDLE, null, -1, 2,
                                noCap, true), 5000),
                        MiningLogic.UiState.OFF, MiningLogic.UiReason.NONE));
        check("state: switched off in settings, the thermal stop still shows"
                        + " for the node that was launched with mining",
                is(MiningLogic.present(false, true, true, Boolean.TRUE, none,
                        true, 5, thermalSnap(MiningLogic.Phase.IDLE, null, -1,
                                2, noCap, true), 5000),
                        MiningLogic.UiState.STOPPED,
                        MiningLogic.UiReason.THERMAL_STOP));
        check("state: a thermal cap alone changes nothing on the status rows",
                is(MiningLogic.present(true, true, true, Boolean.TRUE, none,
                        true, 5, thermalSnap(MiningLogic.Phase.RUNNING, null,
                                4000, 1, 1, false), 5000),
                        MiningLogic.UiState.MINING, MiningLogic.UiReason.NONE)
                        && is(MiningLogic.present(true, true, true,
                        Boolean.TRUE, none, true, 5,
                        thermalSnap(MiningLogic.Phase.RUNNING, null, -1, 1, 1,
                                false), 5000),
                        MiningLogic.UiState.WAITING,
                        MiningLogic.UiReason.WAIT_JOB));
    }

    private static void testPending() {
        final MiningLogic.SkipReason none = MiningLogic.SkipReason.NONE;
        check("pending: a stopped node has nothing pending",
                !MiningLogic.nodeRestartPending(false, Boolean.FALSE, null,
                        none, true, VALID));
        check("pending: before the launch snapshot exists nothing is pending",
                !MiningLogic.nodeRestartPending(true, null, null,
                        none, true, VALID));
        check("pending: enabled after a launch without mining",
                MiningLogic.nodeRestartPending(true, Boolean.FALSE, null,
                        none, true, VALID));
        check("pending: enabled without a usable value changes nothing",
                !MiningLogic.nodeRestartPending(true, Boolean.FALSE, null,
                        none, true, ""));
        check("pending: a launch that skipped mining over the stored value"
                        + " is pending once a usable value is saved",
                MiningLogic.nodeRestartPending(true, Boolean.FALSE, null,
                        MiningLogic.SkipReason.REWARDS_INVALID, true, VALID));
        check("pending: a missing miner binary is not cured by a restart",
                !MiningLogic.nodeRestartPending(true, Boolean.FALSE, null,
                        MiningLogic.SkipReason.MINER_BINARY_MISSING,
                        true, VALID));
        check("pending: a missing network key is - that start created it",
                MiningLogic.nodeRestartPending(true, Boolean.FALSE, null,
                        MiningLogic.SkipReason.NETWORK_KEY_MISSING,
                        true, VALID)
                        && MiningLogic.nodeRestartPendingKind(true,
                        Boolean.FALSE, null,
                        MiningLogic.SkipReason.NETWORK_KEY_MISSING, true, VALID)
                        == MiningLogic.PendingKind.ENABLE);
        check("pending: running exactly as saved",
                !MiningLogic.nodeRestartPending(true, Boolean.TRUE,
                        "7190a2df8cecef3b", none, true, VALID));
        check("pending: the same value in another spelling is not a change",
                !MiningLogic.nodeRestartPending(true, Boolean.TRUE,
                        "7190a2df8cecef3b", none, true,
                        VALID_CORE.toUpperCase(java.util.Locale.ROOT)));
        check("pending: a different saved value",
                MiningLogic.nodeRestartPending(true, Boolean.TRUE,
                        "7190a2df8cecef3b", none, true, OTHER));
        check("pending: disabled while running with mining",
                MiningLogic.nodeRestartPending(true, Boolean.TRUE,
                        "7190a2df8cecef3b", none, false, VALID));
        check("pending: off and launched off",
                !MiningLogic.nodeRestartPending(true, Boolean.FALSE, null,
                        none, false, VALID));

        check("pending kind: switching on after a launch without mining",
                MiningLogic.nodeRestartPendingKind(true, Boolean.FALSE, null,
                        none, true, VALID) == MiningLogic.PendingKind.ENABLE);
        check("pending kind: switching off while running with mining",
                MiningLogic.nodeRestartPendingKind(true, Boolean.TRUE,
                        "7190a2df8cecef3b", none, false, VALID)
                        == MiningLogic.PendingKind.DISABLE);
        check("pending kind: the value was deleted while running with mining",
                MiningLogic.nodeRestartPendingKind(true, Boolean.TRUE,
                        "7190a2df8cecef3b", none, true, "")
                        == MiningLogic.PendingKind.DISABLE);
        check("pending kind: another value saved while running with mining",
                MiningLogic.nodeRestartPendingKind(true, Boolean.TRUE,
                        "7190a2df8cecef3b", none, true, OTHER)
                        == MiningLogic.PendingKind.REWARDS_CHANGED);
        check("pending kind: nothing pending",
                MiningLogic.nodeRestartPendingKind(true, Boolean.TRUE,
                        "7190a2df8cecef3b", none, true, VALID)
                        == MiningLogic.PendingKind.NONE
                        && MiningLogic.nodeRestartPendingKind(false,
                        Boolean.TRUE, "7190a2df8cecef3b", none, false, VALID)
                        == MiningLogic.PendingKind.NONE
                        && MiningLogic.nodeRestartPendingKind(true,
                        Boolean.FALSE, null,
                        MiningLogic.SkipReason.MINER_BINARY_MISSING, true, VALID)
                        == MiningLogic.PendingKind.NONE);

        MiningLogic.MinerSnapshot running = snap(MiningLogic.Phase.RUNNING,
                MiningLogic.FilesStatus.READY, null, -1);
        check("pending: the running miner uses another worker count",
                MiningLogic.minerRestartPending(running, 2));
        check("pending: the running miner already uses the saved count",
                !MiningLogic.minerRestartPending(running, 4));
        check("pending: a miner that is not running picks it up by itself",
                !MiningLogic.minerRestartPending(snap(MiningLogic.Phase.BACKOFF,
                        MiningLogic.FilesStatus.READY, 1, -1), 2)
                        && !MiningLogic.minerRestartPending(null, 2));

        // fix2: a restart of the miner would run it with the setting's
        // count under the session's thermal cap, so that - not the
        // bare setting - is what a running miner is compared with. A
        // miner reduced for heat is not a settings change waiting for
        // a restart.
        MiningLogic.MinerSnapshot reduced = thermalSnap(
                MiningLogic.Phase.RUNNING, null, -1, 2, 2, false);
        check("pending: a miner reduced to the thermal cap does not lag a"
                + " higher setting", !MiningLogic.minerRestartPending(reduced, 4));
        check("pending: under a cap, a setting at the cap is not pending",
                !MiningLogic.minerRestartPending(reduced, 2));
        check("pending: under a cap, a setting below it still is",
                MiningLogic.minerRestartPending(reduced, 1));
        check("pending: until the reduction has happened, the miner does lag",
                MiningLogic.minerRestartPending(thermalSnap(
                        MiningLogic.Phase.RUNNING, null, -1, 4, 2, false), 4));
        check("pending: a miner stopped for heat has nothing pending",
                !MiningLogic.minerRestartPending(thermalSnap(
                        MiningLogic.Phase.IDLE, null, -1, 2, 2, true), 4));
    }

    // ---------------- share helpers ----------------

    private static void testShareHelpers() {
        check("hashrate: below 1 kH/s in whole hashes",
                "850 H/s".equals(MiningLogic.formatHashrate(850.4))
                        && "0 H/s".equals(MiningLogic.formatHashrate(0.0)));
        check("hashrate: kilo with two decimals",
                "12.34 kH/s".equals(MiningLogic.formatHashrate(12340.0))
                        && "107.17 kH/s".equals(
                        MiningLogic.formatHashrate(107170.0)));
        check("hashrate: mega with two decimals",
                "1.25 MH/s".equals(MiningLogic.formatHashrate(1_250_000.0)));
        check("hashrate: unknown has no text",
                MiningLogic.formatHashrate(-1.0) == null
                        && MiningLogic.formatHashrate(Double.NaN) == null);

        String body = N_ADDRESS + "\n"
                + "2026-11-14 18:00:09 note about " + VALID_ADDRESS + " again\n"
                + "2026-11-14 18:00:10 peer qzmTAz3UUw1WGUuVh8nbFmPwcftomduwy6twq6NDR6y9qqtEs\n";
        check("share: the reported rewards address is masked everywhere",
                ("2026-11-14 18:00:00 ⛏️ Rewards wormhole address:"
                        + " <rewards-address>\n"
                        + "2026-11-14 18:00:09 note about <rewards-address>"
                        + " again\n"
                        + "2026-11-14 18:00:10 peer qzmTAz3UUw1WGUuVh8nbFmPwc"
                        + "ftomduwy6twq6NDR6y9qqtEs\n")
                        .equals(MiningLogic.maskRewardsAddress(body)));
        String plain = "2026-11-14 18:00:09 ✨ Imported #123\n";
        check("share: a log without the report is returned untouched",
                MiningLogic.maskRewardsAddress(plain) == plain
                        && MiningLogic.maskRewardsAddress(null) == null);

        check("share: the miner log section",
                ("\n=== miner log (shared excerpt) ===\na\nb\n"
                        + "=== end of miner log ===\n").equals(
                        MiningLogic.minerLogSection("a\nb\n", false)));
        check("share: a cut miner log says so",
                ("\n=== miner log (shared excerpt) ===\n"
                        + "[... earlier miner log omitted for sharing;"
                        + " showing the last part ...]\n"
                        + "a\nb\n=== end of miner log ===\n").equals(
                        MiningLogic.minerLogSection("a\nb", true)));
        check("share: no miner log, no section",
                "".equals(MiningLogic.minerLogSection(null, false))
                        && "".equals(MiningLogic.minerLogSection("  \n", false)));

        MiningLogic.ShareFacts off = new MiningLogic.ShareFacts();
        off.rewardsSaved = true;
        check("summary: with mining off the summary gains no line",
                MiningLogic.summaryLines(off) != null
                        && MiningLogic.summaryLines(off).isEmpty());

        MiningLogic.ShareFacts on = new MiningLogic.ShareFacts();
        on.enabledSetting = true;
        on.launchedMining = Boolean.TRUE;
        on.rewardsSaved = true;
        on.workerSetting = MiningLogic.WORKERS_AUTO;
        on.miningChangedText = "2026-11-14 17:55:01 +0900";
        on.rewardsChangedText = "n/a";
        on.uiState = MiningLogic.UiState.MINING;
        on.uiReason = MiningLogic.UiReason.NONE;
        on.miner = new MiningLogic.MinerSnapshot(MiningLogic.Phase.RUNNING,
                MiningLogic.FilesStatus.READY, 1, 0, 1, 4, true,
                new MiningLogic.StatsSnapshot(12, 1, 1, 0, 4000, 15000.0,
                        VALID_ADDRESS, true, false),
                Collections.<String>emptyList());
        List<String> want = Arrays.asList(
                "Mining: on (this session)",
                "Mining setting: on",
                "Mining setting changed: 2026-11-14 17:55:01 +0900",
                "Rewards inner hash: saved (value never shared)",
                "Rewards setting changed: n/a",
                "Miner state: mining",
                "Miner CPU workers: 4 (setting: auto)",
                "Miner launch method: linker64",
                "Miner hashrate (estimate): 15.00 kH/s",
                "Miner jobs received: 12",
                "Miner solutions submitted: 1",
                "Blocks accepted by the node: 1",
                "Seals rejected by the node: 0",
                "Miner restarts (this session): 1",
                "Miner last exit code: 1");
        check("summary: a mining session's block, line for line",
                want.equals(MiningLogic.summaryLines(on)));
        boolean leak = false;
        List<String> lines = MiningLogic.summaryLines(on);
        if (lines != null) {
            for (String l : lines) {
                if (l.contains(VALID_ADDRESS)) {
                    leak = true;
                }
            }
        }
        check("summary: the rewards address stays out of the unmasked summary",
                lines != null && !leak);

        MiningLogic.ShareFacts pend = new MiningLogic.ShareFacts();
        pend.enabledSetting = true;
        pend.launchedMining = Boolean.FALSE;
        pend.launchSkip = MiningLogic.SkipReason.REWARDS_INVALID;
        pend.rewardsSaved = false;
        pend.workerSetting = 2;
        pend.miningChangedText = "n/a";
        pend.rewardsChangedText = "n/a";
        pend.uiState = MiningLogic.UiState.STOPPED;
        pend.uiReason = MiningLogic.UiReason.REWARDS_INVALID;
        check("summary: enabled but not applied to this launch",
                Arrays.asList(
                        "Mining: off (this session; skipped:"
                                + " rewards inner hash unusable)",
                        "Mining setting: on",
                        "Mining setting changed: n/a",
                        "Rewards inner hash: not saved",
                        "Rewards setting changed: n/a",
                        "Miner state: stopped (rewards inner hash unusable)",
                        "Miner CPU workers: n/a (setting: 2)")
                        .equals(MiningLogic.summaryLines(pend)));

        MiningLogic.ShareFacts noKey = new MiningLogic.ShareFacts();
        noKey.enabledSetting = true;
        noKey.launchedMining = Boolean.FALSE;
        noKey.launchSkip = MiningLogic.SkipReason.NETWORK_KEY_MISSING;
        noKey.rewardsSaved = true;
        noKey.miningChangedText = "2026-11-15 09:00:00 +0900";
        noKey.rewardsChangedText = "2026-11-15 08:59:30 +0900";
        noKey.uiState = MiningLogic.UiState.PREPARING;
        noKey.uiReason = MiningLogic.UiReason.RESTART_TO_ENABLE;
        check("summary: a first start without the network key says so",
                Arrays.asList(
                        "Mining: off (this session; skipped:"
                                + " network key not created yet)",
                        "Mining setting: on",
                        "Mining setting changed: 2026-11-15 09:00:00 +0900",
                        "Rewards inner hash: saved (value never shared)",
                        "Rewards setting changed: 2026-11-15 08:59:30 +0900",
                        "Miner state: preparing (node restart needed)",
                        "Miner CPU workers: n/a (setting: 2)")
                        .equals(MiningLogic.summaryLines(noKey)));

        // fix2: a miner the thermal protection stopped. Its last
        // process ran with one worker under a cap, although the
        // setting says 4; the state line names the reason.
        MiningLogic.ShareFacts hot = new MiningLogic.ShareFacts();
        hot.enabledSetting = true;
        hot.launchedMining = Boolean.TRUE;
        hot.rewardsSaved = true;
        hot.workerSetting = 4;
        hot.miningChangedText = "n/a";
        hot.rewardsChangedText = "n/a";
        hot.uiState = MiningLogic.UiState.STOPPED;
        hot.uiReason = MiningLogic.UiReason.THERMAL_STOP;
        hot.miner = thermalSnap(MiningLogic.Phase.IDLE, null, -1, 1, 1, true);
        List<String> hotLines = MiningLogic.summaryLines(hot);
        check("summary: a thermal stop is named in the miner state line",
                hotLines != null && hotLines.size() > 6
                        && "Miner state: stopped (device too hot)".equals(
                        hotLines.get(5))
                        && "Miner CPU workers: 1 (setting: 4)".equals(
                        hotLines.get(6)));
    }

    // ---------------- bounded log file ----------------

    private static final String ROTATION_MARK =
            "... [app] log rotated, older half dropped ...";

    private static void testBoundedLogFile() throws Exception {
        File dir = tempDir("log");

        File f1 = new File(dir, "plain.log");
        BoundedLogFile plain = new BoundedLogFile(f1, 4096);
        plain.write("a");
        plain.write("b");
        plain.write("c");
        check("log: lines are appended one per line",
                "a\nb\nc\n".equals(readAll(f1)));
        plain.write("cut here", true);
        check("log: a line the producer cut is marked as cut",
                "a\nb\nc\ncut here ...[line truncated]\n".equals(readAll(f1)));

        File f2 = new File(dir, "cap.log");
        BoundedLogFile capped = new BoundedLogFile(f2, 4096);
        for (int i = 0; i < 2000; i++) {
            capped.write(String.format(java.util.Locale.US,
                    "line %04d %s", i, repeat("x", 40)));
        }
        String all2 = readAll(f2);
        check("log: the file stays within its bound however much is written",
                f2.length() <= 4096 + 4096 / 8 + 64);
        check("log: a rotated log opens with the rotation marker",
                all2.startsWith(ROTATION_MARK + "\n"));
        check("log: the newest line is kept",
                all2.endsWith("line 1999 " + repeat("x", 40) + "\n"));
        check("log: the oldest lines are the ones dropped",
                !all2.contains("line 0000 ") && !all2.contains("line 0500 "));
        boolean whole = true;
        String[] lines2 = all2.split("\n");
        for (int i = 1; i < lines2.length; i++) {
            if (!lines2[i].matches("line \\d{4} x{40}")) {
                whole = false;
            }
        }
        check("log: rotation keeps whole lines only", whole);

        File f3 = new File(dir, "long.log");
        BoundedLogFile longLines = new BoundedLogFile(f3, 4096);
        longLines.write(repeat("あ", 3000)); // 9000 UTF-8 bytes
        String all3 = readAll(f3);
        check("log: one oversized line cannot outgrow the per-line cap",
                f3.length() <= 4096 / 4);
        check("log: an oversized line is cut and marked",
                all3.endsWith(" ...[line truncated]\n"));
        check("log: the cut never splits a multi-byte character",
                !all3.contains("�")
                        && all3.startsWith(repeat("あ", 100)));

        File f4 = new File(dir, "tail.log");
        BoundedLogFile tail = new BoundedLogFile(f4, 65536);
        for (int i = 0; i < 100; i++) {
            tail.write(String.format(java.util.Locale.US, "row %03d", i));
        }
        boolean[] cut = new boolean[1];
        check("log tail: a small log is read whole",
                readAll(f4).equals(BoundedLogFile.readTail(f4, 100_000, cut))
                        && !cut[0]);
        String t = BoundedLogFile.readTail(f4, 20, cut);
        check("log tail: a bounded read returns the end, from a line start",
                "row 098\nrow 099\n".equals(t) && cut[0]);
        check("log tail: no file, no tail",
                BoundedLogFile.readTail(new File(dir, "absent.log"), 100, cut)
                        == null && !cut[0]);
        File empty = new File(dir, "empty.log");
        write(empty, "");
        check("log tail: an empty file has no tail",
                BoundedLogFile.readTail(empty, 100, cut) == null);

        File f5 = new File(dir, "big-existing.log");
        write(f5, repeat("old line of some length here\n", 500)); // ~14.5 KB
        BoundedLogFile reopened = new BoundedLogFile(f5, 4096);
        reopened.write("first line after reopen");
        check("log: an over-cap log left by an earlier run is cut at once",
                f5.length() <= 4096
                        && readAll(f5).endsWith("first line after reopen\n"));

        // Two objects on one file (a recreated service leaves two
        // sinks behind): appends and rotations must not tear lines.
        final File f6 = new File(dir, "shared.log");
        final BoundedLogFile one = new BoundedLogFile(f6, 8192);
        final BoundedLogFile two = new BoundedLogFile(f6, 8192);
        Thread ta = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 1500; i++) {
                    one.write("A-" + repeat("a", 30));
                }
            }
        });
        Thread tb = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 1500; i++) {
                    two.write("B-" + repeat("b", 30));
                }
            }
        });
        ta.start();
        tb.start();
        ta.join();
        tb.join();
        boolean intact = true;
        String[] shared = readAll(f6).split("\n");
        for (String line : shared) {
            if (!line.equals("A-" + repeat("a", 30))
                    && !line.equals("B-" + repeat("b", 30))
                    && !line.equals(ROTATION_MARK)) {
                intact = false;
            }
        }
        check("log: two writers on one file never tear a line",
                intact && shared.length > 10);
        check("log: two writers on one file still respect the bound",
                f6.length() <= 8192 + 8192 / 8 + 64);
    }

    static String readAll(File f) throws IOException {
        if (!f.isFile()) {
            return "";
        }
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---------------- bounded line reader ----------------

    private static List<String> drained(String input, int cap)
            throws IOException {
        final List<String> out = new ArrayList<>();
        MinerSupervisor.drainLines(new java.io.ByteArrayInputStream(
                        input.getBytes(StandardCharsets.UTF_8)), cap,
                new MinerSupervisor.LineSink() {
                    @Override
                    public void onLine(String line, boolean truncated) {
                        out.add((truncated ? "T:" : "F:") + line);
                    }
                });
        return out;
    }

    private static void testDrainLines() throws IOException {
        check("drain: lines split at newlines, the last one without",
                Arrays.asList("F:ab", "F:cd", "F:ef").equals(
                        drained("ab\ncd\nef", 100)));
        check("drain: CRLF endings and empty lines",
                Arrays.asList("F:a", "F:", "F:b").equals(
                        drained("a\r\n\r\nb\r\n", 100)));
        check("drain: a line over the cap is cut and flagged, the next is not",
                Arrays.asList("F:ab", "T:cdef", "F:xy").equals(
                        drained("ab\ncdefgh\nxy", 4)));
        check("drain: empty input yields no line",
                drained("", 100).isEmpty());
        List<String> huge = drained(repeat("z", 1 << 20), 100);
        check("drain: a megabyte without a newline is one capped line",
                huge.size() == 1 && huge.get(0).equals("T:" + repeat("z", 100)));
        List<String> hugeThenMore = drained(repeat("z", 300_000) + "\nok\n", 50);
        check("drain: after an oversized line the stream is in step again",
                Arrays.asList("T:" + repeat("z", 50), "F:ok")
                        .equals(hugeThenMore));
    }

    // ---------------- stray miner processes ----------------

    private static void testStrayScan() throws IOException {
        File dir = tempDir("proc");
        File proc = new File(dir, "proc");
        final String bin = "/data/app/x/lib/arm64/libquantus_miner.so";
        fakeProc(proc, 4242, bin + "\0serve\0--node-addr\0" + "127.0.0.1:9833\0");
        fakeProc(proc, 4343, "/system/bin/linker64\0" + bin + "\0serve\0");
        fakeProc(proc, 5000, "/other/bin/tool\0" + bin + "\0");
        fakeProc(proc, 6000,
                "/data/app/x/lib/arm64/libquantus_node.so\0--chain\0mainnet\0");
        fakeProc(proc, 7000, "app.quantusnode.node\0");
        fakeProc(proc, 1, bin + "\0serve\0");
        new File(proc, "self").mkdirs();
        new File(proc, "8000").mkdirs(); // no cmdline: unreadable entry
        List<Long> found = MinerSupervisor.findOwnMinerPids(proc, bin, 1);
        check("strays: only this app's miner executable matches, in pid order",
                Arrays.asList(4242L, 4343L).equals(found));
        check("strays: an absent proc root finds nothing",
                MinerSupervisor.findOwnMinerPids(new File(dir, "nope"), bin, 1)
                        .isEmpty());

        final Rig rig = new Rig("reap", S_HAPPY);
        rig.cfg.procRoot = proc;
        rig.cfg.minerBinary = new File(bin);
        rig.selfPid = 1;
        int reaped = MinerSupervisor.reapStrayMiners(rig.cfg, rig.env);
        List<Long> killed = new ArrayList<>(rig.killed);
        Collections.sort(killed);
        check("strays: reaping terminates exactly the stray miners",
                reaped == 2 && Arrays.asList(4242L, 4343L).equals(killed));
        check("strays: the node, the app and this process are never touched",
                new File(proc, "6000").isDirectory()
                        && new File(proc, "7000").isDirectory()
                        && new File(proc, "1").isDirectory()
                        && new File(proc, "5000").isDirectory());
        check("strays: a second pass finds nothing left",
                MinerSupervisor.reapStrayMiners(rig.cfg, rig.env) == 0
                        && rig.killed.size() == 2);
    }

    private static void fakeProc(File procRoot, long pid, String cmdline)
            throws IOException {
        File d = new File(procRoot, String.valueOf(pid));
        d.mkdirs();
        write(new File(d, "cmdline"), cmdline);
    }

    // ---------------- supervisor (real child processes) ----------------

    static final String S_HAPPY = "#!/bin/sh\n"
            + "echo run >> \"$0.runs\"\n"
            + "echo $$ > \"$0.pid\"\n"
            + "echo \"ARGS: $*\"\n"
            + "echo \"ENV_MINER=$(env | grep -c '^MINER_')\"\n"
            + "echo \"ENV_RUST_LOG=${RUST_LOG:-unset}\"\n"
            + "echo \"[2026-11-14T09:00:00Z INFO  quantus_miner]"
            + " Starting external miner service...\"\n"
            + "echo \"" + M_METRICS_OFF + "\"\n"
            + "echo \"" + M_METRICS + "\"\n"
            + "echo \"[2026-11-14T09:00:01Z INFO  miner_service::quic]"
            + " New block to mine: job 1 (header 0x00)\"\n"
            + "echo \"[2026-11-14T09:00:13Z INFO  miner_service] CPU worker 0"
            + " stopped (new block received): 120000 hashes in 12.00s"
            + " (10.00 KH/s)\"\n"
            + "echo \"[2026-11-14T09:00:13Z INFO  miner_service::quic] Job 1"
            + " solved: 120000 hashes in 12.00s (10.00 KH/s) - submitting"
            + " solution to node\"\n"
            + "exec sleep 30\n";

    static final String S_FAILING = "#!/bin/sh\n"
            + "echo run >> \"$0.runs\"\n"
            + "echo \"Error: failed to read --auth-token-file /x:"
            + " No such file or directory\"\n"
            + "exit 3\n";

    static final String S_STUBBORN = "#!/bin/sh\n"
            + "trap '' TERM\n"
            + "echo run >> \"$0.runs\"\n"
            + "echo $$ > \"$0.pid\"\n"
            + "echo \"stubborn miner up\"\n"
            + "while :; do sleep 1; done\n";

    static final String S_LEAKY = "#!/bin/sh\n"
            + "echo run >> \"$0.runs\"\n"
            + "echo \"token is $(cat \"$5\")\"\n"
            + "echo \"hash is 0X"
            + "E4BE02A913727C01C1A155FD6E807B7C1A4A13ABF37A352B7C9ED4412D127FC3"
            + " end\"\n"
            + "echo \"plain line\"\n"
            + "exec sleep 30\n";

    static final String S_EXPORTER = "#!/bin/sh\n"
            + "echo run >> \"$0.runs\"\n"
            + "echo \"" + M_METRICS + "\"\n"
            + "exec sleep 30\n";

    /** One supervisor test's world: a script as the miner binary,
     *  the two node files, a log, a marker, an empty proc root. */
    static final class Rig {
        final File dir;
        final File bin;
        final File token;
        final File fp;
        final File log;
        final File marker;
        final java.util.concurrent.atomic.AtomicBoolean nodeUp =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        final java.util.concurrent.atomic.AtomicInteger workers =
                new java.util.concurrent.atomic.AtomicInteger(4);
        final java.util.concurrent.atomic.AtomicInteger linkerStarts =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger directStarts =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final List<String> appLog =
                Collections.synchronizedList(new ArrayList<String>());
        final List<Long> killed =
                Collections.synchronizedList(new ArrayList<Long>());
        final MiningLogic.MinerStats stats = new MiningLogic.MinerStats();
        final MinerSupervisor.Config cfg = new MinerSupervisor.Config();
        volatile long selfPid = 1;
        volatile boolean refuseDirect;
        volatile boolean refuseLinker;
        final MinerSupervisor.Env env;

        Rig(String name, String script) throws IOException {
            dir = tempDir("sup-" + name);
            bin = new File(dir, "libquantus_miner.so");
            write(bin, script);
            bin.setExecutable(true, false);
            token = new File(dir, MiningLogic.TOKEN_FILE_NAME);
            fp = new File(dir, MiningLogic.FINGERPRINT_FILE_NAME);
            write(token, TOKEN + "\n");
            write(fp, repeat("ab", 32) + "\n");
            log = new File(dir, "miner.log");
            marker = new File(dir, "miner-used.marker");
            File proc = new File(dir, "proc");
            proc.mkdirs();
            cfg.minerBinary = bin;
            cfg.tokenFile = token;
            cfg.fingerprintFile = fp;
            cfg.logFile = log;
            cfg.markerFile = marker;
            cfg.procRoot = proc;
            cfg.quickExitMs = 400;
            cfg.pollMs = 20;
            cfg.nodeWaitMaxMs = 60_000;
            cfg.stopWaitMs = 600;
            cfg.restartDelaysMs = new long[] {60, 60, 60};
            cfg.stableRunMs = 5000;
            final MinerSupervisor.Launcher real =
                    MinerSupervisor.defaultLauncher();
            cfg.launcher = new MinerSupervisor.Launcher() {
                @Override
                public Process start(List<String> command) throws IOException {
                    boolean linker = "/system/bin/linker64".equals(command.get(0));
                    if (linker) {
                        linkerStarts.incrementAndGet();
                        if (refuseLinker) {
                            throw new IOException("Cannot run program"
                                    + " \"/system/bin/linker64\": error=13,"
                                    + " Permission denied");
                        }
                        // No linker64 on the test host: run what it
                        // would have loaded.
                        return real.start(command.subList(1, command.size()));
                    }
                    directStarts.incrementAndGet();
                    if (refuseDirect) {
                        throw new IOException("Cannot run program \""
                                + command.get(0) + "\": error=13,"
                                + " Permission denied");
                    }
                    return real.start(command);
                }
            };
            env = new MinerSupervisor.Env() {
                @Override
                public long elapsedMs() {
                    return System.nanoTime() / 1_000_000L;
                }

                @Override
                public void log(String message) {
                    appLog.add(message);
                }

                @Override
                public boolean nodeUp() {
                    return nodeUp.get();
                }

                @Override
                public int cpuWorkers() {
                    return workers.get();
                }

                @Override
                public void killStrayProcess(long pid) {
                    killed.add(pid);
                    // The fake proc tree's process "dies".
                    File d = new File(cfg.procRoot, String.valueOf(pid));
                    new File(d, "cmdline").delete();
                    d.delete();
                }

                @Override
                public long selfPid() {
                    return selfPid;
                }
            };
        }

        MinerSupervisor supervisor(MiningLogic.SecretScrubber scrubber) {
            return new MinerSupervisor(cfg, env, scrubber, stats);
        }

        int runs() throws IOException {
            String s = readAll(new File(bin.getPath() + ".runs"));
            return s.isEmpty() ? 0 : s.split("\n").length;
        }

        long pid() throws IOException {
            String s = readAll(new File(bin.getPath() + ".pid")).trim();
            return s.isEmpty() ? -1 : Long.parseLong(s);
        }

        long now() {
            return env.elapsedMs();
        }

        boolean appLogContains(String part) {
            synchronized (appLog) {
                for (String l : appLog) {
                    if (l.contains(part)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    interface Cond {
        boolean ok() throws Exception;
    }

    static boolean eventually(Cond c, long timeoutMs) throws Exception {
        long end = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            if (c.ok()) {
                return true;
            }
            if (System.nanoTime() > end) {
                return false;
            }
            Thread.sleep(15);
        }
    }

    static boolean alive(long pid) {
        if (pid <= 0) {
            return false;
        }
        java.util.Optional<ProcessHandle> h = ProcessHandle.of(pid);
        return h.isPresent() && h.get().isAlive();
    }

    static MiningLogic.Phase phase(Rig rig, MinerSupervisor s) {
        return s.snapshot(rig.now()).phase;
    }

    private static boolean canExecFromTemp() throws Exception {
        File dir = tempDir("exec-probe");
        File probe = new File(dir, "probe.sh");
        write(probe, "#!/bin/sh\nexit 0\n");
        probe.setExecutable(true, false);
        try {
            Process p = new ProcessBuilder(probe.getPath()).start();
            return p.waitFor() == 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static void testSupervisor() throws Exception {
        if (!canExecFromTemp()) {
            skip("supervisor: the temp directory does not allow executing"
                    + " scripts (noexec mount?) - set TMPDIR to one that does");
            return;
        }
        testSupervisorWaitsThenRuns();
        testSupervisorWaitsForFiles();
        testSupervisorNodeWaitFallbacks();
        testSupervisorStop();
        testSupervisorStubbornStop();
        testSupervisorFailingMiner();
        testSupervisorLinkerFallback();
        testSupervisorLinkerUnusable();
        testSupervisorCannotSpawn();
        testSupervisorBinaryMissing();
        testSupervisorScrubsOutput();
        testSupervisorManualRestart();
        testSupervisorExporterWarning();
        testSupervisorReplacesLeftover();
        testSupervisorThermalCarryOver();
        testSupervisorCapCarryOver();
        testSupervisorCapDuringStart();
        testSupervisorCapReadWindow();
        testSupervisorThreadCannotStart();
    }

    private static void testSupervisorThermalCarryOver() throws Exception {
        // A governor that latched its stop on an earlier node
        // process of the session is adopted by the next supervisor:
        // the miner never starts, and the snapshot names the heat.
        final Rig rig = new Rig("thermal", S_HAPPY);
        rig.nodeUp.set(true);
        final MiningLogic.ThermalGovernor gov =
                new MiningLogic.ThermalGovernor();
        gov.onStatus(MiningLogic.THERMAL_SEVERE, 2, 0);
        check("thermal carry-over: the governor latched its stop",
                gov.stopped());
        final MinerSupervisor s = rig.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        s.setThermalGovernor(gov);
        s.start();
        check("thermal carry-over: the snapshot names the heat",
                eventually(new Cond() {
                    @Override
                    public boolean ok() {
                        return s.snapshot(rig.now()).thermalStopped;
                    }
                }, 5000));
        Thread.sleep(400);
        check("thermal carry-over: no miner was started",
                rig.runs() == 0);
        check("thermal carry-over: the supervisor ended by itself",
                s.awaitTermination(5000));
        s.requestStop("test done");
    }

    // ---------------- v4.1.0: thermal cap at a start (FC-1, FC-7) ----------------

    /** The "ARGS:" lines of the miner log, one per miner process. */
    private static List<String> argLines(Rig rig) throws IOException {
        List<String> out = new ArrayList<>();
        for (String l : readAll(rig.log).split("\n")) {
            if (l.startsWith("ARGS: ")) {
                out.add(l);
            }
        }
        return out;
    }

    private static void testSupervisorCapCarryOver() throws Exception {
        // Final check FC-7: nothing showed that a cap a governor
        // already holds decides the worker count of the next start.
        // The same rig is run twice — with an untouched governor and
        // with one whose cap came down on an earlier node process —
        // so the difference between the two runs is the cap alone.
        final Rig free = new Rig("cap-free", S_HAPPY);
        free.workers.set(4);
        final MinerSupervisor a = free.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        a.setThermalGovernor(new MiningLogic.ThermalGovernor());
        a.start();
        check("cap carry-over: (control) an untouched governor leaves the"
                        + " setting's 4 workers",
                eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        List<String> args = argLines(free);
                        return args.size() == 1 && args.get(0).contains(
                                " --cpu-workers 4 ");
                    }
                }, 8000)
                        && a.snapshot(free.now()).cpuWorkers == 4
                        && a.runningWorkers() == 4);
        a.requestStop("test over");
        check("cap carry-over: (cleanup) stops", a.awaitTermination(8000));

        final Rig rig = new Rig("cap-held", S_HAPPY);
        rig.workers.set(4);
        final MiningLogic.ThermalGovernor gov =
                new MiningLogic.ThermalGovernor();
        gov.onStatus(MiningLogic.THERMAL_MODERATE, 4, 0);
        check("cap carry-over: (premise) the governor holds a cap of 2",
                gov.cap() == 2 && !gov.stopped());
        final MinerSupervisor s = rig.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        s.setThermalGovernor(gov);
        s.start();
        check("cap carry-over: the miner is started with the cap's 2 workers,"
                        + " not the setting's 4",
                eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        List<String> args = argLines(rig);
                        return args.size() == 1 && args.get(0).contains(
                                " --cpu-workers 2 ");
                    }
                }, 8000));
        MiningLogic.MinerSnapshot snap = s.snapshot(rig.now());
        check("cap carry-over: the snapshot and the running count say 2",
                snap.cpuWorkers == 2 && snap.thermalCap == 2
                        && !snap.thermalStopped && s.runningWorkers() == 2);
        check("cap carry-over: the app log names the capped count",
                rig.appLogContains("cpu-workers 2")
                        && !rig.appLogContains("cpu-workers 4"));
        check("cap carry-over: a miner within the cap is left alone",
                gov.apply(MiningLogic.THERMAL_MODERATE, 4, s).action
                        == MiningLogic.ThermalAction.NONE && rig.runs() == 1);
        // The setting changes; the cap still decides.
        rig.workers.set(8);
        s.requestRestart();
        check("cap carry-over: a restart with a larger setting stays at the"
                        + " cap",
                eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        List<String> args = argLines(rig);
                        return args.size() == 2 && args.get(1).contains(
                                " --cpu-workers 2 ");
                    }
                }, 8000));
        rig.workers.set(1);
        s.requestRestart();
        check("cap carry-over: a setting below the cap is used as it is",
                eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        List<String> args = argLines(rig);
                        return args.size() == 3 && args.get(2).contains(
                                " --cpu-workers 1 ");
                    }
                }, 8000));
        s.requestStop("test over");
        check("cap carry-over: (cleanup) stops", s.awaitTermination(8000));
    }

    private static void testSupervisorCapDuringStart() throws Exception {
        // Final check FC-1 / audit L-1: a status that arrives while a
        // start is in flight. The launcher is held inside its spawn,
        // so the supervisor sits exactly between "published STARTING
        // with its worker count" and "process exists".
        final Rig rig = new Rig("cap-inflight", S_HAPPY);
        rig.workers.set(4);
        final java.util.concurrent.CountDownLatch entered =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        final MinerSupervisor.Launcher inner = rig.cfg.launcher;
        final java.util.concurrent.atomic.AtomicInteger starts =
                new java.util.concurrent.atomic.AtomicInteger();
        rig.cfg.launcher = new MinerSupervisor.Launcher() {
            @Override
            public Process start(List<String> command) throws IOException {
                if (starts.incrementAndGet() == 1) {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        throw new IOException("interrupted");
                    }
                }
                return inner.start(command);
            }
        };
        final MiningLogic.ThermalGovernor gov =
                new MiningLogic.ThermalGovernor();
        final MinerSupervisor s = rig.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        s.setThermalGovernor(gov);
        s.start();
        check("cap during start: (setup) the first start is in flight",
                entered.await(8, java.util.concurrent.TimeUnit.SECONDS));
        check("cap during start: a start in flight is counted with its"
                        + " worker count",
                phase(rig, s) == MiningLogic.Phase.STARTING
                        && s.runningWorkers() == 4);
        MiningLogic.ThermalDecision d =
                gov.apply(MiningLogic.THERMAL_MODERATE, 4, s);
        check("cap during start: the status reduces the start in flight",
                d.action == MiningLogic.ThermalAction.REDUCE && d.cap == 2);
        release.countDown();
        check("cap during start: the miner comes back with the cap's 2"
                        + " workers",
                eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        List<String> args = argLines(rig);
                        return !args.isEmpty()
                                && args.get(args.size() - 1).contains(
                                " --cpu-workers 2 ")
                                && phase(rig, s) == MiningLogic.Phase.RUNNING
                                && s.runningWorkers() == 2;
                    }
                }, 10000));
        check("cap during start: exactly one restart did it",
                starts.get() == 2);
        s.requestStop("test over");
        check("cap during start: (cleanup) stops", s.awaitTermination(8000));
    }

    private static void testSupervisorCapReadWindow() throws Exception {
        // Audit L-1: the window FC-1 closes - a status that lands
        // between a start's cap read and the publication of its
        // worker count. The governor's test seam runs right after
        // the cap was read; from there another thread delivers
        // MODERATE and is given time to finish. With the cap read
        // and the publication under one supervisor lock that thread
        // cannot finish in the window: it lowers the cap and then
        // waits for the lock to ask what is running, sees this
        // start's 4 and reduces it. With the cap read outside the
        // lock (v4.0.0) it does finish there - it finds nothing
        // running and asks for nothing - and the start then goes
        // out with the 4 it read before the cap came down.
        final Rig rig = new Rig("cap-window", S_HAPPY);
        rig.workers.set(4);
        final MiningLogic.ThermalGovernor gov =
                new MiningLogic.ThermalGovernor();
        final MinerSupervisor s = rig.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        s.setThermalGovernor(gov);
        final java.util.concurrent.atomic.AtomicInteger capReads =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean finishedInWindow =
                new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicReference<
                MiningLogic.ThermalDecision> decision =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.CountDownLatch windowClosed =
                new java.util.concurrent.CountDownLatch(1);
        gov.afterCapReadForTest = new Runnable() {
            @Override
            public void run() {
                if (capReads.incrementAndGet() != 1) {
                    return;
                }
                Thread status = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        decision.set(gov.apply(
                                MiningLogic.THERMAL_MODERATE, 4, s));
                    }
                }, "test-thermal-status");
                status.setDaemon(true);
                status.start();
                try {
                    status.join(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                finishedInWindow.set(!status.isAlive());
                windowClosed.countDown();
            }
        };
        s.start();
        check("cap read window: (setup) a status was delivered right after"
                        + " the first start read the cap",
                windowClosed.await(10, java.util.concurrent.TimeUnit.SECONDS)
                        && gov.cap() == 2);
        check("cap read window: the status could not be dealt with inside"
                        + " the window - the start holds the supervisor's"
                        + " lock from the cap read to the publication",
                !finishedInWindow.get());
        check("cap read window: the miner ends up with the cap's 2 workers",
                eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        List<String> args = argLines(rig);
                        return !args.isEmpty()
                                && args.get(args.size() - 1).contains(
                                " --cpu-workers 2 ")
                                && phase(rig, s) == MiningLogic.Phase.RUNNING
                                && s.runningWorkers() == 2;
                    }
                }, 10000));
        MiningLogic.ThermalDecision d = decision.get();
        check("cap read window: because the status saw the start's 4 and"
                        + " reduced it (" + (d == null ? "no decision"
                        : d.action + ", cap " + d.cap) + ")",
                d != null && d.action == MiningLogic.ThermalAction.REDUCE
                        && d.cap == 2);
        check("cap read window: no miner is left running above the cap",
                s.snapshot(rig.now()).cpuWorkers == 2
                        && s.snapshot(rig.now()).thermalCap == 2);
        gov.afterCapReadForTest = null;
        s.requestStop("test over");
        check("cap read window: (cleanup) stops", s.awaitTermination(8000));
    }

    // ---------------- v4.1.0: a supervisor thread that cannot start (FC-2) ----------------

    private static void testSupervisorThreadCannotStart() throws Exception {
        final Rig rig = new Rig("nothread", S_HAPPY);
        rig.cfg.threads = new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "miner-supervisor") {
                    @Override
                    public synchronized void start() {
                        // What Thread.start() throws when the system
                        // has no thread left to give.
                        throw new OutOfMemoryError(
                                "unable to create native thread (test)");
                    }
                };
            }
        };
        final MinerSupervisor s = rig.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        Throwable thrown = null;
        try {
            s.start();
        } catch (Throwable t) {
            thrown = t;
        }
        check("thread failure: start() does not throw into its caller"
                + " (thrown: " + thrown + ")", thrown == null);
        MiningLogic.MinerSnapshot snap = s.snapshot(rig.now());
        check("thread failure: the status says the miner could not start",
                snap.phase == MiningLogic.Phase.START_FAILED);
        check("thread failure: the app log names the error class and that"
                        + " the node is not affected",
                rig.appLogContains("supervisor thread could not be started"
                        + " (java.lang.OutOfMemoryError)")
                        && rig.appLogContains("The node is not affected"));
        check("thread failure: the supervisor is finished, nothing to wait"
                        + " for",
                s.awaitTermination(50) && s.runningWorkers() == 0);
        Throwable later = null;
        try {
            s.requestRestart();
            s.requestThermalStop();
            s.requestStop("node process exited");
            s.start();
        } catch (Throwable t) {
            later = t;
        }
        Thread.sleep(150);
        check("thread failure: later calls are harmless and start nothing"
                        + " (thrown: " + later + ")",
                later == null && rig.runs() == 0
                        && rig.directStarts.get() == 0
                        && s.snapshot(rig.now()).phase
                        == MiningLogic.Phase.START_FAILED);

        final Rig rig2 = new Rig("nofactory", S_HAPPY);
        rig2.cfg.threads = new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                throw new IllegalStateException("no threads here (test)");
            }
        };
        final MinerSupervisor s2 = rig2.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        Throwable thrown2 = null;
        try {
            s2.start();
        } catch (Throwable t) {
            thrown2 = t;
        }
        check("thread failure: a failing thread factory is contained the"
                        + " same way",
                thrown2 == null && s2.snapshot(rig2.now()).phase
                        == MiningLogic.Phase.START_FAILED
                        && s2.awaitTermination(50)
                        && rig2.appLogContains(
                        "java.lang.IllegalStateException"));

        // The seam itself changes nothing when the thread starts.
        final Rig rig3 = new Rig("factory-ok", S_HAPPY);
        rig3.cfg.threads = new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "miner-supervisor");
            }
        };
        final MinerSupervisor s3 = rig3.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        s3.start();
        check("thread failure: (control) with a thread the miner runs",
                eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        return phase(rig3, s3) == MiningLogic.Phase.RUNNING
                                && rig3.runs() == 1;
                    }
                }, 8000));
        s3.requestStop("test over");
        check("thread failure: (cleanup) stops", s3.awaitTermination(8000));
    }

    private static void testSupervisorWaitsThenRuns() throws Exception {
        final Rig rig = new Rig("run", S_HAPPY);
        rig.nodeUp.set(false);
        final MinerSupervisor s = rig.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        s.start();
        check("supervisor: with the node not up yet it waits for the node",
                eventually(new Cond() {
                    @Override
                    public boolean ok() {
                        return phase(rig, s) == MiningLogic.Phase.WAITING_NODE;
                    }
                }, 5000));
        Thread.sleep(250);
        check("supervisor: no miner is started while the node is not up",
                rig.runs() == 0
                        && phase(rig, s) == MiningLogic.Phase.WAITING_NODE);
        rig.nodeUp.set(true);
        check("supervisor: once the node is up the miner runs",
                eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        return phase(rig, s) == MiningLogic.Phase.RUNNING
                                && rig.runs() == 1;
                    }
                }, 8000));
        check("supervisor: the marker is written before the first spawn",
                rig.marker.isFile());
        final String wantArgs = "ARGS: serve --node-addr 127.0.0.1:9833"
                + " --auth-token-file " + rig.token.getPath()
                + " --tls-cert-sha256-file " + rig.fp.getPath()
                + " --cpu-workers 4 --gpu-devices 0";
        check("supervisor: the child received exactly the fixed arguments",
                eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        return readAll(rig.log).contains(wantArgs + "\n");
                    }
                }, 8000));
        check("supervisor: the miner log holds the miner's lines",
                eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        return readAll(rig.log).contains(
                                "submitting solution to node\n");
                    }
                }, 8000));
        String logText = readAll(rig.log);
        if (System.getenv("MINER_AUTH_TOKEN") != null
                && System.getenv("MINER_NODE_ADDR") != null
                && System.getenv("RUST_LOG") != null) {
            check("supervisor: MINER_* variables do not reach the miner",
                    logText.contains("ENV_MINER=0\n"));
            check("supervisor: RUST_LOG is left to the miner's own default",
                    logText.contains("ENV_RUST_LOG=unset\n"));
        } else {
            // Without the variables in THIS process's environment the
            // two checks would pass without proving anything.
            skip("supervisor: child environment (run through"
                    + " app/tests/run-mining-tests.sh, which sets MINER_*"
                    + " and RUST_LOG for this check)");
        }
        MiningLogic.MinerSnapshot snap = s.snapshot(rig.now());
        check("supervisor: the lines feed the session statistics",
                snap.stats.jobsReceived == 1
                        && snap.stats.solutionsSubmitted == 1
                        && snap.stats.hashrateHps == 10000.0
                        && snap.stats.lastJobElapsedMs >= 0);
        check("supervisor: a build without the exporter raises no warning",
                !snap.stats.metricsExporterActive
                        && !rig.appLogContains("metrics"));
        check("supervisor: the snapshot reports the worker count and method",
                snap.cpuWorkers == 4 && !snap.viaLinker
                        && snap.lastExitCode == null
                        && snap.restartsTotal == 0);
        check("supervisor: the snapshot carries the log's last lines",
                !snap.logTail.isEmpty() && snap.logTail.get(
                        snap.logTail.size() - 1).contains(
                        "submitting solution to node"));
        check("supervisor: the launch is noted in the app log, no secret in it",
                rig.appLogContains("miner launch: " + rig.bin.getPath()
                        + " serve --node-addr 127.0.0.1:9833")
                        && !rig.appLogContains(TOKEN)
                        && !rig.appLogContains(VALID_CORE));
        s.requestStop("test over");
        check("supervisor: (cleanup) stops", s.awaitTermination(8000));
    }

    private static void testSupervisorWaitsForFiles() throws Exception {
        final Rig rig = new Rig("files", S_HAPPY);
        rig.token.delete();
        rig.fp.delete();
        final MinerSupervisor s = rig.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        s.start();
        check("supervisor: without the token file it waits and says which",
                eventually(new Cond() {
                    @Override
                    public boolean ok() {
                        MiningLogic.MinerSnapshot m = s.snapshot(rig.now());
                        return m.phase == MiningLogic.Phase.WAITING_FILES
                                && m.files
                                == MiningLogic.FilesStatus.TOKEN_MISSING;
                    }
                }, 5000));
        write(rig.token, TOKEN + "\n");
        check("supervisor: then it waits for the fingerprint file",
                eventually(new Cond() {
                    @Override
                    public boolean ok() {
                        MiningLogic.MinerSnapshot m = s.snapshot(rig.now());
                        return m.phase == MiningLogic.Phase.WAITING_FILES
                                && m.files
                                == MiningLogic.FilesStatus.FINGERPRINT_MISSING;
                    }
                }, 5000));
        Thread.sleep(200);
        check("supervisor: no miner is started while a file is missing",
                rig.runs() == 0);
        write(rig.fp, repeat("ab", 32) + "\n");
        check("supervisor: with both files in place the miner runs",
                eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        return phase(rig, s) == MiningLogic.Phase.RUNNING
                                && rig.runs() == 1;
                    }
                }, 8000));
        s.requestStop("test over");
        check("supervisor: (cleanup) stops", s.awaitTermination(8000));
    }

    private static void testSupervisorNodeWaitFallbacks() throws Exception {
        final Rig a = new Rig("fallback-time", S_HAPPY);
        a.nodeUp.set(false);
        a.cfg.nodeWaitMaxMs = 300;
        final MinerSupervisor sa = a.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        sa.start();
        check("supervisor: a node that never reports up is not waited on forever",
                eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        return phase(a, sa) == MiningLogic.Phase.RUNNING
                                && a.runs() == 1;
                    }
                }, 8000));
        sa.requestStop("test over");
        check("supervisor: (cleanup) stops", sa.awaitTermination(8000));

        final Rig b = new Rig("fallback-line", S_HAPPY);
        b.nodeUp.set(false);
        final MinerSupervisor sb = b.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        sb.start();
        Thread.sleep(150);
        boolean heldBack = b.runs() == 0;
        b.stats.onNodeLine(N_LISTEN, b.now());
        check("supervisor: the node's 'listening' line is enough to start",
                heldBack && eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        return phase(b, sb) == MiningLogic.Phase.RUNNING
                                && b.runs() == 1;
                    }
                }, 8000));
        sb.requestStop("test over");
        check("supervisor: (cleanup) stops", sb.awaitTermination(8000));
    }

    private static void testSupervisorStop() throws Exception {
        final Rig rig = new Rig("stop", S_HAPPY);
        final MinerSupervisor s = rig.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        s.start();
        check("supervisor: (setup) running", eventually(new Cond() {
            @Override
            public boolean ok() throws Exception {
                return phase(rig, s) == MiningLogic.Phase.RUNNING
                        && alive(rig.pid());
            }
        }, 8000));
        final long pid = rig.pid();
        // Past the quick-exit window, so the exit below is judged
        // as an ordinary one.
        Thread.sleep(rig.cfg.quickExitMs + 150);
        s.requestStop("node stopping");
        check("supervisor: a stop request ends the miner process",
                eventually(new Cond() {
                    @Override
                    public boolean ok() {
                        return !alive(pid);
                    }
                }, 5000));
        check("supervisor: the supervisor thread ends after the stop",
                s.awaitTermination(5000));
        check("supervisor: after a stop it is idle and starts nothing more",
                phase(rig, s) == MiningLogic.Phase.IDLE && rig.runs() == 1);
        s.requestStop("again");
        s.requestRestart();
        Thread.sleep(200);
        check("supervisor: requests after the stop are ignored",
                phase(rig, s) == MiningLogic.Phase.IDLE && rig.runs() == 1);
        check("supervisor: a requested stop is not counted as a failure",
                s.snapshot(rig.now()).restartsTotal == 0
                        && s.snapshot(rig.now()).consecutiveFailures == 0
                        && s.snapshot(rig.now()).lastExitCode == null);

        final Rig early = new Rig("stop-early", S_HAPPY);
        early.nodeUp.set(false);
        final MinerSupervisor se = early.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        se.start();
        Thread.sleep(100);
        se.requestStop("node stopped before the miner started");
        check("supervisor: a stop while still waiting ends it without a spawn",
                se.awaitTermination(5000) && early.runs() == 0);
    }

    private static void testSupervisorStubbornStop() throws Exception {
        final Rig rig = new Rig("stubborn", S_STUBBORN);
        final MinerSupervisor s = rig.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        s.start();
        check("supervisor: (setup) stubborn miner running",
                eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        return phase(rig, s) == MiningLogic.Phase.RUNNING
                                && alive(rig.pid());
                    }
                }, 8000));
        final long pid = rig.pid();
        s.requestStop("node stopping");
        Thread.sleep(250);
        check("supervisor: (premise) this miner ignores a plain destroy",
                alive(pid));
        check("supervisor: a miner that ignores destroy is killed forcibly",
                eventually(new Cond() {
                    @Override
                    public boolean ok() {
                        return !alive(pid);
                    }
                }, 6000));
        check("supervisor: and the supervisor still ends, bounded",
                s.awaitTermination(8000));
        check("supervisor: the escalation is on the record",
                rig.appLogContains("destroyForcibly"));
    }

    private static void testSupervisorFailingMiner() throws Exception {
        final Rig rig = new Rig("failing", S_FAILING);
        final MinerSupervisor s = rig.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        s.start();
        check("supervisor: a miner that always fails ends in GAVE_UP",
                eventually(new Cond() {
                    @Override
                    public boolean ok() {
                        return phase(rig, s) == MiningLogic.Phase.GAVE_UP;
                    }
                }, 15000));
        MiningLogic.MinerSnapshot m = s.snapshot(rig.now());
        check("supervisor: it was started 5 times in all - once, once via"
                        + " linker64, and three restarts",
                rig.runs() == 5 && rig.linkerStarts.get() == 1
                        && rig.directStarts.get() == 4
                        && m.restartsTotal == 3);
        check("supervisor: the exit code is kept for the status",
                Integer.valueOf(3).equals(m.lastExitCode));
        Thread.sleep(400);
        check("supervisor: after giving up nothing is started again",
                rig.runs() == 5
                        && phase(rig, s) == MiningLogic.Phase.GAVE_UP);
        check("supervisor: the give-up is a WARN line in the app log",
                rig.appLogContains("WARN miner: giving up"));
        s.requestRestart();
        check("supervisor: a manual restart tries the whole sequence again",
                eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        return phase(rig, s) == MiningLogic.Phase.GAVE_UP
                                && rig.runs() == 10;
                    }
                }, 15000));
        s.requestStop("test over");
        check("supervisor: a stop ends a supervisor that had given up",
                s.awaitTermination(5000)
                        && phase(rig, s) == MiningLogic.Phase.IDLE);
    }

    private static void testSupervisorLinkerFallback() throws Exception {
        final Rig rig = new Rig("linker", S_HAPPY);
        rig.refuseDirect = true;
        final MinerSupervisor s = rig.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        s.start();
        check("supervisor: direct execution refused -> one retry via linker64",
                eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        return phase(rig, s) == MiningLogic.Phase.RUNNING
                                && rig.runs() == 1;
                    }
                }, 8000));
        check("supervisor: the snapshot says the miner runs via linker64",
                s.snapshot(rig.now()).viaLinker
                        && rig.linkerStarts.get() == 1
                        && rig.directStarts.get() == 1);
        s.requestRestart();
        check("supervisor: later starts go straight to linker64",
                eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        return phase(rig, s) == MiningLogic.Phase.RUNNING
                                && rig.runs() == 2
                                && rig.linkerStarts.get() == 2;
                    }
                }, 8000) && rig.directStarts.get() == 1);
        s.requestStop("test over");
        check("supervisor: (cleanup) stops", s.awaitTermination(8000));
    }

    private static void testSupervisorLinkerUnusable() throws Exception {
        // The miner starts directly and fails by itself (exit 3); the
        // one linker64 retry cannot even be spawned. That says nothing
        // against the direct path, which works: the miner's own
        // failure is what the status must show.
        final Rig rig = new Rig("linker-unusable", S_FAILING);
        rig.refuseLinker = true;
        final MinerSupervisor s = rig.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        s.start();
        check("supervisor: a failing miner whose linker64 retry cannot be"
                        + " spawned still ends in GAVE_UP, not START_FAILED",
                eventually(new Cond() {
                    @Override
                    public boolean ok() {
                        return phase(rig, s) == MiningLogic.Phase.GAVE_UP;
                    }
                }, 15000));
        MiningLogic.MinerSnapshot m = s.snapshot(rig.now());
        check("supervisor: it ran directly four times - once and three"
                        + " restarts - with one refused linker64 attempt",
                rig.runs() == 4 && rig.directStarts.get() == 4
                        && rig.linkerStarts.get() == 1
                        && m.restartsTotal == 3 && !m.viaLinker);
        check("supervisor: and the miner's own exit code is what is shown",
                Integer.valueOf(3).equals(m.lastExitCode));
        s.requestStop("test over");
        check("supervisor: (cleanup) stops", s.awaitTermination(8000));
    }

    private static void testSupervisorCannotSpawn() throws Exception {
        final Rig rig = new Rig("nospawn", S_HAPPY);
        rig.refuseDirect = true;
        rig.refuseLinker = true;
        final MinerSupervisor s = rig.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        s.start();
        check("supervisor: refused both ways -> START_FAILED",
                eventually(new Cond() {
                    @Override
                    public boolean ok() {
                        return phase(rig, s) == MiningLogic.Phase.START_FAILED;
                    }
                }, 8000));
        Thread.sleep(300);
        check("supervisor: exactly two attempts were made, then none",
                rig.linkerStarts.get() == 1 && rig.directStarts.get() == 1
                        && rig.runs() == 0
                        && phase(rig, s) == MiningLogic.Phase.START_FAILED);
        s.requestStop("test over");
        check("supervisor: (cleanup) stops", s.awaitTermination(8000));
    }

    private static void testSupervisorBinaryMissing() throws Exception {
        final Rig rig = new Rig("nobin", S_HAPPY);
        rig.bin.delete();
        final MinerSupervisor s = rig.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        s.start();
        check("supervisor: no miner binary -> BINARY_MISSING, nothing spawned",
                eventually(new Cond() {
                    @Override
                    public boolean ok() {
                        return phase(rig, s) == MiningLogic.Phase.BINARY_MISSING;
                    }
                }, 8000) && rig.linkerStarts.get() == 0);
        s.requestStop("test over");
        check("supervisor: (cleanup) stops", s.awaitTermination(8000));
    }

    private static void testSupervisorScrubsOutput() throws Exception {
        final Rig rig = new Rig("leaky", S_LEAKY);
        final MinerSupervisor s = rig.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        s.start();
        check("supervisor: (setup) the leaky miner has printed its lines",
                eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        return readAll(rig.log).contains("plain line\n");
                    }
                }, 8000));
        String logText = readAll(rig.log);
        check("supervisor: a token the miner prints never reaches the log",
                !logText.contains(TOKEN)
                        && logText.contains("token is <redacted>\n"));
        check("supervisor: an inner hash the miner prints never reaches the log",
                !logText.toLowerCase(java.util.Locale.ROOT).contains(VALID_CORE)
                        && logText.contains("hash is <redacted> end\n"));
        boolean tailLeak = false;
        for (String l : s.snapshot(rig.now()).logTail) {
            if (l.contains(TOKEN) || l.toLowerCase(java.util.Locale.ROOT)
                    .contains(VALID_CORE)) {
                tailLeak = true;
            }
        }
        check("supervisor: nor the on-screen tail", !tailLeak);
        boolean appLeak = false;
        synchronized (rig.appLog) {
            for (String l : rig.appLog) {
                if (l.contains(TOKEN) || l.toLowerCase(java.util.Locale.ROOT)
                        .contains(VALID_CORE)) {
                    appLeak = true;
                }
            }
        }
        check("supervisor: nor the app log", !appLeak);
        s.requestStop("test over");
        check("supervisor: (cleanup) stops", s.awaitTermination(8000));
    }

    private static void testSupervisorManualRestart() throws Exception {
        final Rig rig = new Rig("restart", S_HAPPY);
        final MinerSupervisor s = rig.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        s.start();
        check("supervisor: (setup) running with 4 workers",
                eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        return phase(rig, s) == MiningLogic.Phase.RUNNING
                                && alive(rig.pid())
                                && readAll(rig.log).contains("--cpu-workers 4 ");
                    }
                }, 8000));
        final long firstPid = rig.pid();
        // Let the first process outlive the quick-exit window, so
        // its end below is judged as an ordinary exit, not as a
        // start that never got going.
        Thread.sleep(rig.cfg.quickExitMs + 150);
        rig.workers.set(2);
        check("supervisor: (premise) the running miner lags the new setting",
                MiningLogic.minerRestartPending(s.snapshot(rig.now()), 2));
        s.requestRestart();
        check("supervisor: a restart request replaces the miner process",
                eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        return !alive(firstPid) && rig.runs() == 2
                                && phase(rig, s) == MiningLogic.Phase.RUNNING;
                    }
                }, 8000));
        check("supervisor: the new process runs with the new worker count",
                eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        return readAll(rig.log).contains("--cpu-workers 2 ")
                                && s.snapshot(rig.now()).cpuWorkers == 2;
                    }
                }, 8000));
        check("supervisor: a requested restart is not counted as a failure",
                s.snapshot(rig.now()).restartsTotal == 0
                        && s.snapshot(rig.now()).consecutiveFailures == 0);
        check("supervisor: the session counters carried over the restart",
                eventually(new Cond() {
                    @Override
                    public boolean ok() {
                        return s.snapshot(rig.now()).stats.jobsReceived == 2;
                    }
                }, 8000));
        s.requestStop("test over");
        check("supervisor: (cleanup) stops", s.awaitTermination(8000));
    }

    private static void testSupervisorExporterWarning() throws Exception {
        final Rig rig = new Rig("exporter", S_EXPORTER);
        final MinerSupervisor s = rig.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        s.start();
        check("supervisor: a miner announcing a live metrics endpoint is"
                        + " flagged with a WARN line",
                eventually(new Cond() {
                    @Override
                    public boolean ok() {
                        return s.snapshot(rig.now()).stats.metricsExporterActive
                                && rig.appLogContains(
                                "WARN miner: the bundled miner announced");
                    }
                }, 8000));
        s.requestStop("test over");
        check("supervisor: (cleanup) stops", s.awaitTermination(8000));
    }

    private static void testSupervisorReplacesLeftover() throws Exception {
        final Rig old = new Rig("leftover-old", S_STUBBORN);
        old.cfg.stopWaitMs = 60_000; // its own escalation is far away
        final MinerSupervisor first = old.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        first.start();
        check("supervisor: (setup) a first miner is running",
                eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        return phase(old, first) == MiningLogic.Phase.RUNNING
                                && alive(old.pid());
                    }
                }, 8000));
        final long oldPid = old.pid();
        first.requestStop("its node process exited");
        Thread.sleep(200);
        check("supervisor: (premise) the stopped miner is still exiting",
                alive(oldPid));
        final Rig fresh = new Rig("leftover-new", S_HAPPY);
        final MinerSupervisor second = fresh.supervisor(
                MiningLogic.SecretScrubber.of(VALID, null));
        second.start();
        check("supervisor: a new session first removes the leftover miner",
                eventually(new Cond() {
                    @Override
                    public boolean ok() {
                        return !alive(oldPid);
                    }
                }, 8000));
        check("supervisor: and then runs its own",
                eventually(new Cond() {
                    @Override
                    public boolean ok() throws Exception {
                        return phase(fresh, second) == MiningLogic.Phase.RUNNING
                                && fresh.runs() == 1;
                    }
                }, 8000));
        check("supervisor: the first supervisor ends once its miner is gone",
                first.awaitTermination(8000) && old.runs() == 1);
        second.requestStop("test over");
        check("supervisor: (cleanup) stops", second.awaitTermination(8000));
    }

    // ---------------- v4.1.0: storage — what the resync may delete ----------------

    private static void testStorageRule() {
        final File files = new File("/data/user/0/app.quantusnode.node/files");
        final File chainDir = new File(files, "node/chains/mainnet");
        // Audit H-1: the node's database mode is Auto - ParityDB in
        // paritydb/ on a fresh install, an existing RocksDB in db/.
        check("storage rule: the chain database directories are"
                        + " <files>/node/chains/mainnet/db and .../paritydb,"
                        + " and only those",
                StorageLogic.chainDbDirs(files, "mainnet").equals(
                        Arrays.asList(new File(chainDir, "db"),
                                new File(chainDir, "paritydb")))
                        && "/data/user/0/app.quantusnode.node/files/node/chains/mainnet/db"
                        .equals(new File(chainDir, "db").getPath())
                        && "/data/user/0/app.quantusnode.node/files/node/chains/mainnet/paritydb"
                        .equals(new File(chainDir, "paritydb").getPath()));

        String[] yes = {
                "node/chains/mainnet/db",
                "node/chains/mainnet/db/full",
                "node/chains/mainnet/db/full/000123.sst",
                "node/chains/mainnet/db/full/CURRENT",
                "node/chains/mainnet/db/a/b/c/d",
                "node/chains/mainnet/db/network",
                "node/chains/mainnet/paritydb",
                "node/chains/mainnet/paritydb/full",
                "node/chains/mainnet/paritydb/full/metadata",
                "node/chains/mainnet/paritydb/full/index_00_16",
                "node/chains/mainnet/paritydb/a/b/c/d",
        };
        List<String> wrongNo = new ArrayList<>();
        for (String rel : yes) {
            if (!StorageLogic.isResyncDeletable(files, "mainnet",
                    new File(files, rel))) {
                wrongNo.add(rel);
            }
        }
        check("storage rule: the database directories (db and paritydb) and"
                + " everything inside them may be deleted " + wrongNo,
                wrongNo.isEmpty());

        List<File> no = new ArrayList<>();
        // The node's network key.
        no.add(new File(chainDir, "network"));
        no.add(new File(chainDir, "network/secret_dilithium"));
        // The miner's token and certificate pin, by the names the
        // app itself launches the miner with.
        no.add(MiningLogic.minerTokenFile(new File(files, "node"), "mainnet"));
        no.add(MiningLogic.minerFingerprintFile(
                new File(files, "node"), "mainnet"));
        // Every parent of the database directory.
        no.add(chainDir);
        no.add(new File(files, "node/chains"));
        no.add(new File(files, "node"));
        no.add(files);
        no.add(files.getParentFile());
        no.add(new File("/"));
        // DePIN identity and records.
        no.add(new File(files, "depin"));
        no.add(new File(files, "depin/identity.json"));
        no.add(new File(files, "depin/journal/segment-000001.jsonl"));
        no.add(new File(files, "depin-supervisor"));
        no.add(new File(files, "depin-supervisor/state"));
        // Logs, the daily track file, markers, the pid record.
        no.add(new File(files, "logs/node.log"));
        no.add(new File(files, "logs/miner.log"));
        no.add(DailyTrackStore.file(files));
        no.add(new File(files, "track"));
        no.add(new File(files, "miner-used.marker"));
        no.add(StorageLogic.resetMarkerFile(files));
        no.add(new File(files, "node-process.pid"));
        // Preferences: settings, TrackStore, miner settings, inner hash.
        no.add(new File(files.getParentFile(), "shared_prefs"));
        no.add(new File(files.getParentFile(), "shared_prefs/track.xml"));
        no.add(new File(files.getParentFile(), "shared_prefs/node_settings.xml"));
        // Names that merely resemble the database directory.
        no.add(new File(chainDir, "db2"));
        no.add(new File(chainDir, "db.bak"));
        no.add(new File(chainDir, "dbx/full"));
        no.add(new File(chainDir, "DB"));
        no.add(new File(chainDir, "paritydb2"));
        no.add(new File(chainDir, "paritydb.bak/full"));
        no.add(new File(chainDir, "ParityDb"));
        no.add(new File(chainDir, "parity"));
        no.add(new File(files, "node/paritydb"));
        no.add(new File(files, "node/chains/paritydb"));
        no.add(new File(files, "node/chains/dev/paritydb"));
        no.add(new File(chainDir, "paritydb/../network"));
        no.add(new File(files, "node/db"));
        no.add(new File(files, "node/chains/db"));
        no.add(new File(files, "db"));
        // Another chain, another app, another root.
        no.add(new File(files, "node/chains/dev/db"));
        no.add(new File(files, "node/chains/mainnet2/db"));
        no.add(new File("/data/user/0/app.quantusnode.node/files2/node/chains"
                + "/mainnet/db"));
        no.add(new File("/data/user/0/other.app/files/node/chains/mainnet/db"));
        no.add(new File("/node/chains/mainnet/db"));
        no.add(new File("node/chains/mainnet/db"));
        // Paths that need interpreting are refused, not resolved.
        no.add(new File(chainDir, "db/../network"));
        no.add(new File(chainDir, "db/../network/secret_dilithium"));
        no.add(new File(chainDir, "db/full/../../miner-auth-token"));
        no.add(new File(chainDir, "db/../../../../depin"));
        no.add(new File(chainDir, "db/./full"));
        no.add(new File(chainDir, "./db"));
        no.add(new File(files, "depin/../node/chains/mainnet/db"));
        List<String> wrongYes = new ArrayList<>();
        for (File f : no) {
            if (StorageLogic.isResyncDeletable(files, "mainnet", f)) {
                wrongYes.add(f.getPath());
            }
        }
        check("storage rule: nothing else may be deleted - network key, miner"
                + " files, DePIN, logs, track, preferences, look-alikes,"
                + " other chains, paths with '..' " + wrongYes,
                wrongYes.isEmpty());
        check("storage rule: " + no.size() + " protected paths were asked"
                + " about", no.size() == 53);

        File db = new File(chainDir, "db");
        check("storage rule: missing or odd arguments are refused",
                !StorageLogic.isResyncDeletable(null, "mainnet", db)
                        && !StorageLogic.isResyncDeletable(files, null, db)
                        && !StorageLogic.isResyncDeletable(files, "", db)
                        && !StorageLogic.isResyncDeletable(files, "mainnet", null)
                        && !StorageLogic.isResyncDeletable(files, "..",
                        new File(files, "node/chains/../db"))
                        && !StorageLogic.isResyncDeletable(files, "main/net",
                        new File(files, "node/chains/main/net/db"))
                        && !StorageLogic.isResyncDeletable(
                        new File(files, "node/.."), "mainnet", db)
                        && !StorageLogic.isResyncDeletable(new File("/"),
                        "mainnet", new File("/node/chains/mainnet/db")));
        check("storage rule: the reset marker lives outside the node"
                        + " directory",
                new File(files, "chain-reset.pending").equals(
                        StorageLogic.resetMarkerFile(files)));
    }

    /** Every regular file under root: relative path -> content. */
    private static java.util.Map<String, String> snapshotTree(File root)
            throws IOException {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        java.util.ArrayDeque<File> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            File cur = stack.pop();
            File[] kids = cur.listFiles();
            if (kids == null) {
                continue;
            }
            for (File k : kids) {
                String rel = k.getPath().substring(root.getPath().length() + 1);
                if (java.nio.file.Files.isSymbolicLink(k.toPath())) {
                    out.put(rel, "-> " + java.nio.file.Files
                            .readSymbolicLink(k.toPath()));
                } else if (k.isDirectory()) {
                    out.put(rel + "/", "");
                    stack.push(k);
                } else {
                    out.put(rel, readAll(k));
                }
            }
        }
        return out;
    }

    private static void put(File root, String rel, String content)
            throws IOException {
        File f = new File(root, rel);
        f.getParentFile().mkdirs();
        write(f, content);
    }

    /** A data area shaped like the app's, with a RocksDB-style db. */
    private static File storageWorld(String name) throws IOException {
        File data = tempDir("storage-" + name);
        File files = new File(data, "files");
        put(files, "node/chains/mainnet/db/full/CURRENT", "MANIFEST-000004\n");
        put(files, "node/chains/mainnet/db/full/000007.sst", repeat("s", 4000));
        put(files, "node/chains/mainnet/db/full/000008.log", repeat("l", 1000));
        put(files, "node/chains/mainnet/db/full/deep/er/x", "x");
        put(files, "node/chains/mainnet/db/db_version", "4");
        new File(files, "node/chains/mainnet/db/full/empty-dir").mkdirs();
        put(files, "node/chains/mainnet/network/secret_dilithium",
                repeat("k", 300));
        put(files, "node/chains/mainnet/miner-auth-token", TOKEN + "\n");
        put(files, "node/chains/mainnet/miner-tls-cert-sha256",
                repeat("ab", 32) + "\n");
        put(files, "node/chains/mainnet/paritydb/full/metadata", "pdb");
        put(files, "node/chains/dev/db/full/CURRENT", "other chain");
        put(files, "node/unrelated", "u");
        put(files, "depin/identity.json", "{\"id\":1}");
        put(files, "depin/journal/segment-000001.jsonl", repeat("j", 700));
        put(files, "depin-supervisor/state", "st");
        put(files, "logs/node.log", repeat("n", 2000));
        put(files, "logs/miner.log", repeat("m", 500));
        put(files, "track/daily.jsonl", "{}\n");
        put(files, "miner-used.marker", "m\n");
        put(data, "shared_prefs/track.xml", "<map/>");
        put(data, "shared_prefs/node_settings.xml", "<map>hash</map>");
        return data;
    }

    private static java.util.Map<String, String> withoutPrefix(
            java.util.Map<String, String> m, String prefix) {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        for (java.util.Map.Entry<String, String> e : m.entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** The map without what a reset deletes: db/ and paritydb/. */
    private static java.util.Map<String, String> withoutDb(
            java.util.Map<String, String> m) {
        return withoutPrefix(withoutPrefix(m,
                "files/node/chains/mainnet/db/"),
                "files/node/chains/mainnet/paritydb/");
    }

    private static void testStorageReset() throws Exception {
        final String dbRel = "files/node/chains/mainnet/db/";
        final String pdbRel = "files/node/chains/mainnet/paritydb/";
        File data = storageWorld("reset");
        File files = new File(data, "files");
        java.util.Map<String, String> before = snapshotTree(data);
        check("storage reset: (setup) the world has a database and the things"
                        + " to keep",
                before.containsKey(dbRel + "full/000007.sst")
                        && before.containsKey(pdbRel + "full/metadata")
                        && before.containsKey(
                        "files/node/chains/mainnet/network/secret_dilithium")
                        && before.size() > 30);
        StorageLogic.ResetOutcome out =
                StorageLogic.resetChainDb(files, "mainnet");
        java.util.Map<String, String> after = snapshotTree(data);
        check("storage reset: says DELETED and counts what it removed"
                        + " (db: 5 files + 5 directories; paritydb: 1 file"
                        + " + 2 directories; found "
                        + out.entriesDeleted + ")",
                out.result == StorageLogic.ResetResult.DELETED
                        && out.entriesDeleted == 13 && out.failure == null
                        && out.failureKind == null);
        check("storage reset: both chain database directories are gone",
                !new File(files, "node/chains/mainnet/db").exists()
                        && !new File(files, "node/chains/mainnet/paritydb")
                        .exists());
        check("storage reset: everything else is exactly as it was - network"
                        + " key, miner files, DePIN, logs, track, markers,"
                        + " preferences, the other chain",
                after.equals(withoutDb(before))
                        && after.size() == before.size() - 13);
        check("storage reset: no marker is left after a finished reset",
                !StorageLogic.resetPending(files));
        StorageLogic.ResetOutcome again =
                StorageLogic.resetChainDb(files, "mainnet");
        check("storage reset: with no database there is nothing to delete, and"
                        + " it says so",
                again.result == StorageLogic.ResetResult.NOTHING_TO_DELETE
                        && again.entriesDeleted == 0
                        && snapshotTree(data).equals(after));

        // Audit H-1: what a device this app set up really holds - a
        // ParityDB in paritydb/ and no db/ at all.
        File dataP = storageWorld("paritydb-only");
        File filesP = new File(dataP, "files");
        StorageLogic.resetChainDb(filesP, "mainnet");
        put(filesP, "node/chains/mainnet/paritydb/full/metadata", "pdb");
        put(filesP, "node/chains/mainnet/paritydb/full/index_00_16",
                repeat("i", 2000));
        put(filesP, "node/chains/mainnet/paritydb/full/table_00_1",
                repeat("t", 3000));
        put(filesP, "node/chains/mainnet/paritydb/full/log0", "l");
        java.util.Map<String, String> bP = snapshotTree(dataP);
        check("storage reset (ParityDB only): (setup) there is no db"
                        + " directory, and the figure is the paritydb"
                        + " directory's 5004 bytes",
                !new File(filesP, "node/chains/mainnet/db").exists()
                        && bP.containsKey(pdbRel + "full/table_00_1")
                        && StorageLogic.measure(filesP, dataP, "mainnet")
                        .chainDbBytes == 5004);
        StorageLogic.ResetOutcome oP =
                StorageLogic.resetChainDb(filesP, "mainnet");
        check("storage reset (ParityDB only): the database is found and"
                        + " deleted - 4 files + 2 directories (found "
                        + oP.result + ", " + oP.entriesDeleted + ")",
                oP.result == StorageLogic.ResetResult.DELETED
                        && oP.entriesDeleted == 6
                        && !new File(filesP, "node/chains/mainnet/paritydb")
                        .exists());
        check("storage reset (ParityDB only): everything else is exactly as"
                        + " it was, and no marker is left",
                snapshotTree(dataP).equals(withoutPrefix(bP, pdbRel))
                        && !StorageLogic.resetPending(filesP)
                        && StorageLogic.measure(filesP, dataP, "mainnet")
                        .chainDbBytes == 0);

        // Symbolic links inside the database are removed as links;
        // what they point at is never entered.
        File data2 = storageWorld("links");
        File files2 = new File(data2, "files");
        File db2 = new File(files2, "node/chains/mainnet/db");
        boolean links = true;
        try {
            java.nio.file.Files.createSymbolicLink(
                    new File(db2, "full/to-network").toPath(),
                    new File(files2, "node/chains/mainnet/network").toPath());
            java.nio.file.Files.createSymbolicLink(
                    new File(db2, "to-token").toPath(),
                    new File(files2, "node/chains/mainnet/miner-auth-token")
                            .toPath());
            java.nio.file.Files.createSymbolicLink(
                    new File(db2, "to-depin").toPath(),
                    new File(files2, "depin").toPath());
            java.nio.file.Files.createSymbolicLink(
                    new File(db2, "dangling").toPath(),
                    new File(files2, "no-such-thing").toPath());
        } catch (IOException | UnsupportedOperationException e) {
            links = false;
        }
        if (links) {
            java.util.Map<String, String> b2 = snapshotTree(data2);
            StorageLogic.ResetOutcome o2 =
                    StorageLogic.resetChainDb(files2, "mainnet");
            check("storage reset: links inside the database go, their"
                            + " targets stay untouched",
                    o2.result == StorageLogic.ResetResult.DELETED
                            && o2.entriesDeleted == 17
                            && snapshotTree(data2).equals(withoutDb(b2)));

            // The database directory itself is a link: the link is
            // the entry under the chain directory, and only it goes.
            File data3 = storageWorld("dblink");
            File files3 = new File(data3, "files");
            File db3 = new File(files3, "node/chains/mainnet/db");
            StorageLogic.resetChainDb(files3, "mainnet");
            java.nio.file.Files.createSymbolicLink(db3.toPath(),
                    new File(files3, "depin").toPath());
            java.util.Map<String, String> b3 = snapshotTree(data3);
            StorageLogic.ResetOutcome o3 =
                    StorageLogic.resetChainDb(files3, "mainnet");
            java.util.Map<String, String> want3 =
                    new java.util.TreeMap<>(b3);
            want3.remove("files/node/chains/mainnet/db");
            check("storage reset: a database directory that is a link loses"
                            + " the link only",
                    o3.result == StorageLogic.ResetResult.DELETED
                            && o3.entriesDeleted == 1
                            && snapshotTree(data3).equals(want3)
                            && new File(files3, "depin/identity.json").isFile());

            // A link in a PARENT would send the walk elsewhere: the
            // names are right, the place is not, and nothing is
            // deleted.
            File data4 = storageWorld("parentlink");
            File files4 = new File(data4, "files");
            File elsewhere = new File(data4, "elsewhere");
            put(elsewhere, "db/full/precious", "do not delete");
            File chains4 = new File(files4, "node/chains");
            File real = new File(chains4, "mainnet");
            File moved = new File(chains4, "mainnet-real");
            real.renameTo(moved);
            java.nio.file.Files.createSymbolicLink(real.toPath(),
                    elsewhere.toPath());
            java.util.Map<String, String> b4 = snapshotTree(data4);
            StorageLogic.ResetOutcome o4 =
                    StorageLogic.resetChainDb(files4, "mainnet");
            check("storage reset: a chain directory that resolves elsewhere"
                            + " is refused and nothing is deleted",
                    o4.result == StorageLogic.ResetResult.FAILED
                            && o4.entriesDeleted == 0
                            && o4.failure != null
                            && o4.failure.startsWith("refused")
                            && o4.failureKind
                            == StorageLogic.FailureKind.LOCATION
                            && snapshotTree(data4).equals(b4)
                            && new File(elsewhere, "db/full/precious").isFile());
            // Audit L-2: the refusal comes before the marker is
            // written. A marker left by a reset that deleted nothing
            // would keep every later node start from happening.
            check("storage reset: a refused reset leaves no marker behind"
                            + " (the data area is byte for byte as before),"
                            + " and the failure text carries no path",
                    !StorageLogic.resetPending(files4)
                            && !StorageLogic.resetMarkerFile(files4).exists()
                            && !o4.failure.contains("/"));
            check("storage reset: so the next node start has no pending"
                            + " reset to complete and is not blocked",
                    StorageLogic.completePendingReset(files4, "mainnet")
                            .result
                            == StorageLogic.ResetResult.NOTHING_TO_DELETE
                            && snapshotTree(data4).equals(b4));
            // A marker that was there already is another matter: an
            // earlier reset may be half done, and it stays.
            write(StorageLogic.resetMarkerFile(files4), "started\n");
            StorageLogic.ResetOutcome o4b =
                    StorageLogic.resetChainDb(files4, "mainnet");
            java.util.Map<String, String> a4b = snapshotTree(data4);
            a4b.remove("files/chain-reset.pending");
            check("storage reset: a marker from an earlier, unfinished reset"
                            + " is kept by a refusal - and still nothing is"
                            + " deleted",
                    o4b.result == StorageLogic.ResetResult.FAILED
                            && o4b.entriesDeleted == 0
                            && StorageLogic.resetPending(files4)
                            && a4b.equals(b4));
        } else {
            skip("storage reset: symbolic links (this file system has none)");
        }

        // An interrupted reset: the marker is there, part of the
        // database is gone. Completing it removes the rest.
        File data5 = storageWorld("pending");
        File files5 = new File(data5, "files");
        check("storage reset: without a marker, completing does nothing",
                StorageLogic.completePendingReset(files5, "mainnet").result
                        == StorageLogic.ResetResult.NOTHING_TO_DELETE
                        && new File(files5,
                        "node/chains/mainnet/db/full/CURRENT").isFile());
        java.util.Map<String, String> b5 = snapshotTree(data5);
        write(StorageLogic.resetMarkerFile(files5), "started\n");
        new File(files5, "node/chains/mainnet/db/full/CURRENT").delete();
        StorageLogic.ResetOutcome o5 =
                StorageLogic.completePendingReset(files5, "mainnet");
        check("storage reset: a reset left unfinished is completed - the rest"
                        + " of the database goes, the marker goes, the rest"
                        + " stays",
                o5.result == StorageLogic.ResetResult.DELETED
                        && o5.entriesDeleted == 12
                        && !StorageLogic.resetPending(files5)
                        && snapshotTree(data5).equals(withoutDb(b5)));
        write(StorageLogic.resetMarkerFile(files5), "started\n");
        StorageLogic.ResetOutcome o5b =
                StorageLogic.completePendingReset(files5, "mainnet");
        check("storage reset: a marker with nothing left behind it is simply"
                        + " cleared",
                o5b.result == StorageLogic.ResetResult.DELETED
                        && o5b.entriesDeleted == 0
                        && !StorageLogic.resetPending(files5));

        // An entry that cannot be deleted.
        File data6 = storageWorld("stuck");
        File files6 = new File(data6, "files");
        File locked = new File(files6, "node/chains/mainnet/db/full/deep/er");
        if (locked.setWritable(false, false) && !locked.canWrite()) {
            java.util.Map<String, String> b6 = snapshotTree(data6);
            StorageLogic.ResetOutcome o6 =
                    StorageLogic.resetChainDb(files6, "mainnet");
            java.util.Map<String, String> a6 = snapshotTree(data6);
            locked.setWritable(true, true);
            check("storage reset: an entry that cannot be deleted is a FAILED"
                            + " reset with the marker kept",
                    o6.result == StorageLogic.ResetResult.FAILED
                            && o6.failureKind
                            == StorageLogic.FailureKind.ENTRIES
                            && a6.containsKey("files/chain-reset.pending")
                            && a6.containsKey(dbRel + "full/deep/er/x"));
            check("storage reset: even a failed reset touches nothing outside"
                            + " the database",
                    withoutPrefix(withoutDb(a6),
                            "files/chain-reset.pending").equals(
                            withoutDb(b6)));
            check("storage reset: and is finished by the next attempt",
                    StorageLogic.completePendingReset(files6, "mainnet").result
                            == StorageLogic.ResetResult.DELETED
                            && !new File(files6, "node/chains/mainnet/db")
                            .exists()
                            && !new File(files6,
                            "node/chains/mainnet/paritydb").exists());
        } else {
            locked.setWritable(true, true);
            skip("storage reset: undeletable entry (this user can write"
                    + " anywhere)");
        }
    }

    private static void testStorageUsage() throws Exception {
        File data = storageWorld("usage");
        File files = new File(data, "files");
        StorageLogic.Usage u = StorageLogic.measure(files, data, "mainnet");
        // db: 16 + 4000 + 1000 + 1 + 1 = 5018; paritydb: 3
        check("storage usage: the chain database is the files of the db and"
                + " paritydb directories - what the resync deletes (found "
                + u.chainDbBytes + ")", u.chainDbBytes == 5021);
        // node: db 5018 + paritydb 3 + key 300 + token 65 + pin 65
        //       + dev 11 + unrelated 1 = 5463
        check("storage usage: the node figure is everything under node/"
                + " (found " + u.nodeBytes + ")", u.nodeBytes == 5463);
        check("storage usage: logs (found " + u.logsBytes + ")",
                u.logsBytes == 2500);
        // depin: 8 + 700, depin-supervisor: 2
        check("storage usage: DePIN is depin plus depin-supervisor (found "
                + u.depinBytes + ")", u.depinBytes == 710);
        // total: node 5463 + logs 2500 + depin 710 + track 3 + marker 2
        //        + prefs 6 + 15 = 8699
        check("storage usage: the total is the whole data area (found "
                + u.totalBytes + ")", u.totalBytes == 8699);
        check("storage usage: without a data area the total is unknown, not"
                        + " zero",
                StorageLogic.measure(files, null, "mainnet").totalBytes == -1);
        // Audit I-5: the figures are composed from walks that visit
        // each file once; they must equal what a walk of each whole
        // tree finds.
        check("storage usage: the composed node and total figures equal a"
                        + " plain walk of each tree",
                u.nodeBytes == StorageLogic.treeBytes(
                        new File(files, "node"), null)
                        && u.totalBytes == StorageLogic.treeBytes(data, null)
                        && StorageLogic.treeBytes(new File(files, "node"),
                        StorageLogic.chainDbDirs(files, "mainnet"))
                        == 5463 - 5021);
        StorageLogic.Usage roundabout = StorageLogic.measure(files,
                new File(data, "shared_prefs/.."), "mainnet");
        check("storage usage: a data area not named as the files directory's"
                        + " parent is walked whole, and nothing is counted"
                        + " twice (found " + roundabout.totalBytes + ")",
                roundabout.totalBytes == 8699
                        && roundabout.nodeBytes == 5463);

        File empty = new File(tempDir("storage-empty"), "files");
        empty.mkdirs();
        StorageLogic.Usage z = StorageLogic.measure(empty,
                empty.getParentFile(), "mainnet");
        check("storage usage: parts that do not exist use 0 bytes",
                z.chainDbBytes == 0 && z.nodeBytes == 0 && z.logsBytes == 0
                        && z.depinBytes == 0 && z.totalBytes == 0);
        try {
            java.nio.file.Files.createSymbolicLink(
                    new File(files, "logs/link-to-node").toPath(),
                    new File(files, "node").toPath());
            check("storage usage: a symbolic link is neither followed nor"
                            + " counted",
                    StorageLogic.measure(files, data, "mainnet").logsBytes
                            == 2500);
        } catch (IOException | UnsupportedOperationException e) {
            skip("storage usage: symbolic links (this file system has none)");
        }
        StorageLogic.resetChainDb(files, "mainnet");
        StorageLogic.Usage after = StorageLogic.measure(files, data, "mainnet");
        check("storage usage: after a reset the database is 0 and the node"
                        + " figure fell by exactly its size",
                after.chainDbBytes == 0 && after.nodeBytes == 5463 - 5021
                        && after.totalBytes == 8699 - 5021);
    }

    // ---------------- v4.1.0: the reset's process-wide state (audit L-3) ----------------

    private static void testStorageResetState() throws Exception {
        final StorageResetState st = new StorageResetState();
        check("reset state: idle at first - not busy, no result",
                !st.busy() && st.last() == null);
        File data = storageWorld("reset-state");
        final File files = new File(data, "files");
        final java.util.concurrent.CountDownLatch entered =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch told =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger secondRan =
                new java.util.concurrent.atomic.AtomicInteger();
        final List<String> threads =
                Collections.synchronizedList(new ArrayList<String>());
        boolean started = st.start(
                new java.util.concurrent.Callable<StorageLogic.ResetOutcome>() {
                    @Override
                    public StorageLogic.ResetOutcome call() throws Exception {
                        threads.add(Thread.currentThread().getName() + ":"
                                + Thread.currentThread().isDaemon());
                        entered.countDown();
                        release.await();
                        return StorageLogic.resetChainDb(files, "mainnet");
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        told.countDown();
                    }
                });
        check("reset state: (setup) the reset is running", started
                && entered.await(8, java.util.concurrent.TimeUnit.SECONDS));
        // What an activity created now - after a rotation - reads.
        check("reset state: while the deletion runs, whoever asks finds it"
                        + " busy and without a result yet",
                st.busy() && st.last() == null);
        boolean again = st.start(
                new java.util.concurrent.Callable<StorageLogic.ResetOutcome>() {
                    @Override
                    public StorageLogic.ResetOutcome call() {
                        secondRan.incrementAndGet();
                        return null;
                    }
                }, null);
        check("reset state: a second reset is not started while one runs",
                !again && st.busy());
        release.countDown();
        check("reset state: the caller is told when it is over",
                told.await(8, java.util.concurrent.TimeUnit.SECONDS));
        StorageResetState.Finished fin = st.last();
        check("reset state: afterwards the result is there for whoever asks -"
                        + " it does not go to the instance that started it",
                !st.busy() && fin != null && fin.seq == 1 && !fin.threw
                        && fin.outcome != null && fin.outcome.result
                        == StorageLogic.ResetResult.DELETED
                        && fin.outcome.entriesDeleted == 13
                        && st.last() == fin && secondRan.get() == 0);

        // A reset that throws, one that is refused (null), and a
        // listener that throws: the state is published all the same.
        final java.util.concurrent.CountDownLatch told2 =
                new java.util.concurrent.CountDownLatch(1);
        st.start(new java.util.concurrent.Callable<StorageLogic.ResetOutcome>() {
            @Override
            public StorageLogic.ResetOutcome call() {
                threads.add(Thread.currentThread().getName() + ":"
                        + Thread.currentThread().isDaemon());
                throw new IllegalStateException("test");
            }
        }, new Runnable() {
            @Override
            public void run() {
                told2.countDown();
                throw new IllegalStateException("listener (test)");
            }
        });
        check("reset state: a reset that throws ends as a failure without an"
                        + " outcome, and the state is idle again",
                told2.await(8, java.util.concurrent.TimeUnit.SECONDS)
                        && eventually(new Cond() {
                    @Override
                    public boolean ok() {
                        return !st.busy();
                    }
                }, 5000)
                        && st.last() != null && st.last().seq == 2
                        && st.last().threw && st.last().outcome == null);
        st.start(new java.util.concurrent.Callable<StorageLogic.ResetOutcome>() {
            @Override
            public StorageLogic.ResetOutcome call() {
                return null;
            }
        }, null);
        check("reset state: a refused reset (no outcome, nothing thrown) is"
                        + " told apart from a failed one",
                eventually(new Cond() {
                    @Override
                    public boolean ok() {
                        StorageResetState.Finished f = st.last();
                        return !st.busy() && f != null && f.seq == 3;
                    }
                }, 5000) && !st.last().threw && st.last().outcome == null);

        // One worker for the section: measuring and resets share it.
        final java.util.concurrent.CountDownLatch measured =
                new java.util.concurrent.CountDownLatch(1);
        st.executor().execute(new Runnable() {
            @Override
            public void run() {
                threads.add(Thread.currentThread().getName() + ":"
                        + Thread.currentThread().isDaemon());
                measured.countDown();
            }
        });
        check("reset state: resets and measuring run on the one daemon"
                        + " thread named storage-work " + threads,
                measured.await(8, java.util.concurrent.TimeUnit.SECONDS)
                        && threads.equals(Arrays.asList("storage-work:true",
                        "storage-work:true", "storage-work:true")));
        check("reset state: the app has one instance for the process",
                StorageResetState.PROCESS != null
                        && !StorageResetState.PROCESS.busy());
    }

    // ---------------- v4.1.0: daily track records ----------------

    private static DailyTrackStore.Increment inc(long uptime, long blocks,
                                                 int peerMax, long peerSum,
                                                 long peerSamples, long jobs,
                                                 long miningMs, long hashes) {
        DailyTrackStore.Increment i = new DailyTrackStore.Increment();
        i.uptimeDeltaMs = uptime;
        i.blocksDelta = blocks;
        i.peerMax = peerMax;
        i.peerSum = peerSum;
        i.peerSamples = peerSamples;
        i.jobsDelta = jobs;
        i.miningMs = miningMs;
        i.approxHashes = hashes;
        return i;
    }

    private static java.util.Map<String, DailyTrackStore.Increment> day(
            String date, DailyTrackStore.Increment i) {
        java.util.Map<String, DailyTrackStore.Increment> m =
                new java.util.LinkedHashMap<>();
        m.put(date, i);
        return m;
    }

    private static void testDailyRecords() throws Exception {
        java.util.TimeZone tokyo = java.util.TimeZone.getTimeZone("Asia/Tokyo");
        java.util.TimeZone utc = java.util.TimeZone.getTimeZone("UTC");
        // 2026-11-15T15:00:00Z is midnight of the 16th in Tokyo.
        long midnightTokyo = 1794754800000L;
        check("daily: the date is the LOCAL calendar date",
                "2026-11-16".equals(DailyTrackStore.localDate(
                        midnightTokyo, tokyo))
                        && "2026-11-15".equals(DailyTrackStore.localDate(
                        midnightTokyo - 1, tokyo))
                        && "2026-11-15".equals(DailyTrackStore.localDate(
                        midnightTokyo, utc)));
        check("daily: the file is <files>/track/daily.jsonl",
                "/f/track/daily.jsonl".equals(
                        DailyTrackStore.file(new File("/f")).getPath()));

        String line = "{\"date\":\"2026-11-16\",\"uptimeDeltaMs\":86399000,"
                + "\"blocksDelta\":7201,\"peerMax\":12,\"peerSum\":230112,"
                + "\"peerSamples\":28764,\"jobsDelta\":5,\"miningMs\":3600000,"
                + "\"approxHashes\":36000000}";
        DailyTrackStore.Record r = DailyTrackStore.parseLine(line);
        check("daily: a record line parses to its nine fields",
                r != null && "2026-11-16".equals(r.date)
                        && r.uptimeDeltaMs == 86399000L && r.blocksDelta == 7201
                        && r.peerMax == 12 && r.peerSum == 230112
                        && r.peerSamples == 28764 && r.jobsDelta == 5
                        && r.miningMs == 3600000 && r.approxHashes == 36000000);
        check("daily: and is written back as exactly that line",
                r != null && line.equals(r.toLine()));
        check("daily: a field a later version adds is ignored, not fatal",
                DailyTrackStore.parseLine(line.replace("}",
                        ",\"later\":7,\"note\":\"x\"}")) != null);
        String[] bad = {
                null, "", "   ", "{}", "not json",
                line.substring(0, line.length() - 1),
                line.replace("2026-11-16", "2026-13-01"),
                line.replace("2026-11-16", "2026-02-30"),
                line.replace("2026-11-16", "16/11/2026"),
                line.replace("\"date\":\"2026-11-16\",", ""),
                line.replace("\"blocksDelta\":7201,", ""),
                line.replace("7201", "-1"),
                line.replace("7201", "\"7201\""),
                line.replace("7201", "99999999999999999999"),
                line.replace("\"peerMax\":12", "\"peerMax\":4294967296"),
                line + repeat(" ", 2000) + "}",
        };
        boolean allRejected = true;
        for (String b : bad) {
            if (DailyTrackStore.parseLine(b) != null) {
                allRejected = false;
            }
        }
        check("daily: a damaged line is no record", allRejected);

        // merge: add into the day, max for peerMax, sorted, pruned.
        final String today = "2026-11-16";
        List<DailyTrackStore.Record> one = DailyTrackStore.merge(null,
                day("2026-11-16", inc(1000, 2, 5, 50, 10, 1, 500, 5000)),
                today);
        List<DailyTrackStore.Record> two = DailyTrackStore.merge(one,
                day("2026-11-16", inc(3000, 1, 4, 8, 2, 2, 250, 2500)),
                today);
        check("daily: increments of one day add up; the peer maximum is a"
                        + " maximum",
                two.size() == 1 && two.get(0).uptimeDeltaMs == 4000
                        && two.get(0).blocksDelta == 3
                        && two.get(0).peerMax == 5 && two.get(0).peerSum == 58
                        && two.get(0).peerSamples == 12
                        && two.get(0).jobsDelta == 3
                        && two.get(0).miningMs == 750
                        && two.get(0).approxHashes == 7500);
        List<DailyTrackStore.Record> three = DailyTrackStore.merge(two,
                day("2026-11-14", inc(7, 0, 0, 0, 0, 0, 0, 0)), today);
        check("daily: records are kept oldest first, whatever the order they"
                        + " arrive in",
                three.size() == 2 && "2026-11-14".equals(three.get(0).date)
                        && "2026-11-16".equals(three.get(1).date));
        List<DailyTrackStore.Record> same = DailyTrackStore.merge(two,
                day("yesterday", inc(1, 1, 1, 1, 1, 1, 1, 1)), today);
        check("daily: an invalid date or a missing increment adds nothing",
                same.size() == 1 && same.get(0).blocksDelta == 3
                        && same.get(0).uptimeDeltaMs == 4000
                        && DailyTrackStore.merge(two, day("2026-11-16", null),
                        today).get(0).blocksDelta == 3
                        && DailyTrackStore.merge(null, null, today).isEmpty());
        check("daily: counters saturate instead of wrapping",
                DailyTrackStore.merge(DailyTrackStore.merge(null,
                                day("2026-11-16", inc(Long.MAX_VALUE - 1, 0, 0,
                                        0, 0, 0, 0, 0)), today),
                        day("2026-11-16", inc(5, 0, 0, 0, 0, 0, 0, 0)), today)
                        .get(0).uptimeDeltaMs == Long.MAX_VALUE);

        // Retention: the 400 days ending at the day being written.
        java.util.Map<String, DailyTrackStore.Increment> many =
                new java.util.LinkedHashMap<>();
        java.time.LocalDate newest = java.time.LocalDate.parse("2026-11-16");
        for (int i = 0; i < 450; i++) {
            many.put(newest.minusDays(i).toString(),
                    inc(i + 1, 0, 0, 0, 0, 0, 0, 0));
        }
        List<DailyTrackStore.Record> kept =
                DailyTrackStore.merge(null, many, today);
        check("daily: 400 days are kept, counted back from today (found "
                        + kept.size() + ")",
                kept.size() == 400
                        && "2025-10-13".equals(kept.get(0).date)
                        && kept.get(0).uptimeDeltaMs == 400
                        && "2026-11-16".equals(kept.get(399).date));
        List<DailyTrackStore.Record> sparse = DailyTrackStore.merge(
                DailyTrackStore.merge(null, day("2025-10-12",
                        inc(1, 0, 0, 0, 0, 0, 0, 0)), "2025-10-12"),
                day("2026-11-16", inc(2, 0, 0, 0, 0, 0, 0, 0)), today);
        check("daily: retention goes by date, not by line count - a day 401"
                        + " days back is dropped even from a short file",
                sparse.size() == 1 && "2026-11-16".equals(sparse.get(0).date));
        List<DailyTrackStore.Record> clockBack = DailyTrackStore.merge(kept,
                day("2026-11-10", inc(1000, 0, 0, 0, 0, 0, 0, 0)), today);
        check("daily: a clock set back adds to the earlier day and drops"
                        + " nothing",
                clockBack.size() == 400
                        && "2026-11-16".equals(clockBack.get(399).date)
                        && clockBack.get(393).uptimeDeltaMs == 7 + 1000);

        // Audit M-1: the anchor is today, never the newest date in
        // the file.
        List<DailyTrackStore.Record> ahead = DailyTrackStore.merge(kept,
                day("2099-01-01", inc(9, 9, 9, 9, 9, 9, 9, 9)), today);
        check("daily: an increment dated far in the future is ignored - it"
                        + " is not recorded and prunes nothing",
                ahead.size() == 400
                        && "2025-10-13".equals(ahead.get(0).date)
                        && "2026-11-16".equals(ahead.get(399).date));
        List<DailyTrackStore.Record> tomorrow = DailyTrackStore.merge(kept,
                day("2026-11-17", inc(5, 0, 0, 0, 0, 0, 0, 0)), today);
        List<DailyTrackStore.Record> dayAfter = DailyTrackStore.merge(kept,
                day("2026-11-18", inc(5, 0, 0, 0, 0, 0, 0, 0)), today);
        check("daily: one day ahead of today is accepted (a time-zone change"
                        + " between reading and save), two days ahead is"
                        + " not; neither moves the 400-day window",
                tomorrow.size() == 401
                        && "2026-11-17".equals(tomorrow.get(400).date)
                        && "2025-10-13".equals(tomorrow.get(0).date)
                        && dayAfter.size() == 400
                        && "2026-11-16".equals(dayAfter.get(399).date));
        List<DailyTrackStore.Record> poisoned = new ArrayList<>(
                DailyTrackStore.merge(null, day("2026-11-15",
                        inc(1, 0, 0, 0, 0, 0, 0, 0)), "2026-11-15"));
        poisoned.add(DailyTrackStore.parseLine(line.replace("2026-11-16",
                "2099-01-01")));
        List<DailyTrackStore.Record> healed = DailyTrackStore.merge(poisoned,
                day("2026-11-16", inc(2, 0, 0, 0, 0, 0, 0, 0)), today);
        check("daily: a future-dated record already in a file neither prunes"
                        + " the real records nor keeps today's out",
                healed.size() == 3 && "2026-11-15".equals(healed.get(0).date)
                        && "2026-11-16".equals(healed.get(1).date)
                        && healed.get(1).uptimeDeltaMs == 2);
        List<DailyTrackStore.Record> past = DailyTrackStore.merge(kept,
                day("2020-01-01", inc(3, 0, 0, 0, 0, 0, 0, 0)), "2020-01-01");
        check("daily: a save made while the clock reads a past date drops"
                        + " none of the real records",
                past.size() == 401 && "2020-01-01".equals(past.get(0).date)
                        && "2026-11-16".equals(past.get(400).date));
        check("daily: without a valid date for today nothing is added and"
                        + " nothing is pruned",
                DailyTrackStore.merge(kept, day("2026-11-16",
                        inc(1, 0, 0, 0, 0, 0, 0, 0)), null).size() == 400
                        && DailyTrackStore.merge(kept, day("2026-11-16",
                        inc(1, 0, 0, 0, 0, 0, 0, 0)), "today").get(399)
                        .uptimeDeltaMs == 1);

        // The file.
        File dir = tempDir("daily");
        File f = DailyTrackStore.file(dir);
        check("daily: no file yet is an empty record, and 0 days",
                DailyTrackStore.read(f).isEmpty()
                        && DailyTrackStore.dayCount(f) == 0);
        DailyTrackStore.fold(f, day("2026-11-15",
                inc(1000, 2, 5, 50, 10, 0, 0, 0)), "2026-11-15");
        DailyTrackStore.fold(f, day("2026-11-16",
                inc(2000, 3, 7, 70, 10, 4, 60000, 600000)), today);
        DailyTrackStore.fold(f, day("2026-11-16",
                inc(500, 1, 6, 6, 1, 1, 3000, 30000)), today);
        String want = "{\"date\":\"2026-11-15\",\"uptimeDeltaMs\":1000,"
                + "\"blocksDelta\":2,\"peerMax\":5,\"peerSum\":50,"
                + "\"peerSamples\":10,\"jobsDelta\":0,\"miningMs\":0,"
                + "\"approxHashes\":0}\n"
                + "{\"date\":\"2026-11-16\",\"uptimeDeltaMs\":2500,"
                + "\"blocksDelta\":4,\"peerMax\":7,\"peerSum\":76,"
                + "\"peerSamples\":11,\"jobsDelta\":5,\"miningMs\":63000,"
                + "\"approxHashes\":630000}\n";
        check("daily: the file holds one line per day, the day's record"
                + " updated in place", want.equals(readAll(f)));
        check("daily: a save leaves no temporary file behind",
                f.getParentFile().list().length == 1);
        check("daily: the day count is the number of days on record",
                DailyTrackStore.dayCount(f) == 2);
        write(f, "garbage\n" + want + "{\"date\":\"2026-11-16\"\n\n");
        DailyTrackStore.fold(f, day("2026-11-17",
                inc(1, 0, 0, 0, 0, 0, 0, 0)), "2026-11-17");
        check("daily: damaged lines are dropped at the next save; the good"
                        + " ones survive it",
                readAll(f).equals(want + "{\"date\":\"2026-11-17\","
                        + "\"uptimeDeltaMs\":1,\"blocksDelta\":0,\"peerMax\":0,"
                        + "\"peerSum\":0,\"peerSamples\":0,\"jobsDelta\":0,"
                        + "\"miningMs\":0,\"approxHashes\":0}\n"));
        // A file that cannot be read must not be replaced.
        File dir2 = tempDir("daily-unreadable");
        File f2 = DailyTrackStore.file(dir2);
        f2.mkdirs(); // a directory where the file should be
        boolean threw = false;
        try {
            DailyTrackStore.fold(f2, day("2026-11-16",
                    inc(1, 0, 0, 0, 0, 0, 0, 0)), today);
        } catch (IOException e) {
            threw = true;
        }
        check("daily: a record that cannot be read is never overwritten",
                threw && f2.isDirectory()
                        && DailyTrackStore.dayCount(f2) == -1);

        // Audit M-1, the auditor's probe: five saves in this order,
        // the third dated 2099 while the real date is the 16th. It
        // used to leave the file holding the 2099 record alone.
        File f3 = DailyTrackStore.file(tempDir("daily-probe"));
        DailyTrackStore.fold(f3, day("2026-11-15",
                inc(15, 1, 0, 0, 0, 0, 0, 0)), "2026-11-15");
        DailyTrackStore.fold(f3, day("2026-11-16",
                inc(16, 1, 0, 0, 0, 0, 0, 0)), "2026-11-16");
        DailyTrackStore.fold(f3, day("2099-01-01",
                inc(99, 1, 0, 0, 0, 0, 0, 0)), "2026-11-16");
        DailyTrackStore.fold(f3, day("2026-11-17",
                inc(17, 1, 0, 0, 0, 0, 0, 0)), "2026-11-17");
        DailyTrackStore.fold(f3, day("2026-11-18",
                inc(18, 1, 0, 0, 0, 0, 0, 0)), "2026-11-18");
        List<DailyTrackStore.Record> probe = DailyTrackStore.read(f3);
        check("daily (probe): after 11-15, 11-16, 2099-01-01, 11-17, 11-18"
                        + " the file holds the four real days and no 2099"
                        + " (found " + readAll(f3).replace("\n", " ") + ")",
                probe.size() == 4
                        && "2026-11-15".equals(probe.get(0).date)
                        && probe.get(0).uptimeDeltaMs == 15
                        && "2026-11-16".equals(probe.get(1).date)
                        && probe.get(1).uptimeDeltaMs == 16
                        && "2026-11-17".equals(probe.get(2).date)
                        && probe.get(2).uptimeDeltaMs == 17
                        && "2026-11-18".equals(probe.get(3).date)
                        && probe.get(3).uptimeDeltaMs == 18
                        && !readAll(f3).contains("2099"));
    }

    /** One day of milliseconds. */
    private static final long DAY_MS = 86_400_000L;

    private static void testDailySamplerClock() throws Exception {
        // Audit M-1 at the sampler: the same five dates, as the wall
        // clock of five poll cycles. The third reads 2099 for one
        // poll; the monotonic clock says three seconds went by.
        final java.util.TimeZone utc = java.util.TimeZone.getTimeZone("UTC");
        final long day15 = 1794787200000L - DAY_MS + 12 * 3600_000L;
        final long year2099 = 4070908800000L; // 2099-01-01T00:00:00Z
        check("daily sampler clock: (premise) the test's dates",
                "2026-11-15".equals(DailyTrackStore.localDate(day15, utc))
                        && "2099-01-01".equals(DailyTrackStore.localDate(
                        year2099, utc)));
        File f = DailyTrackStore.file(tempDir("sampler-clock"));
        DailyTrackStore.Sampler s = new DailyTrackStore.Sampler(f, 60_000);
        long up = 1000;
        s.tick(0, day15, utc, up, 0, null, false, 0, -1);
        s.tick(3000, day15 + 3000, utc, up += 15, 0, null, false, 0, -1);
        // The 16th: the date changes, the 15th is saved.
        boolean due = s.tick(DAY_MS, day15 + DAY_MS, utc, up += 16, 0,
                null, false, 0, -1);
        check("daily sampler clock: (setup) the 15th is saved when the 16th"
                        + " begins",
                due && s.save() == null && DailyTrackStore.dayCount(f) == 1);
        // 2099 for one poll.
        due = s.tick(DAY_MS + 3000, year2099, utc, up += 99, 0,
                null, false, 0, -1);
        java.util.Map<String, DailyTrackStore.Increment> pend =
                s.pendingForTest();
        check("daily sampler clock: a wall clock that jumps to 2099 between"
                        + " two polls is not believed - the increment stays"
                        + " with the 16th and no date change is seen",
                !due && pend.size() == 1 && pend.containsKey("2026-11-16")
                        && pend.get("2026-11-16").uptimeDeltaMs == 99);
        check("daily sampler clock: a save made right then keeps the real"
                        + " days",
                s.save() == null && DailyTrackStore.dayCount(f) == 2
                        && !readAll(f).contains("2099"));
        // The clock is back; the 17th and the 18th follow.
        s.tick(DAY_MS + 6000, day15 + DAY_MS + 6000, utc, up += 1, 0,
                null, false, 0, -1);
        due = s.tick(2 * DAY_MS, day15 + 2 * DAY_MS, utc, up += 17, 0,
                null, false, 0, -1);
        check("daily sampler clock: the 17th begins as usual", due
                && s.save() == null);
        s.tick(2 * DAY_MS + 3000, day15 + 2 * DAY_MS + 3000, utc, up += 1, 0,
                null, false, 0, -1);
        due = s.tick(3 * DAY_MS, day15 + 3 * DAY_MS, utc, up += 18, 0,
                null, false, 0, -1);
        s.tick(3 * DAY_MS + 3000, day15 + 3 * DAY_MS + 3000, utc, up += 1, 0,
                null, false, 0, -1);
        check("daily sampler clock: and the 18th", due && s.save() == null);
        List<DailyTrackStore.Record> got = DailyTrackStore.read(f);
        check("daily sampler clock (probe): the file holds 11-15 to 11-18"
                        + " with every increment in it, and no 2099 (found "
                        + readAll(f).replace("\n", " ") + ")",
                got.size() == 4 && "2026-11-15".equals(got.get(0).date)
                        && got.get(0).uptimeDeltaMs == 15 + 16
                        && "2026-11-16".equals(got.get(1).date)
                        && got.get(1).uptimeDeltaMs == 99 + 1 + 17
                        && "2026-11-17".equals(got.get(2).date)
                        && got.get(2).uptimeDeltaMs == 1 + 18
                        && "2026-11-18".equals(got.get(3).date)
                        && got.get(3).uptimeDeltaMs == 1
                        && !readAll(f).contains("2099"));

        // A clock that jumps and STAYS is a correction (the time
        // sync after a boot with a bad clock) or the user's own
        // doing: it is believed once it has lasted.
        File f2 = DailyTrackStore.file(tempDir("sampler-sync"));
        DailyTrackStore.Sampler c = new DailyTrackStore.Sampler(f2, 60_000);
        final long year2000 = 946684800000L + 3600_000L;
        final long real = day15 + DAY_MS; // the 16th, 12:00
        long e = 0;
        up = 0;
        c.tick(e, year2000, utc, up, 0, null, false, 0, -1);
        c.tick(e += 3000, year2000 + 3000, utc, up += 3000, 0,
                null, false, 0, -1);
        // The time sync: the wall clock is right from here on.
        final long confirm = DailyTrackStore.Sampler.CLOCK_CONFIRM_MS;
        final long firstOffAt = e + 3000;
        long newDaySeenAt = -1;
        for (int i = 1; i <= 1000 && newDaySeenAt < 0; i++) {
            c.tick(e += 3000, real + i * 3000L, utc, up += 3000, 0,
                    null, false, 0, -1);
            if (c.pendingForTest().containsKey("2026-11-16")) {
                newDaySeenAt = e;
            }
        }
        // The tick that believes the new clock still closes the old
        // day; the one after it is the first of the new day.
        check("daily sampler clock: a clock that jumps and stays is believed"
                        + " once it has lasted " + (confirm / 60_000)
                        + " minutes, not before (first reading of the new"
                        + " day " + (newDaySeenAt - firstOffAt) + " ms after"
                        + " the jump)",
                newDaySeenAt == firstOffAt + confirm + 3000
                        && c.pendingForTest().get("2026-11-16").uptimeDeltaMs
                        == 3000
                        && c.pendingForTest().get("2000-01-01").uptimeDeltaMs
                        == confirm + 6000);
        check("daily sampler clock: the save is anchored to the believed"
                        + " day - the real day is recorded, the stray old"
                        + " date falls outside the 400 days as it always"
                        + " did",
                c.save() == null && DailyTrackStore.dayCount(f2) == 1
                        && "2026-11-16".equals(
                        DailyTrackStore.read(f2).get(0).date));
        // A step within a day (a time-zone or daylight change, an
        // ordinary adjustment) is believed at once.
        DailyTrackStore.Sampler z = new DailyTrackStore.Sampler(
                DailyTrackStore.file(tempDir("sampler-step")), 60_000);
        z.tick(0, real, utc, 0, 0, null, false, 0, -1);
        boolean stepped = z.tick(3000, real + 13 * 3600_000L, utc, 3000, 0,
                null, false, 0, -1);
        z.tick(6000, real + 13 * 3600_000L + 3000, utc, 6000, 0,
                null, false, 0, -1);
        check("daily sampler clock: a step of less than a day is believed at"
                        + " once",
                stepped && z.pendingForTest().containsKey("2026-11-17")
                        && z.pendingForTest().get("2026-11-16").uptimeDeltaMs
                        == 3000);
    }

    private static void testDailySampler() throws Exception {
        final java.util.TimeZone utc = java.util.TimeZone.getTimeZone("UTC");
        final long day16 = 1794787200000L; // 2026-11-16T00:00:00Z
        check("daily sampler: (premise) the test's clock",
                "2026-11-16".equals(DailyTrackStore.localDate(day16, utc)));
        File dir = tempDir("sampler");
        File f = DailyTrackStore.file(dir);
        DailyTrackStore.Sampler s = new DailyTrackStore.Sampler(f, 60_000);
        long wall = day16 + 3600_000L;

        // First readings are the baseline: the totals accumulated
        // before this process started are not this day's.
        boolean due = s.tick(1000, wall, utc, 5_000_000L, 40_000L,
                null, false, 0, -1);
        check("daily sampler: the first reading is a baseline and adds"
                        + " nothing",
                !due && s.pendingForTest().isEmpty() && !f.exists());
        s.observePeers(8);
        s.observePeers(12);
        s.observePeers(-1);
        due = s.tick(4000, wall + 3000, utc, 5_003_000L, 40_002L,
                null, false, 0, -1);
        DailyTrackStore.Increment p = s.pendingForTest().get("2026-11-16");
        check("daily sampler: the next reading adds the growth of the totals"
                        + " and the peers seen",
                !due && p != null && p.uptimeDeltaMs == 3000
                        && p.blocksDelta == 2 && p.peerMax == 12
                        && p.peerSum == 20 && p.peerSamples == 2
                        && p.jobsDelta == 0 && p.miningMs == 0
                        && p.approxHashes == 0);
        // A reading below the last one is stale: nothing is added,
        // and nothing is counted twice when the total catches up.
        s.tick(7000, wall + 6000, utc, 5_001_000L, 40_001L, null, false, 0, -1);
        s.tick(10000, wall + 9000, utc, 5_004_000L, 40_003L, null, false, 0, -1);
        p = s.pendingForTest().get("2026-11-16");
        check("daily sampler: a stale (lower) reading adds nothing and causes"
                        + " no double count",
                p.uptimeDeltaMs == 4000 && p.blocksDelta == 3);
        s.tick(13000, wall + 12000, utc, -1, -1, null, false, 0, -1);
        check("daily sampler: an unknown total changes nothing",
                s.pendingForTest().get("2026-11-16").uptimeDeltaMs == 4000);

        // Mining: a supervisor's session counters.
        Object sup1 = new Object();
        s.tick(16000, wall + 15000, utc, 5_010_000L, 40_003L,
                sup1, true, 2, 1000.0);
        p = s.pendingForTest().get("2026-11-16");
        check("daily sampler: a new supervisor's jobs count from zero; no"
                        + " mining time before it was seen running twice",
                p.jobsDelta == 2 && p.miningMs == 0 && p.approxHashes == 0
                        && p.uptimeDeltaMs == 10000);
        s.tick(19000, wall + 18000, utc, 5_013_000L, 40_003L,
                sup1, true, 5, 1000.5);
        p = s.pendingForTest().get("2026-11-16");
        check("daily sampler: running on both ticks adds the time between"
                        + " them and rate x time",
                p.jobsDelta == 5 && p.miningMs == 3000
                        && p.approxHashes == 3001);
        s.tick(22000, wall + 21000, utc, 5_016_000L, 40_003L,
                sup1, true, 5, 1000.5);
        p = s.pendingForTest().get("2026-11-16");
        check("daily sampler: the fraction of a hash is carried, not lost"
                        + " (3001.5 + 3001.5 = 6003)",
                p.miningMs == 6000 && p.approxHashes == 6003);
        s.tick(25000, wall + 24000, utc, 5_019_000L, 40_003L,
                sup1, true, 5, -1.0);
        p = s.pendingForTest().get("2026-11-16");
        check("daily sampler: with no reported rate, time counts and hashes"
                        + " do not",
                p.miningMs == 9000 && p.approxHashes == 6003);
        s.tick(28000, wall + 27000, utc, 5_022_000L, 40_003L,
                sup1, false, 5, 1000.0);
        s.tick(31000, wall + 30000, utc, 5_025_000L, 40_003L,
                sup1, true, 5, 1000.0);
        p = s.pendingForTest().get("2026-11-16");
        check("daily sampler: a miner that was not running at one end of an"
                        + " interval adds no mining time for it",
                p.miningMs == 9000 && p.approxHashes == 6003);
        s.tick(31000 + 61_000, wall + 91000, utc, 5_086_000L, 40_003L,
                sup1, true, 5, 1000.0);
        p = s.pendingForTest().get("2026-11-16");
        check("daily sampler: a gap longer than a minute between ticks is not"
                        + " mining time (uptime still follows its total)",
                p.miningMs == 9000 && p.uptimeDeltaMs == 86000);
        Object sup2 = new Object();
        s.tick(95000, wall + 94000, utc, 5_089_000L, 40_003L,
                sup2, true, 1, 1000.0);
        p = s.pendingForTest().get("2026-11-16");
        check("daily sampler: the next node process's supervisor starts its"
                        + " own count (5 + 1 jobs), and its first tick adds"
                        + " no mining time",
                p.jobsDelta == 6 && p.miningMs == 9000);
        s.tick(98000, wall + 97000, utc, 5_092_000L, 40_003L,
                null, false, 0, -1);
        s.tick(101000, wall + 100000, utc, 5_095_000L, 40_003L,
                sup2, true, 1, 1000.0);
        check("daily sampler: with no supervisor nothing is added to mining,"
                        + " and the same supervisor seen again is not counted"
                        + " from zero a second time",
                s.pendingForTest().get("2026-11-16").jobsDelta == 6
                        && s.pendingForTest().get("2026-11-16").miningMs
                        == 9000);

        // Saving.
        check("daily sampler: tick() itself never touches the file",
                !f.exists());
        DailyTrackStore.Sampler t = new DailyTrackStore.Sampler(f, 60_000);
        check("daily sampler: save() with nothing pending writes nothing",
                t.save() == null && !f.exists());
        t.tick(0, wall, utc, 100, 10, null, false, 0, -1);
        boolean d1 = t.tick(30_000, wall + 30_000, utc, 30_100, 11,
                null, false, 0, -1);
        boolean d2 = t.tick(59_999, wall + 59_999, utc, 60_099, 11,
                null, false, 0, -1);
        boolean d3 = t.tick(60_000, wall + 60_000, utc, 60_100, 12,
                null, false, 0, -1);
        check("daily sampler: a save is due once the interval has passed, not"
                        + " before", !d1 && !d2 && d3);
        check("daily sampler: save() writes the day and clears what was"
                        + " pending",
                t.save() == null && t.pendingForTest().isEmpty()
                        && readAll(f).equals("{\"date\":\"2026-11-16\","
                        + "\"uptimeDeltaMs\":60000,\"blocksDelta\":2,"
                        + "\"peerMax\":0,\"peerSum\":0,\"peerSamples\":0,"
                        + "\"jobsDelta\":0,\"miningMs\":0,"
                        + "\"approxHashes\":0}\n"));
        boolean d4 = t.tick(63_000, wall + 63_000, utc, 63_100, 12,
                null, false, 0, -1);
        check("daily sampler: the interval starts again after a save", !d4);

        // Midnight: what was pending belongs to the old day.
        long beforeMidnight = day16 + 86_400_000L - 2000;
        t.observePeers(9);
        t.tick(66_000, beforeMidnight, utc, 66_100, 13, null, false, 0, -1);
        t.observePeers(11);
        boolean d5 = t.tick(69_000, beforeMidnight + 3000, utc, 69_100, 15,
                null, false, 0, -1);
        java.util.Map<String, DailyTrackStore.Increment> pend =
                t.pendingForTest();
        check("daily sampler: when the date changes a save is due at once, and"
                        + " what was pending stays with the day it was seen"
                        + " on",
                d5 && pend.size() == 1 && pend.containsKey("2026-11-16")
                        && pend.get("2026-11-16").uptimeDeltaMs == 9000
                        && pend.get("2026-11-16").blocksDelta == 3
                        && pend.get("2026-11-16").peerMax == 11
                        && pend.get("2026-11-16").peerSamples == 2);
        t.observePeers(4);
        t.tick(72_000, beforeMidnight + 6000, utc, 72_100, 16,
                null, false, 0, -1);
        pend = t.pendingForTest();
        check("daily sampler: the next readings belong to the new day",
                pend.size() == 2 && pend.get("2026-11-17").uptimeDeltaMs == 3000
                        && pend.get("2026-11-17").blocksDelta == 1
                        && pend.get("2026-11-17").peerMax == 4);
        check("daily sampler: one save writes both days",
                t.save() == null && DailyTrackStore.dayCount(f) == 2
                        && DailyTrackStore.read(f).get(0).uptimeDeltaMs == 69000
                        && DailyTrackStore.read(f).get(1).uptimeDeltaMs == 3000);

        // A failed save keeps the increments for the next one.
        File dir2 = tempDir("sampler-fail");
        File f2 = DailyTrackStore.file(dir2);
        f2.mkdirs();
        DailyTrackStore.Sampler u = new DailyTrackStore.Sampler(f2, 1000);
        u.tick(0, wall, utc, 0, 0, null, false, 0, -1);
        u.tick(3000, wall + 3000, utc, 3000, 1, null, false, 0, -1);
        String failure = u.save();
        check("daily sampler: a failed save reports the error's class, no"
                        + " path (" + failure + ")",
                failure != null && failure.startsWith("java.")
                        && !failure.contains("/"));
        u.tick(6000, wall + 6000, utc, 6000, 2, null, false, 0, -1);
        check("daily sampler: and keeps what it could not write",
                u.pendingForTest().get("2026-11-16").uptimeDeltaMs == 6000
                        && u.pendingForTest().get("2026-11-16").blocksDelta
                        == 2);
        f2.delete();
        check("daily sampler: the next save writes all of it, once",
                u.save() == null && u.pendingForTest().isEmpty()
                        && DailyTrackStore.read(f2).size() == 1
                        && DailyTrackStore.read(f2).get(0).uptimeDeltaMs == 6000
                        && DailyTrackStore.read(f2).get(0).blocksDelta == 2);
    }

    // ---------------- share assembly ----------------

    private static void testShareAssembly() {
        final String summary = "=== Quantus Node diagnostic summary ===\n"
                + "App version: 4.0.0 (versionCode 63)\n"
                + "WARN lines: 0\n"
                + "ERROR lines: 0\n"
                + "=== end of summary ===\n";
        check("share: no mining lines leaves the summary untouched",
                MiningLogic.insertSummaryLines(summary,
                        Collections.<String>emptyList()) == summary
                        && MiningLogic.insertSummaryLines(summary, null)
                        == summary);
        check("share: mining lines go in before the summary's end marker",
                ("=== Quantus Node diagnostic summary ===\n"
                        + "App version: 4.0.0 (versionCode 63)\n"
                        + "WARN lines: 0\n"
                        + "ERROR lines: 0\n"
                        + "Mining: on (this session)\n"
                        + "Miner state: waiting (node syncing)\n"
                        + "=== end of summary ===\n").equals(
                        MiningLogic.insertSummaryLines(summary, Arrays.asList(
                                "Mining: on (this session)",
                                "Miner state: waiting (node syncing)"))));
        check("share: a summary without the marker still gets the lines",
                "x\nMining: on (this session)\n".equals(
                        MiningLogic.insertSummaryLines("x\n",
                                Arrays.asList("Mining: on (this session)"))));

        MiningLogic.SecretScrubber both =
                MiningLogic.SecretScrubber.of(VALID, TOKEN);
        final String body = N_ADDRESS + "\nfoo " + VALID + " bar " + TOKEN
                + "\nlast line\n";
        String masked = MiningLogic.finishShareBody(body, true, both);
        check("share: with masking on the rewards address is hidden",
                masked.contains("Rewards wormhole address: <rewards-address>")
                        && !masked.contains(VALID_ADDRESS));
        check("share: the secrets are removed with masking on",
                masked.contains("foo <redacted> bar <redacted>\n")
                        && !masked.contains(VALID_CORE)
                        && !masked.contains(TOKEN));
        String unmasked = MiningLogic.finishShareBody(body, false, both);
        check("share: with masking off the address stays, the secrets do not",
                unmasked.contains(VALID_ADDRESS)
                        && unmasked.contains("foo <redacted> bar <redacted>\n")
                        && !unmasked.contains(VALID_CORE)
                        && !unmasked.contains(TOKEN));
        final String plain = "2026-11-14 09:00:00 plain node line\n";
        check("share: a body with nothing of mining in it comes back as it is",
                MiningLogic.finishShareBody(plain, true, both) == plain
                        && MiningLogic.finishShareBody(plain, false, null)
                        == plain
                        && MiningLogic.finishShareBody(plain, true,
                        MiningLogic.SecretScrubber.of(null, null)) == plain);
        check("share: no body, no text",
                MiningLogic.finishShareBody(null, true, both) == null);
    }

    // ---------------- strings (nine languages) ----------------

    private static final java.util.regex.Pattern PLACEHOLDER =
            java.util.regex.Pattern.compile("%(?:\\d+\\$)?[a-zA-Z%]");

    private static List<String> placeholders(String s) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = PLACEHOLDER.matcher(s);
        while (m.find()) {
            out.add(m.group());
        }
        Collections.sort(out);
        return out;
    }

    private static void testStrings() {
        java.util.Set<String> keys = StringsTable.keys();
        check("strings: the table holds 438 keys - the 329 of v3.0.1, 67 for"
                + " mining, the thermal stop's one, the donation footer's"
                + " three, the 24 of v4.1.0's storage section, the four"
                + " of the UI/UX phase 0, the four first-run keys and the"
                + " five settings keys and the one record key of the"
                + " UI/UX phase 1 (found "
                + keys.size() + ")", keys.size() == 438);
        int miningKeys = 0;
        int donateKeys = 0;
        int storageKeys = 0;
        String lastKey = null;
        String lastBeforeStorage = null;
        for (String k : keys) {
            if (k.startsWith("mining_")) {
                miningKeys++;
            }
            if (k.startsWith("donate_")) {
                donateKeys++;
            }
            if (k.startsWith("storage_")) {
                storageKeys++;
            } else {
                lastBeforeStorage = k;
            }
            lastKey = k;
        }
        check("strings: 68 keys are mining keys (found " + miningKeys + ")",
                miningKeys == 68);
        check("strings: 3 keys are donation keys (found " + donateKeys + ")",
                donateKeys == 3);
        check("strings: 24 keys are storage keys (found " + storageKeys + ")",
                storageKeys == 24);
        check("strings: the donation footer's toast ends the pre-storage"
                        + " run in the constant order, and the UI/UX phase 1"
                        + " settings keys close the table after the phase 0"
                        + " keys and the first-run keys that follow the"
                        + " storage section",
                "donate_copied".equals(StringsTable.DONATE_COPIED)
                        && "member_id_copied".equals(lastBeforeStorage)
                        && "storage_resync_failed".equals(
                        StringsTable.STORAGE_RESYNC_FAILED)
                        && "storage_fail_internal".equals(
                        StringsTable.STORAGE_FAIL_INTERNAL)
                        && "member_id_copied".equals(lastKey));
        check("strings: nine languages, Japanese first",
                Arrays.asList("ja", "en", "zh", "es", "hi", "ar", "ko", "ru",
                        "fr").equals(Arrays.asList(StringsTable.TAGS)));

        java.util.Map<String, String> ja = StringsTable.table("ja");
        List<String> missing = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        List<String> badPlaceholders = new ArrayList<>();
        List<String> badShape = new ArrayList<>();
        List<String> untranslated = new ArrayList<>();
        List<String> badFormat = new ArrayList<>();
        for (String tag : StringsTable.TAGS) {
            java.util.Map<String, String> t = StringsTable.table(tag);
            for (String k : t.keySet()) {
                if (!keys.contains(k)) {
                    extra.add(tag + ":" + k);
                }
            }
            for (String k : keys) {
                String v = t.get(k);
                if (v == null || v.trim().isEmpty()) {
                    missing.add(tag + ":" + k);
                    continue;
                }
                if (!placeholders(v).equals(placeholders(ja.get(k)))) {
                    badPlaceholders.add(tag + ":" + k);
                }
                if (!k.startsWith("mining_") && !k.startsWith("storage_")) {
                    continue;
                }
                boolean control = false;
                for (int i = 0; i < v.length(); i++) {
                    if (Character.isISOControl(v.charAt(i))) {
                        control = true;
                    }
                }
                if (control || !v.equals(v.trim())) {
                    badShape.add(tag + ":" + k);
                }
                if (!"ja".equals(tag) && v.equals(ja.get(k))) {
                    untranslated.add(tag + ":" + k);
                }
                if (!placeholders(v).isEmpty()) {
                    try {
                        String out = String.format(java.util.Locale.US, v,
                                "⁠X⁠");
                        if (!out.contains("⁠X⁠")) {
                            badFormat.add(tag + ":" + k);
                        }
                    } catch (RuntimeException e) {
                        badFormat.add(tag + ":" + k);
                    }
                }
            }
        }
        check("strings: no key is missing in any language " + missing,
                missing.isEmpty());
        check("strings: no language has a key the source lacks " + extra,
                extra.isEmpty());
        check("strings: placeholders match the Japanese source everywhere "
                + badPlaceholders, badPlaceholders.isEmpty());
        check("strings: every mining and storage string is one clean"
                + " paragraph " + badShape, badShape.isEmpty());
        check("strings: no mining or storage string was left in Japanese "
                + untranslated, untranslated.isEmpty());
        check("strings: every mining and storage format string formats "
                + badFormat, badFormat.isEmpty());
        boolean keepsNamed = true;
        for (String tag : StringsTable.TAGS) {
            java.util.Map<String, String> t = StringsTable.table(tag);
            String keeps = t.get(StringsTable.STORAGE_RESYNC_CONFIRM_KEEPS);
            String failedText = t.get(StringsTable.STORAGE_RESYNC_FAILED);
            if (keeps == null || !keeps.contains("DePIN")
                    || !keeps.contains("Inner Hash") || failedText == null
                    || !placeholders(failedText).equals(
                    Arrays.asList("%1$s"))) {
                keepsNamed = false;
            }
        }
        check("strings: the resync confirmation names DePIN and the Inner"
                + " Hash among what is kept, in every language; the failure"
                + " line takes exactly one value", keepsNamed);
        // Audit I-4: what goes into the failure line's brackets is a
        // phrase of the same language, one per failure kind.
        List<String> badKinds = new ArrayList<>();
        java.util.Set<String> kindKeys = new java.util.TreeSet<>();
        List<StorageLogic.FailureKind> kinds = new ArrayList<>(
                Arrays.asList(StorageLogic.FailureKind.values()));
        kinds.add(null);
        for (StorageLogic.FailureKind kind : kinds) {
            String key = StorageResetState.failureKey(kind);
            kindKeys.add(key);
            for (String tag : StringsTable.TAGS) {
                java.util.Map<String, String> t = StringsTable.table(tag);
                String phrase = t.get(key);
                if (phrase == null || phrase.isEmpty()
                        || !placeholders(phrase).isEmpty()
                        || !String.format(java.util.Locale.US,
                        t.get(StringsTable.STORAGE_RESYNC_FAILED), phrase)
                        .contains(phrase)
                        || (!"en".equals(tag) && phrase.equals(StringsTable
                        .table("en").get(key)))) {
                    badKinds.add(tag + ":" + kind);
                }
            }
        }
        check("strings: every failure kind of a reset has a phrase of its own"
                        + " language for the failure line, and none is the"
                        + " English one " + badKinds,
                badKinds.isEmpty() && kindKeys.equals(new java.util.TreeSet<>(
                        Arrays.asList("storage_fail_location",
                                "storage_fail_entries",
                                "storage_fail_internal")))
                        && StringsTable.STORAGE_FAIL_INTERNAL.equals(
                        StorageResetState.failureKey(
                                StorageLogic.FailureKind.MARKER)));
        check("strings: the status words the specification names",
                "オフ".equals(ja.get(StringsTable.MINING_STATE_OFF))
                        && "準備中".equals(ja.get(StringsTable.MINING_STATE_PREPARING))
                        && "待機中".equals(ja.get(StringsTable.MINING_STATE_WAITING))
                        && "マイニング中".equals(
                        ja.get(StringsTable.MINING_STATE_MINING))
                        && "停止（終了コード %1$s）".equals(
                        ja.get(StringsTable.MINING_STATE_STOPPED_CODE)));
        boolean officialTermKept = true;
        boolean addressLineKept = true;
        for (String tag : StringsTable.TAGS) {
            java.util.Map<String, String> t = StringsTable.table(tag);
            String label = t.get(StringsTable.MINING_REWARDS_LABEL);
            String how = t.get(StringsTable.MINING_REWARDS_INFO_HOW);
            if (label == null || !label.contains("Inner Hash") || how == null
                    || !how.contains(
                    "quantus-node key quantus --scheme wormhole")) {
                officialTermKept = false;
            }
            // The address on screen is compared with the official
            // tool's output, so the note names the command and the
            // tool's two labels exactly as the tool prints them.
            String note = t.get(StringsTable.MINING_ADDRESS_NOTE);
            if (note == null || !note.contains("Address:")
                    || !note.contains("Inner Hash") || !note.contains(
                    "quantus-node key quantus --scheme wormhole")) {
                addressLineKept = false;
            }
        }
        check("strings: 'Inner Hash' and the official command stay as written"
                + " in every language", officialTermKept);
        check("strings: the address note names the official command, its"
                + " 'Address:' line and 'Inner Hash' as written in every"
                + " language", addressLineKept);
    }

    // ---------------- status text ----------------

    private static MiningLogic.Presentation pres(MiningLogic.UiState s,
                                                 MiningLogic.UiReason r,
                                                 Integer code, String file) {
        return new MiningLogic.Presentation(s, r, code, file);
    }

    private static void testUiText() {
        check("text: the state label, in the reader's language",
                "マイニング中".equals(MiningLogic.stateText("ja", pres(
                        MiningLogic.UiState.MINING, MiningLogic.UiReason.NONE,
                        null, null)))
                        && "Mining".equals(MiningLogic.stateText("en", pres(
                        MiningLogic.UiState.MINING, MiningLogic.UiReason.NONE,
                        null, null))));
        check("text: a stop with an exit code shows the code",
                "停止（終了コード 3）".equals(MiningLogic.stateText("ja", pres(
                        MiningLogic.UiState.STOPPED,
                        MiningLogic.UiReason.GAVE_UP, 3, null)))
                        && "Stopped (exit code 101)".equals(
                        MiningLogic.stateText("en", pres(
                                MiningLogic.UiState.STOPPED,
                                MiningLogic.UiReason.RETRYING, 101, null))));
        check("text: a stop without an exit code is the plain word",
                "停止".equals(MiningLogic.stateText("ja", pres(
                        MiningLogic.UiState.STOPPED,
                        MiningLogic.UiReason.START_FAILED, null, null))));
        check("text: waiting for a file names the file",
                "ノードが用意するファイルを待っています（miner-auth-token）。".equals(
                        MiningLogic.reasonText("ja", pres(
                                MiningLogic.UiState.PREPARING,
                                MiningLogic.UiReason.FILES, null,
                                "miner-auth-token")))
                        && "Waiting for a file the node provides"
                        .concat(" (miner-tls-cert-sha256).").equals(
                        MiningLogic.reasonText("en", pres(
                                MiningLogic.UiState.PREPARING,
                                MiningLogic.UiReason.FILES, null,
                                "miner-tls-cert-sha256"))));
        check("text: mining and off need no reason line",
                MiningLogic.reasonText("ja", pres(MiningLogic.UiState.MINING,
                        MiningLogic.UiReason.NONE, null, null)) == null
                        && MiningLogic.reasonText("ja", pres(
                        MiningLogic.UiState.OFF, MiningLogic.UiReason.NONE,
                        null, null)) == null);
        check("text: a stopped node says what starting it will do",
                "ノードを開始すると、マイニングの準備が始まります。".equals(
                        MiningLogic.reasonText("ja", pres(
                                MiningLogic.UiState.NODE_STOPPED,
                                MiningLogic.UiReason.NONE, null, null))));
        // From the plan to the screen: the node of a fresh install,
        // started with mining already switched on — first while it
        // comes up, then once it has answered (a peer count is known).
        MiningLogic.LaunchPlan noKey =
                MiningLogic.planLaunch(true, VALID, true, false);
        MiningLogic.Presentation comingUp = MiningLogic.present(true, true,
                true, Boolean.valueOf(noKey.active), noKey.skip, false, -1,
                null, 0);
        MiningLogic.Presentation firstStart = MiningLogic.present(true, true,
                true, Boolean.valueOf(noKey.active), noKey.skip, false, 0,
                null, 0);
        check("text: a first start without the network key waits for the"
                        + " node in the existing words",
                "準備中".equals(MiningLogic.stateText("ja", comingUp))
                        && "ノードの起動を待っています。".equals(
                        MiningLogic.reasonText("ja", comingUp)));
        check("text: once that node is up, the existing 'stop and start"
                        + " again' sentence",
                "準備中".equals(MiningLogic.stateText("ja", firstStart))
                        && "ノードを停止して開始し直すと、マイニングが始まります。".equals(
                        MiningLogic.reasonText("ja", firstStart))
                        && "Stop the node and start it again to begin mining."
                        .equals(MiningLogic.reasonText("en", firstStart)));
        check("text: the three kinds of waiting are told apart",
                "ノードの同期が終わるのを待っています。".equals(
                        MiningLogic.reasonText("ja", pres(
                                MiningLogic.UiState.WAITING,
                                MiningLogic.UiReason.WAIT_SYNC, null, null)))
                        && "ピアとの接続を待っています。".equals(
                        MiningLogic.reasonText("ja", pres(
                                MiningLogic.UiState.WAITING,
                                MiningLogic.UiReason.WAIT_PEERS, null, null)))
                        && "ノードからの仕事（ジョブ）を待っています。".equals(
                        MiningLogic.reasonText("ja", pres(
                                MiningLogic.UiState.WAITING,
                                MiningLogic.UiReason.WAIT_JOB, null, null))));
        // fix2: the thermal stop. The Japanese sentence is the one the
        // request gives; the state itself is the plain "stopped" - the
        // miner's exit code after a requested stop is not a cause.
        MiningLogic.Presentation heat = pres(MiningLogic.UiState.STOPPED,
                MiningLogic.UiReason.THERMAL_STOP, null, null);
        check("text: a thermal stop says why mining stopped and what brings"
                        + " it back",
                "端末の温度が高いため、マイニングを停止しました。ノードを再起動すると再開します。".equals(
                        MiningLogic.reasonText("ja", heat))
                        && ("Mining was stopped because the device temperature"
                        + " is high. It resumes when the node is restarted.")
                        .equals(MiningLogic.reasonText("en", heat)));
        check("text: a thermal stop is the plain 'stopped', on the screen and"
                        + " in the notification",
                "停止".equals(MiningLogic.stateText("ja", heat))
                        && "Mining: Stopped".equals(
                        MiningLogic.notificationText("en", heat)));
        boolean everyStateNamed = true;
        boolean everyReasonWorded = true;
        for (String tag : StringsTable.TAGS) {
            for (MiningLogic.UiState st : MiningLogic.UiState.values()) {
                String label = MiningLogic.stateText(tag,
                        pres(st, MiningLogic.UiReason.NONE, null, null));
                if (label == null || label.isEmpty()
                        || label.startsWith("mining_")) {
                    everyStateNamed = false;
                }
            }
            for (MiningLogic.UiReason r : MiningLogic.UiReason.values()) {
                if (r == MiningLogic.UiReason.NONE) {
                    continue;
                }
                String text = MiningLogic.reasonText(tag, pres(
                        MiningLogic.UiState.STOPPED, r, 1, "f"));
                if (text == null || text.isEmpty()
                        || text.startsWith("mining_") || text.contains("%")) {
                    everyReasonWorded = false;
                }
            }
        }
        check("text: every state has a label in every language",
                everyStateNamed);
        check("text: every reason has a sentence in every language",
                everyReasonWorded);
        check("text: the notification says 'mining' in one word while mining",
                "マイニング中".equals(MiningLogic.notificationText("ja", pres(
                        MiningLogic.UiState.MINING, MiningLogic.UiReason.NONE,
                        null, null))));
        check("text: the notification names the other states as mining's",
                "マイニング：待機中".equals(MiningLogic.notificationText("ja",
                        pres(MiningLogic.UiState.WAITING,
                                MiningLogic.UiReason.WAIT_SYNC, null, null)))
                        && "Mining: Stopped (exit code 1)".equals(
                        MiningLogic.notificationText("en", pres(
                                MiningLogic.UiState.STOPPED,
                                MiningLogic.UiReason.GAVE_UP, 1, null))));
        check("text: nothing is added to the notification while mining is off",
                MiningLogic.notificationText("ja", pres(MiningLogic.UiState.OFF,
                        MiningLogic.UiReason.NONE, null, null)) == null);
        check("text: each pending kind has its sentence",
                "ノードを停止して開始し直すと、マイニングが始まります。".equals(
                        MiningLogic.pendingText("ja",
                                MiningLogic.PendingKind.ENABLE))
                        && MiningLogic.pendingText("ja",
                        MiningLogic.PendingKind.DISABLE).startsWith(
                        "マイニングはオフに設定されています。")
                        && MiningLogic.pendingText("ja",
                        MiningLogic.PendingKind.REWARDS_CHANGED).startsWith(
                        "報酬の宛先が変更されています。")
                        && MiningLogic.pendingText("ja",
                        MiningLogic.PendingKind.NONE) == null);
    }

    // ---------------- helpers ----------------

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    static String path(File f) {
        return f == null ? null : f.getPath();
    }

    static File tempDir(String name) throws IOException {
        File base = new File(System.getProperty("java.io.tmpdir"));
        File dir = new File(base, "mining-harness-" + name + "-"
                + System.nanoTime());
        if (!dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        dir.deleteOnExit();
        return dir;
    }

    static void write(File f, String content) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    static List<String> list(String... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    static void skip(String name) {
        skipped++;
        System.out.println("SKIP " + name);
    }
}
