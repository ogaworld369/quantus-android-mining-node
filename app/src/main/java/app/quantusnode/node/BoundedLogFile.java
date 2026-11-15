package app.quantusnode.node;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * An append-only text log with a hard size cap: the miner's log
 * (v4.0.0). It is the node log's sink ({@code NodeService.LogSink})
 * restated as a small Android-free class, so the same rules — which
 * were each learned the hard way there — can be exercised on a JVM
 * and reused for a second file without touching the node's own
 * sink:
 *
 *  - once the file passes its cap, it is rewritten keeping only
 *    its newer half, starting at a line boundary and opening with a
 *    marker line; the rewrite goes through a sibling temp file and
 *    a rename, never in place, so a kill mid-rotation cannot lose
 *    the retained half;
 *  - one line can never outgrow a quarter of the cap: a longer
 *    line is cut at a UTF-8 boundary and marked, so a single
 *    runaway line cannot push every retained line out at the next
 *    rotation;
 *  - a log left over the cap by an earlier process is cut on the
 *    first write (the rotation counter starts at the file's real
 *    length);
 *  - all access to one file — appends, rotations and the bounded
 *    tail read for sharing — is serialized on a lock shared by
 *    every object that names that file, because a service that is
 *    destroyed and recreated leaves two objects pointing at the
 *    same log.
 *
 * Logging never throws: a failed write is dropped, exactly as the
 * node log's is — a log must never take down what it describes.
 */
public final class BoundedLogFile {

    private static final byte[] TRUNC_SUFFIX =
            " ...[line truncated]\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ROTATION_MARK =
            "... [app] log rotated, older half dropped ...\n"
                    .getBytes(StandardCharsets.UTF_8);

    /** One lock object per log file path, for the process's lifetime. */
    private static final Map<String, Object> LOCKS = new HashMap<>();

    private final File file;
    private final long maxBytes;
    private final int maxLineBytes;
    private final long checkEveryBytes;
    private final Object lock;
    private long bytesSinceCheck;

    /**
     * @param file     the log file; its directory is created if needed
     * @param maxBytes the cap; the file is cut back to about half of
     *                 it whenever a check finds it larger
     */
    public BoundedLogFile(File file, long maxBytes) {
        this.file = file;
        this.maxBytes = Math.max(1024L, maxBytes);
        this.maxLineBytes = (int) Math.min(Integer.MAX_VALUE,
                Math.max(128L, this.maxBytes / 4));
        this.checkEveryBytes = Math.max(64L, this.maxBytes / 8);
        this.lock = lockFor(file);
        File dir = file.getParentFile();
        if (dir != null) {
            dir.mkdirs();
        }
        this.bytesSinceCheck = file.isFile() ? file.length() : 0;
    }

    private static Object lockFor(File f) {
        String key;
        try {
            key = f.getCanonicalPath();
        } catch (IOException | RuntimeException e) {
            key = f.getAbsolutePath();
        }
        synchronized (LOCKS) {
            Object l = LOCKS.get(key);
            if (l == null) {
                l = new Object();
                LOCKS.put(key, l);
            }
            return l;
        }
    }

    /** The file this log writes to. */
    public File file() {
        return file;
    }

    /** Append one line. */
    public void write(String line) {
        write(line, false);
    }

