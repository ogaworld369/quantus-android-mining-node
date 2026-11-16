package app.quantusnode.node;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The chain-database reset as the screen sees it — is one running,
 * and how did the last one end — held for the app process rather
 * than for one activity (v4.1.0; pre-release audit, L-3), the way
 * {@link NodeState} holds the node's status. An activity that is
 * recreated while a deletion runs (a rotation, a theme or language
 * change) finds the reset still busy and, afterwards, its result;
 * the per-activity fields this replaces showed it an idle button
 * and delivered the result to an instance nobody was looking at.
 *
 * It also owns the one thread the storage section works on — the
 * measuring walk and the reset, one at a time — so that activity
 * instances do not each leave a thread behind. The thread ends by
 * itself when it has had nothing to do for a while.
 *
 * Pure Java, no Android types: driven on a JVM by the harness
 * (app/tests). The app uses {@link #PROCESS}.
 */
final class StorageResetState {

    /** The app process's one instance. */
    static final StorageResetState PROCESS = new StorageResetState();

    /** How a reset run ended. Immutable. */
    static final class Finished {
        /** Which run of this process: 1, 2, ... */
        final long seq;
        /** The outcome; null when the run was refused because a
         *  node is, or may be, running — or when it {@link #threw}. */
        final StorageLogic.ResetOutcome outcome;
        /** The run ended in an exception (or could not be queued). */
        final boolean threw;

        Finished(long seq, StorageLogic.ResetOutcome outcome, boolean threw) {
            this.seq = seq;
            this.outcome = outcome;
            this.threw = threw;
        }
    }

    private final ThreadPoolExecutor executor;
    private final Object lock = new Object();
    private boolean busy;
    private long runs;
    private Finished last;

    StorageResetState() {
        executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(), r -> {
                    Thread t = new Thread(r, "storage-work");
                    t.setDaemon(true);
                    return t;
                });
        executor.allowCoreThreadTimeOut(true);
    }

    /** The storage section's worker: one task at a time. */
    Executor executor() {
        return executor;
    }

    /** A reset is queued or running. */
    boolean busy() {
        synchronized (lock) {
            return busy;
        }
    }

    /** The last finished run of this process; null before the first
     *  and while one is running. */
    Finished last() {
        synchronized (lock) {
            return last;
        }
    }

    /**
     * Run a reset on the worker unless one is running already.
     *
     * @param reset    the deletion; its result is kept as the
     *                 {@link #last} outcome
     * @param whenDone run on the worker once the result is
     *                 published; may be null
     * @return false when a reset was already running (nothing was
     *         started)
     */
    boolean start(final Callable<StorageLogic.ResetOutcome> reset,
                  final Runnable whenDone) {
        final long seq;
        synchronized (lock) {
            if (busy) {
                return false;
            }
            busy = true;
            last = null;
            seq = ++runs;
        }
        try {
            executor.execute(() -> {
                StorageLogic.ResetOutcome outcome = null;
                boolean threw = false;
                try {
                    outcome = reset.call();
                } catch (Throwable t) {
                    threw = true;
                }
                finish(new Finished(seq, outcome, threw), whenDone);
            });
        } catch (RuntimeException e) {
            finish(new Finished(seq, null, true), whenDone);
        }
        return true;
    }

    private void finish(Finished result, Runnable whenDone) {
        synchronized (lock) {
            busy = false;
            last = result;
        }
        if (whenDone != null) {
            try {
                whenDone.run();
            } catch (Throwable ignored) {
                // The result is published; a screen that could not
                // be told reads it at its next refresh.
            }
        }
    }

    /**
     * The string key of the phrase that names a failure kind inside
     * {@link StringsTable#STORAGE_RESYNC_FAILED} — a localised
     * phrase, where an English fragment used to be set in
     * (pre-release audit, I-4). A missing kind is an internal error.
     */
    static String failureKey(StorageLogic.FailureKind kind) {
        if (kind == StorageLogic.FailureKind.LOCATION) {
            return StringsTable.STORAGE_FAIL_LOCATION;
        }
        if (kind == StorageLogic.FailureKind.ENTRIES) {
            return StringsTable.STORAGE_FAIL_ENTRIES;
        }
        return StringsTable.STORAGE_FAIL_INTERNAL;
    }
}
