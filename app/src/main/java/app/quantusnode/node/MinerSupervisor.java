package app.quantusnode.node;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the bundled miner as the app's second child process, for one
 * node session (v4.0.0, specification sections 2.4 and 3.2).
 *
 * The node's own worker in {@link NodeService} is the model, and the
 * rules are the same ones: an argument array and no shell, one retry
 * through /system/bin/linker64 when the direct start is refused, a
 * bounded stop (destroy, a grace period, destroyForcibly), every
 * child's exit awaited by a thread of ours so nothing is left
 * unreaped, and output read through a bounded line reader into a
 * capped log. It lives in a class of its own, free of Android types,
 * for two reasons: NodeService's worker is written around exactly
 * one process and its session bookkeeping (uptime, attempts, the pid
 * record), none of which a miner has; and a supervisor that only
 * needs a clock, a logger and four facts about the node can be
 * driven on a JVM against real child processes, which is where its
 * tests run.
 *
 * What the supervisor does, in order:
 *
 *  1. removes any miner left over from an earlier session (see
 *     {@link #LIVE_MINERS} and {@link #reapStrayMiners});
 *  2. waits until the node is up — its RPC answers, or its log said
 *     the miner endpoint is listening, or a bounded wait ran out;
 *  3. waits until the node's token and certificate-pin files exist,
 *     are readable and are non-empty, and says which one it is
 *     waiting for; nothing is started before that;
 *  4. starts the miner with the fixed command of
 *     {@link MiningLogic#minerCommand} and watches it.
 *
 * A miner that exits without having been asked to is restarted on
 * {@link MiningLogic.RestartPolicy}'s modest schedule and then left
 * alone. Whatever the miner does, the node is never touched: the
 * supervisor holds no reference to the node process and has no way
 * to stop or restart it.
 *
 * Secrets. The auth token reaches the miner as a file path. The
 * supervisor reads the token file once per start, into
 * {@link MiningLogic.SecretScrubber} only, and every line of miner
 * output passes through that scrubber before it is written to the
 * miner log, kept for the status screen or parsed — so neither the
 * token nor the rewards inner hash can reach the log, the screen or
 * a shared report through this class even if a later miner build
 * were to print one.
 */
public final class MinerSupervisor implements MiningLogic.MinerView,
        MiningLogic.ThermalControl {

    /** What the supervisor needs from the app around it. */
    public interface Env {
        /** A monotonic clock, in milliseconds. */
        long elapsedMs();

        /** One line for the app's own log ("[app] ..." in node.log). */
        void log(String message);

        /** True once the node has answered its RPC in this session. */
        boolean nodeUp();

        /** The worker count to start the miner with; read at every start. */
        int cpuWorkers();

        /** Kill a stray miner process this app owns (SIGKILL). */
        void killStrayProcess(long pid);

        /** This app process's own pid — never a target. */
        long selfPid();
    }

    /** Starts a process from an argument array. A seam for tests. */
    interface Launcher {
        Process start(List<String> command) throws IOException;
    }

    /** Receives the lines read by {@link #drainLines}. */
    interface LineSink {
        void onLine(String line, boolean truncated);
    }

    /** Where things are, and how long to wait for them. */
    public static final class Config {
        /** The miner executable (lib/arm64-v8a/libquantus_miner.so). */
        public File minerBinary;
        /** The node's miner-auth-token file. */
        public File tokenFile;
        /** The node's miner-tls-cert-sha256 file. */
        public File fingerprintFile;
        /** The miner's own bounded log. */
        public File logFile;
        /**
         * Written before the first miner is ever started and never
         * read here: its presence tells a later launch WITHOUT mining
         * that a miner may have been left behind by a killed app
         * process, so only then is /proc looked at. An install that
         * never mined has no marker and does no scan.
         */
        public File markerFile;
        public File procRoot = new File("/proc");
        /** A failed exit sooner than this is a start that never got going. */
        public long quickExitMs = 2500;
        /** Readiness is polled this often. */
        public long pollMs = 1000;
        /** The longest the node's "up" is waited for before the file check. */
        public long nodeWaitMaxMs = 60_000;
        /** Grace after destroy(), and again after destroyForcibly(). */
        public long stopWaitMs = 5000;
        public long logMaxBytes = 256L * 1024L;
        /** Test seams; null / 0 mean the production values. */
        long[] restartDelaysMs;
        long stableRunMs;
        Launcher launcher;
        /** Creates the supervisor's own thread; null means a plain
         *  {@code new Thread}. */
        java.util.concurrent.ThreadFactory threads;
    }

    private static final String LINKER64 = "/system/bin/linker64";
    private static final int TOKEN_READ_MAX_BYTES = 4096;
    private static final int CMDLINE_READ_MAX_BYTES = 8192;
    private static final int TAIL_LINES = 30;
    private static final int TAIL_LINE_CHARS = 400;
    private static final long DRAIN_JOIN_MS = 3000;

    /**
     * Every miner process started by this app process whose exit has
     * not been observed yet. A supervisor belongs to one node
     * session; when the next session's supervisor starts, anything
     * still listed here is a miner that was told to stop and has not
     * finished exiting, and it is terminated before a new one is
     * started — two miners must never run at once.
     *
     * This is deliberately different from the node's registry
     * (NodeService.LIVE_CHILD_PROCESSES), which "never licenses a
     * kill": a node that is still closing its database must be
     * waited for. A miner holds no state on disk — it is a stateless
     * client of the node — so ending it forcibly loses nothing, and
     * waiting for it would only delay the new session.
     */
    private static final Set<Process> LIVE_MINERS =
            Collections.newSetFromMap(new ConcurrentHashMap<Process, Boolean>());

    private final Config cfg;
    private final Env env;
    private final MiningLogic.MinerStats stats;
    private final MiningLogic.RestartPolicy policy;
    private final Launcher launcher;
    private final BoundedLogFile logFile;
    private final int maxLineBytes;

    /** Guards everything below; never held across a blocking call. */
    private final Object lock = new Object();
    private Thread thread;
    private boolean started;
    private boolean finished;
    private boolean stopRequested;
    private boolean restartRequested;
    private Process current;
    private MiningLogic.Phase phase = MiningLogic.Phase.IDLE;
    private MiningLogic.FilesStatus files = MiningLogic.FilesStatus.READY;
    private Integer lastExitCode;
    private int cpuWorkers;
    private boolean viaLinker;
    /** Set when the thermal protection ended this miner (fix2). */
    private boolean thermalStop;
    /** This session's thermal governor (fix2); the service feeds it
     *  platform thermal statuses, and this supervisor applies its
     *  cap to the environment's worker count itself. Shared across
     *  the session's node processes (the service hands it to each
     *  supervisor), so the cap and the stop survive the app's own
     *  restarts and reset only at the user's next start (fix2
     *  audit L-2). */
    private volatile MiningLogic.ThermalGovernor thermalGovernor =
            new MiningLogic.ThermalGovernor();

    /** Adopt the session's governor. Call before start(); without
     *  it the supervisor uses its own. */
    public void setThermalGovernor(MiningLogic.ThermalGovernor g) {
        if (g != null) {
            thermalGovernor = g;
        }
    }
    private final ArrayDeque<String> tail = new ArrayDeque<>();

    private volatile MiningLogic.SecretScrubber scrubber;
    private volatile boolean exporterWarned;

    /**
     * @param scrubber what is known of the session's secrets at
     *                 launch (the inner hash; the token too when its
     *                 file already exists); the token is added here
     *                 once the file can be read
     * @param stats    the session's counters; the node's output feeds
     *                 the same object from NodeService's drain
     */
    public MinerSupervisor(Config cfg, Env env,
                           MiningLogic.SecretScrubber scrubber,
                           MiningLogic.MinerStats stats) {
        this.cfg = cfg;
        this.env = env;
        this.scrubber = scrubber != null
                ? scrubber : MiningLogic.SecretScrubber.of(null, null);
        this.stats = stats != null ? stats : new MiningLogic.MinerStats();
        this.policy = cfg.restartDelaysMs != null
                ? new MiningLogic.RestartPolicy(cfg.restartDelaysMs,
                        cfg.stableRunMs)
                : new MiningLogic.RestartPolicy();
        this.launcher = cfg.launcher != null ? cfg.launcher : defaultLauncher();
        this.logFile = new BoundedLogFile(cfg.logFile, cfg.logMaxBytes);
        // One byte under the log's own per-line cap, as the node's
        // drain does: a line cut here still reaches the log marked
        // as cut.
        this.maxLineBytes = (int) Math.max(256L,
                Math.min(Integer.MAX_VALUE, cfg.logMaxBytes / 4L - 1L));
    }

    /**
     * The production launcher: the argument array as given, no shell,
     * standard error merged into standard output. The miner reads
     * every one of its options from the environment as well
     * (MINER_AUTH_TOKEN, MINER_NODE_ADDR, MINER_METRICS_PORT, ...);
     * those variables and RUST_LOG are removed from the child's
     * environment, so the fixed command line is the miner's whole
     * configuration and nothing inherited can redirect it — or hand
     * it a token by value.
     */
    static Launcher defaultLauncher() {
        return new Launcher() {
            @Override
            public Process start(List<String> command) throws IOException {
                ProcessBuilder pb = new ProcessBuilder(command);
                Map<String, String> environment = pb.environment();
                List<String> drop = new ArrayList<>();
                for (String key : environment.keySet()) {
                    if (key != null && (key.startsWith("MINER_")
                            || key.equals("RUST_LOG"))) {
                        drop.add(key);
                    }
                }
                for (String key : drop) {
                    environment.remove(key);
                }
                pb.redirectErrorStream(true);
                return pb.start();
            }
        };
    }

    // ------------------------------------------------------------------
    // Control
    // ------------------------------------------------------------------

    /**
     * Start supervising. A second call, or one after a stop, does
     * nothing.
     *
     * Never throws (v4.1.0, final check FC-2): the caller is the
     * node's own worker thread, and a thread that cannot be created
     * here — an OutOfMemoryError from Thread.start() is the known
     * shape — must not end that worker and the node session with
     * it. The supervisor is then finished before it began: the
     * snapshot reports START_FAILED, the app log says why, and
     * nothing is left to stop.
     */
    public void start() {
        Throwable failure;
        synchronized (lock) {
            if (started || stopRequested) {
                return;
            }
            started = true;
            try {
                Thread t = cfg.threads != null
                        ? cfg.threads.newThread(this::run)
                        : new Thread(this::run, "miner-supervisor");
                t.setDaemon(true);
                thread = t;
                t.start();
                return;
            } catch (Throwable t) {
                failure = t;
                thread = null;
                phase = MiningLogic.Phase.START_FAILED;
                finished = true;
                lock.notifyAll();
            }
        }
        try {
            safeLog("WARN miner: the supervisor thread could not be started ("
                    + failure.getClass().getName() + "); mining stays off"
                    + " until the node is restarted. The node is not"
                    + " affected");
        } catch (Throwable ignored) {
            // The state above already tells the status screen.
        }
    }

    /**
     * End the miner and the supervisor. Returns at once: the process
     * is asked to exit here, and a watcher escalates to
     * destroyForcibly() if it has not gone within the grace period.
     * Safe to call from any thread, any number of times.
     *
     * @param why a fixed phrase for the log (never user data)
     */
    public void requestStop(String why) {
        Process p;
        boolean live;
        synchronized (lock) {
            if (stopRequested) {
                return;
            }
            stopRequested = true;
            live = started && !finished;
            p = current;
            lock.notifyAll();
        }
        if (!live) {
            return;
        }
        // The process is ended BEFORE the log line is written
        // (v4.2.2, final-check FC-4): the log appends to a file,
        // and if that call threw, the miner would be left running
        // while every later stop returns early on the flag. Only
        // an Error can escape the log at all — LogSink already
        // swallows an IOException — but the stop must not depend
        // on the log in any case.
        if (p != null) {
            destroyQuietly(p);
            escalateAsync(p);
        }
        safeLog("miner: stop requested (" + why + ")");
    }

    /**
     * Restart the miner process alone: the running one is ended and
     * a new one started with the current worker setting. A manual
     * restart also clears the failure count, so it is the way out of
     * "gave up" and "could not start". Ignored once stopped.
     */
    @Override
    public void requestRestart() {
        Process p;
        synchronized (lock) {
            if (!started || finished || stopRequested) {
                return;
            }
            restartRequested = true;
            p = current;
            lock.notifyAll();
        }
        // Ended before the log line, as in requestStop (v4.2.2,
        // final-check FC-4): the stop must not depend on the log.
        if (p != null) {
            destroyQuietly(p);
            escalateAsync(p);
        }
        safeLog("miner: restart requested");
    }

    /** This session's thermal governor (fix2). */
    public MiningLogic.ThermalGovernor thermalGovernor() {
        return thermalGovernor;
    }

    /** Workers of the miner process alive or being started right
     *  now; 0 while there is none (fix2,
     *  {@link MiningLogic.ThermalControl}). The thermal cap is
     *  read, and the worker count and the STARTING phase are
     *  published, in one scope of the lock, so a start in flight is
     *  counted (fix2 audit L-1; v4.1.0, final check FC-1). */
    @Override
    public int runningWorkers() {
        synchronized (lock) {
            return current != null
                    || phase == MiningLogic.Phase.STARTING
                    ? cpuWorkers : 0;
        }
    }

    /**
     * End the miner for the rest of the session because the device
     * is too hot (fix2, {@link MiningLogic.ThermalControl}). The
     * stop is the ordinary bounded one; the flag is what the
     * snapshot reports, so the status can name the cause. The miner
     * does not come back on its own, nor with the app's own
     * restarts; it starts again only at the user's next start.
     *
     * The stop is requested before the log line is written
     * (v4.2.2, final-check FC-4): the log appends to a file, and
     * an Error escaping that call — a full disk cannot do it,
     * LogSink already swallows an IOException — must never be
     * able to leave the miner running.
     */
    @Override
    public void requestThermalStop() {
        synchronized (lock) {
            if (thermalStop) {
                // The governor calls again on every status after
                // the latch; the stop and its log line happen once.
                return;
            }
            thermalStop = true;
        }
        requestStop("thermal protection");
        safeLog("miner: stopped by thermal protection");
    }

    @Override
    public MiningLogic.MinerSnapshot snapshot(long nowElapsedMs) {
        MiningLogic.StatsSnapshot s = stats.snapshot(nowElapsedMs);
        synchronized (lock) {
            return new MiningLogic.MinerSnapshot(phase, files, lastExitCode,
                    policy.consecutiveFailures(), policy.restartsTotal(),
                    cpuWorkers, viaLinker, s,
                    Collections.unmodifiableList(new ArrayList<>(tail)),
                    thermalGovernor.cap(),
                    thermalStop || thermalGovernor.stopped());
        }
    }

    /** The session's scrubber, including the token once it was read. */
    public MiningLogic.SecretScrubber scrubber() {
        return scrubber;
    }

    /**
     * Wait for the supervisor thread to end.
     *
     * @return true when it has ended (or was never started)
     */
    public boolean awaitTermination(long timeoutMs) {
        Thread t;
        synchronized (lock) {
            if (!started || finished) {
                return true;
            }
            t = thread;
        }
        try {
            t.join(Math.max(1L, timeoutMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (lock) {
            return finished;
        }
    }

    // ------------------------------------------------------------------
    // The supervisor thread
    // ------------------------------------------------------------------

    private void run() {
        try {
            supervise();
        } catch (Throwable t) {
            // Nothing here may take the app process down with it. The
            // class name is enough for the log; a message could carry
            // text that was never scrubbed.
            safeLog("WARN miner: supervisor ended on an unexpected error: "
                    + t.getClass().getName());
            Process p;
            synchronized (lock) {
                p = current;
            }
            if (p != null) {
                destroyQuietly(p);
                escalateAsync(p);
            }
        } finally {
            synchronized (lock) {
                phase = MiningLogic.Phase.IDLE;
                finished = true;
                lock.notifyAll();
            }
        }
    }

    private void supervise() {
        if (stopRequested()) {
            return;
        }
        removeLeftovers();

        // The node first: before it is up there is no endpoint to
        // connect to, and on a first run no token file either.
        setPhase(MiningLogic.Phase.WAITING_NODE);
        long waitStart = env.elapsedMs();
        while (!stopRequested()) {
            takeRestartRequest();
            long now = env.elapsedMs();
            if (env.nodeUp() || stats.snapshot(now).endpointListening) {
                break;
            }
            if (now - waitStart >= cfg.nodeWaitMaxMs) {
                // The file check below is the real gate; this wait
                // only keeps the miner from racing a node that is
                // still starting.
                safeLog("miner: the node has not reported ready after "
                        + cfg.nodeWaitMaxMs + "ms; going on to the file check");
                break;
            }
            pause(cfg.pollMs);
        }

        // linker64 is used from then on once it has been shown to be
        // the way this device runs the binary; a probe that failed
        // just like the direct start proves nothing and is not kept.
        boolean preferLinker = false;
        boolean linkerProbed = false;
        boolean retryViaLinker = false;
        // What made this round turn to linker64: the direct start
        // was refused outright, or a direct run exited at once (its
        // code and lifetime are kept until the retry has spawned).
        boolean directRefused = false;
        Integer probedExitCode = null;
        long probedLivedMs = 0;
        MiningLogic.FilesStatus notedFiles = null;

        while (!stopRequested()) {
            if (takeRestartRequest()) {
                linkerProbed = false;
                retryViaLinker = false;
                directRefused = false;
                probedExitCode = null;
            }

            if (!cfg.minerBinary.isFile()) {
                setPhase(MiningLogic.Phase.BINARY_MISSING);
                safeLog("WARN miner: the miner binary is missing from this"
                        + " build; mining cannot start");
                awaitStopOrRestart();
                continue;
            }

            MiningLogic.FilesStatus fs = MiningLogic.checkMinerFiles(
                    cfg.tokenFile, cfg.fingerprintFile);
            if (fs != MiningLogic.FilesStatus.READY) {
                synchronized (lock) {
                    phase = MiningLogic.Phase.WAITING_FILES;
                    files = fs;
                }
                if (fs != notedFiles) {
                    notedFiles = fs;
                    safeLog("miner: waiting for the node's "
                            + MiningLogic.blockingFileName(fs)
                            + " (" + fs + ")");
                }
                pause(cfg.pollMs);
                continue;
            }
            notedFiles = null;
            synchronized (lock) {
                files = MiningLogic.FilesStatus.READY;
            }
            learnToken();

            if (thermalGovernor.stopped()) {
                // The session's governor stopped mining on an
                // earlier node process of this session: the miner
                // does not start again until the user's next start.
                requestThermalStop();
                break;
            }

            final boolean useLinker = preferLinker || retryViaLinker;
            retryViaLinker = false;
            // The environment resolves the setting, outside the lock
            // (it reads a preference). This session's thermal cap is
            // applied by the governor's owner (fix2 audit L-6), and
            // the cap is read and the result published — with the
            // STARTING phase — inside ONE lock scope (v4.1.0, final
            // check FC-1): ThermalGovernor.apply lowers the cap
            // first and asks runningWorkers() second, so it either
            // sees this start's count (and reduces it) or this
            // start sees the lowered cap. Reading the cap before
            // the lock left a start in between that neither side
            // saw. Lock order is this lock, then the governor's —
            // the order snapshot() already uses; the governor calls
            // nothing while it holds its own.
            final int resolved = env.cpuWorkers();
            final int workers;
            synchronized (lock) {
                workers = Math.max(1, thermalGovernor.capWorkers(resolved));
                phase = MiningLogic.Phase.STARTING;
                cpuWorkers = workers;
                viaLinker = useLinker;
            }
            List<String> cmd = MiningLogic.minerCommand(
                    cfg.minerBinary.getAbsolutePath(), useLinker,
                    cfg.tokenFile.getAbsolutePath(),
                    cfg.fingerprintFile.getAbsolutePath(), workers);
            ensureMarker();
            if (stopRequested()) {
                break;
            }
            // The command carries file paths only; it goes through
            // the same redaction as the node's launch line anyway.
            safeLog("miner launch: " + MiningLogic.redactCommandForLog(cmd));

            final Process p;
            try {
                p = launcher.start(cmd);
            } catch (Throwable t) {
                safeLog("miner start failed ("
                        + (useLinker ? "linker64" : "direct") + "): "
                        + describe(t));
                if (!useLinker) {
                    directRefused = true;
                    if (!linkerProbed) {
                        // Execution refused: the one fallback attempt.
                        // If linker64 can start it, that is this
                        // device's way and there is no point asking
                        // directly again.
                        linkerProbed = true;
                        retryViaLinker = true;
                        preferLinker = true;
                        continue;
                    }
                } else {
                    preferLinker = false;
                    if (!directRefused && probedExitCode != null) {
                        // linker64 cannot be used here, but the direct
                        // path does start the miner — it was the miner
                        // that failed. Judge THAT exit, by the same
                        // policy as any other, instead of reporting a
                        // start problem that does not exist.
                        int code = probedExitCode;
                        probedExitCode = null;
                        afterAbnormalExit(code, probedLivedMs);
                        continue;
                    }
                }
                setPhase(MiningLogic.Phase.START_FAILED);
                safeLog("WARN miner: could not be started, directly or via"
                        + " linker64; mining stays off until the miner or"
                        + " the node is restarted");
                preferLinker = false;
                awaitStopOrRestart();
                continue;
            }
            probedExitCode = null;

            final long spawnedAt = env.elapsedMs();
            LIVE_MINERS.add(p);
            boolean endAtOnce;
            synchronized (lock) {
                current = p;
                phase = MiningLogic.Phase.RUNNING;
                // A stop or restart that arrived while the process
                // was being created saw no process to end. Both sides
                // decide under this lock, so one of them always sees
                // the other.
                endAtOnce = stopRequested || restartRequested;
            }
            stats.onMinerProcessStarted(spawnedAt);
            String method = useLinker ? "via linker64" : "direct";
            logFile.write("[app] miner process started (" + method
                    + ", cpu-workers " + workers + ")");
            safeLog("miner started (" + method + "), cpu-workers " + workers);
            Thread drain = new Thread(() -> drainOutput(p), "miner-output");
            drain.setDaemon(true);
            drain.start();
            if (endAtOnce) {
                destroyQuietly(p);
                escalateAsync(p);
            }

            final int code = waitForExit(p);
            final long livedMs = env.elapsedMs() - spawnedAt;
            LIVE_MINERS.remove(p);
            boolean stop;
            boolean restart;
            synchronized (lock) {
                if (current == p) {
                    current = null;
                }
                stop = stopRequested;
                restart = restartRequested;
            }
            joinDrain(drain, p);
            logFile.write("[app] miner process exited: code=" + code
                    + " after " + livedMs + "ms");
            safeLog("miner exited: code=" + code + " after " + livedMs + "ms"
                    + (stop ? " (stop requested)"
                    : restart ? " (restart requested)" : ""));
            if (useLinker && livedMs >= cfg.quickExitMs) {
                preferLinker = true;
            }
            if (stop) {
                break;
            }
            if (restart) {
                continue;
            }

            synchronized (lock) {
                lastExitCode = code;
            }
            boolean quickFailure = code != 0 && livedMs < cfg.quickExitMs;
            if (!useLinker && !linkerProbed && quickFailure) {
                // Same rule as the node: a start that failed at once
                // on the direct path gets one try via linker64, and
                // that try is not counted as a failure of the miner.
                linkerProbed = true;
                retryViaLinker = true;
                probedExitCode = code;
                probedLivedMs = livedMs;
                safeLog("miner: quick exit; retrying once via " + LINKER64);
                continue;
            }
            afterAbnormalExit(code, livedMs);
        }
    }

    /**
     * Count an exit nobody asked for, and wait as the restart policy
     * says: the pause before the next start, or — once the automatic
     * restarts are used up — until a stop or a manual restart.
     */
    private void afterAbnormalExit(int code, long livedMs) {
        long delay;
        int failures;
        synchronized (lock) {
            lastExitCode = code;
            delay = policy.onAbnormalExit(livedMs);
            failures = policy.consecutiveFailures();
        }
        if (delay == MiningLogic.GIVE_UP) {
            setPhase(MiningLogic.Phase.GAVE_UP);
            safeLog("WARN miner: giving up after repeated failures (last"
                    + " exit code " + code + "); the node keeps running."
                    + " Restart the miner or the node to try again");
            awaitStopOrRestart();
            return;
        }
        setPhase(MiningLogic.Phase.BACKOFF);
        safeLog("WARN miner: exited on its own (code " + code
                + "); restart " + failures + " in " + delay + "ms");
        pause(delay);
    }

    private boolean stopRequested() {
        synchronized (lock) {
            return stopRequested;
        }
    }

    /** Consume a pending restart request; a restart starts the count afresh. */
    private boolean takeRestartRequest() {
        synchronized (lock) {
            if (!restartRequested) {
                return false;
            }
            restartRequested = false;
            policy.reset();
            return true;
        }
    }

    private void setPhase(MiningLogic.Phase p) {
        synchronized (lock) {
            phase = p;
        }
    }

    /** Sleep, but wake at once for a stop or a restart request. */
    private void pause(long ms) {
        long deadline = env.elapsedMs() + ms;
        synchronized (lock) {
            while (!stopRequested && !restartRequested) {
                long left = deadline - env.elapsedMs();
                if (left <= 0) {
                    return;
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    // Nothing interrupts this thread today; if that
                    // changes, an interrupt is a teardown.
                    stopRequested = true;
                }
            }
        }
    }

    private void awaitStopOrRestart() {
        synchronized (lock) {
            while (!stopRequested && !restartRequested) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    stopRequested = true;
                }
            }
        }
    }

    /**
     * Read the token file into the scrubber, which is all the app
     * ever does with the token: the value goes nowhere else.
     */
    private void learnToken() {
        String token = MiningLogic.readSmallTextFile(cfg.tokenFile,
                TOKEN_READ_MAX_BYTES);
        if (token != null) {
            scrubber = scrubber.withToken(token);
        }
    }

    private void ensureMarker() {
        File marker = cfg.markerFile;
        if (marker == null || marker.isFile()) {
            return;
        }
        try {
            File dir = marker.getParentFile();
            if (dir != null) {
                dir.mkdirs();
            }
            try (FileOutputStream out = new FileOutputStream(marker)) {
                out.write("a miner process has been started by this app\n"
                        .getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            safeLog("miner: marker could not be written; a miner left by a"
                    + " killed app process is then only found while mining"
                    + " is on");
        }
    }

    /** waitFor() that always comes back with the real exit code. */
    private int waitForExit(Process p) {
        for (;;) {
            try {
                return p.waitFor();
            } catch (InterruptedException e) {
                // As in the node's worker: end the process and keep
                // waiting for its real exit, so it is always reaped.
                requestStop("supervisor interrupted");
                destroyQuietly(p);
            }
        }
    }

    private void joinDrain(Thread drain, Process p) {
        try {
            drain.join(DRAIN_JOIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (drain.isAlive()) {
            // Something else still holds the pipe's write end; close
            // our end rather than wait on it.
            try {
                p.getInputStream().close();
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------

    private void drainOutput(Process p) {
        InputStream in = p.getInputStream();
        try {
            drainLines(in, maxLineBytes, (line, truncated) -> {
                // The per-line work must never end this thread: with
                // nobody reading, the pipe fills and the miner blocks
                // on its own logging.
                try {
                    onMinerLine(line, truncated);
                } catch (Exception e) {
                    safeLog("miner output: line dropped after error: "
                            + e.getClass().getName());
                }
            });
        } catch (IOException ignored) {
            // The stream closed because the process ended.
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void onMinerLine(String rawLine, boolean truncated) {
        // Scrub first: nothing below ever sees the raw line.
        String line = scrubber.apply(rawLine, truncated);
        long now = env.elapsedMs();
        logFile.write(line, truncated);
        String shown = line;
        if (shown.length() > TAIL_LINE_CHARS) {
            int keep = TAIL_LINE_CHARS;
            if (Character.isHighSurrogate(shown.charAt(keep - 1))) {
                keep--;
            }
            shown = shown.substring(0, keep) + " …";
        }
        synchronized (lock) {
            if (tail.size() >= TAIL_LINES) {
                tail.pollFirst();
            }
            tail.addLast(shown);
        }
        stats.onMinerLine(line, now);
        if (!exporterWarned
                && MiningLogic.classifyMinerLine(line)
                == MiningLogic.MinerEvent.METRICS_ANNOUNCED
                && stats.snapshot(now).metricsExporterActive) {
            exporterWarned = true;
            safeLog("WARN miner: the bundled miner announced a live metrics"
                    + " endpoint. The distribution build is meant to ship"
                    + " with the exporter disabled; this binary serves"
                    + " miner metrics on every network interface");
        }
    }

    /**
     * Read {@code in} as UTF-8 lines, bounding the memory one line
     * can take: the same reader as {@code NodeService.drainLines}
     * (see there for the history), restated here so the miner's
     * output gets the identical treatment without this class
     * depending on the service. A line longer than
     * {@code maxLineBytes} is delivered cut, flagged as truncated,
     * and its remainder is read and discarded up to the newline.
     */
    static void drainLines(InputStream in, int maxLineBytes,
                           LineSink sink) throws IOException {
        byte[] chunk = new byte[8192];
        ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
        boolean discarding = false;
        int n;
        while ((n = in.read(chunk)) != -1) {
            int segmentStart = 0;
            for (int i = 0; i < n; i++) {
                if (chunk[i] == (byte) '\n') {
                    boolean truncated = discarding;
                    if (!discarding) {
                        int room = maxLineBytes - lineBuf.size();
                        int avail = i - segmentStart;
                        int take = Math.min(avail, Math.max(room, 0));
                        if (take > 0) {
                            lineBuf.write(chunk, segmentStart, take);
                        }
                        if (take < avail) {
                            truncated = true;
                        }
                    }
                    emitLine(lineBuf, truncated, sink);
                    lineBuf.reset();
                    discarding = false;
                    segmentStart = i + 1;
                }
            }
            if (segmentStart < n && !discarding) {
                int room = maxLineBytes - lineBuf.size();
                int take = Math.min(n - segmentStart, Math.max(room, 0));
                if (take > 0) {
                    lineBuf.write(chunk, segmentStart, take);
                }
                if (take < n - segmentStart) {
                    discarding = true;
                }
            }
        }
        if (lineBuf.size() > 0 || discarding) {
            emitLine(lineBuf, discarding, sink);
        }
    }

    private static void emitLine(ByteArrayOutputStream buf, boolean truncated,
                                 LineSink sink) {
        byte[] bytes = buf.toByteArray();
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == (byte) '\r') {
            len--;
        }
        sink.onLine(new String(bytes, 0, len, StandardCharsets.UTF_8),
                truncated);
    }

    // ------------------------------------------------------------------
    // Stopping and leftovers
    // ------------------------------------------------------------------

    private static void destroyQuietly(Process p) {
        try {
            p.destroy();
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * The stop's second half, off the caller's thread: if the process
     * has not exited within the grace period of destroy(), end it
     * with destroyForcibly(). The supervisor thread's own waitFor()
     * observes the exit either way.
     */
    private void escalateAsync(final Process p) {
        Thread t = new Thread(() -> {
            if (waitForExitBounded(p, cfg.stopWaitMs)) {
                return;
            }
            safeLog("miner process did not exit within " + cfg.stopWaitMs
                    + "ms of destroy(); escalating to destroyForcibly()");
            try {
                p.destroyForcibly();
            } catch (RuntimeException e) {
                safeLog("miner destroyForcibly() failed: "
                        + e.getClass().getName());
                return;
            }
            if (!waitForExitBounded(p, cfg.stopWaitMs)) {
                safeLog("WARN miner process still running after"
                        + " destroyForcibly(); the next start removes it");
            }
        }, "miner-stop-escalation");
        t.setDaemon(true);
        try {
            t.start();
        } catch (Throwable e) {
            // A stop must never throw into the node's own teardown
            // paths (fix2 audit L-5): without the escalation thread
            // the supervisor's waitFor() still observes the exit,
            // and the next start removes a leftover.
            safeLog("miner stop escalation could not start: "
                    + e.getClass().getName());
        }
    }

    /** Poll exitValue() — see NodeService.waitForExitBounded for why. */
    private boolean waitForExitBounded(Process p, long timeoutMs) {
        return waitForExitBounded(p, timeoutMs, env);
    }

    private static boolean waitForExitBounded(Process p, long timeoutMs,
                                              Env env) {
        long deadline = env.elapsedMs() + timeoutMs;
        for (;;) {
            try {
                p.exitValue();
                return true;
            } catch (IllegalThreadStateException stillAlive) {
                if (env.elapsedMs() >= deadline) {
                    return false;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    private void removeLeftovers() {
        List<Process> left = new ArrayList<>();
        for (Process p : LIVE_MINERS) {
            if (p.isAlive()) {
                left.add(p);
            } else {
                LIVE_MINERS.remove(p);
            }
        }
        for (Process p : left) {
            safeLog("miner: a miner process of an earlier session is still"
                    + " alive; terminating it before starting a new one");
            try {
                p.destroyForcibly();
            } catch (RuntimeException ignored) {
            }
        }
        for (Process p : left) {
            if (waitForExitBounded(p, cfg.stopWaitMs)) {
                LIVE_MINERS.remove(p);
            } else {
                safeLog("WARN miner: an earlier miner process is still"
                        + " running after destroyForcibly()");
            }
        }
        try {
            reapStrayMiners(cfg, env);
        } catch (RuntimeException e) {
            safeLog("miner: stray-process check failed: "
                    + e.getClass().getName());
        }
    }

    /**
     * End miner processes this app owns but no longer holds a handle
     * for — children of an app process that was killed while mining.
     * A process qualifies only by the strict identity test of
     * {@link #findOwnMinerPids}; anything else is left alone.
     *
     * @return how many processes were signalled
     */
    public static int reapStrayMiners(Config cfg, Env env) {
        if (cfg.minerBinary == null) {
            return 0;
        }
        String bin = cfg.minerBinary.getAbsolutePath();
        long self = env.selfPid();
        List<Long> strays = findOwnMinerPids(cfg.procRoot, bin, self);
        if (strays.isEmpty()) {
            return 0;
        }
        for (long pid : strays) {
            env.log("miner: found a miner process left by an earlier app"
                    + " process (pid " + pid + "); terminating it");
            env.killStrayProcess(pid);
        }
        long deadline = env.elapsedMs() + cfg.stopWaitMs;
        for (;;) {
            List<Long> still = findOwnMinerPids(cfg.procRoot, bin, self);
            still.retainAll(strays);
            if (still.isEmpty()) {
                break;
            }
            if (env.elapsedMs() >= deadline) {
                env.log("WARN miner: stray miner process(es) still present"
                        + " after kill: " + still);
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return strays.size();
    }

    /**
     * The pids under {@code procRoot} whose command line shows a
     * process started from THIS app's miner executable: argv[0] is
     * that executable's absolute path, or argv[0] is the system
     * linker and argv[1] is that path (the fallback launch form).
     * Full paths are compared, never substrings, because the result
     * authorizes a kill — the same rule as
     * {@code NodeService.isOwnNodeProcessStrict}. An entry that
     * cannot be read is not ours.
     */
    static List<Long> findOwnMinerPids(File procRoot, String minerBinPath,
                                       long selfPid) {
        List<Long> out = new ArrayList<>();
        if (procRoot == null || minerBinPath == null) {
            return out;
        }
        String[] names;
        try {
            names = procRoot.list();
        } catch (RuntimeException e) {
            names = null;
        }
        if (names == null) {
            return out;
        }
        for (String name : names) {
            if (!isDigits(name)) {
                continue;
            }
            long pid;
            try {
                pid = Long.parseLong(name);
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid <= 0 || pid == selfPid) {
                continue;
            }
            String[] argv = readArgv(new File(new File(procRoot, name),
                    "cmdline"));
            if (argv == null || argv.length < 1) {
                continue;
            }
            if (minerBinPath.equals(argv[0])
                    || (argv.length > 1 && LINKER64.equals(argv[0])
                    && minerBinPath.equals(argv[1]))) {
                out.add(pid);
            }
        }
        Collections.sort(out);
        return out;
    }

    private static boolean isDigits(String s) {
        if (s == null || s.isEmpty() || s.length() > 18) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static String[] readArgv(File cmdline) {
        try (FileInputStream in = new FileInputStream(cmdline);
             ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[512];
            int n;
            while (buf.size() < CMDLINE_READ_MAX_BYTES
                    && (n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8)
                    .split("\0", 3);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Logging
    // ------------------------------------------------------------------

    /** The app log must never end the supervisor. */
    private void safeLog(String message) {
        try {
            env.log(message);
        } catch (Throwable ignored) {
            // Logging must never break control flow: the stop
            // paths call this after ending the process, and a
            // caller must not fail for a log line (v4.2.2 audit,
            // L1 — an Error escaping here used to leave the miner
            // running while later stops returned early).
        }
    }

    /**
     * A start failure for the log: the exception's class and message
     * (for an IOException, the system's "Cannot run program ...:
     * error=13, Permission denied"), scrubbed like any other text
     * that reaches a log.
     */
    private String describe(Throwable t) {
        String message = t.getMessage();
        String text = t.getClass().getSimpleName()
                + (message == null ? "" : ": " + message);
        return scrubber.apply(text);
    }
}
