package app.quantusnode.node.depin.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * This app's node process as /proc shows it (v4.2.4, audit V4-1).
 *
 * The verified-endpoint record ({@link NodeRpcEndpoint}) names the
 * node process that reported the endpoint: its pid and its start
 * time. A reader trusts the record only while /proc still shows that
 * process — the same start time at that pid, running this app's node
 * executable. The main process removes the record when it sees its
 * node exit, but a force-stop, a reboot or a kill of the main process
 * ends the node without that; a record bound only to the (stable,
 * public) peer ID would then let whatever answers on the recorded
 * port pass for the node.
 *
 * The executable check is the strict rule NodeService has used since
 * versionCode 23 to authorise the orphan kill (audit finding A-4),
 * moved here so that the main process and the :depin process judge
 * by one implementation: argv[0] is the node executable's absolute
 * path in this app's nativeLibraryDir, or argv[0] is the system
 * linker and argv[1] is that path (the fallback launch form). Full
 * paths, never a substring. A start time is compared as well,
 * because a pid is reused once its process is gone; the start time
 * (clock ticks after boot, field 22 of /proc/&lt;pid&gt;/stat) does
 * not repeat for a later process at the same pid in the same boot.
 *
 * Read failures read as "not the node" (and a start time of -1):
 * a process this code cannot identify is never trusted. Same-UID
 * /proc entries are readable on Android, and both processes of this
 * app run under its UID, as the node does.
 */
public final class NodeProcess {
    /** The node executable's file name in the app's nativeLibraryDir. */
    public static final String LIBRARY = "libquantus_node.so";
    /** The system linker: the fallback launch form runs the node as
     *  its argv[1]. */
    public static final String LINKER = "/system/bin/linker64";
    /** The real /proc; the harness passes a stand-in tree instead. */
    public static final File PROC = new File("/proc");

    private NodeProcess() {}

    /** The node executable's absolute path under
     *  {@code nativeLibraryDir} — the path argv[0] (or the linker's
     *  argv[1]) carries. */
    public static String binaryPath(String nativeLibraryDir) {
        return new File(nativeLibraryDir, LIBRARY).getAbsolutePath();
    }

    /**
     * The strict rule on a NUL-separated cmdline: argv[0] is
     * {@code binaryPath}, or argv[0] is {@link #LINKER} and argv[1]
     * is {@code binaryPath}. An empty cmdline (a zombie) and an
     * empty or missing expected path match nothing.
     */
    public static boolean isNodeCmdline(byte[] cmdline, String binaryPath) {
        if (cmdline == null || binaryPath == null || binaryPath.isEmpty()) {
            return false;
        }
        String[] argv = new String(cmdline, StandardCharsets.UTF_8)
                .split("\0", 3);
        if (argv.length < 1) {
            return false;
        }
        if (binaryPath.equals(argv[0])) {
            return true;
        }
        return argv.length > 1 && LINKER.equals(argv[0])
                && binaryPath.equals(argv[1]);
    }

    /** True only when {@code procRoot}/&lt;pid&gt;/cmdline shows this
     *  app's node executable ({@link #isNodeCmdline}); a pid below 1
     *  or an unreadable entry is never the node. */
    public static boolean runsNode(File procRoot, long pid, String binaryPath) {
        if (pid <= 0) {
            return false;
        }
        byte[] cmdline = readProcFile(procRoot, pid, "cmdline");
        return cmdline != null && isNodeCmdline(cmdline, binaryPath);
    }

    /**
     * The process's start time, field 22 of
     * {@code procRoot}/&lt;pid&gt;/stat (clock ticks after boot), or -1
     * when there is no such process or the line cannot be read. The
     * comm (field 2) is parenthesised and may itself hold spaces and
     * parentheses, so the fields are counted after the LAST ')': the
     * state (field 3) comes first there, the start time twentieth.
     */
    public static long startTicks(File procRoot, long pid) {
        if (pid <= 0) {
            return -1;
        }
        byte[] data = readProcFile(procRoot, pid, "stat");
        if (data == null) {
            return -1;
        }
        String stat = new String(data, StandardCharsets.UTF_8);
        int close = stat.lastIndexOf(')');
        if (close < 0 || close + 1 >= stat.length()) {
            return -1;
        }
        String[] fields = stat.substring(close + 1).trim().split("\\s+");
        if (fields.length < 20) {
            return -1;
        }
        try {
            long ticks = Long.parseLong(fields[19]); // field 22
            return ticks >= 0 ? ticks : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A /proc file read whole with a plain chunk loop
     *  (InputStream.readAllBytes is API 33+ on Android; this core
     *  runs in the app from API 31), or null when unreadable. */
    private static byte[] readProcFile(File procRoot, long pid, String name) {
        try (InputStream in = new FileInputStream(
                new File(new File(procRoot, Long.toString(pid)), name))) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[512];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
