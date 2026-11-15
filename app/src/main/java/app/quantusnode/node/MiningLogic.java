package app.quantusnode.node;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure logic of the mining feature (v4.0.0): every decision that
 * can be made without Android, kept in one Android-free class so
 * the rules are exercised on a JVM against the real code (see
 * app/tests/run-mining-tests.sh), in the way {@link UiLogic} and
 * {@link DiagShare} keep the display and share rules.
 *
 * Mining is an opt-in, off by default. When the user turns it on
 * (and has saved the rewards inner hash), the NEXT node start adds
 * three arguments to the node's launch — nothing else about the
 * launch changes — and the service runs the bundled official miner
 * as a second child process that connects to the node over
 * loopback. (Where the node has never run, that start is still one
 * without mining and the start after it mines: see
 * {@link #planLaunch}.) With the setting off, a node start reads the
 * setting and stops there: the launch plan is {@link LaunchPlan#OFF},
 * nothing is added to the command, and none of the rest of this
 * class is reached.
 *
 * What lives here:
 *
 *  - inner-hash validation, by the official node's own rules
 *    (quantus-node v1.0.5, node/src/command.rs): "0x" + 64 hex
 *    digits, decoding to a canonical Poseidon digest. A value the
 *    node would refuse is refused here first — a node that will not
 *    start is worse than a node that starts without mining;
 *  - the two fixed argument lists: what the node gains, and the
 *    miner's whole command. The auth token never appears in an
 *    argument — only the path of the file the node wrote it to;
 *  - redaction for the one app log line that prints a command, and
 *    the scrubber every line of child-process output passes through
 *    while a mining session runs: the inner hash and the token are
 *    replaced before a line reaches a log file, the on-screen
 *    attempt record or a share text;
 *  - classification of the few log lines the state display reads.
 *    The texts are the bundled binaries' own format strings (miner
 *    v4.2.1, node v1.0.5); a line that does not match is simply not
 *    an event, so a future wording change degrades to "no evidence",
 *    never to a wrong claim;
 *  - the restart policy for a miner that exits on its own (three
 *    spaced restarts, then stop), the state presentation and its
 *    wording (which {@link StringsTable} key a state or a reason
 *    is shown with — the table is plain Java too), and the
 *    fixed-English lines the diagnostic share gains while mining is
 *    in use.
 */
public final class MiningLogic {

    private MiningLogic() {
    }

    // ------------------------------------------------------------------
    // Fixed names and values
    // ------------------------------------------------------------------

    /** The bundled miner, packaged like the node binary (lib*.so in
     *  nativeLibraryDir, executed by path). */
    public static final String MINER_LIB = "libquantus_miner.so";
    /** The node's external-miner port; the miner's own default. */
    public static final int MINER_LISTEN_PORT = 9833;
    /** Where the miner connects: this device, nowhere else. */
    public static final String MINER_NODE_ADDR = "127.0.0.1:9833";
    /** Written by the node under {@code <base-path>/chains/<chain>/}. */
    public static final String TOKEN_FILE_NAME = "miner-auth-token";
    /** Written by the node next to the token. */
    public static final String FINGERPRINT_FILE_NAME = "miner-tls-cert-sha256";
    /** The node's network key, in the "network" directory beside them. */
    private static final String NETWORK_KEY_FILE_NAME = "secret_dilithium";
    /** Stands in for a secret in any text the app writes or shares. */
    public static final String REDACTED = "<redacted>";
    /** Stands in for the rewards address in a masked share. */
    public static final String ADDRESS_MASK = "<rewards-address>";

    private static final String LINKER64 = "/system/bin/linker64";
    private static final String ARG_REWARDS = "--rewards-inner-hash";
    private static final String ARG_AUTH_TOKEN = "--auth-token";

    // ------------------------------------------------------------------
    // Rewards inner hash
    // ------------------------------------------------------------------

    /** Outcome of {@link #checkInnerHash}. */
    public enum InnerHashStatus {
        /** Usable: the node will accept it. */
        OK,
        /** Nothing entered. */
        EMPTY,
        /** Not "0x" + 64 hex digits (or the all-zero placeholder). */
        BAD_FORMAT,
        /** 64 hex digits that are not a canonical Poseidon digest. */
        NOT_CANONICAL
    }

    /** The Goldilocks modulus p = 2^64 - 2^32 + 1. */
    private static final long GOLDILOCKS_P = 0xFFFFFFFF00000001L;

    /**
     * Judge a typed or pasted inner hash by the rules the node
     * applies to {@code --rewards-inner-hash} (v1.0.5,
     * node/src/command.rs and qp-poseidon-core 3.1
     * serialization::bytes_to_digest):
     *
     *  - "0x" followed by exactly 64 hex digits. The official key
     *    tool prints the value in that shape ("Inner Hash: 0x…").
     *    A bare 64-digit value is accepted here as well and gains
     *    the prefix on normalization: it names the same 32 bytes,
     *    and refusing it would only make a careful paste fail;
     *  - the 32 bytes must be a canonical digest: four
     *    little-endian u64 limbs, each below the Goldilocks
     *    modulus. A real inner hash always is.
     *
     * Surrounding whitespace is ignored (a paste often carries a
     * newline); whitespace inside the value is not. The all-zero
     * value is refused although it is canonical: it is a
     * placeholder, never a derived value, and rewards sent to its
     * address could not be claimed by anyone.
     *
     * A value that fails here is never stored and never passed to
     * the node, whose answer to a malformed value is to refuse to
     * start.
     */
    public static InnerHashStatus checkInnerHash(String raw) {
        if (raw == null) {
            return InnerHashStatus.EMPTY;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return InnerHashStatus.EMPTY;
        }
        byte[] bytes = decodeInnerHash(s);
        if (bytes == null) {
            return InnerHashStatus.BAD_FORMAT;
        }
        boolean allZero = true;
        for (byte b : bytes) {
            if (b != 0) {
                allZero = false;
                break;
            }
        }
        if (allZero) {
            return InnerHashStatus.BAD_FORMAT;
        }
        for (int limb = 0; limb < 4; limb++) {
            long v = 0;
            for (int j = 7; j >= 0; j--) {
                v = (v << 8) | (bytes[limb * 8 + j] & 0xFFL);
            }
            if (Long.compareUnsigned(v, GOLDILOCKS_P) >= 0) {
                return InnerHashStatus.NOT_CANONICAL;
            }
        }
        return InnerHashStatus.OK;
    }

    /** The 32 bytes of a trimmed "0x…" / bare 64-hex text, or null. */
    private static byte[] decodeInnerHash(String trimmed) {
        String hex = trimmed;
        if (hex.length() >= 2 && hex.charAt(0) == '0'
                && (hex.charAt(1) == 'x' || hex.charAt(1) == 'X')) {
            hex = hex.substring(2);
        }
        if (hex.length() != 64) {
            return null;
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            int hi = hexValue(hex.charAt(i * 2));
            int lo = hexValue(hex.charAt(i * 2 + 1));
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static boolean isHexChar(char c) {
        return hexValue(c) >= 0;
    }

    /** What the inner-hash editor shows for the text typed so far. */
    public enum EntryState {
        /** Nothing typed. */
        EMPTY,
        /** A proper beginning of a well-formed value: not an error yet. */
        INCOMPLETE,
        /** Cannot become a well-formed value (or is 64 zeros). */
        BAD_FORMAT,
        /** Well-formed, but not a value the node accepts. */
        NOT_CANONICAL,
        /** {@link #checkInnerHash} says OK: the value can be saved. */
        OK
    }

    /**
     * {@link #checkInnerHash} for an editor: the same verdicts, plus
     * the one distinction an editor needs — text that is merely
     * unfinished ("0x" and fewer than 64 hex digits so far) is
     * {@link EntryState#INCOMPLETE}, so someone typing the value by
     * hand is not shown an error on every keystroke. Saving is
     * allowed for {@link EntryState#OK} only, which is exactly
     * {@link InnerHashStatus#OK}.
     */
    public static EntryState innerHashEntryState(String raw) {
        switch (checkInnerHash(raw)) {
            case OK:
                return EntryState.OK;
            case EMPTY:
                return EntryState.EMPTY;
            case NOT_CANONICAL:
                return EntryState.NOT_CANONICAL;
            case BAD_FORMAT:
            default:
                break;
        }
        String s = raw.trim();
        if (s.length() >= 2 && s.charAt(0) == '0'
                && (s.charAt(1) == 'x' || s.charAt(1) == 'X')) {
            s = s.substring(2);
        }
        if (s.length() >= 64) {
            return EntryState.BAD_FORMAT;
        }
        for (int i = 0; i < s.length(); i++) {
            if (hexValue(s.charAt(i)) < 0) {
                return EntryState.BAD_FORMAT;
            }
        }
        return EntryState.INCOMPLETE;
    }

    /**
     * The one stored and launched form of an inner hash: "0x" plus
     * 64 lowercase hex digits. Null when the value does not pass
     * {@link #checkInnerHash}. What the editor saves, what the node
     * receives and what the scrubber looks for are all this string.
     */
    public static String normalizeInnerHash(String raw) {
        if (checkInnerHash(raw) != InnerHashStatus.OK) {
            return null;
        }
        String s = raw.trim();
        if (s.charAt(0) == '0' && (s.charAt(1) == 'x' || s.charAt(1) == 'X')
                && s.length() == 66) {
            s = s.substring(2);
        }
        return "0x" + s.toLowerCase(Locale.ROOT);
    }

    /**
     * A short tag that tells two inner hashes apart without being
     * either of them: the first 8 bytes of a SHA-256 over a fixed
     * prefix and the normalized value. The service keeps the tag of
     * the value a node was launched with, so the settings screen
     * can say "saved value differs from the running one" without
     * the launched value itself staying anywhere outside the launch
     * path. Never shown, logged or shared.
     */
    public static String rewardsTag(String normalizedInnerHash) {
        if (normalizedInnerHash == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(("quantus-node-android/rewards-tag/v1:"
                    + normalizedInnerHash).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(d[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 exists on every Android and JVM this code
            // runs on; without a tag the comparison simply reads
            // "unknown", which the caller treats as "changed".
            return null;
        }
    }

    // ------------------------------------------------------------------
    // CPU workers
    // ------------------------------------------------------------------

    /** Stored value of the "auto" choice. */
    public static final int WORKERS_AUTO = 0;
    /**
     * The default choice. The specification's default was 4; the
     * owner's decision of 2026-11-15 lowered it to 2 — a phone that
     * mines must be left headroom, and a user who wants more workers
     * can still choose them. The choices themselves, and what "auto"
     * resolves to, are unchanged.
     */
    public static final int WORKERS_DEFAULT = 2;
    /** Most workers the "auto" choice ever runs. */
    private static final int WORKERS_AUTO_CAP = 4;
    private static final int[] WORKER_CHOICES = {1, 2, 4, WORKERS_AUTO};

    /** The choices offered, in picker order: 1, 2, 4, auto. */
    public static int[] workerChoices() {
        return WORKER_CHOICES.clone();
    }

    /** A stored worker setting as one of the choices; anything
     *  else (a damaged preference) is the default. */
    public static int normalizeWorkerSetting(int stored) {
        for (int c : WORKER_CHOICES) {
            if (c == stored) {
                return stored;
            }
        }
        return WORKERS_DEFAULT;
    }

    /**
     * The worker count actually passed to the miner. Two bounds
     * keep the phone usable and the node fed:
     *
     *  - never more than (cores - 1): one core always stays free
     *    for the node's own sync and import work and for the UI —
     *    the node comes first (specification section 3.5);
     *  - "auto" is half the cores (the miner's own notion of auto),
     *    capped at {@value #WORKERS_AUTO_CAP}: a many-core device
     *    does not silently run hotter than the largest explicit
     *    choice.
     *
     * The result is always at least 1, and the miner is always
     * given an explicit number — its own auto-detection is never
     * relied on.
     */
    public static int effectiveCpuWorkers(int setting, int availableProcessors) {
        int cores = Math.max(1, availableProcessors);
        int limit = Math.max(1, cores - 1);
        int s = normalizeWorkerSetting(setting);
        int wanted = s == WORKERS_AUTO
                ? Math.min(WORKERS_AUTO_CAP, Math.max(1, cores / 2))
                : s;
        return Math.max(1, Math.min(wanted, limit));
    }

    // ------------------------------------------------------------------
    // Launch plan and argument lists
    // ------------------------------------------------------------------

    /** Why an enabled mining setting was not applied to a launch. */
    public enum SkipReason {
        NONE,
        /** No usable inner hash is stored. */
        REWARDS_INVALID,
        /** The bundled miner binary is not in this install. */
        MINER_BINARY_MISSING,
        /** The node has no network key yet (see
         *  {@link #networkKeyExists}); the start that skips mining
         *  for this reason is the one that creates it. */
        NETWORK_KEY_MISSING
    }

    /**
     * What one node launch does about mining. Immutable. An
     * inactive plan has no arguments and carries no value; an
     * active plan carries the normalized inner hash for exactly two
     * uses — the node's argument and the scrubber — and its tag.
     * A plan is never printed.
     */
    public static final class LaunchPlan {
        /** Mining off: the launch is the v3.0.1 launch. */
        public static final LaunchPlan OFF = new LaunchPlan(false,
                SkipReason.NONE, Collections.<String>emptyList(), null, null);

        public final boolean active;
        public final SkipReason skip;
        /** Appended to the node's fixed arguments; empty when inactive. */
        public final List<String> nodeArgs;
        /** Normalized value; null when inactive. */
        public final String innerHash;
        /** {@link #rewardsTag} of {@link #innerHash}; null when inactive. */
        public final String rewardsTag;

        LaunchPlan(boolean active, SkipReason skip, List<String> nodeArgs,
                   String innerHash, String rewardsTag) {
            this.active = active;
            this.skip = skip;
            this.nodeArgs = nodeArgs;
            this.innerHash = innerHash;
            this.rewardsTag = rewardsTag;
        }
    }

    /**
     * Whether the node already has its network key:
     * {@code <base-path>/chains/<chain>/network/secret_dilithium}
     * exists. Started as a validator, the node refuses to run
     * without that file ("NetworkKeyNotFound"; v1.0.5,
     * client/cli/src/params/node_key_params.rs — the test here is
     * the node's own, plain existence); started without the
     * validator role, it creates the file as its network comes up.
     * So the key is missing only where the node has not yet run —
     * a fresh install, or node data that was removed — and a launch
     * with mining there would be a node that does not start.
     */
    public static boolean networkKeyExists(File nodeBasePath, String chain) {
        return new File(new File(new File(new File(nodeBasePath, "chains"),
                chain), "network"), NETWORK_KEY_FILE_NAME).exists();
    }

    /**
     * Decide a launch. With the setting off the answer is
     * {@link LaunchPlan#OFF} and nothing else is examined. With it
     * on, mining is applied only when the launch can actually mine:
     * the stored inner hash must still pass {@link #checkInnerHash}
     * (it did when it was saved; this re-check covers a damaged
     * preferences file), the miner binary must be present, and the
     * node must already have its network key
     * ({@link #networkKeyExists}). Otherwise the node starts
     * exactly as it does without mining — and the reason travels
     * with the plan for the status display — because a node that
     * syncs without mining is the better failure than a node that
     * refuses to start, or a validator with an open endpoint and no
     * miner to use it. The key is asked about last: a start without
     * mining is itself the cure for a missing key, so that reason
     * is kept only when nothing else stands in the way.
     */
    public static LaunchPlan planLaunch(boolean enabled, String innerHashRaw,
                                        boolean minerBinaryPresent,
                                        boolean networkKeyPresent) {
        if (!enabled) {
            return LaunchPlan.OFF;
        }
        String normalized = normalizeInnerHash(innerHashRaw);
        if (normalized == null) {
            return new LaunchPlan(false, SkipReason.REWARDS_INVALID,
                    Collections.<String>emptyList(), null, null);
        }
        if (!minerBinaryPresent) {
            return new LaunchPlan(false, SkipReason.MINER_BINARY_MISSING,
                    Collections.<String>emptyList(), null, null);
        }
        if (!networkKeyPresent) {
            return new LaunchPlan(false, SkipReason.NETWORK_KEY_MISSING,
                    Collections.<String>emptyList(), null, null);
        }
        return new LaunchPlan(true, SkipReason.NONE,
                nodeMiningArgs(normalized), normalized,
                rewardsTag(normalized));
    }

    /**
     * The arguments an opted-in launch adds to the node's fixed
     * set (specification section 2.2): validator role — required by
     * the node for the miner endpoint — the endpoint's port, and
     * the rewards inner hash. Nothing else: the token file and the
     * TLS material stay at the node's defaults under its chain
     * directory.
     */
    public static List<String> nodeMiningArgs(String normalizedInnerHash) {
        List<String> out = new ArrayList<>(5);
        out.add("--validator");
        out.add("--miner-listen-port");
        out.add(String.valueOf(MINER_LISTEN_PORT));
        out.add(ARG_REWARDS);
        out.add(normalizedInnerHash);
        return Collections.unmodifiableList(out);
    }

    /**
     * The miner's whole command (specification section 2.3), fixed
     * except for the worker count:
     *
     *   quantus-miner serve --node-addr 127.0.0.1:9833
     *     --auth-token-file &lt;file&gt; --tls-cert-sha256-file &lt;file&gt;
     *     --cpu-workers &lt;N&gt; --gpu-devices 0
     *
     * The token and the certificate pin are handed over as the
     * paths of the files the node wrote — never as values, so
     * neither can appear in a process listing or in the launch
     * line. GPU mining is switched off explicitly (unverified on
     * devices). Nothing is passed about the miner's metrics port:
     * the app does not use it.
     */
    public static List<String> minerCommand(String minerBinPath, boolean viaLinker,
                                            String tokenFilePath,
                                            String fingerprintFilePath,
                                            int cpuWorkers) {
        List<String> cmd = new ArrayList<>(13);
        if (viaLinker) {
            cmd.add(LINKER64);
        }
        cmd.add(minerBinPath);
        cmd.add("serve");
        cmd.add("--node-addr");
        cmd.add(MINER_NODE_ADDR);
        cmd.add("--auth-token-file");
        cmd.add(tokenFilePath);
        cmd.add("--tls-cert-sha256-file");
        cmd.add(fingerprintFilePath);
        cmd.add("--cpu-workers");
        cmd.add(String.valueOf(Math.max(1, cpuWorkers)));
        cmd.add("--gpu-devices");
        cmd.add("0");
        return Collections.unmodifiableList(cmd);
    }

    // ------------------------------------------------------------------
    // Redaction and scrubbing
    // ------------------------------------------------------------------

    /**
     * A command as one log line, with every secret-bearing element
     * replaced: the value after {@code --rewards-inner-hash} (and
     * its {@code --flag=value} spelling), the value after an inline
     * {@code --auth-token} (the app never builds one; covered
     * anyway), and any element that is itself a valid inner hash.
     * For a command without such elements the result is the plain
     * space-joined command.
     */
    public static String redactCommandForLog(List<String> cmd) {
        StringBuilder sb = new StringBuilder(256);
        boolean redactNext = false;
        for (int i = 0; i < cmd.size(); i++) {
            String a = cmd.get(i);
            if (a == null) {
                a = "";
            }
            String shown = a;
            if (redactNext) {
                shown = REDACTED;
                redactNext = false;
            } else if (a.equals(ARG_REWARDS) || a.equals(ARG_AUTH_TOKEN)) {
                redactNext = true;
            } else if (a.startsWith(ARG_REWARDS + "=")) {
                shown = ARG_REWARDS + "=" + REDACTED;
            } else if (a.startsWith(ARG_AUTH_TOKEN + "=")) {
                shown = ARG_AUTH_TOKEN + "=" + REDACTED;
            } else if (checkInnerHash(a) == InnerHashStatus.OK) {
                shown = REDACTED;
            }
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(shown);
        }
        return sb.toString();
    }

    /**
     * Replaces the session's two secrets wherever they occur in a
     * line of text. Built once per launch (and once per share);
     * immutable.
     *
     * Why it exists although neither bundled binary is meant to
     * print them: the node DOES echo a malformed inner hash to its
     * error output ("Provided: …", node/src/command.rs), and the
     * miner keeps the token in a structure that derives a debug
     * representation. The app's own validation keeps the first
     * path from being reached and nothing prints the second today,
     * but "today" is not a guarantee about text the app writes to
     * a shareable log — so every line of child output passes
     * through here before it is stored, shown or shared.
     *
     * Matching ignores ASCII case, and for the inner hash also
     * takes a "0x" / "0X" standing in front of the 64 digits, so
     * the value is caught in whichever spelling a line carries. A
     * token shorter than {@value #MIN_TOKEN_CHARS} characters is not
     * used: the node generates 64 hex characters, and a short
     * string replaced everywhere would shred ordinary log text
     * without protecting anything.
     */
    public static final class SecretScrubber {
        private static final int MIN_TOKEN_CHARS = 16;
        private static final int MIN_PARTIAL_CHARS = 8;
        private static final SecretScrubber NONE = new SecretScrubber(null, null);

        /** 64 lowercase hex digits, or null. */
        private final String hashCore;
        /** The token as read (trimmed), or null. */
        private final String token;

        private SecretScrubber(String hashCore, String token) {
            this.hashCore = hashCore;
            this.token = token;
        }

        /**
         * @param innerHash an inner hash in any accepted spelling,
         *        or null; a value that is not a valid inner hash
         *        is not used
         * @param authToken the token file's content, or null
         */
        public static SecretScrubber of(String innerHash, String authToken) {
            String normalized = normalizeInnerHash(innerHash);
            String core = normalized == null ? null : normalized.substring(2);
            String tok = authToken == null ? null : authToken.trim();
            if (tok != null && tok.length() < MIN_TOKEN_CHARS) {
                tok = null;
            }
            if (core == null && tok == null) {
                return NONE;
            }
            return new SecretScrubber(core, tok);
        }

        /**
         * This scrubber plus an auth token learned later. The node
         * writes the token file while it starts, so the scrubber
         * built at launch may know only the inner hash; the miner
         * supervisor adds the token once the file exists. Returns
         * this scrubber itself when the token is unusable.
         */
        public SecretScrubber withToken(String authToken) {
            String tok = authToken == null ? null : authToken.trim();
            if (tok == null || tok.length() < MIN_TOKEN_CHARS
                    || tok.equals(token)) {
                return this;
            }
            return new SecretScrubber(hashCore, tok);
        }

        /** True when there is nothing to scrub for. */
        public boolean isEmpty() {
            return hashCore == null && token == null;
        }

        /**
         * The text with every occurrence of a held secret replaced
         * by {@link #REDACTED}. Returns the argument itself when
         * nothing matched.
         */
        public String apply(String text) {
            if (text == null || isEmpty()) {
                return text;
            }
            String out = text;
            if (hashCore != null) {
                out = replaceIgnoreCase(out, hashCore, true);
            }
            if (token != null) {
                out = replaceIgnoreCase(out, token, false);
            }
            return out;
        }

        /**
         * {@link #apply(String)} for a line that may have been cut at
         * a length cap before it got here. The bounded line readers
         * cut an over-long line BEFORE it is scrubbed, so a secret
         * standing across the cut would arrive as its beginning
         * only and match nothing. When {@code cutAtEnd} is set, a
         * line that ends in the first {@value #MIN_PARTIAL_CHARS}
         * or more characters of a held secret loses that ending too.
         * Fewer characters than that are not treated as a secret:
         * they give nothing away, and a shorter rule would eat
         * ordinary hex digits.
         */
        public String apply(String text, boolean cutAtEnd) {
            String out = apply(text);
            if (!cutAtEnd || out == null || isEmpty()) {
                return out;
            }
            int cut = -1;
            if (hashCore != null) {
                cut = partialAtEnd(out, hashCore, true);
            }
            if (token != null) {
                int c = partialAtEnd(out, token, false);
                if (c >= 0 && (cut < 0 || c < cut)) {
                    cut = c;
                }
            }
            return cut < 0 ? out : out.substring(0, cut) + REDACTED;
        }

        /**
         * Where the longest ending of {@code text} that is a proper
         * beginning of {@code needle} starts, or -1.
         */
        private static int partialAtEnd(String text, String needle,
                                        boolean takeHexPrefix) {
            int max = Math.min(text.length(), needle.length() - 1);
            for (int k = max; k >= MIN_PARTIAL_CHARS; k--) {
                int start = text.length() - k;
                if (text.regionMatches(true, start, needle, 0, k)) {
                    if (takeHexPrefix && start >= 2
                            && text.charAt(start - 2) == '0'
                            && (text.charAt(start - 1) == 'x'
                            || text.charAt(start - 1) == 'X')) {
                        start -= 2;
                    }
                    return start;
                }
            }
            return -1;
        }

        private static String replaceIgnoreCase(String text, String needle,
                                                boolean takeHexPrefix) {
            int n = needle.length();
            int idx = indexOfIgnoreCase(text, needle, 0);
            if (idx < 0) {
                return text;
            }
            StringBuilder sb = new StringBuilder(text.length());
            int emitted = 0;
            while (idx >= 0) {
                int start = idx;
                if (takeHexPrefix && start - 2 >= emitted
                        && text.charAt(start - 2) == '0'
                        && (text.charAt(start - 1) == 'x'
                        || text.charAt(start - 1) == 'X')) {
                    start -= 2;
                }
                sb.append(text, emitted, start).append(REDACTED);
                emitted = idx + n;
                idx = indexOfIgnoreCase(text, needle, emitted);
            }
            sb.append(text, emitted, text.length());
            return sb.toString();
        }

        private static int indexOfIgnoreCase(String text, String needle,
                                             int from) {
            int n = needle.length();
            int last = text.length() - n;
            if (n == 0 || last < from) {
                return -1;
            }
            char first = Character.toLowerCase(needle.charAt(0));
            for (int i = from; i <= last; i++) {
                if (Character.toLowerCase(text.charAt(i)) == first
                        && text.regionMatches(true, i, needle, 0, n)) {
                    return i;
                }
            }
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // The node's miner files
    // ------------------------------------------------------------------

    /** Whether the miner's two input files are in place. */
    public enum FilesStatus {
        READY,
        TOKEN_MISSING, TOKEN_UNREADABLE, TOKEN_EMPTY,
        FINGERPRINT_MISSING, FINGERPRINT_UNREADABLE, FINGERPRINT_EMPTY
    }

    /** {@code <base-path>/chains/<chain>/miner-auth-token}. */
    public static File minerTokenFile(File nodeBasePath, String chain) {
        return new File(new File(new File(nodeBasePath, "chains"), chain),
                TOKEN_FILE_NAME);
    }

    /** {@code <base-path>/chains/<chain>/miner-tls-cert-sha256}. */
    public static File minerFingerprintFile(File nodeBasePath, String chain) {
        return new File(new File(new File(nodeBasePath, "chains"), chain),
                FINGERPRINT_FILE_NAME);
    }

    /**
     * The check that gates the miner's start (specification section
     * 3.2): both files must exist as regular files, be readable and
     * be non-empty. The token is checked first, so the status names
     * the first file the node has yet to provide. Neither file's
     * content is read here.
     */
    public static FilesStatus checkMinerFiles(File tokenFile,
                                              File fingerprintFile) {
        FilesStatus t = checkOne(tokenFile, FilesStatus.TOKEN_MISSING,
                FilesStatus.TOKEN_UNREADABLE, FilesStatus.TOKEN_EMPTY);
        if (t != FilesStatus.READY) {
            return t;
        }
        return checkOne(fingerprintFile, FilesStatus.FINGERPRINT_MISSING,
                FilesStatus.FINGERPRINT_UNREADABLE,
                FilesStatus.FINGERPRINT_EMPTY);
    }

    private static FilesStatus checkOne(File f, FilesStatus missing,
                                        FilesStatus unreadable,
                                        FilesStatus empty) {
        try {
            if (f == null || !f.isFile()) {
                return missing;
            }
            if (!f.canRead()) {
                return unreadable;
            }
            if (f.length() <= 0) {
                return empty;
            }
            return FilesStatus.READY;
        } catch (RuntimeException e) {
            return unreadable;
        }
    }

    /** The file a not-ready status is waiting on (a file NAME — the
     *  status line shows it), or null for {@link FilesStatus#READY}. */
    public static String blockingFileName(FilesStatus s) {
        if (s == null) {
            return null;
        }
        switch (s) {
            case TOKEN_MISSING:
            case TOKEN_UNREADABLE:
            case TOKEN_EMPTY:
                return TOKEN_FILE_NAME;
            case FINGERPRINT_MISSING:
            case FINGERPRINT_UNREADABLE:
            case FINGERPRINT_EMPTY:
                return FINGERPRINT_FILE_NAME;
            case READY:
            default:
                return null;
        }
    }

    /**
     * A small text file's content, trimmed, or null when it is
     * missing, unreadable or larger than {@code maxBytes} (never a
     * prefix). Its callers read the node's auth token and hand it
     * to a {@link SecretScrubber} — the only place the app ever
     * holds the token, and only to remove it from text.
     */
    public static String readSmallTextFile(File f, int maxBytes) {
        try {
            if (f == null || !f.isFile() || f.length() > maxBytes) {
                return null;
            }
            byte[] buf = new byte[maxBytes + 1];
            int total = 0;
            try (FileInputStream in = new FileInputStream(f)) {
                int n;
                while (total < buf.length
                        && (n = in.read(buf, total, buf.length - total)) != -1) {
                    total += n;
                }
            }
            if (total > maxBytes) {
                return null;
            }
            return new String(buf, 0, total, StandardCharsets.UTF_8).trim();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Log lines (bundled miner v4.2.1, bundled node v1.0.5)
    // ------------------------------------------------------------------

    /** What a line of miner output evidences. */
    public enum MinerEvent {
        NONE,
        /** "🧱 New block to mine: job N (header 0x…)" */
        JOB_RECEIVED,
        /** "✅ Job N solved: … - submitting solution to node" */
        SOLUTION_SUBMITTED,
        /** "CPU worker N stopped (…): H hashes in S s (…)" */
        WORKER_REPORT,
        /** "Metrics available at http://0.0.0.0:PORT/metrics" */
        METRICS_ANNOUNCED,
        /** "… 'http-exporter' feature is disabled; ignoring" */
        METRICS_DISABLED_NOTE
    }

    private static final String M_JOB = "New block to mine: job ";
    private static final String M_SOLVED = " solved: ";
    private static final String M_SUBMITTING = "submitting solution to node";
    private static final String M_HASHES_IN = " hashes in ";
    private static final String M_METRICS = "Metrics available at http://";
    private static final String M_METRICS_OFF =
            "'http-exporter' feature is disabled";
    /** miner-service log_worker_completion: "{type} worker {id}
     *  {outcome}: {count} hashes in {secs:.2}s ({rate})". */
    private static final Pattern WORKER_REPORT = Pattern.compile(
            "\\b(CPU|GPU) worker (\\d{1,4}) [^:]{0,80}: (\\d{1,18}) hashes in"
                    + " (\\d{1,9}(?:\\.\\d{1,3})?)s");

    /** Classify one line of miner output; unknown text is NONE. */
    public static MinerEvent classifyMinerLine(String line) {
        if (line == null || line.isEmpty()) {
            return MinerEvent.NONE;
        }
        if (line.contains(M_JOB)) {
            return MinerEvent.JOB_RECEIVED;
        }
        if (line.contains(M_SOLVED) && line.contains(M_SUBMITTING)) {
            return MinerEvent.SOLUTION_SUBMITTED;
        }
        if (parseWorkerReport(line) != null) {
            return MinerEvent.WORKER_REPORT;
        }
        if (line.contains(M_METRICS_OFF)) {
            return MinerEvent.METRICS_DISABLED_NOTE;
        }
        if (line.contains(M_METRICS)) {
            return MinerEvent.METRICS_ANNOUNCED;
        }
        return MinerEvent.NONE;
    }

    /** One worker's report at the end of a job. */
    public static final class WorkerReport {
        /** "CPU 0", "CPU 1", … */
        public final String worker;
        public final long hashes;
        public final double seconds;

        WorkerReport(String worker, long hashes, double seconds) {
            this.worker = worker;
            this.hashes = hashes;
            this.seconds = seconds;
        }
    }

    /** The numbers of a worker report line, or null when the line
     *  is not one. */
    public static WorkerReport parseWorkerReport(String line) {
        if (line == null || !line.contains(M_HASHES_IN)) {
            return null;
        }
        Matcher m = WORKER_REPORT.matcher(line);
        if (!m.find()) {
            return null;
        }
        try {
            return new WorkerReport(m.group(1) + " " + m.group(2),
                    Long.parseLong(m.group(3)),
                    Double.parseDouble(m.group(4)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** What a line of node output evidences about mining. */
    public enum NodeEvent {
        NONE,
        /** "🥇 Successfully mined and submitted a new block …" */
        BLOCK_ACCEPTED,
        /** "⛏️ Failed to submit seal from miner N, …" */
        SEAL_REJECTED,
        /** "⛏️ Miner server listening on port N" */
        ENDPOINT_LISTENING,
        /** "⛏️ Rewards wormhole address: <ss58>" */
        REWARDS_ADDRESS
    }

    private static final String N_ACCEPTED =
            "Successfully mined and submitted a new block";
    private static final String N_REJECTED = "Failed to submit seal from miner";
    private static final String N_LISTENING = "Miner server listening on port";
    private static final String N_ADDRESS = "Rewards wormhole address: ";

    /** Classify one line of node output; unknown text is NONE. */
    public static NodeEvent classifyNodeLine(String line) {
        if (line == null || line.isEmpty()) {
            return NodeEvent.NONE;
        }
        if (line.contains(N_ACCEPTED)) {
            return NodeEvent.BLOCK_ACCEPTED;
        }
        if (line.contains(N_REJECTED)) {
            return NodeEvent.SEAL_REJECTED;
        }
        if (line.contains(N_LISTENING)) {
            return NodeEvent.ENDPOINT_LISTENING;
        }
        if (line.contains(N_ADDRESS)) {
            return NodeEvent.REWARDS_ADDRESS;
        }
        return NodeEvent.NONE;
    }

    /**
     * The address after the "Rewards wormhole address: " marker in
     * a line of node output. Null unless the text after the marker
     * is a run of base58 characters of an address's length.
     *
     * The node prints the destination it derived from the inner
     * hash it was launched with in this form — once, while it
     * starts, before it opens any network endpoint (v1.0.5,
     * node/src/command.rs). Reading that line is how the app can
     * show the user where rewards will go without carrying an
     * implementation of the derivation. The text alone does not
     * say who wrote it, though: the node also logs what a peer on
     * its miner port sent before authenticating
     * (node/src/miner_server.rs), so the marker can stand in a
     * later line that is not the node's statement at all. This
     * method only reads a line; which line counts is
     * {@link MinerStats#onNodeLine}'s decision.
     */
    public static String parseRewardsAddress(String line) {
        if (line == null) {
            return null;
        }
        int at = line.indexOf(N_ADDRESS);
        if (at < 0) {
            return null;
        }
        return addressAt(line, at + N_ADDRESS.length());
    }

    private static String addressAt(String text, int from) {
        int end = from;
        while (end < text.length() && isBase58(text.charAt(end))) {
            end++;
        }
        int len = end - from;
        if (len < 40 || len > 60) {
            return null;
        }
        if (end < text.length() && isAsciiAlnum(text.charAt(end))) {
            return null; // ran into a non-base58 letter: not an address
        }
        return text.substring(from, end);
    }

    private static boolean isBase58(char c) {
        return (c >= '1' && c <= '9')
                || (c >= 'A' && c <= 'Z' && c != 'I' && c != 'O')
                || (c >= 'a' && c <= 'z' && c != 'l');
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z');
    }

    // ------------------------------------------------------------------
    // Session statistics
    // ------------------------------------------------------------------

    /** How long a worker's last report keeps counting toward the
     *  hashrate, and how long a job counts as recent. Jobs follow
     *  the 12-second block cadence; two minutes without one means
     *  the node has stopped handing out work. */
    static final long RECENT_MS = 120_000L;

    /** An immutable view of {@link MinerStats}. */
    public static final class StatsSnapshot {
        public static final StatsSnapshot EMPTY =
                new StatsSnapshot(0, 0, 0, 0, -1, -1.0, null, false, false);

        public final long jobsReceived;
        public final long solutionsSubmitted;
        public final long blocksAccepted;
        public final long sealsRejected;
        /** Monotonic time of the last job, -1 while none arrived. */
        public final long lastJobElapsedMs;
        /** Hashes per second, negative while unknown. */
        public final double hashrateHps;
        /** The node's reported rewards address, or null. */
        public final String rewardsAddress;
        public final boolean endpointListening;
        /** The running miner announced a live metrics endpoint. */
        public final boolean metricsExporterActive;

        StatsSnapshot(long jobsReceived, long solutionsSubmitted,
                      long blocksAccepted, long sealsRejected,
                      long lastJobElapsedMs, double hashrateHps,
                      String rewardsAddress, boolean endpointListening,
                      boolean metricsExporterActive) {
            this.jobsReceived = jobsReceived;
            this.solutionsSubmitted = solutionsSubmitted;
            this.blocksAccepted = blocksAccepted;
            this.sealsRejected = sealsRejected;
            this.lastJobElapsedMs = lastJobElapsedMs;
            this.hashrateHps = hashrateHps;
            this.rewardsAddress = rewardsAddress;
            this.endpointListening = endpointListening;
            this.metricsExporterActive = metricsExporterActive;
        }
    }

    /**
     * What one node session's log lines have evidenced about
     * mining: counts of jobs received and solutions submitted (from
     * the miner's lines), of blocks accepted and seals rejected
     * (from the node's — the wire protocol carries no
     * acknowledgement back to the miner, so acceptance is only ever
     * visible on the node's side), the workers' latest hash reports
     * and the node's reported rewards address. Fed by the two drain
     * threads, read by the UI tick; every method is synchronized.
     * The counters belong to the node session and survive a miner
     * restart; the per-process facts (worker reports, the metrics
     * announcement) are reset by {@link #onMinerProcessStarted}.
     *
     * The rewards address is the first one reported in the node
     * process's output (the service creates one of these per node
     * process) and is never replaced; once the node has announced
     * its miner endpoint, none is taken at all. The node's own
     * report comes first, ahead of that announcement, and whatever
     * carries the marker afterwards may be text a peer on the miner
     * port chose (see {@link #parseRewardsAddress}) — the address
     * on screen is what the user checks the destination against,
     * so such a line must not be able to change it. The two counts
     * read from the node's lines have no such anchor and can be
     * inflated the same way; the UI calls them estimates.
     */
    public static final class MinerStats {
        private static final int MAX_WORKERS = 64;

        private long jobsReceived;
        private long solutionsSubmitted;
        private long blocksAccepted;
        private long sealsRejected;
        private long lastJobElapsedMs = -1;
        private String rewardsAddress;
        private boolean endpointListening;
        private boolean sawExporterDisabledNote;
        private boolean metricsExporterActive;
        /** worker -> {hashes per second, report time}. */
        private final Map<String, double[]> workers = new LinkedHashMap<>();

        public synchronized void onMinerProcessStarted(long nowElapsedMs) {
            workers.clear();
            sawExporterDisabledNote = false;
            metricsExporterActive = false;
        }

        public synchronized void onMinerLine(String line, long nowElapsedMs) {
            switch (classifyMinerLine(line)) {
                case JOB_RECEIVED:
                    jobsReceived++;
                    lastJobElapsedMs = nowElapsedMs;
                    break;
                case SOLUTION_SUBMITTED:
                    solutionsSubmitted++;
                    break;
                case WORKER_REPORT: {
                    WorkerReport r = parseWorkerReport(line);
                    if (r != null && r.seconds > 0
                            && (workers.containsKey(r.worker)
                            || workers.size() < MAX_WORKERS)) {
                        workers.put(r.worker, new double[] {
                                r.hashes / r.seconds, nowElapsedMs});
                    }
                    break;
                }
                case METRICS_DISABLED_NOTE:
                    sawExporterDisabledNote = true;
                    break;
                case METRICS_ANNOUNCED:
                    // The announcement is printed unconditionally;
                    // only a build without the exporter prints the
                    // "disabled" note ahead of it.
                    metricsExporterActive = !sawExporterDisabledNote;
                    break;
                case NONE:
                default:
                    break;
            }
        }

        public synchronized void onNodeLine(String line, long nowElapsedMs) {
            switch (classifyNodeLine(line)) {
                case BLOCK_ACCEPTED:
                    blocksAccepted++;
                    break;
                case SEAL_REJECTED:
                    sealsRejected++;
                    break;
                case ENDPOINT_LISTENING:
                    endpointListening = true;
                    break;
                case REWARDS_ADDRESS:
                    // The first report only, and none once the
                    // miner endpoint is announced (see the class
                    // comment): a later line with the marker is
                    // not the node's own report.
                    if (rewardsAddress == null && !endpointListening) {
                        rewardsAddress = parseRewardsAddress(line);
                    }
                    break;
                case NONE:
                default:
                    break;
            }
        }

        public synchronized StatsSnapshot snapshot(long nowElapsedMs) {
            double sum = 0;
            boolean any = false;
            for (double[] w : workers.values()) {
                if (nowElapsedMs - (long) w[1] <= RECENT_MS) {
                    sum += w[0];
                    any = true;
                }
            }
            return new StatsSnapshot(jobsReceived, solutionsSubmitted,
                    blocksAccepted, sealsRejected, lastJobElapsedMs,
                    any ? sum : -1.0, rewardsAddress, endpointListening,
                    metricsExporterActive);
        }
    }

    // ------------------------------------------------------------------
    // Restart policy
    // ------------------------------------------------------------------

    /** {@link RestartPolicy#onAbnormalExit} answer: do not restart. */
    public static final long GIVE_UP = -1L;

    /**
     * What to do when the miner exits without having been asked to
     * (specification section 3.2: restarts stay modest and stop
     * after repeated failures). The miner is restarted after a
     * growing pause — 10 s, 30 s, 60 s — and a fourth failure in a
     * row is the end: the status shows the exit code and nothing
     * further happens until the user restarts the miner or the
     * node. A run of ten minutes or more counts as healthy and
     * starts the count afresh, so a miner that fails once a day is
     * simply restarted, while one that cannot stay up is left
     * alone. None of this touches the node process: the node keeps
     * syncing and relaying whatever the miner does.
     */
    public static final class RestartPolicy {
        private static final long[] DEFAULT_DELAYS_MS = {10_000L, 30_000L, 60_000L};
        private static final long DEFAULT_STABLE_RUN_MS = 600_000L;

        private final long[] delaysMs;
        private final long stableRunMs;
        private int consecutive;
        private int restartsTotal;

        public RestartPolicy() {
            this(DEFAULT_DELAYS_MS, DEFAULT_STABLE_RUN_MS);
        }

        /** Test seam: the same rules on a shorter clock. */
        RestartPolicy(long[] delaysMs, long stableRunMs) {
            this.delaysMs = delaysMs.clone();
            this.stableRunMs = stableRunMs;
        }

        /**
         * @param livedMs how long the exited process had run
         * @return the pause before the restart, or {@link #GIVE_UP}
         */
        public synchronized long onAbnormalExit(long livedMs) {
            if (livedMs >= stableRunMs) {
                consecutive = 0;
            }
            if (consecutive >= delaysMs.length) {
                return GIVE_UP;
            }
            long delay = delaysMs[consecutive];
            consecutive++;
            restartsTotal++;
            return delay;
        }

        /** A user-requested restart starts the count afresh. */
        public synchronized void reset() {
            consecutive = 0;
        }

        public synchronized int consecutiveFailures() {
            return consecutive;
        }

        public synchronized int restartsTotal() {
            return restartsTotal;
        }
    }

    // ------------------------------------------------------------------
    // Thermal protection
    // ------------------------------------------------------------------

    // The device's thermal status, as the platform reports it
    // (PowerManager.THERMAL_STATUS_*; the numbers are the platform's
    // and are passed through unchanged).
    public static final int THERMAL_NONE = 0;
    public static final int THERMAL_LIGHT = 1;
    public static final int THERMAL_MODERATE = 2;
    public static final int THERMAL_SEVERE = 3;
    public static final int THERMAL_CRITICAL = 4;
    public static final int THERMAL_EMERGENCY = 5;
    public static final int THERMAL_SHUTDOWN = 6;

    /** {@link ThermalGovernor}'s cap while nothing has lowered it. */
    public static final int THERMAL_NO_CAP = Integer.MAX_VALUE;

    /** What a thermal status asks of the miner. */
    public enum ThermalAction {
        /** Nothing: the miner, if any, runs within the cap. */
        NONE,
        /** Restart the miner; it comes back with fewer workers. */
        REDUCE,
        /** End the miner for the rest of the session. */
        STOP
    }

    /** {@link ThermalGovernor}'s answer to one status. Immutable. */
    public static final class ThermalDecision {
        public final ThermalAction action;
        /** The cap after this status; {@link #THERMAL_NO_CAP} while
         *  nothing has lowered it. */
        public final int cap;

        ThermalDecision(ThermalAction action, int cap) {
            this.action = action;
            this.cap = cap;
        }
    }

    /**
     * The miner as the thermal protection needs it: how many workers
     * run right now, and the two things that can be done about it.
     * {@link MinerSupervisor} is the implementation.
     */
    public interface ThermalControl {
        /** Workers of the miner process that is alive or being
         *  started; 0 while there is none. */
        int runningWorkers();

        /** Restart the miner process alone; a start reads its worker
         *  count afresh, so it comes back within the cap. */
        void requestRestart();

        /** End the miner for the rest of the session. */
        void requestThermalStop();
    }

    /**
     * What the device's temperature means for the miner, for one node
     * session (fix2). The owner's rule is that the phone is not to be
     * pushed: mining backs off when the device gets warm and ends
     * when it gets hot, and it does not come back by itself.
     *
     *  - NONE and LIGHT: nothing. In particular a cap that has been
     *    lowered is not raised again — a device that needed the cap
     *    once is given the headroom for the rest of the session;
     *  - MODERATE: the cap becomes half the worker count the setting
     *    resolves to (rounded down, at least 1), unless it is lower
     *    already. A miner running above the cap is to be restarted
     *    ({@link ThermalAction#REDUCE}); it starts again within the
     *    cap, because every start takes its worker count through
     *    {@link #capWorkers};
     *  - SEVERE and above: {@link ThermalAction#STOP}, latched. From
     *    then on every status is answered with STOP and nothing else
     *    changes: a device that cools down does not start mining
     *    again. One governor serves one session of the service and
     *    is shared by the node processes that session launches, so
     *    the app's own restarts do not clear the cap or the stop;
     *    the user's next start begins with a fresh governor.
     *
     * A status outside the platform's range is read by the same two
     * comparisons: below NONE is not heat, above SHUTDOWN is.
     *
     * One of these exists per node process launched with mining. It
     * is fed by the service's thermal listener and read by the miner
     * supervisor's worker count; every method is synchronized, and
     * none calls out while holding the lock.
     */
    public static final class ThermalGovernor {
        private int cap = THERMAL_NO_CAP;
        private boolean stopped;

        /**
         * Take one status in and say what it asks of the miner.
         *
         * @param status          the platform's thermal status
         * @param resolvedWorkers what the worker setting resolves to
         *                        ({@link #effectiveCpuWorkers}), before
         *                        any cap
         * @param runningWorkers  workers of the miner that is running
         *                        or being started; 0 when none is
         */
        public synchronized ThermalDecision onStatus(int status,
                                                     int resolvedWorkers,
                                                     int runningWorkers) {
            note(status, resolvedWorkers);
            return judge(status, runningWorkers);
        }

        /**
         * {@link #onStatus} for a live miner, and the action carried
         * out. The order of the two halves is the point: the status
         * is taken in first — the cap comes down, or the stop is
         * latched — and only then is the miner asked what it runs
         * with. A miner start that read its worker count before the
         * cap came down has published that count with its STARTING
         * phase under the supervisor's lock by the time it is asked
         * (MinerSupervisor.runningWorkers counts a start in flight)
         * and is reduced here; one that reads afterwards gets the
         * cap. Asking first and lowering the cap second would leave
         * a start in between that neither side sees.
         */
        public ThermalDecision apply(int status, int resolvedWorkers,
                                     ThermalControl miner) {
            synchronized (this) {
                note(status, resolvedWorkers);
            }
            int running = miner.runningWorkers();
            ThermalDecision d;
            synchronized (this) {
                d = judge(status, running);
            }
            switch (d.action) {
                case REDUCE:
                    miner.requestRestart();
                    break;
                case STOP:
                    miner.requestThermalStop();
                    break;
                case NONE:
                default:
                    break;
            }
            return d;
        }

        /** The state change of a status. Caller holds the lock. */
        private void note(int status, int resolvedWorkers) {
            if (stopped) {
                return;
            }
            if (status >= THERMAL_SEVERE) {
                stopped = true;
            } else if (status == THERMAL_MODERATE) {
                int half = Math.max(1, resolvedWorkers / 2);
                if (half < cap) {
                    cap = half;
                }
            }
        }

        /** What the state asks of the miner. Caller holds the lock. */
        private ThermalDecision judge(int status, int runningWorkers) {
            if (stopped) {
                return new ThermalDecision(ThermalAction.STOP, cap);
            }
            if (status == THERMAL_MODERATE && runningWorkers > cap) {
                return new ThermalDecision(ThermalAction.REDUCE, cap);
            }
            return new ThermalDecision(ThermalAction.NONE, cap);
        }

        /** The cap; {@link #THERMAL_NO_CAP} while nothing lowered it. */
        public synchronized int cap() {
            return cap;
        }

        /** True once a status of SEVERE or above was taken in. */
        public synchronized boolean stopped() {
            return stopped;
        }

        /**
         * The worker count a miner start may use: what the setting
         * resolves to, or the cap where that is lower.
         */
        public synchronized int capWorkers(int resolvedWorkers) {
            return Math.min(resolvedWorkers, cap);
        }
    }

    /** A thermal status for the app log: "moderate (2)". */
    public static String thermalStatusText(int status) {
        String name;
        switch (status) {
            case THERMAL_NONE:
                name = "none";
                break;
            case THERMAL_LIGHT:
                name = "light";
                break;
            case THERMAL_MODERATE:
                name = "moderate";
                break;
            case THERMAL_SEVERE:
                name = "severe";
                break;
            case THERMAL_CRITICAL:
                name = "critical";
                break;
            case THERMAL_EMERGENCY:
                name = "emergency";
                break;
            case THERMAL_SHUTDOWN:
                name = "shutdown";
                break;
            default:
                name = "unknown";
                break;
        }
        return name + " (" + status + ")";
    }

    // ------------------------------------------------------------------
    // Supervisor snapshot and presentation
    // ------------------------------------------------------------------

    /** What the miner supervisor is doing. */
    public enum Phase {
        /** No miner and none wanted (before start, after stop). */
        IDLE,
        /** The node has not come up yet. */
        WAITING_NODE,
        /** The node's token / fingerprint files are not ready. */
        WAITING_FILES,
        /** The miner process is being spawned. */
        STARTING,
        /** The miner process is alive. */
        RUNNING,
        /** Exited on its own; an automatic restart is scheduled. */
        BACKOFF,
        /** Exited on its own; automatic restarts are exhausted. */
        GAVE_UP,
        /** The miner binary is not there. */
        BINARY_MISSING,
        /** The miner could not be spawned, directly or via linker64. */
        START_FAILED
    }

    /** An immutable view of the supervisor for the UI and the share. */
    public static final class MinerSnapshot {
        public final Phase phase;
        public final FilesStatus files;
        /** Exit code of the last miner process, or null. */
        public final Integer lastExitCode;
        public final int consecutiveFailures;
        public final int restartsTotal;
        /** Workers of the current (or last) miner process, 0 before one. */
        public final int cpuWorkers;
        public final boolean viaLinker;
        public final StatsSnapshot stats;
        /** Last lines of miner output, already scrubbed. */
        public final List<String> logTail;
        /** The session's thermal worker cap ({@link ThermalGovernor});
         *  {@link #THERMAL_NO_CAP} while nothing has lowered it. */
        public final int thermalCap;
        /** The thermal protection ended the miner for this session. */
        public final boolean thermalStopped;

        /** A snapshot of a session the thermal protection has not
         *  touched: no cap, no stop. */
        public MinerSnapshot(Phase phase, FilesStatus files, Integer lastExitCode,
                             int consecutiveFailures, int restartsTotal,
                             int cpuWorkers, boolean viaLinker,
                             StatsSnapshot stats, List<String> logTail) {
            this(phase, files, lastExitCode, consecutiveFailures, restartsTotal,
                    cpuWorkers, viaLinker, stats, logTail, THERMAL_NO_CAP,
                    false);
        }

        public MinerSnapshot(Phase phase, FilesStatus files, Integer lastExitCode,
                             int consecutiveFailures, int restartsTotal,
                             int cpuWorkers, boolean viaLinker,
                             StatsSnapshot stats, List<String> logTail,
                             int thermalCap, boolean thermalStopped) {
            this.phase = phase;
            this.files = files;
            this.lastExitCode = lastExitCode;
            this.consecutiveFailures = consecutiveFailures;
            this.restartsTotal = restartsTotal;
            this.cpuWorkers = cpuWorkers;
            this.viaLinker = viaLinker;
            this.stats = stats == null ? StatsSnapshot.EMPTY : stats;
            this.logTail = logTail == null
                    ? Collections.<String>emptyList() : logTail;
            this.thermalCap = thermalCap;
            this.thermalStopped = thermalStopped;
        }
    }

    /**
     * The handle {@link NodeState} publishes for the running
     * session's miner: the UI takes a snapshot per tick and may ask
     * for a restart of the miner process alone.
     */
    public interface MinerView {
        MinerSnapshot snapshot(long nowElapsedMs);

        /** Restart only the miner process (never the node). */
        void requestRestart();
    }

    /** The states the status row shows (specification section 3.4). */
    public enum UiState {
        /** Mining is off. */
        OFF,
        /** Mining is on, the node is not running. */
        NODE_STOPPED,
        /** Getting there: node restart, node start-up, files, spawn. */
        PREPARING,
        /** The miner runs and no job has arrived lately. */
        WAITING,
        /** The miner runs and jobs are arriving. */
        MINING,
        /** The miner is not running and something is wrong. */
        STOPPED
    }

    /** The reason line under the state. */
    public enum UiReason {
        NONE,
        REWARDS_INVALID, BINARY_MISSING,
        RESTART_TO_ENABLE, NODE_STARTING, FILES, MINER_STARTING,
        WAIT_SYNC, WAIT_PEERS, WAIT_JOB,
        RETRYING, GAVE_UP, START_FAILED,
        /** The thermal protection ended the miner for this session. */
        THERMAL_STOP
    }

    /** One refresh's answer for the status rows. */
    public static final class Presentation {
        public final UiState state;
        public final UiReason reason;
        /** The miner's exit code when {@link UiState#STOPPED} has one. */
        public final Integer exitCode;
        /** The file being waited on when the reason is FILES. */
        public final String fileName;

        Presentation(UiState state, UiReason reason, Integer exitCode,
                     String fileName) {
            this.state = state;
            this.reason = reason;
            this.exitCode = exitCode;
            this.fileName = fileName;
        }
    }

    private static Presentation p(UiState state, UiReason reason) {
        return new Presentation(state, reason, null, null);
    }

    /**
     * Map facts onto the status rows. The rules, in order:
     *
     *  1. A stopped node: OFF, or NODE_STOPPED while the setting is
     *     on. Whatever a previous session's miner last reported is
     *     history, not state.
     *  2. A running node that was (or is being) launched without
     *     mining: OFF when the setting is off; otherwise the reason
     *     mining is not part of this launch — the node simply has
     *     to be restarted (that includes a first start, which had
     *     no network key to be a validator with), or the launch
     *     skipped mining because the stored inner hash or the miner
     *     binary was unusable.
     *  3. A node launched with mining shows what the miner is
     *     actually doing, even after the setting was switched off —
     *     it keeps mining until the node is restarted, and the
     *     display must not claim otherwise.
     *  4. Within rule 3, a miner the thermal protection has stopped
     *     is STOPPED for that reason, whatever phase the supervisor
     *     was in when the stop came (fix2). The stop is final for
     *     the session, so it outranks every other reason a miner can
     *     be down for — retrying, gave up, could not start, binary
     *     missing: none of those is coming back either, and the
     *     heat is the one the user can act on. No exit code is
     *     shown with it; the code a miner leaves when it is told to
     *     stop is not a cause. Rules 1 and 2 still come first: the
     *     stop belongs to the node process it happened in.
     *
     * MINING is claimed on evidence only: a job seen within the
     * last two minutes. A live miner without one is WAITING, and
     * the reason comes from the node's own state — still syncing,
     * no peers, or simply no job yet. That is the normal picture
     * while the node catches up: the node hands out work only once
     * it is synced and connected.
     *
     * @param enabledSetting  the saved mining switch
     * @param rewardsValid    the saved inner hash passes the check
     * @param nodeRunning     a node session is live
     * @param launchedMining  whether this session's node process
     *                        was launched with mining; null before
     *                        the launch
     * @param launchSkip      why an enabled launch skipped mining
     * @param nodeSynced      the node reports "synced"
     * @param peers           connected peers; -1 while unknown
     *                        (unknown is never a measured zero)
     * @param miner           the supervisor's snapshot (it carries the
     *                        session's thermal cap and stop), or null
     * @param nowElapsedMs    the monotonic clock the snapshot's
     *                        times were taken on
     */
    public static Presentation present(boolean enabledSetting, boolean rewardsValid,
                                       boolean nodeRunning, Boolean launchedMining,
                                       SkipReason launchSkip, boolean nodeSynced,
                                       int peers, MinerSnapshot miner,
                                       long nowElapsedMs) {
        if (!nodeRunning) {
            if (!enabledSetting) {
                return p(UiState.OFF, UiReason.NONE);
            }
            return p(UiState.NODE_STOPPED, rewardsValid
                    ? UiReason.NONE : UiReason.REWARDS_INVALID);
        }
        if (launchedMining == null) {
            return enabledSetting
                    ? p(UiState.PREPARING, UiReason.NODE_STARTING)
                    : p(UiState.OFF, UiReason.NONE);
        }
        if (!launchedMining) {
            if (!enabledSetting) {
                return p(UiState.OFF, UiReason.NONE);
            }
            if (launchSkip == SkipReason.MINER_BINARY_MISSING) {
                return p(UiState.STOPPED, UiReason.BINARY_MISSING);
            }
            if (launchSkip == SkipReason.REWARDS_INVALID || !rewardsValid) {
                return p(UiState.STOPPED, UiReason.REWARDS_INVALID);
            }
            if (launchSkip == SkipReason.NETWORK_KEY_MISSING
                    && !nodeSynced && peers < 0) {
                // This launch is the one that creates the key, and
                // the node has not answered yet: a restart now would
                // only repeat the same launch. Wait for the node;
                // once it answers, the restart advice below is the
                // whole story.
                return p(UiState.PREPARING, UiReason.NODE_STARTING);
            }
            return p(UiState.PREPARING, UiReason.RESTART_TO_ENABLE);
        }
        if (miner == null) {
            return p(UiState.PREPARING, UiReason.MINER_STARTING);
        }
        if (miner.thermalStopped) {
            return p(UiState.STOPPED, UiReason.THERMAL_STOP);
        }
        switch (miner.phase) {
            case WAITING_NODE:
                return p(UiState.PREPARING, UiReason.NODE_STARTING);
            case WAITING_FILES:
                return new Presentation(UiState.PREPARING, UiReason.FILES,
                        null, blockingFileName(miner.files));
            case RUNNING: {
                long lastJob = miner.stats.lastJobElapsedMs;
                if (lastJob >= 0 && nowElapsedMs - lastJob <= RECENT_MS) {
                    return p(UiState.MINING, UiReason.NONE);
                }
                if (!nodeSynced) {
                    return p(UiState.WAITING, UiReason.WAIT_SYNC);
                }
                if (peers == 0) {
                    return p(UiState.WAITING, UiReason.WAIT_PEERS);
                }
                return p(UiState.WAITING, UiReason.WAIT_JOB);
            }
            case BACKOFF:
                return new Presentation(UiState.STOPPED, UiReason.RETRYING,
                        miner.lastExitCode, null);
            case GAVE_UP:
                return new Presentation(UiState.STOPPED, UiReason.GAVE_UP,
                        miner.lastExitCode, null);
            case BINARY_MISSING:
                return p(UiState.STOPPED, UiReason.BINARY_MISSING);
            case START_FAILED:
                return p(UiState.STOPPED, UiReason.START_FAILED);
            case STARTING:
            case IDLE:
            default:
                return p(UiState.PREPARING, UiReason.MINER_STARTING);
        }
    }

    /**
     * Whether restarting the NODE would change what it runs with —
     * the mining counterpart of {@link UiLogic#settingsPending}: no
     * running node or no launch snapshot means nothing is pending;
     * otherwise the launch is compared with what the saved settings
     * would launch now (mining on with a usable inner hash, and
     * which inner hash). A launch that skipped mining because the
     * miner binary is missing is not pending: a restart would skip
     * it again. One that skipped it for want of the node's network
     * key is: that start is where the node creates the key.
     */
    public static boolean nodeRestartPending(boolean nodeRunning,
                                             Boolean launchedMining,
                                             String launchedRewardsTag,
                                             SkipReason launchSkip,
                                             boolean wantEnabled,
                                             String wantInnerHashRaw) {
        if (!nodeRunning || launchedMining == null) {
            return false;
        }
        String wantNormalized = wantEnabled
                ? normalizeInnerHash(wantInnerHashRaw) : null;
        boolean wantActive = wantNormalized != null;
        if (!launchedMining) {
            return wantActive
                    && launchSkip != SkipReason.MINER_BINARY_MISSING;
        }
        if (!wantActive) {
            return true;
        }
        String wantTag = rewardsTag(wantNormalized);
        return wantTag == null || !wantTag.equals(launchedRewardsTag);
    }

    /** What a node restart would change about mining. */
    public enum PendingKind {
        /** Nothing: the node runs as the saved settings say. */
        NONE,
        /** The node runs without mining; a restart would start it. */
        ENABLE,
        /** The node runs with mining; a restart would end it. */
        DISABLE,
        /** The node mines for another inner hash than the saved one. */
        REWARDS_CHANGED
    }

    /**
     * {@link #nodeRestartPending} with the answer spelled out, for the
     * line that tells the user what restarting the node will do.
     */
    public static PendingKind nodeRestartPendingKind(boolean nodeRunning,
                                                     Boolean launchedMining,
                                                     String launchedRewardsTag,
                                                     SkipReason launchSkip,
                                                     boolean wantEnabled,
                                                     String wantInnerHashRaw) {
        if (!nodeRestartPending(nodeRunning, launchedMining, launchedRewardsTag,
                launchSkip, wantEnabled, wantInnerHashRaw)) {
            return PendingKind.NONE;
        }
        if (!launchedMining) {
            return PendingKind.ENABLE;
        }
        boolean wantActive = wantEnabled
                && normalizeInnerHash(wantInnerHashRaw) != null;
        return wantActive ? PendingKind.REWARDS_CHANGED : PendingKind.DISABLE;
    }

    // ------------------------------------------------------------------
    // Status wording (StringsTable keys; plain Java, like the table)
    // ------------------------------------------------------------------

    private static String stateKey(UiState s) {
        switch (s) {
            case NODE_STOPPED:
                return StringsTable.MINING_STATE_NODE_STOPPED;
            case PREPARING:
                return StringsTable.MINING_STATE_PREPARING;
            case WAITING:
                return StringsTable.MINING_STATE_WAITING;
            case MINING:
                return StringsTable.MINING_STATE_MINING;
            case STOPPED:
                return StringsTable.MINING_STATE_STOPPED;
            case OFF:
            default:
                return StringsTable.MINING_STATE_OFF;
        }
    }

    /**
     * The status row's value in the language {@code tag}: the state
     * word, and for a stop that has one, the miner's exit code
     * ("Stopped (exit code 1)").
     */
    public static String stateText(String tag, Presentation p) {
        if (p.state == UiState.STOPPED && p.exitCode != null) {
            return String.format(Locale.US, StringsTable.get(tag,
                    StringsTable.MINING_STATE_STOPPED_CODE),
                    String.valueOf(p.exitCode.intValue()));
        }
        return StringsTable.get(tag, stateKey(p.state));
    }

    /**
     * The sentence under the status row, or null when the state
     * needs none (off; mining).
     */
    public static String reasonText(String tag, Presentation p) {
        String key;
        switch (p.reason) {
            case REWARDS_INVALID:
                key = StringsTable.MINING_REASON_REWARDS_INVALID;
                break;
            case BINARY_MISSING:
                key = StringsTable.MINING_REASON_BINARY_MISSING;
                break;
            case RESTART_TO_ENABLE:
                key = StringsTable.MINING_REASON_RESTART_TO_ENABLE;
                break;
            case NODE_STARTING:
                key = StringsTable.MINING_REASON_NODE_STARTING;
                break;
            case FILES:
                return String.format(Locale.US, StringsTable.get(tag,
                        StringsTable.MINING_REASON_FILES),
                        p.fileName == null ? "" : p.fileName);
            case MINER_STARTING:
                key = StringsTable.MINING_REASON_MINER_STARTING;
                break;
            case WAIT_SYNC:
                key = StringsTable.MINING_REASON_WAIT_SYNC;
                break;
            case WAIT_PEERS:
                key = StringsTable.MINING_REASON_WAIT_PEERS;
                break;
            case WAIT_JOB:
                key = StringsTable.MINING_REASON_WAIT_JOB;
                break;
            case RETRYING:
                key = StringsTable.MINING_REASON_RETRYING;
                break;
            case GAVE_UP:
                key = StringsTable.MINING_REASON_GAVE_UP;
                break;
            case START_FAILED:
                key = StringsTable.MINING_REASON_START_FAILED;
                break;
            case THERMAL_STOP:
                key = StringsTable.MINING_STOPPED_THERMAL;
                break;
            case NONE:
            default:
                key = p.state == UiState.NODE_STOPPED
                        ? StringsTable.MINING_REASON_NODE_STOPPED : null;
                break;
        }
        return key == null ? null : StringsTable.get(tag, key);
    }

    /**
     * What the status notification adds for a node launched with
     * mining: "Mining" while it is, "Mining: <state>" otherwise;
     * null for {@link UiState#OFF} — nothing is added then.
     */
    public static String notificationText(String tag, Presentation p) {
        if (p.state == UiState.OFF) {
            return null;
        }
        if (p.state == UiState.MINING) {
            return StringsTable.get(tag, StringsTable.MINING_STATE_MINING);
        }
        return String.format(Locale.US, StringsTable.get(tag,
                StringsTable.MINING_NOTIF_STATE), stateText(tag, p));
    }

    /** What restarting the node will change, or null for NONE. */
    public static String pendingText(String tag, PendingKind kind) {
        switch (kind) {
            case ENABLE:
                return StringsTable.get(tag,
                        StringsTable.MINING_REASON_RESTART_TO_ENABLE);
            case DISABLE:
                return StringsTable.get(tag,
                        StringsTable.MINING_REASON_RESTART_TO_DISABLE);
            case REWARDS_CHANGED:
                return StringsTable.get(tag,
                        StringsTable.MINING_REASON_REWARDS_CHANGED);
            case NONE:
            default:
                return null;
        }
    }

    /**
     * Whether restarting the MINER alone would change its worker
     * count: only a running miner can lag behind the setting — a
     * miner that is not running reads the setting when it next
     * starts.
     *
     * What a restart would run with is the setting's count under
     * the session's thermal cap (the snapshot carries it), so that
     * is what the running miner is compared with: a miner the
     * thermal protection reduced runs exactly what a restart would
     * give it, and is not shown as a settings change waiting to be
     * applied.
     *
     * @param wantEffectiveWorkers what the saved setting resolves to
     *        ({@link #effectiveCpuWorkers}), before any thermal cap
     */
    public static boolean minerRestartPending(MinerSnapshot miner,
                                              int wantEffectiveWorkers) {
        return miner != null && miner.phase == Phase.RUNNING
                && miner.cpuWorkers
                != Math.min(wantEffectiveWorkers, miner.thermalCap);
    }

    // ------------------------------------------------------------------
    // Display and share helpers (fixed English, ASCII digits)
    // ------------------------------------------------------------------

    /** "850 H/s" / "12.34 kH/s" / "1.25 MH/s"; null while unknown. */
    public static String formatHashrate(double hps) {
        if (Double.isNaN(hps) || Double.isInfinite(hps) || hps < 0) {
            return null;
        }
        if (hps < 999.5) {
            return String.format(Locale.US, "%.0f H/s", hps);
        }
        if (hps < 999_995.0) {
            return String.format(Locale.US, "%.2f kH/s", hps / 1000.0);
        }
        return String.format(Locale.US, "%.2f MH/s", hps / 1_000_000.0);
    }

    /**
     * Mask the rewards address in a share text. The address is
     * public on-chain, but in a shared log it ties the log — and
     * whatever else the log identifies — to one wallet; with the
     * share's masking on (the default) it is hidden like the
     * display name and the device's addresses. Every address the
     * node reported in the text ("Rewards wormhole address: …") is
     * found first, then each is replaced wherever it stands as a
     * whole token. Returns the argument itself when the text
     * carries no such report.
     */
    public static String maskRewardsAddress(String text) {
        if (text == null) {
            return null;
        }
        List<String> found = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = text.indexOf(N_ADDRESS, from);
            if (at < 0) {
                break;
            }
            String a = addressAt(text, at + N_ADDRESS.length());
            if (a != null && !found.contains(a)) {
                found.add(a);
            }
            from = at + N_ADDRESS.length();
        }
        if (found.isEmpty()) {
            return text;
        }
        String out = text;
        for (String a : found) {
            out = replaceToken(out, a, ADDRESS_MASK);
        }
        return out;
    }

    private static String replaceToken(String text, String token,
                                       String replacement) {
        StringBuilder sb = null;
        int emitted = 0;
        int search = 0;
        while (true) {
            int idx = text.indexOf(token, search);
            if (idx < 0) {
                break;
            }
            int after = idx + token.length();
            boolean beforeOk = idx == 0 || !isAsciiAlnum(text.charAt(idx - 1));
            boolean afterOk = after == text.length()
                    || !isAsciiAlnum(text.charAt(after));
            if (beforeOk && afterOk) {
                if (sb == null) {
                    sb = new StringBuilder(text.length());
                }
                sb.append(text, emitted, idx).append(replacement);
                emitted = after;
                search = after;
            } else {
                search = idx + 1;
            }
        }
        if (sb == null) {
            return text;
        }
        sb.append(text, emitted, text.length());
        return sb.toString();
    }

    /**
     * The miner log's part of a share text: the excerpt between two
     * fixed marker lines, appended after the node log. Empty when
     * there is no miner log — a share from an install that never
     * mined is exactly the share it was before.
     */
    public static String minerLogSection(String tail, boolean truncated) {
        if (tail == null || tail.trim().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(tail.length() + 160);
        sb.append("\n=== miner log (shared excerpt) ===\n");
        if (truncated) {
            sb.append("[... earlier miner log omitted for sharing; "
                    + "showing the last part ...]\n");
        }
        sb.append(tail);
        if (!tail.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append("=== end of miner log ===\n");
        return sb.toString();
    }

    /** The diagnostic summary's closing line (DiagShare.buildSummary). */
    private static final String SUMMARY_END = "=== end of summary ===\n";

    /**
     * The diagnostic summary with the mining lines added, just ahead
     * of its closing line. The summary itself is built by DiagShare,
     * which this leaves untouched: with no lines to add, the summary
     * comes back as the very same string.
     */
    public static String insertSummaryLines(String summary, List<String> lines) {
        if (summary == null || lines == null || lines.isEmpty()) {
            return summary;
        }
        StringBuilder block = new StringBuilder(lines.size() * 48);
        for (String line : lines) {
            block.append(line).append('\n');
        }
        int at = summary.lastIndexOf(SUMMARY_END);
        if (at < 0) {
            return summary + block;
        }
        return summary.substring(0, at) + block + summary.substring(at);
    }

    /**
     * The last two steps of a share's log body, in this order:
     *
     *  1. with the share's masking on, the rewards address the node
     *     reported is hidden ({@link #maskRewardsAddress});
     *  2. ALWAYS — whatever the user chose about masking — the inner
     *     hash and the miner token are removed. Neither is ever
     *     written to a log in the first place; this is the last
     *     line of defence in front of the one path that leaves the
     *     device.
     *
     * A body with nothing of either in it comes back as the very
     * same string.
     */
    public static String finishShareBody(String body, boolean maskOn,
                                         SecretScrubber scrubber) {
        if (body == null) {
            return null;
        }
        String out = maskOn ? maskRewardsAddress(body) : body;
        return scrubber == null ? out : scrubber.apply(out);
    }

    /** The mining facts of one share, gathered by the Android side. */
    public static final class ShareFacts {
        public boolean enabledSetting;
        public Boolean launchedMining;
        public SkipReason launchSkip = SkipReason.NONE;
        /** A usable inner hash is stored (never the value). */
        public boolean rewardsSaved;
        public int workerSetting = WORKERS_DEFAULT;
        /** Pre-formatted change stamps ("n/a" when never changed). */
        public String miningChangedText;
        public String rewardsChangedText;
        public UiState uiState = UiState.OFF;
        public UiReason uiReason = UiReason.NONE;
        public MinerSnapshot miner;
    }

    /**
     * The lines the diagnostic summary gains while mining is in
     * use — the setting is on, or this session's node was launched
     * with it. Otherwise the list is empty and the summary is the
     * v3.0.1 summary, byte for byte. Labels are fixed English, like
     * the rest of the summary. No line carries the inner hash, the
     * token or the rewards address: the summary is never masked,
     * so nothing identifying may be in it.
     */
    public static List<String> summaryLines(ShareFacts f) {
        List<String> out = new ArrayList<>();
        if (f == null || (!f.enabledSetting
                && !Boolean.TRUE.equals(f.launchedMining))) {
            return out;
        }
        String session;
        if (f.launchedMining == null) {
            session = "n/a (no launch in this session)";
        } else if (f.launchedMining) {
            session = "on (this session)";
        } else if (f.launchSkip != null && f.launchSkip != SkipReason.NONE) {
            session = "off (this session; skipped: "
                    + skipWord(f.launchSkip) + ")";
        } else {
            session = "off (this session)";
        }
        out.add("Mining: " + session);
        out.add("Mining setting: " + (f.enabledSetting ? "on" : "off"));
        out.add("Mining setting changed: " + orNa(f.miningChangedText));
        out.add("Rewards inner hash: " + (f.rewardsSaved
                ? "saved (value never shared)" : "not saved"));
        out.add("Rewards setting changed: " + orNa(f.rewardsChangedText));
        out.add("Miner state: " + stateWord(f.uiState, f.uiReason));
        String setting = normalizeWorkerSetting(f.workerSetting) == WORKERS_AUTO
                ? "auto"
                : String.valueOf(normalizeWorkerSetting(f.workerSetting));
        MinerSnapshot m = f.miner;
        out.add("Miner CPU workers: "
                + (m != null && m.cpuWorkers > 0
                        ? String.valueOf(m.cpuWorkers) : "n/a")
                + " (setting: " + setting + ")");
        if (m == null) {
            return out;
        }
        out.add("Miner launch method: " + (m.viaLinker ? "linker64" : "direct"));
        String rate = formatHashrate(m.stats.hashrateHps);
        out.add("Miner hashrate (estimate): " + (rate == null ? "n/a" : rate));
        out.add("Miner jobs received: " + m.stats.jobsReceived);
        out.add("Miner solutions submitted: " + m.stats.solutionsSubmitted);
        out.add("Blocks accepted by the node: " + m.stats.blocksAccepted);
        out.add("Seals rejected by the node: " + m.stats.sealsRejected);
        out.add("Miner restarts (this session): " + m.restartsTotal);
        out.add("Miner last exit code: "
                + (m.lastExitCode == null ? "n/a" : m.lastExitCode.toString()));
        if (m.stats.metricsExporterActive) {
            out.add("Miner metrics exporter: ACTIVE (binds 0.0.0.0;"
                    + " not expected in a release build)");
        }
        return out;
    }

    private static String orNa(String s) {
        return (s == null || s.isEmpty()) ? "n/a" : s;
    }

    private static String skipWord(SkipReason r) {
        switch (r) {
            case REWARDS_INVALID:
                return "rewards inner hash unusable";
            case MINER_BINARY_MISSING:
                return "miner binary missing";
            case NETWORK_KEY_MISSING:
                return "network key not created yet";
            case NONE:
            default:
                return "n/a";
        }
    }

    /** A state and its reason as fixed English words. */
    static String stateWord(UiState state, UiReason reason) {
        String s;
        switch (state == null ? UiState.OFF : state) {
            case NODE_STOPPED:
                s = "node stopped";
                break;
            case PREPARING:
                s = "preparing";
                break;
            case WAITING:
                s = "waiting";
                break;
            case MINING:
                s = "mining";
                break;
            case STOPPED:
                s = "stopped";
                break;
            case OFF:
            default:
                s = "off";
                break;
        }
        String r;
        switch (reason == null ? UiReason.NONE : reason) {
            case REWARDS_INVALID:
                r = "rewards inner hash unusable";
                break;
            case BINARY_MISSING:
                r = "miner binary missing";
                break;
            case RESTART_TO_ENABLE:
                r = "node restart needed";
                break;
            case NODE_STARTING:
                r = "node starting";
                break;
            case FILES:
                r = "waiting for the node's miner files";
                break;
            case MINER_STARTING:
                r = "miner starting";
                break;
            case WAIT_SYNC:
                r = "node syncing";
                break;
            case WAIT_PEERS:
                r = "no peers";
                break;
            case WAIT_JOB:
                r = "no job yet";
                break;
            case RETRYING:
                r = "automatic restart pending";
                break;
            case GAVE_UP:
                r = "automatic restarts exhausted";
                break;
            case START_FAILED:
                r = "miner could not be started";
                break;
            case THERMAL_STOP:
                r = "device too hot";
                break;
            case NONE:
            default:
                r = null;
                break;
        }
        return r == null ? s : s + " (" + r + ")";
    }
}
