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
        check("strings: the table holds 400 keys - the 329 of v3.0.1, 67 for"
                + " mining, the thermal stop's one and the donation footer's"
                + " three (found " + keys.size()
                + ")", keys.size() == 400);
        int miningKeys = 0;
        int donateKeys = 0;
        String lastKey = null;
        for (String k : keys) {
            if (k.startsWith("mining_")) {
                miningKeys++;
            }
            if (k.startsWith("donate_")) {
                donateKeys++;
            }
            lastKey = k;
        }
        check("strings: 68 keys are mining keys (found " + miningKeys + ")",
                miningKeys == 68);
        check("strings: 3 keys are donation keys (found " + donateKeys + ")",
                donateKeys == 3);
        check("strings: the donation footer's toast is the 400th key",
                "donate_copied".equals(StringsTable.DONATE_COPIED)
                        && "donate_copied".equals(lastKey));
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
                if (!k.startsWith("mining_")) {
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
        check("strings: every mining string is one clean paragraph " + badShape,
                badShape.isEmpty());
        check("strings: no mining string was left in Japanese " + untranslated,
                untranslated.isEmpty());
        check("strings: every mining format string formats " + badFormat,
                badFormat.isEmpty());
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
