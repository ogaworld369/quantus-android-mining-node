package app.quantusnode.node;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the app may trust in a line of the bundled node's or the
 * bundled miner's output (v4.2.4, audit L-2 / I-12). Pure Java, so
 * the suites run the real rules.
 *
 * A peer can make the node print text that carries line breaks: an
 * external address its peers confirm (litep2p logs it verbatim, and
 * tracing-subscriber escapes no newline), or what a client on the
 * miner port sent before it authenticated. Every line after such a
 * break reaches the app's drain as a line of its own. Two rules
 * keep that text inert:
 *
 *  - a line carries meaning only when it has the header the shipped
 *    binary writes ({@link #parseNodeLine}, {@link #parseMinerLine})
 *    AND the text the app looks for stands at the START of its
 *    message ({@link #messageStartsWith}) rather than somewhere in
 *    peer-supplied text. A line without the header is an inert
 *    continuation: it is stored and shown as log text and never
 *    changes a counter, a state or a statistic;
 *  - before a line is stored, shown or shared, control characters
 *    (tab excepted), the line and paragraph separators, and the bidi
 *    controls and marks are removed ({@link #sanitize}, applied at
 *    the drains through {@link #intake}).
 *
 * What this does not close: a peer that reproduces the whole header
 * after a line break still produces a header-shaped line. Only the
 * node escaping the text it logs closes that (the audit's optional
 * upstream fix). The anchoring removes the cheap forms — fragments,
 * markers inside other messages, level words outside the level slot.
 */
public final class NodeLogLines {

    private NodeLogLines() {
    }

    // ------------------------------------------------------------------
    // Sanitising (audit L-2 item 2)
    // ------------------------------------------------------------------

    /**
     * The line without control characters (C0 except tab, DEL, C1)
     * and without the bidi embedding / override / isolate controls
     * (U+202A–U+202E, U+2066–U+2069) — the characters that can make
     * a stored, shown or shared line read as something it is not.
     * Also (v4.2.4, audit V4-8) without the line and
     * paragraph separators (U+2028, U+2029: many renderers, ICU line
     * breaking among them, start a new line at them, so one line
     * could read as two) and the bidi marks (U+200E, U+200F, U+061C).
     * Everything else, emoji and joiners included, is kept. Returns
     * the argument itself when there is nothing to remove.
     */
    public static String sanitize(String line) {
        if (line == null) {
            return null;
        }
        int n = line.length();
        int i = 0;
        while (i < n && !strips(line.charAt(i))) {
            i++;
        }
        if (i == n) {
            return line;
        }
        StringBuilder sb = new StringBuilder(n);
        sb.append(line, 0, i);
        for (; i < n; i++) {
            char c = line.charAt(i);
            if (!strips(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean strips(char c) {
        if (c == '\t') {
            return false;
        }
        return c < 0x20
                || (c >= 0x7f && c <= 0x9f)
                || (c >= 0x202a && c <= 0x202e)
                || (c >= 0x2066 && c <= 0x2069)
                // v4.2.4 (audit V4-8): line / paragraph separators,
                // left-to-right, right-to-left and Arabic letter marks.
                || c == 0x2028 || c == 0x2029
                || c == 0x200e || c == 0x200f || c == 0x061c;
    }

    /**
     * One drained line as every sink receives it: sanitised FIRST,
     * then scrubbed of the session's secrets. The order matters: a
     * control character standing inside a secret would hide it from
     * the scrubber, and removing the character afterwards would
     * reassemble the secret in the stored text. Scrubbing the
     * sanitised line means the scrubber sees exactly what is stored.
     *
     * @param truncated the line was cut at the drain's cap (the
     *        scrubber then also removes a secret's beginning at the
     *        line's end)
     * @param scrubber the session's scrubber, or null (no secrets
     *        in play: the line is only sanitised)
     */
    public static String intake(String rawLine, boolean truncated,
                                MiningLogic.SecretScrubber scrubber) {
        String line = sanitize(rawLine);
        return scrubber == null ? line : scrubber.apply(line, truncated);
    }

    // ------------------------------------------------------------------
    // The node's header (sc-tracing, detailed format)
    // ------------------------------------------------------------------

    /**
     * The shipped node's line header. The app launches the node with
     * RUST_LOG raising the sync targets to debug, and with any target
     * above info sc-tracing writes its detailed format:
     *
     *   2026-11-14 13:01:31.143  WARN tokio-runtime-worker sync: …
     *   2026-11-02 16:30:53.325  INFO                 main sc_rpc_server: …
     *
     * — a millisecond timestamp, the level in a five-character slot
     * (" INFO", " WARN", "DEBUG", …), the thread name right-aligned
     * to the longest seen so far, and the log target followed by
     * ": " (sc-tracing-44 event_format.rs; the shape of every node
     * line in the 2026-11-14 device export). Colours are off: the
     * node's output is a pipe, not a terminal.
     */
    private static final Pattern NODE_HEADER = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} "
                    + "(TRACE|DEBUG| INFO| WARN|ERROR) {1,64}(\\S{1,64})"
                    + " (\\S{1,128}?): ");

    /** A node line with the shipped header. */
    public static final class NodeLine {
        /** "TRACE", "DEBUG", "INFO", "WARN" or "ERROR". */
        public final String level;
        /** The log target ("sc_rpc_server", "sub-libp2p", …). */
        public final String target;
        /** Everything after the header. */
        public final String message;

        NodeLine(String level, String target, String message) {
            this.level = level;
            this.target = target;
            this.message = message;
        }
    }

    /**
     * The line's header and message, or null when the line does not
     * start with the shipped node's header — a continuation of the
     * line before it, which is never evidence of anything.
     */
    public static NodeLine parseNodeLine(String line) {
        if (line == null || line.length() < 30) {
            return null;
        }
        Matcher m = NODE_HEADER.matcher(line);
        if (!m.lookingAt()) {
            return null;
        }
        return new NodeLine(m.group(1).trim(), m.group(3),
                line.substring(m.end()));
    }

    // ------------------------------------------------------------------
    // The miner's header (env_logger 0.10 default format)
    // ------------------------------------------------------------------

    /**
     * The bundled miner's line header (miner-cli calls
     * env_logger::init(), default format):
     *
     *   [2026-11-02T07:28:46Z INFO  miner_service::quic] …
     *
     * — an RFC 3339 timestamp in seconds, the level padded to five
     * characters, the module path, a closing bracket and a space.
     */
    private static final Pattern MINER_HEADER = Pattern.compile(
            "^\\[\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z "
                    + "(TRACE|DEBUG|INFO |WARN |ERROR) ([A-Za-z0-9_:]{1,128})\\] ");

    /** A miner line with the shipped header. */
    public static final class MinerLine {
        public final String level;
        /** The module path ("miner_service::quic", …). */
        public final String target;
        public final String message;

        MinerLine(String level, String target, String message) {
            this.level = level;
            this.target = target;
            this.message = message;
        }
    }

    /** The miner counterpart of {@link #parseNodeLine}. */
    public static MinerLine parseMinerLine(String line) {
        if (line == null || line.length() < 28) {
            return null;
        }
        Matcher m = MINER_HEADER.matcher(line);
        if (!m.lookingAt()) {
            return null;
        }
        return new MinerLine(m.group(1).trim(), m.group(2),
                line.substring(m.end()));
    }

    // ------------------------------------------------------------------
    // The node's own RPC endpoint and identity (audit M-1)
    // ------------------------------------------------------------------

    /**
     * The node does not fail when its RPC port is taken: it rebinds
     * to a random port and logs nothing about it (upstream
     * retry_random_port), so another app holding 127.0.0.1:9944
     * would answer every poll in the node's place. What the node
     * DOES print, before it opens any network connection, is where
     * its RPC listens and who it is:
     *
     *   … sc_rpc_server: Running JSON-RPC server: addr=127.0.0.1:9944,[::1]:9944
     *   … sub-libp2p: Local node identity is: Qm…
     *
     * The app adopts those two facts from the node's own header-
     * anchored lines (the FIRST of each), polls the address it
     * reported, and requires the RPC's system_localPeerId to equal
     * the identity on every poll round (checkPeerId).
     */
    private static final String RPC_MARKER = "Running JSON-RPC server: addr=";
    private static final String IDENTITY_MARKER = "Local node identity is: ";

    /** An IPv4 loopback host and a port. */
    public static final class HostPort {
        public final String host;
        public final int port;

        HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /** The endpoint the node reported and the identity it reported. */
    public static final class RpcIdentity {
        public final String host;
        public final int port;
        public final String peerId;

        RpcIdentity(String host, int port, String peerId) {
            this.host = host;
            this.port = port;
            this.peerId = peerId;
        }

        /** The JSON-RPC URL for this endpoint. */
        public String url() {
            return "http://" + host + ":" + port + "/";
        }
    }

    /**
     * The first IPv4 loopback address in the node's "Running JSON-RPC
     * server" line, or null — for a line without the node's header,
     * one whose message does not start with the marker, or a list
     * without such an address. The list is comma-separated
     * (client/rpc-servers format_listen_addrs: a single address
     * carries a trailing comma, and "[::1]:port" entries are
     * skipped); parsing is tolerant of spacing and of the previous
     * upstream format's ", allowed origins=…" tail, and strict about
     * each address — canonical dotted decimal in 127.0.0.0/8 and a
     * port in 1..65535, or the entry is skipped.
     */
    public static HostPort parseRpcEndpoint(String line) {
        NodeLine n = parseNodeLine(line);
        if (n == null || !n.message.startsWith(RPC_MARKER)) {
            return null;
        }
        String list = n.message.substring(RPC_MARKER.length());
        for (String raw : list.split(",", -1)) {
            HostPort hp = parseLoopbackV4(raw.trim());
            if (hp != null) {
                return hp;
            }
        }
        return null;
    }

    private static HostPort parseLoopbackV4(String entry) {
        int colon = entry.lastIndexOf(':');
        if (colon <= 0 || entry.indexOf(':') != colon) {
            return null; // IPv6 ("[::1]:9944") or no port
        }
        String host = entry.substring(0, colon);
        String portText = entry.substring(colon + 1);
        if (portText.isEmpty() || portText.length() > 5) {
            return null;
        }
        int port = 0;
        for (int i = 0; i < portText.length(); i++) {
            char c = portText.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
            port = port * 10 + (c - '0');
        }
        if (port < 1 || port > 65535 || (portText.length() > 1
                && portText.charAt(0) == '0')) {
            return null;
        }
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        for (String o : octets) {
            if (o.isEmpty() || o.length() > 3
                    || (o.length() > 1 && o.charAt(0) == '0')) {
                return null;
            }
            for (int i = 0; i < o.length(); i++) {
                if (o.charAt(i) < '0' || o.charAt(i) > '9') {
                    return null;
                }
            }
            if (Integer.parseInt(o) > 255) {
                return null;
            }
        }
        if (!"127".equals(octets[0])) {
            return null; // only the IPv4 loopback is adopted
        }
        return new HostPort(host, port);
    }

    /** The longest peer ID token taken (a multihash in base58 is
     *  ~46–53 characters; the bound only keeps junk out). */
    private static final int MAX_PEER_ID_CHARS = 128;

    /**
     * The peer ID in the node's "Local node identity is: …" line, or
     * null — for a line without the node's header, one whose message
     * does not start with the marker, or a token that is not base58.
     * The token runs to the end of the line or the first space.
     */
    public static String parsePeerId(String line) {
        NodeLine n = parseNodeLine(line);
        if (n == null || !n.message.startsWith(IDENTITY_MARKER)) {
            return null;
        }
        String rest = n.message.substring(IDENTITY_MARKER.length());
        int end = 0;
        while (end < rest.length() && rest.charAt(end) != ' '
                && rest.charAt(end) != '\t') {
            end++;
        }
        String token = rest.substring(0, end);
        if (token.isEmpty() || token.length() > MAX_PEER_ID_CHARS) {
            return null;
        }
        for (int i = 0; i < token.length(); i++) {
            if (!isBase58(token.charAt(i))) {
                return null;
            }
        }
        return token;
    }

    private static boolean isBase58(char c) {
        return (c >= '1' && c <= '9')
                || (c >= 'A' && c <= 'Z' && c != 'I' && c != 'O')
                || (c >= 'a' && c <= 'z' && c != 'l');
    }

    /**
     * The two startup facts of ONE node process, taken from its
     * output in order. Only the first header-anchored line of each
     * kind counts: the node prints each once, before it opens any
     * network connection, so a later line of the same kind is not
     * the node's statement. A first RPC line that names no IPv4
     * loopback address therefore closes the endpoint without one —
     * nothing is adopted for that process. Fragments without the
     * node's header neither adopt anything nor use up the first
     * line. Fed by the drain thread, read by the poller.
     *
     * Failing closed must not be silent (v4.2.4, audit V4-10): a node
     * whose facts are never captured leaves the app on "Starting" for
     * good, with the :depin consumers, the mining statistics and the
     * miner gate quiet. The capture therefore also hands out two fixed
     * WARN texts, each at most once per node process: one for a first
     * RPC line that named no IPv4 loopback address
     * ({@link #takeRpcLineWarning}), and one once the process has run
     * {@link #FACTS_REPORT_AFTER_MS} without both facts
     * ({@link #takeLateWarning}). Neither copies anything of the lines.
     */
    public static final class StartupCapture {
        /** How long a node process may run without both startup facts
         *  before that is reported (v4.2.4, audit V4-10). The node
         *  prints both within its first second. */
        public static final long FACTS_REPORT_AFTER_MS = 60_000L;

        private boolean rpcLineSeen;
        private boolean identityLineSeen;
        private HostPort endpoint;
        private String peerId;
        private RpcIdentity identity;
        /** A line with the node's detailed header was seen. */
        private boolean headerLineSeen;
        private boolean rpcLineReported;
        private boolean lateReported;

        /**
         * Take one (sanitised) line. Returns true exactly once: for
         * the line that completed the identity.
         */
        public synchronized boolean onLine(String line) {
            if (identity != null || (rpcLineSeen && identityLineSeen)) {
                return false;
            }
            NodeLine n = parseNodeLine(line);
            if (n == null) {
                return false;
            }
            headerLineSeen = true;
            if (!rpcLineSeen && n.message.startsWith(RPC_MARKER)) {
                rpcLineSeen = true;
                endpoint = parseRpcEndpoint(line);
            } else if (!identityLineSeen
                    && n.message.startsWith(IDENTITY_MARKER)) {
                identityLineSeen = true;
                peerId = parsePeerId(line);
            }
            if (endpoint != null && peerId != null) {
                identity = new RpcIdentity(endpoint.host, endpoint.port,
                        peerId);
                return true;
            }
            return false;
        }

        /** The adopted endpoint and identity, or null until both
         *  were reported (and forever, when the first report of
         *  either was unusable). */
        public synchronized RpcIdentity identity() {
            return identity;
        }

        /**
         * The WARN text (without the "[app] " prefix) for a first RPC
         * line that named no IPv4 loopback address — exactly once,
         * after that line; null otherwise (v4.2.4, audit V4-10).
         */
        public synchronized String takeRpcLineWarning() {
            if (rpcLineReported || !rpcLineSeen || endpoint != null) {
                return null;
            }
            rpcLineReported = true;
            return "WARN rpc: the node's first \"Running JSON-RPC server\""
                    + " line named no IPv4 loopback address; nothing is polled"
                    + " for this node process, so the app stays at starting"
                    + " and DePIN observations and transfer height reads stay"
                    + " unavailable until the node is started again";
        }

        /**
         * The WARN text (without the "[app] " prefix) for a node process
         * that has run {@code runMs} ≥ {@link #FACTS_REPORT_AFTER_MS}
         * without both startup facts — exactly once, naming what is
         * missing, and whether any line carried the node's detailed
         * header at all (without it nothing can be captured: the
         * format, or RUST_LOG, has changed); null before that, after
         * it, and once both facts are in (v4.2.4, audit V4-10).
         */
        public synchronized String takeLateWarning(long runMs) {
            if (lateReported || identity != null
                    || runMs < FACTS_REPORT_AFTER_MS) {
                return null;
            }
            lateReported = true;
            String missing = endpoint == null && peerId == null
                    ? "its RPC address and its peer identity"
                    : endpoint == null ? "its RPC address"
                    : "its peer identity";
            return "WARN rpc: this node process has run for "
                    + (FACTS_REPORT_AFTER_MS / 1000) + " s without "
                    + missing + " being captured from its startup output;"
                    + " nothing is polled until then, so the app stays at"
                    + " starting and DePIN observations and transfer height"
                    + " reads stay unavailable"
                    + (headerLineSeen ? "" : ". No line so far carried the"
                    + " node's detailed log header (time with milliseconds,"
                    + " level, thread, target): the node's log format, or"
                    + " its RUST_LOG, has changed");
        }
    }

    /** One poll round's identity check. */
    public enum PeerCheck {
        /** The endpoint answered with the node's own identity. */
        MATCH,
        /** Something answered with another identity. */
        MISMATCH,
        /** No usable answer (call failed, wrong type). */
        NO_ANSWER
    }

    /**
     * Judge a round's system_localPeerId result against the identity
     * the node reported at startup. Only MATCH lets the round adopt
     * anything; MISMATCH and NO_ANSWER adopt nothing.
     */
    public static PeerCheck checkPeerId(String captured, Object result) {
        if (captured == null || !(result instanceof String)) {
            return PeerCheck.NO_ANSWER;
        }
        return captured.equals(result) ? PeerCheck.MATCH : PeerCheck.MISMATCH;
    }

    /** At most one event per interval (the mismatch log line). */
    public static final class RateLimit {
        private final long intervalMs;
        private boolean any;
        private long lastMs;

        public RateLimit(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        /** True when an event at {@code nowMs} (monotonic) may be
         *  reported: the first one, and then one per interval. */
        public synchronized boolean allow(long nowMs) {
            if (any && nowMs - lastMs < intervalMs) {
                return false;
            }
            any = true;
            lastMs = nowMs;
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Message anchoring
    // ------------------------------------------------------------------

    /** The longest lead of pictographs and spaces a message may carry
     *  in front of its text ("⛏️ " is three code points). */
    private static final int MAX_LEAD_CODE_POINTS = 8;

    /**
     * True when {@code message} begins with {@code marker}, allowing
     * in front of it only the short run of pictographs and spaces the
     * binaries put there ("⛏️ ", "🥇 ", "✅ ") — no letter or digit.
     * A marker further into the message (inside a peer's address, a
     * quoted client message) does not count.
     */
    public static boolean messageStartsWith(String message, String marker) {
        if (message == null || marker == null) {
            return false;
        }
        int i = 0;
        int lead = 0;
        while (i < message.length() && lead < MAX_LEAD_CODE_POINTS) {
            int cp = message.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                break;
            }
            i += Character.charCount(cp);
            lead++;
        }
        return message.startsWith(marker, i);
    }
}
