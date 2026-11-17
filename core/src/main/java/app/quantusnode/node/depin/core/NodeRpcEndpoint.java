package app.quantusnode.node.depin.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The verified-endpoint record (v4.2.4, audit M-1): where the
 * on-device node's JSON-RPC listens and which peer identity it
 * reported — both as the node itself printed them at startup.
 *
 * The node does not fail when its RPC port is taken: it rebinds to a
 * random port without a word (upstream retry_random_port). The fixed
 * http://127.0.0.1:9944/ this module polled until v4.2.3 could
 * therefore be any other app's, and every observation the observer
 * signed, and every finalized height a transfer permit was issued
 * against, could come from it. Now the Node app's main process reads
 * the node's own "Running JSON-RPC server: addr=…" and "Local node
 * identity is: …" lines and writes this record, atomically, once it
 * has both; it deletes the record before every node launch and when
 * the node stops. The :depin process reads it before every round
 * ({@link RpcClient#forNodeRecord}): the calls go to the recorded
 * address, and system_localPeerId must equal the recorded identity
 * before anything is adopted. No record means no verified node, and
 * the round fails closed.
 *
 * The record is bound to the node process's life on the reader's side
 * too (v4.2.4, audit V4-1). The main process deletes it only while it
 * watches its node; a force-stop, a reboot or a kill of the main
 * process ends the node without that, and the record stayed until the
 * next start. The peer ID cannot tell such a stale record from a live
 * one — it is stable across restarts and public (any local app can
 * ask the node for it while it runs) — so whatever later held the
 * recorded port passed the check. The record therefore names the node
 * process (its pid, and its start time: field 22 of
 * /proc/&lt;pid&gt;/stat), and a reader trusts it only while /proc
 * still shows that start time at that pid, running this app's node
 * executable ({@link NodeProcess}), and only when it was written in
 * the current boot ({@link #staleReason}). While that process lives,
 * the recorded port is its own: no other app can listen on it.
 *
 *   files/node/rpc-endpoint.json
 *   {"host":"127.0.0.1","peerId":"Qm…","pid":…,"port":9944,
 *    "procStartTicks":…,"sessionStartMillis":…}
 *
 * Parsing is strict: exactly these six keys; the host an IPv4
 * loopback address in canonical dotted decimal; the port in
 * 1..65535; the peer ID base58, 1..128 characters; the pid in
 * 1..2^31-1; the start time and the session start non-negative
 * integers (the session start: wall-clock milliseconds of the node
 * process's spawn).
 *
 * The peer ID is public (every peer learns it), so the identity check
 * is a consistency check, not authentication: what it rules out is
 * an app that merely holds the port the app used to poll blindly, and
 * the process binding rules out a record that outlived its node.
 */
public final class NodeRpcEndpoint {
    /** The record's file name, in files/node/ (the node's base path). */
    public static final String RECORD_NAME = "rpc-endpoint.json";
    /** Cap on the record file; a real one is about 170 bytes. */
    static final int MAX_RECORD_BYTES = 4096;
    private static final int MAX_PEER_ID_CHARS = 128;
    /**
     * How far a record's session start may lie before the current
     * boot's wall-clock start and still count as this boot's (v4.2.4,
     * audit V4-1). The boot's start is derived from the wall clock
     * (now minus the time since boot), so it moves whenever the wall
     * clock is corrected; network time corrections are seconds, and
     * ten minutes leaves room for a long-offline drift. A larger
     * forward correction after the node started makes its record read
     * as stale: the :depin consumers then fail closed until the node
     * is started again — never the reverse. A reboot after the node
     * had run for about ten minutes is caught by this check alone; a
     * record of a shorter run is caught by the process check.
     */
    static final long BOOT_MARGIN_MS = 10L * 60 * 1000;

    public final String host;
    public final int port;
    public final String peerId;
    public final long sessionStartMillis;
    /** The node process's pid (v4.2.4, audit V4-1). */
    public final long pid;
    /** The node process's start time, field 22 of
     *  /proc/&lt;pid&gt;/stat: clock ticks after boot (v4.2.4, audit
     *  V4-1). */
    public final long procStartTicks;

    /** @throws IllegalArgumentException for a value the record
     *          could not carry (see the class comment) */
    public NodeRpcEndpoint(String host, int port, String peerId,
                           long sessionStartMillis, long pid,
                           long procStartTicks) {
        if (!isLoopbackV4(host)) {
            throw new IllegalArgumentException(
                    "rpc endpoint: host is not an IPv4 loopback address");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                    "rpc endpoint: port outside 1..65535");
        }
        if (!isPeerId(peerId)) {
            throw new IllegalArgumentException(
                    "rpc endpoint: peer id is not a base58 token");
        }
        if (sessionStartMillis < 0) {
            throw new IllegalArgumentException(
                    "rpc endpoint: negative session start");
        }
        if (pid < 1 || pid > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "rpc endpoint: pid outside 1..2^31-1");
        }
        if (procStartTicks < 0) {
            throw new IllegalArgumentException(
                    "rpc endpoint: negative process start time");
        }
        this.host = host;
        this.port = port;
        this.peerId = peerId;
        this.sessionStartMillis = sessionStartMillis;
        this.pid = pid;
        this.procStartTicks = procStartTicks;
    }

    /** files/node/rpc-endpoint.json under the app's files directory
     *  — the one place both processes look. */
    public static File recordFile(File filesDir) {
        return new File(new File(filesDir, "node"), RECORD_NAME);
    }

    /** The JSON-RPC URL of this endpoint. */
    public String url() {
        return "http://" + host + ":" + port + "/";
    }

    /** The record's canonical JSON text (sorted keys). */
    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("host", host);
        m.put("port", (long) port);
        m.put("peerId", peerId);
        m.put("sessionStartMillis", sessionStartMillis);
        m.put("pid", pid);
        m.put("procStartTicks", procStartTicks);
        return Json.canonical(m);
    }

    /** Parse a record strictly (see the class comment).
     *  @throws IllegalArgumentException for anything else */
    public static NodeRpcEndpoint parse(String json) {
        Map<String, Object> m = Json.parseObject(json);
        if (m.size() != 6 || !m.containsKey("host") || !m.containsKey("port")
                || !m.containsKey("peerId")
                || !m.containsKey("sessionStartMillis")
                || !m.containsKey("pid")
                || !m.containsKey("procStartTicks")) {
            throw new IllegalArgumentException(
                    "rpc endpoint: not exactly host, port, peerId,"
                            + " sessionStartMillis, pid, procStartTicks");
        }
        Object host = m.get("host");
        Object port = m.get("port");
        Object peerId = m.get("peerId");
        Object start = m.get("sessionStartMillis");
        Object pid = m.get("pid");
        Object ticks = m.get("procStartTicks");
        if (!(host instanceof String) || !(port instanceof Long)
                || !(peerId instanceof String) || !(start instanceof Long)
                || !(pid instanceof Long) || !(ticks instanceof Long)) {
            throw new IllegalArgumentException(
                    "rpc endpoint: a field has the wrong type");
        }
        long p = (Long) port;
        if (p < 1 || p > 65535) {
            throw new IllegalArgumentException(
                    "rpc endpoint: port outside 1..65535");
        }
        return new NodeRpcEndpoint((String) host, (int) p, (String) peerId,
                (Long) start, (Long) pid, (Long) ticks);
    }

    /**
     * Why this record no longer describes a live node process — or
     * null when it still does (v4.2.4, audit V4-1). Stale is: written
     * before the current boot ({@code bootWallMillis}: now minus the
     * time since boot, on the wall clock; see {@link #BOOT_MARGIN_MS}),
     * or /proc no longer showing the recorded start time at the
     * recorded pid, or that pid not running this app's node executable
     * ({@link NodeProcess#isNodeCmdline}). The start time is read
     * before and after the executable, so a pid that changed hands
     * between the two reads cannot pass.
     */
    public String staleReason(File procRoot, String nodeBinaryPath,
                              long bootWallMillis) {
        if (sessionStartMillis < bootWallMillis - BOOT_MARGIN_MS) {
            return "it was written before the current boot";
        }
        long before = NodeProcess.startTicks(procRoot, pid);
        if (before < 0) {
            return "the node process it names is gone";
        }
        if (before != procStartTicks) {
            return "its pid now belongs to another process";
        }
        if (!NodeProcess.runsNode(procRoot, pid, nodeBinaryPath)
                || NodeProcess.startTicks(procRoot, pid) != procStartTicks) {
            return "its pid is not this app's node";
        }
        return null;
    }

    /**
     * The record in {@code f}, or null when there is none.
     *
     * @throws IOException when the file cannot be read or exceeds
     *         the cap
     * @throws IllegalArgumentException when it is not a valid record
     */
    public static NodeRpcEndpoint read(File f) throws IOException {
        if (!f.exists()) {
            return null;
        }
        if (f.length() > MAX_RECORD_BYTES) {
            throw new IOException("rpc endpoint record exceeds "
                    + MAX_RECORD_BYTES + " bytes");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_RECORD_BYTES) {
                    throw new IOException("rpc endpoint record exceeds "
                            + MAX_RECORD_BYTES + " bytes");
                }
            }
        }
        return parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    /** Canonical dotted decimal (no leading zeros) in 127.0.0.0/8. */
    static boolean isLoopbackV4(String host) {
        if (host == null) {
            return false;
        }
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4 || !"127".equals(octets[0])) {
            return false;
        }
        for (String o : octets) {
            if (o.isEmpty() || o.length() > 3
                    || (o.length() > 1 && o.charAt(0) == '0')) {
                return false;
            }
            for (int i = 0; i < o.length(); i++) {
                char c = o.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(o) > 255) {
                return false;
            }
        }
        return true;
    }

    static boolean isPeerId(String s) {
        if (s == null || s.isEmpty() || s.length() > MAX_PEER_ID_CHARS) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean b58 = (c >= '1' && c <= '9')
                    || (c >= 'A' && c <= 'Z' && c != 'I' && c != 'O')
                    || (c >= 'a' && c <= 'z' && c != 'l');
            if (!b58) {
                return false;
            }
        }
        return true;
    }
}
