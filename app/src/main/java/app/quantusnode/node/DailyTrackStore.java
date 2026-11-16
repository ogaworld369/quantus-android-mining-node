package app.quantusnode.node;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The day-by-day side of the track record (v4.1.0): one record per
 * local calendar day in {@code <files>/track/daily.jsonl}, kept for
 * the last {@link #KEEP_DAYS} days — counted back from an anchor
 * that a single save may move at most one day past the newest day
 * already on record (v4.2.2, gap inventory B-4, as fixed after the
 * v4.2.2 audit, M1/L3): the anchor is the earlier of the day being
 * written and one day past that newest day, so a clock far ahead
 * can neither prune the real history in one save nor stop the
 * record for good, and a day the file already holds is never
 * dropped for being dated after today (pre-release audit, M-1).
 * {@link TrackStore} holds running
 * totals only; this file is what a later version draws a history
 * from. Nothing reads it for display yet — this version only
 * records.
 *
 * Like {@link TrackStore} it never leaves the device, carries no
 * monetary value and ranks nobody. Pure Java, no Android types: the
 * file format, the retention and the sampler are driven on a JVM by
 * the harness (app/tests).
 *
 * A record is one JSON object on one line:
 *
 * <pre>
 * {"date":"2026-11-16","uptimeDeltaMs":86399000,"blocksDelta":7201,
 *  "peerMax":12,"peerSum":230112,"peerSamples":28764,"jobsDelta":0,
 *  "miningMs":0,"approxHashes":0}
 * </pre>
 *
 * (on one line in the file). {@code peerSum / peerSamples} is the
 * day's average peer count. {@code approxHashes} is an ESTIMATE and
 * named as one: the miner's own reported hash rate multiplied by the
 * time it ran while the device was awake, summed over the day — not
 * a count of hashes.
 *
 * Every save rewrites the whole file — a few hundred short lines —
 * into a temporary file beside it and renames that over the old one,
 * so a reader (or a process killed mid-save) sees the old file or
 * the new one, never a torn one.
 */
public final class DailyTrackStore {

    static final String DIR_NAME = "track";
    static final String FILE_NAME = "daily.jsonl";
    /** Days kept, counted back from the retention anchor — the
     *  earlier of the day being written and one day past the
     *  newest day already on record (see {@link #merge}). */
    static final int KEEP_DAYS = 400;

    /** A line longer than this is not one of ours. */
    private static final int MAX_LINE_CHARS = 1024;
    /** Lines read at most; the file never holds more than KEEP_DAYS. */
    private static final int MAX_LINES = 4 * KEEP_DAYS;

    private DailyTrackStore() {
    }

    /** {@code <files>/track/daily.jsonl}. */
    public static File file(File filesDir) {
        return new File(new File(filesDir, DIR_NAME), FILE_NAME);
    }

    /** The local calendar date of a wall-clock time: "2026-11-16". */
    public static String localDate(long wallMs, TimeZone tz) {
        return Instant.ofEpochMilli(wallMs)
                .atZone((tz != null ? tz : TimeZone.getDefault()).toZoneId())
                .toLocalDate().toString();
    }

    // ------------------------------------------------------------------
    // Records
    // ------------------------------------------------------------------

    /** What is added to a day. All fields are increments, >= 0. */
    public static final class Increment {
        /** Node uptime gained. */
        public long uptimeDeltaMs;
        /** New blocks observed. */
        public long blocksDelta;
        /** Largest peer count seen; joined with max, not added. */
        public int peerMax;
        public long peerSum;
        public long peerSamples;
        /** Jobs the miner received. */
        public long jobsDelta;
        /** Time a miner process was running while the device was
         *  awake (an uptimeMillis gap — time the CPU spent
         *  suspended is not counted, v4.2.2 audit L4). */
        public long miningMs;
        /** Reported hash rate x time; an estimate. */
        public long approxHashes;

        boolean isEmpty() {
            return uptimeDeltaMs == 0 && blocksDelta == 0 && peerMax == 0
                    && peerSum == 0 && peerSamples == 0 && jobsDelta == 0
                    && miningMs == 0 && approxHashes == 0;
        }

        void add(Increment o) {
            uptimeDeltaMs = satAdd(uptimeDeltaMs, o.uptimeDeltaMs);
            blocksDelta = satAdd(blocksDelta, o.blocksDelta);
            peerMax = Math.max(peerMax, o.peerMax);
            peerSum = satAdd(peerSum, o.peerSum);
            peerSamples = satAdd(peerSamples, o.peerSamples);
            jobsDelta = satAdd(jobsDelta, o.jobsDelta);
            miningMs = satAdd(miningMs, o.miningMs);
            approxHashes = satAdd(approxHashes, o.approxHashes);
        }
    }

    /** One day. Immutable. */
    public static final class Record {
        /** Local calendar date, "yyyy-MM-dd". */
        public final String date;
        public final long uptimeDeltaMs;
        public final long blocksDelta;
        public final int peerMax;
        public final long peerSum;
        public final long peerSamples;
        public final long jobsDelta;
        public final long miningMs;
        /** An estimate: reported hash rate x time. */
        public final long approxHashes;

        Record(String date, long uptimeDeltaMs, long blocksDelta, int peerMax,
               long peerSum, long peerSamples, long jobsDelta, long miningMs,
               long approxHashes) {
            this.date = date;
            this.uptimeDeltaMs = uptimeDeltaMs;
            this.blocksDelta = blocksDelta;
            this.peerMax = peerMax;
            this.peerSum = peerSum;
            this.peerSamples = peerSamples;
            this.jobsDelta = jobsDelta;
            this.miningMs = miningMs;
            this.approxHashes = approxHashes;
        }

        Record plus(Increment i) {
            return new Record(date,
                    satAdd(uptimeDeltaMs, i.uptimeDeltaMs),
                    satAdd(blocksDelta, i.blocksDelta),
                    Math.max(peerMax, i.peerMax),
                    satAdd(peerSum, i.peerSum),
                    satAdd(peerSamples, i.peerSamples),
                    satAdd(jobsDelta, i.jobsDelta),
                    satAdd(miningMs, i.miningMs),
                    satAdd(approxHashes, i.approxHashes));
        }

        /** The record's line, without the newline. */
        String toLine() {
            return "{\"date\":\"" + date + "\""
                    + ",\"uptimeDeltaMs\":" + uptimeDeltaMs
                    + ",\"blocksDelta\":" + blocksDelta
                    + ",\"peerMax\":" + peerMax
                    + ",\"peerSum\":" + peerSum
                    + ",\"peerSamples\":" + peerSamples
                    + ",\"jobsDelta\":" + jobsDelta
                    + ",\"miningMs\":" + miningMs
                    + ",\"approxHashes\":" + approxHashes
                    + "}";
        }
    }

    /** Saturating add of two non-negative counts. */
    static long satAdd(long a, long b) {
        if (b <= 0) {
            return a;
        }
        return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b;
    }

    private static final Pattern FIELD = Pattern.compile(
            "\"([A-Za-z]+)\"\\s*:\\s*(?:\"([^\"\\\\]*)\"|(-?[0-9]{1,19})(?![0-9]))");
    private static final Pattern DATE =
            Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    /**
     * Parse one line; null for anything that is not a whole, valid
     * record (a damaged line says nothing and is dropped at the next
     * save). A field this version does not know is ignored; a field
     * it knows must be a non-negative integer.
     */
    static Record parseLine(String line) {
        if (line == null) {
            return null;
        }
        String s = line.trim();
        if (s.length() < 2 || s.length() > MAX_LINE_CHARS
                || s.charAt(0) != '{' || s.charAt(s.length() - 1) != '}') {
            return null;
        }
        Map<String, String> text = new LinkedHashMap<>();
        Map<String, Long> num = new LinkedHashMap<>();
        Matcher m = FIELD.matcher(s);
        while (m.find()) {
            if (m.group(2) != null) {
                text.put(m.group(1), m.group(2));
            } else {
                try {
                    num.put(m.group(1), Long.parseLong(m.group(3)));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        String date = text.get("date");
        if (!validDate(date)) {
            return null;
        }
        long[] v = new long[8];
        String[] names = {"uptimeDeltaMs", "blocksDelta", "peerMax",
                "peerSum", "peerSamples", "jobsDelta", "miningMs",
                "approxHashes"};
        for (int i = 0; i < names.length; i++) {
            Long x = num.get(names[i]);
            if (x == null || x < 0) {
                return null;
            }
            v[i] = x;
        }
        if (v[2] > Integer.MAX_VALUE) {
            return null;
        }
        return new Record(date, v[0], v[1], (int) v[2], v[3], v[4], v[5],
                v[6], v[7]);
    }

    static boolean validDate(String date) {
        if (date == null || !DATE.matcher(date).matches()) {
            return false;
        }
        try {
            LocalDate.parse(date);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // The file
    // ------------------------------------------------------------------

    /**
     * The file's records, oldest first, one per date (of two lines
     * for one date the later one stands). A file that does not exist
     * is an empty record; one that exists and cannot be read is an
     * IOException, so that a save never replaces what it failed to
     * read.
     */
    public static List<Record> read(File file) throws IOException {
        TreeMap<String, Record> byDate = new TreeMap<>();
        if (file == null || !file.exists()) {
            return new ArrayList<>();
        }
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            int lines = 0;
            while (lines++ < MAX_LINES && (line = in.readLine()) != null) {
                Record r = parseLine(line);
                if (r != null) {
                    byDate.put(r.date, r);
                }
            }
        }
        return new ArrayList<>(byDate.values());
    }

    /**
     * Fold increments into their days and return the record to save,
     * oldest first. Pure; touches no file.
     *
     * {@code today} — the day being written — bounds what may be
     * added (pre-release audit, M-1):
     *
     *  - an increment dated more than a day after {@code today} is
     *    ignored (one day is allowed for a time-zone change between
     *    the reading and the save);
     *  - a record already in the file is never dropped for being
     *    dated after {@code today}: it moves no anchor, so it can
     *    neither prune nor block anything, and dropping it would let
     *    a clock that reads a past date for one save erase the real
     *    record.
     *
     * Retention drops records older than the {@link #KEEP_DAYS}
     * days ending at the anchor. The anchor is the earlier of
     * {@code today} and one day past the newest day already on
     * record (with no record yet, {@code today} itself) — never
     * {@code today} alone, and never past that newest day plus
     * one. A single save therefore cannot move the window far in
     * either direction: a save whose clock reads far in the
     * future prunes at most the oldest day of the window (v4.2.2
     * audit, L3), and a user who returns after more than
     * {@link #KEEP_DAYS} days is recorded again at once — the
     * first new day is kept, and the window catches up at the
     * next save, instead of refusing every day for good (v4.2.2
     * audit, M1). In the ordinary case, where the newest day on
     * record is {@code today} itself, the window is the one it
     * always was.
     *
     * An invalid {@code today} adds nothing and prunes nothing.
     */
    static List<Record> merge(List<Record> existing,
                              Map<String, Increment> byDate, String today) {
        boolean anchored = validDate(today);
        String ceiling = anchored
                ? LocalDate.parse(today).plusDays(1).toString() : null;
        TreeMap<String, Record> all = new TreeMap<>();
        String newestExisting = null;
        if (existing != null) {
            for (Record r : existing) {
                if (r != null && validDate(r.date)) {
                    all.put(r.date, r);
                    if (newestExisting == null
                            || r.date.compareTo(newestExisting) > 0) {
                        newestExisting = r.date;
                    }
                }
            }
        }
        if (byDate != null) {
            for (Map.Entry<String, Increment> e : byDate.entrySet()) {
                String date = e.getKey();
                Increment inc = e.getValue();
                if (!validDate(date) || inc == null) {
                    continue;
                }
                // ISO dates sort as text.
                if (!anchored || date.compareTo(ceiling) > 0) {
                    continue;
                }
                Record base = all.get(date);
                if (base == null) {
                    base = new Record(date, 0, 0, 0, 0, 0, 0, 0, 0);
                }
                all.put(date, base.plus(inc));
            }
        }
        if (!anchored) {
            return new ArrayList<>(all.values());
        }
        String anchor;
        if (newestExisting == null) {
            anchor = today;
        } else {
            String cap = LocalDate.parse(newestExisting)
                    .plusDays(1).toString();
            anchor = today.compareTo(cap) <= 0 ? today : cap;
        }
        String floor = LocalDate.parse(anchor)
                .minusDays(KEEP_DAYS - 1L).toString();
        return new ArrayList<>(all.tailMap(floor, true).values());
    }

    /** Write {@code records} over {@code file}: temporary file, then rename. */
    static void write(File file, List<Record> records) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()
                && !dir.isDirectory()) {
            throw new IOException("track directory could not be created");
        }
        File tmp = new File(dir, file.getName() + ".tmp");
        StringBuilder sb = new StringBuilder(records.size() * 160);
        for (Record r : records) {
            sb.append(r.toLine()).append('\n');
        }
        try (FileOutputStream out = new FileOutputStream(tmp, false)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        try {
            Files.move(tmp.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            // The temporary file must not linger as a second copy.
            if (!tmp.delete()) {
                tmp.deleteOnExit();
            }
            throw e instanceof IOException
                    ? (IOException) e : new IOException(e);
        }
    }

    /**
     * Read, {@link #merge}, {@link #write}.
     *
     * @param today the local date of the save, "yyyy-MM-dd"
     */
    public static void fold(File file, Map<String, Increment> byDate,
                            String today) throws IOException {
        write(file, merge(read(file), byDate, today));
    }

    /** How many days are on record; -1 when the file cannot be read. */
    public static int dayCount(File file) {
        try {
            return read(file).size();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // The sampler
    // ------------------------------------------------------------------

    /**
     * Turns the app's running totals into per-day increments and
     * saves them every so often. One per app process; the service's
     * poller is its only driver — it adds no thread, alarm or job of
     * its own, and when the poller is not running nothing is
     * sampled.
     *
     * The totals it is shown — {@link TrackStore}'s cumulative
     * uptime and block count, the miner supervisor's session job
     * count — only ever grow, so an increment is "this reading minus
     * the last one". The first reading after the process started is
     * the baseline and adds nothing; a reading below the last one
     * is a stale reading and adds nothing either. What is still
     * unsaved when the app process is killed is lost: at most one
     * save interval's worth.
     *
     * Increments from the running totals are attributed to the day
     * that was current at the start of the interval they accrued
     * over — the previous tick's day — so a tick that crosses
     * midnight closes the old day with what the interval actually
     * produced, and its save falls due at once. Peer observations
     * carry their own time and are filed under the day they were
     * taken on, so the first sample after midnight — or after a
     * long stop, which used to inherit the day of the tick that
     * folded it — belongs to its own day (v4.2.2, audit I-6).
     *
     * The date comes from the wall clock, and the wall clock can be
     * wrong for a moment (pre-release audit, M-1). So it is checked
     * against the monotonic one: a reading more than
     * {@link #CLOCK_JUMP_MS} away from where the last believed
     * reading plus the elapsed time puts it is not believed until
     * the clock has stayed away for {@link #CLOCK_CONFIRM_MS} — a
     * time sync or a deliberate change lasts, a glitch does not.
     * Meanwhile the believed clock runs on by elapsed time, so the
     * increments go to the day they would have gone to. The same
     * believed date is the "today" of every save. A clock that
     * stays wrong for longer than that is taken at its word.
     */
    public static final class Sampler {
        /** How far the wall clock may move against the monotonic
         *  clock between two ticks and still be believed at once. */
        static final long CLOCK_JUMP_MS = 24L * 3600_000L;
        /** How long a jumped wall clock must stay where it jumped
         *  to before it is believed. */
        static final long CLOCK_CONFIRM_MS = 10L * 60_000L;

        private final File file;
        private final long saveEveryMs;

        /** Serialises saves; never taken with the monitor held. */
        private final Object ioLock = new Object();

        private long lastUptimeMs = -1;
        private long lastBlocks = -1;
        /** The supervisor whose job count {@link #lastJobs} follows.
         *  Held weakly: it is compared, never used, and must not
         *  keep a finished session's objects alive. */
        private java.lang.ref.WeakReference<Object> minerSource;
        private long lastJobs;
        private boolean lastMinerRunning;
        private long lastTickElapsedMs = -1;
        /** The uptimeMillis reading of the last regular tick:
         *  the mining gap is measured on this clock, which
         *  stops while the device sleeps, so suspended time is
         *  never credited as mining time (v4.2.2 audit, L4). */
        private long lastTickUptimeMs = -1;
        private double hashCarry;

        /** Unsaved increments by date, oldest first. */
        private final Map<String, Increment> pending = new LinkedHashMap<>();
        /** The day the next totals increment belongs to: the day
         *  current at the previous tick (the interval's start). */
        private String currentDate;
        private long lastSaveElapsedMs = -1;
        /** The wall clock as believed at the last tick. */
        private long believedWallMs;
        /** Since when the wall clock has disagreed with the believed
         *  one; -1 while they agree. */
        private long clockOffSinceElapsedMs = -1;

        public Sampler(File file, long saveEveryMs) {
            this.file = file;
            this.saveEveryMs = saveEveryMs;
        }

        /**
         * One answered peer count (cheap; no file access). The
         * observation is filed under the day it was taken on —
         * computed from its own clocks through the same believed-
         * clock logic the ticks use — never under the day of the
         * tick that happens to fold it later (v4.2.2, audit I-6).
         * The lookup does not move the believed clock (v4.2.2
         * audit, I1): believing a jump here made the next tick
         * count the time up to the observation twice.
         */
        public synchronized void observePeers(int peers, long elapsedMs,
                                              long wallMs, TimeZone tz) {
            if (peers < 0) {
                return;
            }
            String date = localDate(
                    believedWallForObservation(elapsedMs, wallMs), tz);
            Increment day = pending.get(date);
            if (day == null) {
                day = new Increment();
                pending.put(date, day);
            }
            day.peerMax = Math.max(day.peerMax, peers);
            day.peerSum = satAdd(day.peerSum, peers);
            day.peerSamples = satAdd(day.peerSamples, 1);
        }

        /**
         * One poll cycle's readings. The mining gap is measured
         * on {@code uptimeMs} (SystemClock.uptimeMillis): unlike
         * {@code elapsedMs} it stops while the device sleeps, so
         * a suspended stretch is never credited as mining time
         * or hashed over (v4.2.2 audit, L4).
         *
         * @param elapsedMs     a monotonic clock that keeps counting
         *                      while the device sleeps
         * @param uptimeMs      the same moment on the sleep-free
         *                      clock (SystemClock.uptimeMillis)
         * @param wallMs        the wall clock, for the date only
         * @param cumUptimeMs   cumulative node uptime, -1 when unknown
         * @param cumBlocks     cumulative blocks observed, -1 when unknown
         * @param minerSource   identity of the miner supervisor whose
         *                      session counters follow; null without one
         * @param minerRunning  a miner process is running
         * @param jobsReceived  that supervisor's session job count
         * @param hashrateHps   the miner's reported rate; negative
         *                      while unknown
         * @return true when a save is due — call {@link #save()}
         *         (outside any lock of the caller's)
         */
        public synchronized boolean tick(long elapsedMs, long uptimeMs,
                                         long wallMs,
                                         TimeZone tz, long cumUptimeMs,
                                         long cumBlocks, Object minerSource,
                                         boolean minerRunning,
                                         long jobsReceived,
                                         double hashrateHps) {
            return tickInternal(elapsedMs, uptimeMs, wallMs, tz,
                    cumUptimeMs, cumBlocks, minerSource, minerRunning,
                    jobsReceived, hashrateHps, false);
        }

        /**
         * One poll cycle's readings, as {@link #tick(long, long,
         * long, TimeZone, long, long, Object, boolean, long,
         * double)} with the sleep-free clock taken as the
         * monotonic one: for a caller that cannot tell them apart
         * the device is taken to have stayed awake, which is the
         * ordinary case on a JVM harness.
         */
        public synchronized boolean tick(long elapsedMs, long wallMs,
                                         TimeZone tz, long cumUptimeMs,
                                         long cumBlocks, Object minerSource,
                                         boolean minerRunning,
                                         long jobsReceived,
                                         double hashrateHps) {
            return tickInternal(elapsedMs, elapsedMs, wallMs, tz,
                    cumUptimeMs, cumBlocks, minerSource, minerRunning,
                    jobsReceived, hashrateHps, false);
        }

        /**
         * The closing fold of a poller that is about to stop
         * (v4.2.2, audit I-6): the totals the session accumulated
         * since the last tick are recorded, and nothing else
         * happens. A poller being replaced may be stale — the
         * session it sampled has ended and a new one may already
         * be live — so this fold must not clear the miner baseline
         * ({@code lastMinerRunning}, the job baseline, the
         * supervisor identity) or move the tick anchor the way a
         * regular tick does: the old final tick did exactly that,
         * and the live session's next interval then counted no
         * mining time for want of a "previous tick". The caller
         * saves unconditionally after this returns.
         */
        public synchronized void tickFinal(long elapsedMs, long wallMs,
                                           TimeZone tz, long cumUptimeMs,
                                           long cumBlocks) {
            tickInternal(elapsedMs, elapsedMs, wallMs, tz, cumUptimeMs,
                    cumBlocks, null, false, 0, -1, true);
        }

        private boolean tickInternal(long elapsedMs, long uptimeMs,
                                     long wallMs,
                                     TimeZone tz, long cumUptimeMs,
                                     long cumBlocks, Object minerSource,
                                     boolean minerRunning,
                                     long jobsReceived, double hashrateHps,
                                     boolean finalTick) {
            String date = localDate(believeWall(elapsedMs, wallMs), tz);
            boolean dateChanged = currentDate != null
                    && !currentDate.equals(date);
            if (currentDate == null) {
                currentDate = date;
            }
            // What accrued over the interval belongs to the day
            // that was current at its start; only then does the
            // new day begin.
            Increment inc = new Increment();
            if (cumUptimeMs >= 0) {
                if (lastUptimeMs >= 0 && cumUptimeMs > lastUptimeMs) {
                    inc.uptimeDeltaMs = cumUptimeMs - lastUptimeMs;
                }
                lastUptimeMs = Math.max(lastUptimeMs, cumUptimeMs);
            }
            if (cumBlocks >= 0) {
                if (lastBlocks >= 0 && cumBlocks > lastBlocks) {
                    inc.blocksDelta = cumBlocks - lastBlocks;
                }
                lastBlocks = Math.max(lastBlocks, cumBlocks);
            }
            if (finalTick) {
                // No miner accounting at all: see tickFinal.
            } else if (minerSource == null) {
                lastMinerRunning = false;
            } else {
                Object known = this.minerSource == null
                        ? null : this.minerSource.get();
                if (minerSource != known) {
                    // A new supervisor counts its session from zero.
                    this.minerSource =
                            new java.lang.ref.WeakReference<>(minerSource);
                    lastJobs = 0;
                    lastMinerRunning = false;
                }
                if (jobsReceived > lastJobs) {
                    inc.jobsDelta = jobsReceived - lastJobs;
                    lastJobs = jobsReceived;
                }
                // Mining time is the awake gap between the two
                // ticks that saw the miner running at both ends,
                // counted in full like the uptime deltas beside it:
                // uptimeMillis stops while the CPU sleeps, so time
                // the device spent suspended is not credited, and
                // no hashes are estimated for it either (v4.2.2
                // audit, L4 — during a suspend the miner freezes
                // with the CPU; the old 60 s cap dropped such gaps
                // outright, which undercounted, and the uncapped
                // monotonic gap overcounted them). A delayed tick
                // still counts the awake time between the readings.
                long upGap = lastTickUptimeMs < 0 || uptimeMs < 0
                        ? 0 : uptimeMs - lastTickUptimeMs;
                if (minerRunning && lastMinerRunning && upGap > 0) {
                    inc.miningMs = upGap;
                    if (hashrateHps > 0 && !Double.isInfinite(hashrateHps)
                            && !Double.isNaN(hashrateHps)) {
                        hashCarry += hashrateHps * upGap / 1000.0;
                        double whole = Math.floor(hashCarry);
                        if (whole >= 1.0) {
                            inc.approxHashes = whole >= (double) Long.MAX_VALUE
                                    ? Long.MAX_VALUE : (long) whole;
                            hashCarry -= whole;
                        }
                    }
                }
                lastMinerRunning = minerRunning;
            }
            if (!finalTick) {
                lastTickElapsedMs = elapsedMs;
                lastTickUptimeMs = uptimeMs;
            }
            if (!inc.isEmpty()) {
                Increment day = pending.get(currentDate);
                if (day == null) {
                    pending.put(currentDate, inc);
                } else {
                    day.add(inc);
                }
            }
            currentDate = date;
            if (lastSaveElapsedMs < 0) {
                lastSaveElapsedMs = elapsedMs;
            }
            if (pending.isEmpty()) {
                return false;
            }
            if (dateChanged || elapsedMs - lastSaveElapsedMs >= saveEveryMs) {
                lastSaveElapsedMs = elapsedMs;
                return true;
            }
            return false;
        }

        /** The wall clock to go by at this tick; see the class text. */
        private long believeWall(long elapsedMs, long wallMs) {
            if (lastTickElapsedMs < 0) {
                believedWallMs = wallMs;
                return wallMs;
            }
            long expected = believedWallMs
                    + Math.max(0L, elapsedMs - lastTickElapsedMs);
            long off = wallMs - expected;
            if (off >= -CLOCK_JUMP_MS && off <= CLOCK_JUMP_MS) {
                clockOffSinceElapsedMs = -1;
                believedWallMs = wallMs;
            } else if (clockOffSinceElapsedMs < 0) {
                clockOffSinceElapsedMs = elapsedMs;
                believedWallMs = expected;
            } else if (elapsedMs - clockOffSinceElapsedMs
                    >= CLOCK_CONFIRM_MS) {
                clockOffSinceElapsedMs = -1;
                believedWallMs = wallMs;
            } else {
                believedWallMs = expected;
            }
            return believedWallMs;
        }

        /**
         * The wall clock an observation between ticks goes by:
         * the same branches as {@link #believeWall}, computed
         * from the stored state without changing it. An
         * observation is not a tick — believing a jump here
         * would move {@code believedWallMs} forward and the next
         * tick would count the time up to the observation a
         * second time (v4.2.2 audit, I1).
         */
        private long believedWallForObservation(long elapsedMs,
                                                long wallMs) {
            if (lastTickElapsedMs < 0) {
                return wallMs;
            }
            long expected = believedWallMs
                    + Math.max(0L, elapsedMs - lastTickElapsedMs);
            long off = wallMs - expected;
            if (off >= -CLOCK_JUMP_MS && off <= CLOCK_JUMP_MS) {
                return wallMs;
            }
            if (clockOffSinceElapsedMs < 0) {
                return expected;
            }
            if (elapsedMs - clockOffSinceElapsedMs >= CLOCK_CONFIRM_MS) {
                return wallMs;
            }
            return expected;
        }

        /**
         * Save what is pending. Blocking file access; on failure the
         * increments stay pending for the next save.
         *
         * @return null on success or with nothing to save, else the
         *         failure's class name (for a log line; never a path)
         */
        public String save() {
            synchronized (ioLock) {
                Map<String, Increment> taken;
                String today;
                synchronized (this) {
                    if (pending.isEmpty()) {
                        return null;
                    }
                    taken = new LinkedHashMap<>(pending);
                    pending.clear();
                    // The last tick's date: the day being written.
                    today = currentDate;
                }
                try {
                    fold(file, taken, today);
                    return null;
                } catch (IOException | RuntimeException e) {
                    synchronized (this) {
                        // Put back, ahead of what arrived meanwhile.
                        Map<String, Increment> later =
                                new LinkedHashMap<>(pending);
                        pending.clear();
                        pending.putAll(taken);
                        for (Map.Entry<String, Increment> x
                                : later.entrySet()) {
                            Increment have = pending.get(x.getKey());
                            if (have == null) {
                                pending.put(x.getKey(), x.getValue());
                            } else {
                                have.add(x.getValue());
                            }
                        }
                    }
                    return e.getClass().getName();
                }
            }
        }

        /** Unsaved days, for the harness. */
        synchronized Map<String, Increment> pendingForTest() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(pending));
        }
    }
}