    /**
     * Append one line.
     *
     * @param truncated true when the producer already cut this line
     *        at its own cap: the line is then marked as cut even
     *        though what arrives fits.
     */
    public void write(String line, boolean truncated) {
        byte[] bytes = encodeLine(line == null ? "" : line, truncated);
        synchronized (lock) {
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(bytes);
            } catch (IOException | RuntimeException ignored) {
                return;
            }
            bytesSinceCheck += bytes.length;
            if (bytesSinceCheck >= checkEveryBytes) {
                bytesSinceCheck = 0;
                rotateIfNeededLocked();
            }
        }
    }

    private byte[] encodeLine(String line, boolean truncated) {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        if (!truncated && bytes.length <= maxLineBytes) {
            return bytes;
        }
        int keep = Math.min(bytes.length - 1,
                maxLineBytes - TRUNC_SUFFIX.length);
        // Do not split a multi-byte UTF-8 sequence: back off while
        // the first dropped byte is a continuation byte (10xxxxxx).
        while (keep > 0 && (bytes[keep] & 0xC0) == 0x80) {
            keep--;
        }
        byte[] out = new byte[keep + TRUNC_SUFFIX.length];
        System.arraycopy(bytes, 0, out, 0, keep);
        System.arraycopy(TRUNC_SUFFIX, 0, out, keep, TRUNC_SUFFIX.length);
        return out;
    }

    /** Caller must hold {@link #lock}. */
    private void rotateIfNeededLocked() {
        try {
            long len = file.length();
            if (len <= maxBytes) {
                return;
            }
            byte[] all;
            try (FileInputStream in = new FileInputStream(file)) {
                all = readFully(in);
            }
            int keep = (int) (maxBytes / 2);
            int from = Math.max(0, all.length - keep);
            // Start at a line boundary so the log stays readable.
            while (from < all.length && all[from] != (byte) '\n') {
                from++;
            }
            if (from < all.length) {
                from++;
            }
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            boolean moved = false;
            try {
                try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                    out.write(ROTATION_MARK);
                    out.write(all, from, all.length - from);
                }
                moved = tmp.renameTo(file);
            } finally {
                if (!moved) {
                    tmp.delete();
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Still over the cap; the next check tries again.
        }
    }

    /**
     * The end of a log for the share sheet and the diagnostics
     * view: at most {@code maxChars} chars, read with a bound on
     * the bytes touched however large the file is, starting at a
     * line boundary where one exists, and taken under the same
     * lock as every write and rotation of that file — a rotation
     * cannot tear the read. {@code truncatedOut} (when given)
     * reports whether anything was left out. Null when there is no
     * log.
     */
    public static String readTail(File file, int maxChars,
                                  boolean[] truncatedOut) {
        if (truncatedOut != null && truncatedOut.length > 0) {
            truncatedOut[0] = false;
        }
        if (file == null || maxChars <= 0) {
            return null;
        }
        synchronized (lockFor(file)) {
            if (!file.isFile()) {
                return null;
            }
            long len = file.length();
            if (len <= 0) {
                return null;
            }
            // A UTF-16 char costs at most 3 UTF-8 bytes (4 bytes
            // make 2 chars), so 3 bytes per wanted char cover the
            // last maxChars chars whenever the file holds them.
            long want = Math.min(len, (long) maxChars * 3L + 64L);
            byte[] buf = new byte[(int) want];
            try (java.io.RandomAccessFile raf =
                         new java.io.RandomAccessFile(file, "r")) {
                raf.seek(len - want);
                raf.readFully(buf);
            } catch (IOException | RuntimeException e) {
                return null;
            }
            int off = 0;
            if (want < len) {
                mark(truncatedOut);
                int nl = -1;
                for (int i = 0; i < buf.length; i++) {
                    if (buf[i] == (byte) '\n') {
                        nl = i;
                        break;
                    }
                }
                if (nl >= 0) {
                    off = nl + 1;
                } else {
                    while (off < buf.length && (buf[off] & 0xC0) == 0x80) {
                        off++;
                    }
                }
            }
            String s = new String(buf, off, buf.length - off,
                    StandardCharsets.UTF_8);
            if (s.length() > maxChars) {
                mark(truncatedOut);
                int cut = s.length() - maxChars;
                if (Character.isLowSurrogate(s.charAt(cut))) {
                    cut++;
                }
                s = s.substring(cut);
                int nl = s.indexOf('\n');
                if (nl >= 0 && nl + 1 < s.length()) {
                    s = s.substring(nl + 1);
                }
            }
            return s;
        }
    }

    private static void mark(boolean[] truncatedOut) {
        if (truncatedOut != null && truncatedOut.length > 0) {
            truncatedOut[0] = true;
        }
    }

    /** A plain chunk loop: InputStream.readAllBytes() does not
     *  exist on every runtime this app supports (API 31-32). */
    private static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }
}
