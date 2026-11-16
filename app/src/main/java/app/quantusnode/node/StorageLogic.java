package app.quantusnode.node;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Storage management (v4.1.0): how much of the app's private storage
 * each part uses, and the one deletion the app offers — the chain
 * database, so that the node syncs from the start again.
 *
 * Pure Java, no Android types, like {@link MiningLogic}: everything
 * here is driven on a JVM by the harness (app/tests), and the rule
 * that matters most is a pure function of path names.
 *
 * What the resync deletes is decided in one place,
 * {@link #isResyncDeletable}: a path is deletable only when it is a
 * chain-database directory itself — {@code
 * <files>/node/chains/<chain>/<one of CHAIN_DB_DIR_NAMES>} — or lies
 * inside one. Everything else under the app's files directory is
 * out of reach by construction, and that is the point: the node's
 * network key ({@code chains/<chain>/network/}), the miner's token
 * and certificate-pin files (directly in {@code chains/<chain>/}),
 * the DePIN identity and records ({@code depin},
 * {@code depin-supervisor}), the daily track file, the logs and the
 * preferences (a different directory altogether) are never named by
 * a deletable path. The walk that does the deleting asks the same
 * function about every single entry before it removes it, and never
 * follows a symbolic link.
 */
public final class StorageLogic {

    static final String NODE_DIR_NAME = "node";
    static final String CHAINS_DIR_NAME = "chains";
    static final String LOGS_DIR_NAME = "logs";
    static final String DEPIN_DIR_NAME = "depin";
    static final String DEPIN_SUPERVISOR_DIR_NAME = "depin-supervisor";

    /**
     * The directory names, directly under {@code chains/<chain>/},
     * that hold the chain database — the whole of what the resync
     * may delete, and what the "chain database" figure measures.
     *
     * Two names, because the bundled node opens its database in
     * Substrate's {@code Auto} mode: a fresh install creates ParityDB
     * in {@code paritydb} (what every device set up by this app
     * has), and a RocksDB already present in {@code db} is reused
     * instead. The v4.1.0 specification first named {@code db}
     * alone; with that the resync found nothing to delete on a real
     * device (pre-release audit, H-1), and the specification was
     * amended to this set.
     */
    static final String[] CHAIN_DB_DIR_NAMES = {"db", "paritydb"};

    /**
     * Written under the files directory (outside {@code node/})
     * before the first entry of a resync is deleted and removed
     * after the last: while it exists, a deletion the user confirmed
     * may be unfinished — the app process can be killed mid-walk —
     * and the node must not be started on the half-deleted
     * database. {@link #completePendingReset} finishes the job
     * before the next node start.
     */
    static final String RESET_MARKER_FILE_NAME = "chain-reset.pending";

    /** A measuring walk gives up past this many entries. */
    private static final int WALK_MAX_ENTRIES = 200_000;

    private StorageLogic() {
    }

    // ------------------------------------------------------------------
    // Places
    // ------------------------------------------------------------------

    /** {@code <files>/node}: the node's base path. */
    public static File nodeDir(File filesDir) {
        return new File(filesDir, NODE_DIR_NAME);
    }

    /** {@code <files>/node/chains/<chain>}. */
    public static File chainDir(File filesDir, String chain) {
        return new File(new File(nodeDir(filesDir), CHAINS_DIR_NAME), chain);
    }

    /** The chain-database directories, existing or not. */
    public static List<File> chainDbDirs(File filesDir, String chain) {
        List<File> out = new ArrayList<>();
        File chainDir = chainDir(filesDir, chain);
        for (String name : CHAIN_DB_DIR_NAMES) {
            out.add(new File(chainDir, name));
        }
        return out;
    }

    public static File resetMarkerFile(File filesDir) {
        return new File(filesDir, RESET_MARKER_FILE_NAME);
    }

    // ------------------------------------------------------------------
    // The deletion rule
    // ------------------------------------------------------------------

    /**
     * May the resync delete {@code candidate}? True only for a
     * chain-database directory of {@code chain} under
     * {@code filesDir}, or a path inside one.
     *
     * The decision is made on the path names alone and touches no
     * file. Both paths are taken as absolute paths and compared
     * segment by segment; a candidate that carries a {@code .} or
     * {@code ..} segment, an empty chain name, or a chain name that
     * is not a single plain segment is refused outright rather than
     * resolved — a path that needs interpreting is not one this
     * function vouches for.
     */
    public static boolean isResyncDeletable(File filesDir, String chain,
                                            File candidate) {
        if (filesDir == null || candidate == null || !plainSegment(chain)) {
            return false;
        }
        List<String> base = segments(filesDir);
        List<String> path = segments(candidate);
        if (base == null || path == null || base.isEmpty()) {
            return false;
        }
        // <files> / node / chains / <chain> / <db name> [/ ...]
        int need = base.size() + 4;
        if (path.size() < need) {
            return false;
        }
        for (int i = 0; i < base.size(); i++) {
            if (!base.get(i).equals(path.get(i))) {
                return false;
            }
        }
        int at = base.size();
        if (!NODE_DIR_NAME.equals(path.get(at))
                || !CHAINS_DIR_NAME.equals(path.get(at + 1))
                || !chain.equals(path.get(at + 2))) {
            return false;
        }
        String dbName = path.get(at + 3);
        for (String name : CHAIN_DB_DIR_NAMES) {
            if (name.equals(dbName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean plainSegment(String s) {
        return s != null && !s.isEmpty() && !s.equals(".") && !s.equals("..")
                && s.indexOf('/') < 0 && s.indexOf('\\') < 0
                && s.indexOf('\0') < 0;
    }

    /** The absolute path's segments; null when one is "." or "..". */
    private static List<String> segments(File f) {
        List<String> out = new ArrayList<>();
        for (String part : f.getAbsolutePath().split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            if (part.equals(".") || part.equals("..")) {
                return null;
            }
            out.add(part);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // The deletion
    // ------------------------------------------------------------------

    /** How a reset ended. */
    public enum ResetResult {
        /** The chain database was there and is gone. */
        DELETED,
        /** There was no chain database; nothing was touched. */
        NOTHING_TO_DELETE,
        /** The reset did not finish. If it was refused before
         *  anything was touched there is no marker; otherwise the
         *  marker stays and the rest goes before the next start. */
        FAILED
    }

    /**
     * Why a reset failed, as the screen needs it: one of a few
     * kinds, each with a sentence of its own in every language. The
     * English detail in {@link ResetOutcome#failure} is for the app
     * log only.
     */
    public enum FailureKind {
        /** A database directory is not where its name says it is (a
         *  symbolic link in a parent, a path that does not resolve):
         *  it is not walked. */
        LOCATION,
        /** An entry could not be deleted, or a directory not listed. */
        ENTRIES,
        /** The marker could not be written or removed. */
        MARKER
    }

    /** A reset's result and what it did. Immutable. */
    public static final class ResetOutcome {
        public final ResetResult result;
        /** Entries (files, links, directories) removed. */
        public final long entriesDeleted;
        /** Null unless the result is {@link ResetResult#FAILED}. */
        public final FailureKind failureKind;
        /** For the log: a fixed English phrase or an exception's
         *  class name; never a path. Null unless FAILED. */
        public final String failure;

        ResetOutcome(ResetResult result, long entriesDeleted,
                     FailureKind failureKind, String failure) {
            this.result = result;
            this.entriesDeleted = entriesDeleted;
            this.failureKind = failureKind;
            this.failure = failure;
        }
    }

    /** One failure inside a reset: its kind and its log text. */
    private static final class Failure {
        final FailureKind kind;
        final String text;

        Failure(FailureKind kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    private static ResetOutcome failed(long deleted, Failure f) {
        return new ResetOutcome(ResetResult.FAILED, deleted, f.kind, f.text);
    }

    /**
     * Delete the chain database of {@code chain}: the marker first,
     * then every chain-database directory, then the marker again.
     * The caller guarantees that no node process is alive and none
     * can start meanwhile (NodeService holds its launch lock around
     * this call). Blocking; never call it on the UI thread.
     *
     * Whether each directory is where its name says is looked at
     * BEFORE the marker is written (pre-release audit, L-2): a reset
     * refused on those grounds has deleted nothing, so it must not
     * leave behind a marker that keeps the node from starting. Only
     * a marker that was already there — an earlier reset may be
     * half done — is left as it is.
     */
    public static ResetOutcome resetChainDb(File filesDir, String chain) {
        boolean any = false;
        Failure refusal = null;
        for (File dir : chainDbDirs(filesDir, chain)) {
            if (present(dir)) {
                any = true;
                Failure f = misplaced(filesDir, chain, dir);
                if (f != null && refusal == null) {
                    refusal = f;
                }
            }
        }
        File marker = resetMarkerFile(filesDir);
        boolean pending = marker.exists();
        if (!any && !pending) {
            return new ResetOutcome(ResetResult.NOTHING_TO_DELETE, 0, null,
                    null);
        }
        if (refusal != null && !pending) {
            return failed(0, refusal);
        }
        try (FileOutputStream out = new FileOutputStream(marker)) {
            out.write("a chain database reset was started\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException | RuntimeException e) {
            // Without the marker a kill mid-walk would leave a
            // half-deleted database nobody knows about: do not start.
            return failed(0, new Failure(FailureKind.MARKER,
                    "marker: " + e.getClass().getSimpleName()));
        }
        return deleteMarked(filesDir, chain, marker);
    }

    /** True while a confirmed reset may be unfinished. */
    public static boolean resetPending(File filesDir) {
        return resetMarkerFile(filesDir).exists();
    }

    /**
     * Finish a reset whose marker is still there (the app process
     * died mid-walk, or an entry could not be deleted). Called
     * before a node start; without a marker it does nothing and
     * says {@link ResetResult#NOTHING_TO_DELETE}.
     */
    public static ResetOutcome completePendingReset(File filesDir,
                                                    String chain) {
        File marker = resetMarkerFile(filesDir);
        if (!marker.exists()) {
            return new ResetOutcome(ResetResult.NOTHING_TO_DELETE, 0, null,
                    null);
        }
        return deleteMarked(filesDir, chain, marker);
    }

    private static ResetOutcome deleteMarked(File filesDir, String chain,
                                             File marker) {
        long deleted = 0;
        Failure failure = null;
        for (File dir : chainDbDirs(filesDir, chain)) {
            long[] count = new long[1];
            Failure f = deleteDbDir(filesDir, chain, dir, count);
            deleted += count[0];
            if (f != null && failure == null) {
                failure = f;
            }
        }
        if (failure != null) {
            return failed(deleted, failure);
        }
        if (!marker.delete() && marker.exists()) {
            return failed(deleted, new Failure(FailureKind.MARKER,
                    "marker not removed"));
        }
        return new ResetOutcome(ResetResult.DELETED, deleted, null, null);
    }

    /**
     * Is a chain-database directory that is present somewhere other
     * than where its name says? Null when it may be removed: its
     * path passes the rule and it either is a symbolic link itself
     * (removed as a link, never entered) or resolves to exactly
     * {@code <files>/node/chains/<chain>/<name>}.
     */
    private static Failure misplaced(File filesDir, String chain, File dir) {
        if (!isResyncDeletable(filesDir, chain, dir)) {
            return new Failure(FailureKind.LOCATION,
                    "refused: not a chain database path");
        }
        if (isSymlink(dir)) {
            return null;
        }
        // The names said yes; make sure the file system agrees that
        // this directory is where the names say it is — a symbolic
        // link in a parent would send the walk somewhere else.
        try {
            String want = new File(new File(new File(new File(
                    filesDir.getCanonicalFile(), NODE_DIR_NAME),
                    CHAINS_DIR_NAME), chain), dir.getName()).getPath();
            if (!want.equals(dir.getCanonicalPath())) {
                return new Failure(FailureKind.LOCATION,
                        "refused: the path resolves elsewhere");
            }
        } catch (IOException | RuntimeException e) {
            return new Failure(FailureKind.LOCATION,
                    "refused: " + e.getClass().getSimpleName());
        }
        return null;
    }

    /**
     * Remove one chain-database directory and everything in it.
     *
     * @return null on success (including "was not there"), else why
     */
    private static Failure deleteDbDir(File filesDir, String chain, File dir,
                                       long[] count) {
        if (!present(dir)) {
            return null;
        }
        Failure refusal = misplaced(filesDir, chain, dir);
        if (refusal != null) {
            return refusal;
        }
        if (isSymlink(dir)) {
            // The link is the entry under the chain directory; what
            // it points at is not ours to walk.
            return deleteEntry(dir, count) ? null
                    : new Failure(FailureKind.ENTRIES, "link not removed");
        }
        // Children before parents: a directory is visited twice,
        // once to queue its entries and once, emptied, to go itself.
        ArrayDeque<File> stack = new ArrayDeque<>();
        ArrayDeque<Boolean> emptied = new ArrayDeque<>();
        stack.push(dir);
        emptied.push(Boolean.FALSE);
        Failure failure = null;
        while (!stack.isEmpty()) {
            File cur = stack.pop();
            boolean done = emptied.pop();
            if (!isResyncDeletable(filesDir, chain, cur)) {
                // Unreachable by construction; the rule is asked
                // anyway, for every entry, before anything is removed.
                return new Failure(FailureKind.LOCATION,
                        "refused: left the chain database");
            }
            if (done || isSymlink(cur) || !cur.isDirectory()) {
                if (!deleteEntry(cur, count) && failure == null) {
                    failure = new Failure(FailureKind.ENTRIES,
                            "an entry could not be deleted");
                }
                continue;
            }
            File[] kids = cur.listFiles();
            if (kids == null) {
                if (failure == null) {
                    failure = new Failure(FailureKind.ENTRIES,
                            "a directory could not be listed");
                }
                continue;
            }
            stack.push(cur);
            emptied.push(Boolean.TRUE);
            for (File k : kids) {
                stack.push(k);
                emptied.push(Boolean.FALSE);
            }
        }
        return failure;
    }

    private static boolean deleteEntry(File f, long[] count) {
        try {
            if (f.delete()) {
                count[0]++;
                return true;
            }
        } catch (RuntimeException ignored) {
            // Falls through to the existence check.
        }
        return !present(f);
    }

    /** Exists, counting a dangling symbolic link as present. */
    private static boolean present(File f) {
        try {
            return Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS);
        } catch (RuntimeException e) {
            return f.exists();
        }
    }

    private static boolean isSymlink(File f) {
        try {
            return Files.isSymbolicLink(f.toPath());
        } catch (RuntimeException e) {
            // A path that cannot be examined is not walked into.
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Usage
    // ------------------------------------------------------------------

    /**
     * Bytes used, per part; -1 where a figure could not be measured
     * (a listing failed, or the tree is larger than the walk's cap).
     * A part that does not exist uses 0 bytes. The figures of a
     * running node move while they are being read — its database is
     * open — so they are estimates then, and the screen says so.
     */
    public static final class Usage {
        /** The chain database ({@link #CHAIN_DB_DIR_NAMES}). */
        public final long chainDbBytes;
        /** Everything under {@code <files>/node}. */
        public final long nodeBytes;
        /** {@code <files>/logs}. */
        public final long logsBytes;
        /** {@code <files>/depin} plus {@code <files>/depin-supervisor}. */
        public final long depinBytes;
        /** The app's whole data area. */
        public final long totalBytes;

        Usage(long chainDbBytes, long nodeBytes, long logsBytes,
              long depinBytes, long totalBytes) {
            this.chainDbBytes = chainDbBytes;
            this.nodeBytes = nodeBytes;
            this.logsBytes = logsBytes;
            this.depinBytes = depinBytes;
            this.totalBytes = totalBytes;
        }
    }

    /**
     * Measure. Blocking — a database of a few thousand files is
     * walked — so never on the UI thread.
     *
     * Every file is visited once (pre-release audit, I-5): the
     * chain database is walked for its own figure and left out of
     * the walk of the rest of {@code node}, and the parts already
     * measured are left out of the walk of the data area; the
     * larger figures are sums.
     *
     * @param dataDir the app's data area (the parent of the files
     *                directory on a device); null leaves the total
     *                unmeasured
     */
    public static Usage measure(File filesDir, File dataDir, String chain) {
        List<File> dbDirs = chainDbDirs(filesDir, chain);
        long chainDb = 0;
        for (File dir : dbDirs) {
            chainDb = sum(chainDb, treeBytes(dir, null));
        }
        File nodeDir = nodeDir(filesDir);
        File logsDir = new File(filesDir, LOGS_DIR_NAME);
        File depinDir = new File(filesDir, DEPIN_DIR_NAME);
        File depinSupervisorDir = new File(filesDir, DEPIN_SUPERVISOR_DIR_NAME);
        long node = sum(chainDb, treeBytes(nodeDir, dbDirs));
        long logs = treeBytes(logsDir, null);
        long depin = sum(treeBytes(depinDir, null),
                treeBytes(depinSupervisorDir, null));
        long total;
        if (dataDir == null) {
            total = -1;
        } else if (dataDir.equals(filesDir.getParentFile())) {
            List<File> measured = new ArrayList<>();
            measured.add(nodeDir);
            measured.add(logsDir);
            measured.add(depinDir);
            measured.add(depinSupervisorDir);
            total = sum(sum(node, logs),
                    sum(depin, treeBytes(dataDir, measured)));
        } else {
            // The files directory was not named as a child of the
            // data area: the walk would not recognise the measured
            // parts by their paths, so it takes everything itself.
            total = treeBytes(dataDir, null);
        }
        return new Usage(chainDb, node, logs, depin, total);
    }

    private static long sum(long a, long b) {
        return a < 0 || b < 0 ? -1 : a + b;
    }

    /**
     * The bytes of the files in a tree: 0 for a path that does not
     * exist, -1 when the tree cannot be read whole (a partial total
     * would be a wrong answer, not a smaller one). Symbolic links
     * are neither followed nor counted. An entry that equals one of
     * {@code skip} (as the walk names it: the root's path plus the
     * names below it) is left out, with everything in it.
     */
    static long treeBytes(File root, List<File> skip) {
        if (root == null || !present(root) || isSymlink(root)) {
            return 0;
        }
        if (!root.isDirectory()) {
            return root.length();
        }
        long total = 0;
        ArrayDeque<File> stack = new ArrayDeque<>();
        stack.push(root);
        int seen = 0;
        while (!stack.isEmpty()) {
            File cur = stack.pop();
            File[] kids = cur.listFiles();
            if (kids == null) {
                if (!present(cur)) {
                    // Removed while we walked (a running node does
                    // that); it uses nothing now.
                    continue;
                }
                return -1;
            }
            for (File k : kids) {
                if (++seen > WALK_MAX_ENTRIES) {
                    return -1;
                }
                if (isSymlink(k) || (skip != null && skip.contains(k))) {
                    continue;
                }
                if (k.isDirectory()) {
                    stack.push(k);
                } else {
                    total += k.length();
                }
            }
        }
        return total;
    }
}
