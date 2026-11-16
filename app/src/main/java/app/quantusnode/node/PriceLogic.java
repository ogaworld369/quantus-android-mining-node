package app.quantusnode.node;

/**
 * The price fetch's pure decisions (no Android, no I/O), split
 * out of {@link PriceStore} so they are exercisable on a JVM:
 * the failure backoff and the staleness judgement.
 *
 * Backoff shape: the first failure waits 15 minutes, the
 * second 30, every later failure 60 — the ordinary fetch
 * cadence, so a failing endpoint is retried no more often
 * than a healthy one is refreshed (v4.2.2, gap inventory
 * B-4). History: the original tiers were 5/15/60; they were
 * flattened to 5/15 on 2026-11-10 (internal.23) because a
 * failure was recorded nowhere and an hour of silence was
 * undiagnosable. The fetch error has been persisted since
 * then (and the settings row says when the last attempt
 * failed), so the silence is no longer blind and the gentler
 * cadence stands.
 */
public final class PriceLogic {

    private PriceLogic() {
    }

    /** Delay before the next attempt after {@code failures}
     *  consecutive failures (1-based: the delay scheduled BY
     *  the failure that just happened). */
    public static long backoffMs(int failures) {
        if (failures <= 1) {
            return 15L * 60 * 1000;
        }
        if (failures == 2) {
            return 30L * 60 * 1000;
        }
        return 60L * 60 * 1000;
    }

    /**
     * Whether a cached value is presented as stale: the most
     * recent attempt failed, or the value's age exceeds
     * {@code staleMs}. A value that was never fetched (fetch
     * time 0) is not stale — it is absent, which the UI renders
     * as a dash, a different fact.
     *
     * The age is measured on the monotonic clock whenever both
     * elapsed readings are usable AND both were taken in the
     * same boot (the boot counts match): the wall clock can be
     * set backwards or forwards by the user or a time sync, and
     * a jump must not make a fresh value look ancient or an
     * ancient one fresh (v4.2.2, gap inventory B-4). A reboot
     * resets the monotonic clock, so readings from different
     * boots are never subtracted: when the counts differ — or
     * either is unknown (-1, e.g. a save written before the
     * boot count was persisted) — the wall age decides, as it
     * always did (v4.2.2 audit, L2).
     *
     * A stored fetch time ahead of the wall clock is stale
     * outright: a value that claims to come from the future
     * cannot be vouched for. That rule is deliberate and costs
     * something: a backward wall step can dim a fresh value
     * until the wall clock passes the stored fetch time or the
     * next fetch succeeds (v4.2.2 audit, I2). A jump must not
     * make a fresh value look ancient — except by this rule,
     * which is the price of not letting a jump freshen an old
     * one.
     */
    public static boolean isStale(long fetchedWallMs,
                                  long fetchedElapsedMs,
                                  int fetchedBootCount,
                                  long nowWallMs, long nowElapsedMs,
                                  int nowBootCount,
                                  long staleMs,
                                  boolean lastAttemptFailed) {
        if (fetchedWallMs <= 0) {
            return false;
        }
        if (lastAttemptFailed) {
            return true;
        }
        if (fetchedWallMs > nowWallMs) {
            return true;
        }
        long ageMs;
        if (fetchedElapsedMs > 0 && nowElapsedMs >= fetchedElapsedMs
                && fetchedBootCount >= 0
                && fetchedBootCount == nowBootCount) {
            ageMs = nowElapsedMs - fetchedElapsedMs;
        } else {
            ageMs = nowWallMs - fetchedWallMs;
        }
        return ageMs > staleMs;
    }
}
