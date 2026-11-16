import app.quantusnode.node.DiagShare;
import app.quantusnode.node.PriceLogic;
import app.quantusnode.node.UiLogic;
import app.quantusnode.node.depin.DepinGateVerdict;
import app.quantusnode.node.depin.DepinJournalAggregates;
import app.quantusnode.node.depin.DepinLaunchDerivation;
import app.quantusnode.node.depin.DepinReconcilePolicy;
import app.quantusnode.node.depin.DepinStatus;
import app.quantusnode.node.depin.DepinStatusMerge;
import app.quantusnode.node.depin.DepinUserIntent;
import app.quantusnode.node.depin.DepinTickBreaker;
import app.quantusnode.node.depin.RpcAttemptLog;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * JVM checks for the vision UI stage 1+2 pure logic:
 * DepinJournalAggregates (streak / slot window / last-24h
 * series / today's progress) and UiLogic's network-state
 * classification + ghost-layer geometry. The Android-side
 * wiring (views, IPC) is verified by the build; these are
 * the derivations whose correctness is decidable here.
 *
 * Run via run-vision-check.sh. Prints passed/failed counts
 * and exits non-zero on any failure.
 */
public final class VisionLogicCheckMain {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<>();

    private static void check(String name, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            failures.add(name);
            System.out.println("FAIL " + name);
        }
    }

    private static void checkEq(String name, long want, long got) {
        check(name + " (want=" + want + " got=" + got + ")", want == got);
    }

    private static void checkClose(String name, double want, double got) {
        check(name + " (want=" + want + " got=" + got + ")",
                Math.abs(want - got) < 1e-6);
    }

    public static void main(String[] args) {
        aggregates();
        todayCounting();
        classify();
        ghostMath();
        priceLogic();
        reconcilePolicy();
        backoffSchedule();
        launchDerivation();
        statusMerge();
        presentedState();
        diagPriceLines();
        gateVerdict();
        userIntent();
        try {
            attemptLogRules();
            attemptLogH04();
            tickBreakerRules();
        } catch (Exception e) {
            failed++;
            System.out.println("FAIL attemptLogRules threw " + e);
        }
        System.out.println("----");
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------- aggregates ----------------

    private static final ZoneId UTC = ZoneId.of("UTC");
    /** 2026-11-11T12:00:00Z — a Wednesday noon, exactly on a
     *  slot boundary (the interesting case for the window). */
    private static final long NOW = 1794398400L;

    private static void aggregates() {
        // Empty journal: window and today still derive from
        // now; streak 0; no recent-day points.
        DepinJournalAggregates.Result r = DepinJournalAggregates
                .compute(new long[0], new long[0], NOW, UTC);
        checkEq("agg empty: streak 0", 0, r.longestStreakDays);
        checkEq("agg empty: slot end = now (now on a boundary:"
                        + " the just-closed slot is newest)",
                NOW, r.recentSlotEndUnix);
        check("agg empty: 48 slots all -1",
                r.recentSlotPeers.length == 48
                        && allMinusOne(r.recentSlotPeers));
        checkEq("agg empty: recent day empty", 0, r.recentDayTs.length);
        checkEq("agg empty: today count 0", 0, r.todayCheckpoints);
        // Noon UTC: slots begun since midnight = 12h / 600s.
        checkEq("agg empty: today slots elapsed 72",
                72, r.todaySlotsElapsed);

        // One checkpoint now-ish: lands in the newest slot.
        r = DepinJournalAggregates.compute(
                new long[]{NOW - 60}, new long[]{7}, NOW, UTC);
        checkEq("agg one: newest slot holds its peers",
                7, r.recentSlotPeers[47]);
        checkEq("agg one: streak 1", 1, r.longestStreakDays);
        checkEq("agg one: today count 1", 1, r.todayCheckpoints);
        checkEq("agg one: recent day size 1", 1, r.recentDayTs.length);
        checkEq("agg one: recent day peers", 7, r.recentDayPeers[0]);

        // Same slot twice: the LATER record wins.
        r = DepinJournalAggregates.compute(
                new long[]{NOW - 300, NOW - 60},
                new long[]{3, 9}, NOW, UTC);
        checkEq("agg same slot: later wins", 9, r.recentSlotPeers[47]);

        // A record just before the window is invisible to the
        // ring but still inside the 24 h series (window is 8h).
        long old = NOW - 9 * 3600L;
        r = DepinJournalAggregates.compute(
                new long[]{old}, new long[]{5}, NOW, UTC);
        check("agg old: ring all -1", allMinusOne(r.recentSlotPeers));
        checkEq("agg old: recent day keeps it", 1, r.recentDayTs.length);

        // A record older than 24h is in neither series, but
        // still counts for the streak.
        long older = NOW - 25 * 3600L;
        r = DepinJournalAggregates.compute(
                new long[]{older}, new long[]{5}, NOW, UTC);
        checkEq("agg older: recent day empty", 0, r.recentDayTs.length);
        checkEq("agg older: streak 1", 1, r.longestStreakDays);

        // Streak: three consecutive days, a gap, two more.
        long d = 86_400L;
        long base = NOW - 10 * d;
        long[] ts = {base, base + d, base + 2 * d,
                base + 4 * d, base + 5 * d, base + 5 * d + 3600};
        long[] ps = {1, 1, 1, 1, 1, 1};
        r = DepinJournalAggregates.compute(ts, ps, NOW, UTC);
        checkEq("agg streak: longest run 3", 3, r.longestStreakDays);

        // Residual F-23: a future-dated record (a clock
        // moved forward, then back) must not enter the
        // streak's day set.
        long[] fut = {NOW + d, NOW + 2 * d, NOW + 3 * d};
        long[] futP = {1, 1, 1};
        r = DepinJournalAggregates.compute(fut, futP, NOW, UTC);
        checkEq("agg streak: future days do not count",
                0, r.longestStreakDays);
        long[] mix = {NOW - 3600, NOW + d};
        long[] mixP = {1, 1};
        r = DepinJournalAggregates.compute(mix, mixP, NOW, UTC);
        checkEq("agg streak: a future day does not extend today",
                1, r.longestStreakDays);

        // Unsorted input derives the same as sorted input.
        long[] rev = {base + 5 * d + 3600, base + 4 * d, base + 2 * d,
                base, base + 5 * d, base + d};
        long[] revP = {1, 1, 1, 1, 1, 1};
        r = DepinJournalAggregates.compute(rev, revP, NOW, UTC);
        checkEq("agg streak: unsorted input, same run",
                3, r.longestStreakDays);

        // Recent-day cap: 200 records inside 24h keeps the
        // LATEST 144.
        int n = 200;
        long[] many = new long[n];
        long[] manyP = new long[n];
        for (int i = 0; i < n; i++) {
            many[i] = NOW - (n - i) * 400L;
            manyP[i] = i;
        }
        r = DepinJournalAggregates.compute(many, manyP, NOW, UTC);
        checkEq("agg cap: recent day capped at 144",
                144, r.recentDayTs.length);
        checkEq("agg cap: oldest kept is the 57th record",
                many[56], r.recentDayTs[0]);
        checkEq("agg cap: newest kept is the last record",
                many[n - 1], r.recentDayTs[143]);

        // Today boundary: a checkpoint 1 s before local
        // midnight does not count for today; one at midnight
        // does. (NOW is noon UTC; midnight = NOW - 12h.)
        long midnight = NOW - 12 * 3600L;
        r = DepinJournalAggregates.compute(
                new long[]{midnight - 1, midnight},
                new long[]{2, 2}, NOW, UTC);
        checkEq("agg today: only the post-midnight record",
                1, r.todayCheckpoints);

        // A recorded ZERO peer count is a value, not a gap:
        // the slot reads 0, never -1.
        r = DepinJournalAggregates.compute(
                new long[]{NOW - 60}, new long[]{0}, NOW, UTC);
        checkEq("agg zero peers: slot holds 0", 0, r.recentSlotPeers[47]);
    }

    // ---------------- today's row counting ----------------
    // internal.28: the home card's today row counts BOTH
    // sides over the same window (local midnight through
    // the end of the slot containing now) and in the same
    // unit (slots), so the numerator can never exceed the
    // denominator. Pre-fix the denominator counted only
    // COMPLETED slots while the numerator counted records
    // (including one inside the still-open slot, and two in
    // one slot as 2), rendering "16 / 15" and "1 / 0" on
    // the device (F-23 items 2 and 4, for this display).

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    /** 2026-11-11T00:00 JST = 2026-11-10T15:00:00Z. */
    private static final long JST_MIDNIGHT = 1794322800L;

    private static void todayCounting() {
        // The device case (internal.27, 2026-11-11 02:39
        // JST): a checkpoint ~30 s into every slot since
        // midnight, including the still-open 02:30 slot.
        long[] ts = new long[16];
        long[] ps = new long[16];
        for (int i = 0; i < 16; i++) {
            ts[i] = JST_MIDNIGHT + i * 600L + 30;
            ps[i] = 5;
        }
        DepinJournalAggregates.Result r = DepinJournalAggregates
                .compute(ts, ps, JST_MIDNIGHT + 2 * 3600L + 39 * 60L,
                        JST);
        checkEq("today 02:39 JST: count 16", 16, r.todayCheckpoints);
        checkEq("today 02:39 JST: slots 16 (current slot included)",
                16, r.todaySlotsElapsed);

        // Just after midnight: a checkpoint 30 s into the
        // first slot. Pre-fix this rendered "1 / 0".
        r = DepinJournalAggregates.compute(
                new long[]{JST_MIDNIGHT + 30}, new long[]{7},
                JST_MIDNIGHT + 300, JST);
        checkEq("today 00:05 JST: count 1", 1, r.todayCheckpoints);
        checkEq("today 00:05 JST: slots 1", 1, r.todaySlotsElapsed);

        // Thirty seconds after midnight, no records yet: the
        // first slot has begun, so the row is "0 / 1",
        // not "0 / 0".
        r = DepinJournalAggregates.compute(
                new long[0], new long[0], JST_MIDNIGHT + 30, JST);
        checkEq("today 00:00:30 JST: slots 1", 1, r.todaySlotsElapsed);

        // Mid-slot with only the completed slots covered:
        // the denominator already includes the open slot.
        r = DepinJournalAggregates.compute(
                new long[]{JST_MIDNIGHT + 30, JST_MIDNIGHT + 630},
                new long[]{5, 5}, JST_MIDNIGHT + 1500, JST);
        checkEq("today 00:25 JST: count 2", 2, r.todayCheckpoints);
        checkEq("today 00:25 JST: slots 3", 3, r.todaySlotsElapsed);

        // Two records inside ONE slot count once: the
        // numerator counts covered slots, not records.
        r = DepinJournalAggregates.compute(
                new long[]{JST_MIDNIGHT + 600, JST_MIDNIGHT + 660},
                new long[]{5, 5}, JST_MIDNIGHT + 1500, JST);
        checkEq("today dup slot: count 1", 1, r.todayCheckpoints);
        checkEq("today dup slot: slots 3", 3, r.todaySlotsElapsed);

        // Records exactly ON slot boundaries belong to the
        // slot they open; one at every boundary 00:00..01:00
        // with now 30 s into the 01:00 slot gives 7 / 7.
        long[] bts = new long[7];
        long[] bps = new long[7];
        for (int i = 0; i < 7; i++) {
            bts[i] = JST_MIDNIGHT + i * 600L;
            bps[i] = 5;
        }
        r = DepinJournalAggregates.compute(
                bts, bps, JST_MIDNIGHT + 3630, JST);
        checkEq("today boundary records: count 7",
                7, r.todayCheckpoints);
        checkEq("today boundary records: slots 7",
                7, r.todaySlotsElapsed);

        // A record stamped exactly at the end of the current
        // slot (== the today window end; a future stamp at
        // render time) is outside the window, not counted.
        r = DepinJournalAggregates.compute(
                new long[]{JST_MIDNIGHT + 30, JST_MIDNIGHT + 1800},
                new long[]{5, 5}, JST_MIDNIGHT + 1500, JST);
        checkEq("today future boundary: count 1",
                1, r.todayCheckpoints);
        checkEq("today future boundary: slots 3",
                3, r.todaySlotsElapsed);

        // Invariant sweep: across a full day, stepped
        // 7 minutes at a time, with a checkpoint in every
        // slot, the numerator never exceeds the denominator.
        long[] dense = new long[144];
        long[] denseP = new long[144];
        for (int i = 0; i < 144; i++) {
            dense[i] = JST_MIDNIGHT + i * 600L + 30;
            denseP[i] = 5;
        }
        boolean invariant = true;
        for (long now = JST_MIDNIGHT; now < JST_MIDNIGHT + 86_400L;
                now += 420) {
            DepinJournalAggregates.Result s = DepinJournalAggregates
                    .compute(dense, denseP, now, JST);
            if (s.todayCheckpoints > s.todaySlotsElapsed) {
                invariant = false;
            }
        }
        check("today sweep: count never exceeds slots", invariant);
    }

    private static boolean allMinusOne(int[] a) {
        for (int v : a) {
            if (v != -1) {
                return false;
            }
        }
        return true;
    }

    // ---------------- state classification ----------------

    private static void classify() {
        // Priority: offline beats everything.
        check("cls offline wins", UiLogic.classifyNetState(
                false, true, false, false, 5, 0, 0)
                == UiLogic.NetState.OFFLINE);
        // Stopped (not running, or failed) next.
        check("cls stopped when not running", UiLogic.classifyNetState(
                true, false, false, false, 5, 0, 0)
                == UiLogic.NetState.STOPPED);
        check("cls stopped when failed", UiLogic.classifyNetState(
                true, true, true, false, 5, 0, 0)
                == UiLogic.NetState.STOPPED);
        // Fetch failed: age over the threshold while running.
        check("cls rpc fail at 16 s", UiLogic.classifyNetState(
                true, true, false, false, 5, 16, 0)
                == UiLogic.NetState.RPC_FAIL);
        check("cls not rpc fail at 15 s", UiLogic.classifyNetState(
                true, true, false, false, 5, 15, 0)
                == UiLogic.NetState.NORMAL);
        // Never observed (-1) is not a failure.
        check("cls never observed is not rpc fail",
                UiLogic.classifyNetState(
                        true, true, false, false, 5, -1, -1)
                        == UiLogic.NetState.NORMAL);
        // Syncing beats searching/stalled.
        check("cls syncing", UiLogic.classifyNetState(
                true, true, false, true, 0, 0, 999)
                == UiLogic.NetState.SYNCING);
        // Searching: zero or unknown peers while running.
        check("cls searching at 0 peers", UiLogic.classifyNetState(
                true, true, false, false, 0, 0, 0)
                == UiLogic.NetState.SEARCHING);
        check("cls searching at unknown peers",
                UiLogic.classifyNetState(
                        true, true, false, false, -1, 0, 0)
                        == UiLogic.NetState.SEARCHING);
        // Residual F-08: aggregates present as current only
        // for a live state with a fresh snapshot.
        check("agg gate: live and fresh is current",
                UiLogic.aggregatesCurrent(true, 1_000_000L,
                        1_000_000L
                                + UiLogic.AGGREGATES_STALE_AFTER_MS));
        check("agg gate: one ms past the bound is stale",
                !UiLogic.aggregatesCurrent(true, 1_000_000L,
                        1_000_000L
                                + UiLogic.AGGREGATES_STALE_AFTER_MS
                                + 1));
        check("agg gate: a non-live state is never current",
                !UiLogic.aggregatesCurrent(false, 1_000_000L,
                        1_000_000L));
        check("agg gate: an unbuilt snapshot is never current",
                !UiLogic.aggregatesCurrent(true, 0L, 1_000_000L));
        // Stalled: synced, peers present, best stale > 180 s.
        check("cls stalled at 181 s", UiLogic.classifyNetState(
                true, true, false, false, 5, 0, 181)
                == UiLogic.NetState.STALLED);
        check("cls not stalled at 180 s", UiLogic.classifyNetState(
                true, true, false, false, 5, 0, 180)
                == UiLogic.NetState.NORMAL);
        check("cls normal", UiLogic.classifyNetState(
                true, true, false, false, 5, 0, 10)
                == UiLogic.NetState.NORMAL);
    }

    // ---------------- ghost geometry ----------------

    private static void ghostMath() {
        checkClose("ghost angle: 00:00 is up",
                -Math.PI / 2, UiLogic.ghostAngleRad(0));
        checkClose("ghost angle: 06:00 is right",
                0.0, UiLogic.ghostAngleRad(6 * 3600));
        checkClose("ghost angle: 12:00 is down",
                Math.PI / 2, UiLogic.ghostAngleRad(12 * 3600));
        checkClose("ghost angle: 18:00 is left",
                Math.PI, UiLogic.ghostAngleRad(18 * 3600));
        checkClose("ghost radius: fresh is 0.90",
                0.90, UiLogic.ghostRadiusFactor(0));
        checkClose("ghost radius: 12 h is 1.025",
                1.025, UiLogic.ghostRadiusFactor(12 * 3600));
        checkClose("ghost radius: 24 h is 1.15",
                1.15, UiLogic.ghostRadiusFactor(86_400));
        checkClose("ghost radius: beyond a day clamps",
                1.15, UiLogic.ghostRadiusFactor(200_000));
        checkEq("ghost alpha: zero peers is the floor",
                30, UiLogic.ghostAlpha(0, 20));
        checkEq("ghost alpha: the max is 130",
                130, UiLogic.ghostAlpha(20, 20));
        checkEq("ghost alpha: half is 80",
                80, UiLogic.ghostAlpha(10, 20));
        checkEq("ghost alpha: no max is the floor",
                30, UiLogic.ghostAlpha(5, 0));
        checkClose("ghost size: zero peers is 2.0 dp",
                2.0, UiLogic.ghostRadiusDp(0, 20));
        checkClose("ghost size: the max is 3.5 dp",
                3.5, UiLogic.ghostRadiusDp(20, 20));
    }

    // ---------------- price logic (internal.23) ----------------

    private static void priceLogic() {
        // Backoff (v4.2.2, gap inventory B-4): 15 min for the
        // first failure, 30 for the second, 60 — the ordinary
        // fetch cadence — after that. A failing endpoint is
        // retried no more often than a healthy one is refreshed;
        // the failure itself is persisted, so the slower cadence
        // hides nothing (the 2026-11-10 flattening to 5/15 was
        // for the days when it was recorded nowhere).
        checkEq("price backoff: 1st failure is 15 min",
                900_000L, PriceLogic.backoffMs(1));
        checkEq("price backoff: 2nd failure is 30 min",
                1_800_000L, PriceLogic.backoffMs(2));
        checkEq("price backoff: 3rd failure is 60 min",
                3_600_000L, PriceLogic.backoffMs(3));
        checkEq("price backoff: 10th failure stays 60 min",
                3_600_000L, PriceLogic.backoffMs(10));
        // Staleness: age over the threshold, or a failed last
        // attempt; a never-fetched value is absent, not stale.
        // The age runs on the monotonic clock when both elapsed
        // readings exist AND both were taken in the same boot
        // (v4.2.2 audit, L2); otherwise the wall clock decides.
        long staleMs = 600_000L;
        long t0 = 1_700_000_000_000L;
        long e0 = 5_000_000L;
        int b0 = 7;
        check("price stale: never fetched is not stale",
                !PriceLogic.isStale(0, 0, b0, t0, e0, b0, staleMs, true));
        check("price stale: fresh value, no failure",
                !PriceLogic.isStale(t0, e0, b0, t0 + 300_000L,
                        e0 + 300_000L, b0, staleMs, false));
        check("price stale: age over the threshold",
                PriceLogic.isStale(t0, e0, b0, t0 + 660_000L,
                        e0 + 660_000L, b0, staleMs, false));
        check("price stale: last attempt failed",
                PriceLogic.isStale(t0, e0, b0, t0 + 60_000L,
                        e0 + 60_000L, b0, staleMs, true));
        // The wall clock jumping an hour forwards must not age
        // the value: the monotonic age (5 min) rules.
        check("price stale: a wall jump forward does not age the value",
                !PriceLogic.isStale(t0, e0, b0, t0 + 3_600_000L,
                        e0 + 300_000L, b0, staleMs, false));
        // Nor may a jump backwards freshen an old value: the
        // stored fetch then reads as ahead of the wall clock,
        // which is stale outright.
        check("price stale: a wall jump back does not freshen the value",
                PriceLogic.isStale(t0, e0, b0, t0 - 3_600_000L,
                        e0 + 660_000L, b0, staleMs, false));
        // A stored fetch time ahead of the wall clock is stale:
        // a value from the future cannot be vouched for.
        check("price stale: a fetch time in the future is stale",
                PriceLogic.isStale(t0 + 3_600_000L, 0, b0, t0, e0,
                        b0, staleMs, false));
        // After a reboot the monotonic reading restarts, so it
        // cannot be compared with the stored one: the wall age
        // decides, as it always did. The two checks disagree the
        // clocks on purpose, so the wall age is the only
        // explanation: elapsed says old but the wall says fresh,
        // and elapsed says fresh but the wall says old.
        check("price stale: after a reboot the wall age decides (fresh)",
                !PriceLogic.isStale(t0, e0, b0, t0 + 300_000L,
                        e0 + 3_600_000L, b0 + 1, staleMs, false));
        check("price stale: after a reboot the wall age decides (old)",
                PriceLogic.isStale(t0, e0, b0, t0 + 660_000L,
                        e0 + 60_000L, b0 + 1, staleMs, false));
        // An unknown boot count (a save written before it was
        // persisted, or a read that failed) falls back to the
        // wall age too, whichever side is unknown.
        check("price stale: unknown stored boot falls back to the wall age",
                PriceLogic.isStale(t0, e0, -1, t0 + 660_000L,
                        e0 + 60_000L, b0, staleMs, false));
        check("price stale: unknown current boot falls back to the wall age",
                !PriceLogic.isStale(t0, e0, b0, t0 + 300_000L,
                        e0 + 3_600_000L, -1, staleMs, false));
        // A save from before the elapsed reading existed (0)
        // falls back to the wall age too.
        check("price stale: no elapsed reading falls back to the wall age",
                PriceLogic.isStale(t0, 0, b0, t0 + 660_000L, e0,
                        b0, staleMs, false));
    }

    // ---------------- reconcile policy (internal.24) -----------
    //
    // The internal.23 policy took a boolean "suppressed" (the
    // supervisor's permanent latch). Internal.24 replaces the
    // latch with a failure-counted backoff: the policy now
    // takes the consecutive-failure count and the wall time
    // the next launch is allowed at, and launches again when
    // the backoff expires — there is no state in which an
    // enabled observer is never retried.

    private static void reconcilePolicy() {
        long t0 = 1_700_000_000_000L;
        DepinReconcilePolicy p = new DepinReconcilePolicy();
        check("reconcile: disabled stays quiet",
                p.decide(false, false, 0, 0, t0)
                        == DepinReconcilePolicy.Decision.NONE);
        check("reconcile: alive stays quiet",
                p.decide(true, true, 0, 0, t0)
                        == DepinReconcilePolicy.Decision.NONE);
        check("reconcile: enabled + dead launches",
                p.decide(true, false, 0, 0, t0)
                        == DepinReconcilePolicy.Decision.LAUNCH);
        check("reconcile: immediate retry is throttled",
                p.decide(true, false, 0, 0, t0 + 1_000L)
                        == DepinReconcilePolicy.Decision.NONE);
        check("reconcile: after the interval it launches again",
                p.decide(true, false, 0, 0,
                        t0 + DepinReconcilePolicy.INTERVAL_MS)
                        == DepinReconcilePolicy.Decision.LAUNCH);

        // Backoff: while the next launch is not yet allowed,
        // the decision is a one-per-count notice, then quiet;
        // at the boundary the launch happens — the old latch's
        // "never again" is the defect being pinned here.
        DepinReconcilePolicy q = new DepinReconcilePolicy();
        long next1 = t0 + DepinReconcilePolicy.backoffDelayMs(1);
        check("reconcile: a failure backs off, noticed once",
                q.decide(true, false, 1, next1, t0)
                        == DepinReconcilePolicy.Decision
                                .BACKOFF_NOTICE);
        check("reconcile: the same count is not re-noticed",
                q.decide(true, false, 1, next1, t0 + 30_000L)
                        == DepinReconcilePolicy.Decision.NONE);
        check("reconcile: at the backoff boundary it launches",
                q.decide(true, false, 1, next1, next1)
                        == DepinReconcilePolicy.Decision.LAUNCH);
        long next2 = next1 + DepinReconcilePolicy.backoffDelayMs(2);
        check("reconcile: a new failure count is noticed again",
                q.decide(true, false, 2, next2, next1 + 60_000L)
                        == DepinReconcilePolicy.Decision
                                .BACKOFF_NOTICE);
        check("reconcile: disabled + backed off stays quiet",
                q.decide(false, false, 2, next2, next1 + 90_000L)
                        == DepinReconcilePolicy.Decision.NONE);
        check("reconcile: alive + backed off stays quiet",
                q.decide(true, true, 2, next2, next1 + 90_000L)
                        == DepinReconcilePolicy.Decision.NONE);
        // Recovery (failures back to 0) re-arms the notice, so
        // a failure after a healthy run is surfaced again.
        check("reconcile: recovery launches and re-arms",
                q.decide(true, false, 0, 0, next2 + 1)
                        == DepinReconcilePolicy.Decision.LAUNCH);
        long next3 = next2 + 1
                + DepinReconcilePolicy.backoffDelayMs(1);
        check("reconcile: failure after recovery noticed again",
                q.decide(true, false, 1, next3, next2 + 2)
                        == DepinReconcilePolicy.Decision
                                .BACKOFF_NOTICE);
    }

    // ---------------- restart backoff schedule (internal.24) ----

    private static void backoffSchedule() {
        checkEq("backoff: 0 failures, no delay",
                0L, DepinReconcilePolicy.backoffDelayMs(0));
        checkEq("backoff: 1 failure, 1 min",
                60_000L, DepinReconcilePolicy.backoffDelayMs(1));
        checkEq("backoff: 2 failures, 2 min",
                120_000L, DepinReconcilePolicy.backoffDelayMs(2));
        checkEq("backoff: 3 failures, 4 min",
                240_000L, DepinReconcilePolicy.backoffDelayMs(3));
        checkEq("backoff: 4 failures, 8 min",
                480_000L, DepinReconcilePolicy.backoffDelayMs(4));
        checkEq("backoff: 5 failures, 15 min",
                900_000L, DepinReconcilePolicy.backoffDelayMs(5));
        checkEq("backoff: 6 failures, 30 min",
                1_800_000L, DepinReconcilePolicy.backoffDelayMs(6));
        checkEq("backoff: 7 failures, 60 min cap",
                3_600_000L, DepinReconcilePolicy.backoffDelayMs(7));
        checkEq("backoff: 20 failures stay at the cap",
                3_600_000L, DepinReconcilePolicy.backoffDelayMs(20));
        long t0 = 1_700_000_000_000L;
        checkEq("backoff: no launch yet is immediate",
                0L, DepinReconcilePolicy.nextLaunchAllowedMs(0, 5));
        checkEq("backoff: no failures is immediate",
                0L, DepinReconcilePolicy.nextLaunchAllowedMs(t0, 0));
        checkEq("backoff: next = last launch + delay",
                t0 + 60_000L,
                DepinReconcilePolicy.nextLaunchAllowedMs(t0, 1));
        checkEq("backoff: next at the cap",
                t0 + 3_600_000L,
                DepinReconcilePolicy.nextLaunchAllowedMs(t0, 9));
    }

    // ---------------- launch failure derivation (internal.26) --
    //
    // Audit finding F-02: the supervisor's derivation stopped
    // counting at an unjudged latest launch (a break), so
    // under the ~30 s reconcile drive the newest entry was
    // always inside its 120 s grace, the derived count was
    // permanently 0, and the backoff above never engaged —
    // 121 launches/hour, 2,881/day, no BACKOFF_NOTICE. The
    // fix: the unjudged latest entry is SKIPPED (exempt from
    // the count, but it does not truncate it). The simulation
    // below mirrors the audit's ReproBackoff — real policy +
    // real derivation, a launch appended per LAUNCH, the
    // service never binds — and pins the bounded counts.

    private static DepinLaunchDerivation.Launch launch(
            boolean clean, boolean seenAlive,
            long issuedWallMs, long ageMs) {
        return new DepinLaunchDerivation.Launch(
                clean, seenAlive, issuedWallMs, ageMs);
    }

    private static void launchDerivation() {
        long grace = 120_000L; // DepinSupervisor.FAIL_GRACE_MS
        long now = 1_800_000_000_000L;
        List<DepinLaunchDerivation.Launch> w =
                new ArrayList<>();

        checkEq("derive: empty window is 0", 0,
                DepinLaunchDerivation.consecutiveFailures(
                        w, now, grace));
        w.add(launch(false, false, now - 30_000L, 30_000L));
        checkEq("derive: unjudged latest alone is 0", 0,
                DepinLaunchDerivation.consecutiveFailures(
                        w, now, grace));
        w.clear();
        w.add(launch(false, false, now - 180_000L, 180_000L));
        checkEq("derive: settled latest is 1", 1,
                DepinLaunchDerivation.consecutiveFailures(
                        w, now, grace));
        w.clear();
        w.add(launch(false, false, 0L, 180_000L));
        checkEq("derive: age fallback settles the latest", 1,
                DepinLaunchDerivation.consecutiveFailures(
                        w, now, grace));
        w.clear();
        w.add(launch(false, false, 0L, 30_000L));
        checkEq("derive: age fallback keeps grace", 0,
                DepinLaunchDerivation.consecutiveFailures(
                        w, now, grace));

        // THE regression: two settled failures superseded by
        // an unjudged latest launch. Shipped semantics
        // returned 0 (the break); the count must be 2.
        w.clear();
        w.add(launch(false, false, now - 90_000L, 90_000L));
        w.add(launch(false, false, now - 60_000L, 60_000L));
        w.add(launch(false, false, now - 30_000L, 30_000L));
        checkEq("derive: unjudged latest does not truncate", 2,
                DepinLaunchDerivation.consecutiveFailures(
                        w, now, grace));

        // The run still stops at the first clean / seen-alive
        // entry, even when the unjudged latest is skipped.
        w.clear();
        w.add(launch(true, false, now - 300_000L, 300_000L));
        w.add(launch(false, false, now - 60_000L, 60_000L));
        w.add(launch(false, false, now - 30_000L, 30_000L));
        checkEq("derive: clean entry bounds the run", 1,
                DepinLaunchDerivation.consecutiveFailures(
                        w, now, grace));
        w.clear();
        w.add(launch(false, false, now - 300_000L, 300_000L));
        w.add(launch(true, false, now - 200_000L, 200_000L));
        w.add(launch(false, false, now - 30_000L, 30_000L));
        checkEq("derive: clean before the unjudged latest", 0,
                DepinLaunchDerivation.consecutiveFailures(
                        w, now, grace));
        w.clear();
        w.add(launch(false, true, now - 30_000L, 30_000L));
        checkEq("derive: seen-alive latest is not a failure", 0,
                DepinLaunchDerivation.consecutiveFailures(
                        w, now, grace));
        w.clear();
        w.add(launch(false, false, now - 300_000L, 300_000L));
        w.add(launch(false, true, now - 200_000L, 200_000L));
        w.add(launch(false, false, now - 180_000L, 180_000L));
        checkEq("derive: seen-alive bounds the run", 1,
                DepinLaunchDerivation.consecutiveFailures(
                        w, now, grace));
        w.clear();
        w.add(launch(false, false, now - 300_000L, 300_000L));
        w.add(launch(false, false, now - 240_000L, 240_000L));
        w.add(launch(false, false, now - 180_000L, 180_000L));
        checkEq("derive: three settled failures", 3,
                DepinLaunchDerivation.consecutiveFailures(
                        w, now, grace));

        // The 30 s drive, simulated exactly as the audit's
        // reproduction: derive -> next-allowed -> decide ->
        // (on LAUNCH) derive again as tryLaunch does, then
        // record the entry. Every launch fails to bind.
        checkEq("derive sim: launches in 1 h are bounded", 7,
                simulateDrive(3_600_000L));
        checkEq("derive sim: launches in 24 h are bounded", 30,
                simulateDrive(24L * 3_600_000L));
        int[] notices = new int[1];
        simulateDrive(3_600_000L, notices);
        check("derive sim: the backoff surfaces a notice",
                notices[0] > 0);
    }

    private static int simulateDrive(long durationMs) {
        return simulateDrive(durationMs, null);
    }

    private static int simulateDrive(long durationMs,
                                     int[] noticesOut) {
        List<DepinLaunchDerivation.Launch> entries =
                new ArrayList<>();
        DepinReconcilePolicy policy = new DepinReconcilePolicy();
        long grace = 120_000L;
        long t0 = 1_800_000_000_000L;
        int launches = 0;
        for (long now = t0; now <= t0 + durationMs;
                now += 30_000L) {
            int failures = DepinLaunchDerivation
                    .consecutiveFailures(entries, now, grace);
            long last = entries.isEmpty() ? 0L
                    : entries.get(entries.size() - 1).issuedWallMs;
            long next = DepinReconcilePolicy
                    .nextLaunchAllowedMs(last, failures);
            DepinReconcilePolicy.Decision d =
                    policy.decide(true, false, failures, next, now);
            if (d == DepinReconcilePolicy.Decision.LAUNCH) {
                int f2 = DepinLaunchDerivation
                        .consecutiveFailures(entries, now, grace);
                long n2 = DepinReconcilePolicy
                        .nextLaunchAllowedMs(last, f2);
                if (n2 <= now) {
                    entries.add(launch(false, false, now, 0L));
                    launches++;
                }
            } else if (d == DepinReconcilePolicy.Decision
                    .BACKOFF_NOTICE && noticesOut != null) {
                noticesOut[0]++;
            }
        }
        return launches;
    }

    // ---------------- status / intent merge (internal.24) -------
    //
    // The 2026-11-10 internal.23 device failure: the settings
    // switch is rendered from the facade's cached snapshot,
    // which every refresh refills from the status FILE — a
    // dead observer's last write (enabled=true) — so a user
    // disable, though persisted in the settings file, was
    // overwritten in the presentation within a second and the
    // switch turned itself back on. The merge makes the
    // user's recorded intent win whenever it is the freshest
    // fact, and lets a fresher snapshot (the service's own
    // later report — e.g. a self-stop after an enable) win
    // over the intent.
    //
    // Internal.26 amendment (audit finding F-04): the rule
    // is now ASYMMETRIC. A recorded OFF is superseded only
    // by a later recorded ON (which replaces the record
    // itself) — never by a snapshot, however fresh. The
    // internal.24 pin "an older OFF intent loses to the
    // file" encoded the defect: the fresher enabled=true
    // snapshot in the field was a claim-refusal UNAVAILABLE
    // write stamped with the current time by an observer
    // that had never seen the intent, and the merge let it
    // resurrect recording over the user's OFF. Grants are
    // the user's alone; a snapshot can still cancel (a
    // fresher enabled=false report beats an older ON intent:
    // the self-stop direction is unchanged).

    private static void statusMerge() {
        check("merge: no intent keeps the snapshot",
                DepinStatusMerge.enabled(true, 1_000L,
                        false, false, 0L));
        check("merge: no intent keeps a false snapshot",
                !DepinStatusMerge.enabled(false, 1_000L,
                        false, true, 9_999L));
        check("merge: a newer OFF intent beats a stale ON file",
                !DepinStatusMerge.enabled(true, 1_000L,
                        true, false, 2_000L));
        check("merge: an OFF intent beats a fresher ON file"
                        + " (F-04: the file never saw the OFF)",
                !DepinStatusMerge.enabled(true, 3_000L,
                        true, false, 2_000L));
        check("merge: a newer snapshot beats an ON intent"
                        + " (self-stop after enable)",
                !DepinStatusMerge.enabled(false, 3_000L,
                        true, true, 2_000L));
        check("merge: a later ON intent re-enables",
                DepinStatusMerge.enabled(false, 1_000L,
                        true, true, 2_000L));
        check("merge: a tie goes to the intent",
                DepinStatusMerge.enabled(false, 2_000L,
                        true, true, 2_000L));

        // Residual F-11: a bind request counts as
        // outstanding only while it is fresh. The facade
        // used to trust the bare flag forever, so one lost
        // callback disabled binding (and held the
        // presentation at "connecting") for the rest of
        // the process.
        check("bind: a fresh request is outstanding",
                DepinStatusMerge.bindOutstanding(
                        100_000L, 100_000L
                                + DepinStatusMerge.BIND_STALE_MS - 1));
        check("bind: a request at the bound is stale",
                !DepinStatusMerge.bindOutstanding(
                        100_000L, 100_000L
                                + DepinStatusMerge.BIND_STALE_MS));
        check("bind: no request is never outstanding",
                !DepinStatusMerge.bindOutstanding(0L, 100_000L));

        DepinStatus s = new DepinStatus();
        s.state = DepinStatus.STATE_RECORDING;
        s.enabled = true;
        s.updatedWallMs = 1_000L;
        // User turned it OFF at 2000; the file still says
        // RECORDING (written 1000) and nothing is bound: the
        // presented state is OFF — the toggle stays off.
        checkEq("merge: OFF intent + stale recording is OFF",
                DepinStatus.STATE_OFF,
                DepinStatusMerge.presentedState(s, false, false,
                        false, true));
        // Same, while still bound (the service is stopping):
        // the freshest fact is the user's OFF.
        checkEq("merge: OFF intent while bound is OFF",
                DepinStatus.STATE_OFF,
                DepinStatusMerge.presentedState(s, true, false,
                        false, true));
        // No OFF intent recorded: plain liveness rules.
        checkEq("merge: no OFF intent keeps liveness rules",
                DepinStatus.STATE_UNAVAILABLE,
                DepinStatusMerge.presentedState(s, false, false,
                        true, false));
        // Enabled (merged) + unbound degrades as before.
        checkEq("merge: enabled + unbound still degrades",
                DepinStatus.STATE_UNAVAILABLE,
                DepinStatusMerge.presentedState(s, false, false,
                        true, false));
        // F-04: a NEWER snapshot still describing RECORDING
        // does not outrank the recorded OFF — the snapshot's
        // freshness never proved it saw the intent. (The
        // last presentedState argument is now "the recorded
        // intent is OFF", not "the intent is freshest".)
        s.updatedWallMs = 5_000L;
        checkEq("merge: OFF intent + newer recording is OFF",
                DepinStatus.STATE_OFF,
                DepinStatusMerge.presentedState(s, false, false,
                        false, true));
        s.updatedWallMs = 1_000L;
        // Unavailable under an OFF intent is OFF: the
        // user's choice is why nothing is running.
        s.state = DepinStatus.STATE_UNAVAILABLE;
        checkEq("merge: OFF intent + unavailable is OFF",
                DepinStatus.STATE_OFF,
                DepinStatusMerge.presentedState(s, false, false,
                        false, true));
        // A settled state is never rewritten by the merge.
        s.state = DepinStatus.STATE_SELF_STOPPED;
        checkEq("merge: self-stopped passes through",
                DepinStatus.STATE_SELF_STOPPED,
                DepinStatusMerge.presentedState(s, false, false,
                        false, true));
        s.state = DepinStatus.STATE_OFF;
        checkEq("merge: off passes through",
                DepinStatus.STATE_OFF,
                DepinStatusMerge.presentedState(s, false, false,
                        false, true));
    }

    // ---------------- presented state (internal.23) -------------

    private static void presentedState() {
        DepinStatus s = new DepinStatus();
        s.state = DepinStatus.STATE_RECORDING;
        checkEq("presented: recording while bound stays",
                DepinStatus.STATE_RECORDING,
                s.presentedState(true, false));
        checkEq("presented: recording while binding stays",
                DepinStatus.STATE_RECORDING,
                s.presentedState(false, true));
        checkEq("presented: recording while unbound degrades",
                DepinStatus.STATE_UNAVAILABLE,
                s.presentedState(false, false));
        s.state = DepinStatus.STATE_PREPARING;
        checkEq("presented: preparing while unbound degrades",
                DepinStatus.STATE_UNAVAILABLE,
                s.presentedState(false, false));
        s.state = DepinStatus.STATE_HELD;
        checkEq("presented: held while unbound degrades",
                DepinStatus.STATE_UNAVAILABLE,
                s.presentedState(false, false));
        s.state = DepinStatus.STATE_OFF;
        checkEq("presented: off passes through",
                DepinStatus.STATE_OFF,
                s.presentedState(false, false));
        s.state = DepinStatus.STATE_SELF_STOPPED;
        checkEq("presented: self-stopped passes through",
                DepinStatus.STATE_SELF_STOPPED,
                s.presentedState(false, false));
        s.state = DepinStatus.STATE_UNAVAILABLE;
        checkEq("presented: unavailable passes through",
                DepinStatus.STATE_UNAVAILABLE,
                s.presentedState(false, false));
    }

    // ---------------- diagnostic price lines (internal.23) ------
    //
    // Internal.26 amendment (audit finding F-09): the summary
    // renders only the error's KIND (the exception class),
    // never the message. Before .26 the persisted text was
    // "ClassName: message" and the summary printed it
    // verbatim, outside the body's masking — and a network
    // failure message can carry the device's own address
    // ("failed to connect to host/ip ... from /<own address>").
    // The internal.23 pin below expected the verbatim text;
    // that expectation was the leak. PriceStore now persists
    // the class name alone, and buildSummary reduces any
    // legacy "ClassName: message" value to the class name,
    // so values written by older builds are safe too.

    private static void diagPriceLines() {
        DiagShare.Facts f = new DiagShare.Facts();
        String empty = DiagShare.buildSummary(f, "", false);
        check("diag: no price record renders n/a",
                empty.contains("QTC price fetched: n/a")
                        && empty.contains(
                                "QTC price fetch error: n/a"));
        f.priceFetchedMs = 1794000000000L;
        f.priceError = "IllegalStateException: HTTP 429";
        f.priceErrorMs = 1794000600000L;
        String s = DiagShare.buildSummary(f, "", false);
        check("diag: last success time is rendered",
                s.contains("QTC price fetched: "
                        + DiagShare.formatTimestamp(1794000000000L)));
        check("diag: last error renders kind + time only",
                s.contains("QTC price fetch error:"
                        + " IllegalStateException (at "
                        + DiagShare.formatTimestamp(1794000600000L)
                        + ")"));
        check("diag: the message is not rendered (F-09)",
                !s.contains("HTTP 429"));

        // The field case: a connect failure whose message
        // carries the device's own global address.
        f.priceError = "ConnectException: failed to connect to"
                + " api.coingecko.com/203.0.113.7 (port 443)"
                + " from /2001:db8:85a3::8a2e:370:7334"
                + " (port 54321) after 10000ms";
        String s2 = DiagShare.buildSummary(f, "", false);
        check("diag: connect failure renders its kind",
                s2.contains("QTC price fetch error:"
                        + " ConnectException (at "));
        check("diag: own address never reaches the summary",
                !s2.contains("2001:db8")
                        && !s2.contains("203.0.113.7")
                        && !s2.contains("failed to connect"));

        // A bare class name (the post-.26 persisted form)
        // passes through whole.
        f.priceError = "SocketTimeoutException";
        String s3 = DiagShare.buildSummary(f, "", false);
        check("diag: bare kind passes through",
                s3.contains("QTC price fetch error:"
                        + " SocketTimeoutException (at "));
    }

    // ---------------- writer-gate verdicts (internal.25) ----

    /**
     * The internal.24 device failure at the verdict level:
     * DepinObserverService.onCreate starts the journal
     * construction (prefetch) before any command runs, so the
     * construction evaluates TransferInit.writerGate while the
     * on-disk enable flag is still OFF — the state .24's
     * durable disable() made reachable for the first time.
     * processEnable then records setEnabled(liveId) and calls
     * ensureInitialized, which must NOT be served the
     * prefetch's not_switched_on verdict: that one reason is a
     * function of the recorded authorisation, which the enable
     * command itself writes. Every other gate reason is a
     * function of transfer state alone (changed only by
     * transfer operations, which invalidate the cache), so
     * those keep the cached, fail-closed treatment, and an
     * unrecognised reason fails closed with them.
     */
    private static void gateVerdict() {
        // The constant pins the exact reason string the core
        // gate produces (TransferInit.writerGate's last check).
        check("gate: reason constant is the gate's string",
                "not_switched_on".equals(
                        DepinGateVerdict.REASON_NOT_SWITCHED_ON));

        // Which reasons are authorisation-dependent.
        check("gate: not_switched_on is authorisation-dependent",
                DepinGateVerdict.isAuthorizationDependent(
                        "not_switched_on"));
        String[] stateOnly = {
                "frozen_s1", "restoration_pending",
                "restoration_unknown", "loss_path_open",
                "no_active_binding", "not_holder",
                "introduction_not_complete",
                "transfer_record_missing",
                "transfer_record_rolled_back",
                "unreadable: state file truncated",
                "some_future_reason"};
        for (String r : stateOnly) {
            check("gate: " + r + " is state-only",
                    !DepinGateVerdict.isAuthorizationDependent(r));
        }
        check("gate: null reason is state-only",
                !DepinGateVerdict.isAuthorizationDependent(null));

        // Cache disposition.
        check("gate: not_switched_on is re-evaluated, not cached",
                DepinGateVerdict.failureCache("not_switched_on")
                        == DepinGateVerdict.FailureCache.RE_EVALUATE);
        check("gate: frozen_s1 stays cached (fail-closed)",
                DepinGateVerdict.failureCache("frozen_s1")
                        == DepinGateVerdict.FailureCache.CACHED);
        check("gate: non-gate failure (null reason) stays cached",
                DepinGateVerdict.failureCache(null)
                        == DepinGateVerdict.FailureCache.CACHED);

        // The race, replayed against the runtime's publication
        // rule in miniature: a failure is stored for later
        // readers only when its disposition is CACHED.
        String[] cachedSlot = new String[1];
        java.util.function.Consumer<String> publish = reason -> {
            if (DepinGateVerdict.failureCache(reason)
                    == DepinGateVerdict.FailureCache.CACHED) {
                cachedSlot[0] = reason;
            }
        };
        // 1) Service created with the flag OFF: the prefetch's
        //    construction evaluates the gate and fails.
        publish.accept("not_switched_on");
        // 2) The enable command records the authorisation.
        // 3) Initialisation reads the construction outcome:
        //    nothing may be cached, so it re-evaluates against
        //    the recorded authorisation and can succeed.
        check("gate race: prefetch not_switched_on is not served"
                        + " to initialisation after enable",
                cachedSlot[0] == null);
        // The fail-closed counterpart: a frozen gate verdict
        // IS served to later readers — no re-evaluation can
        // talk the observer past a sender freeze.
        publish.accept("frozen_s1");
        check("gate race: frozen_s1 verdict is cached and served",
                "frozen_s1".equals(cachedSlot[0]));

        // Initialisation outcome: an authorisation wait is not
        // a self-stop (a recorded self-stop writes enabled=false
        // and makes the state self-perpetuating).
        check("gate: not_switched_on initialises as auth wait",
                DepinGateVerdict.initFailure("not_switched_on")
                        == DepinGateVerdict.InitFailure
                                .AUTHORIZATION_WAIT);
        check("gate: frozen_s1 initialises as self-stop",
                DepinGateVerdict.initFailure("frozen_s1")
                        == DepinGateVerdict.InitFailure.SELF_STOP);
        check("gate: non-gate failure initialises as self-stop",
                DepinGateVerdict.initFailure(null)
                        == DepinGateVerdict.InitFailure.SELF_STOP);
    }

    // ---------------- user intent record (internal.26) --------
    //
    // Audit findings F-03/F-04: the main process no longer
    // writes depin_settings at all. The user's OFF is the
    // recorded intent in the facade's own preferences file,
    // and the observer reads that record AS A FILE at every
    // start (a SharedPreferences read would serve the
    // observer process's own stale in-memory map — the very
    // mechanism of F-03). These checks pin the wire format
    // DepinModule writes: DepinUserIntent.parse over the
    // exact XML shape SharedPreferences produces.

    private static void userIntent() {
        String off = "<?xml version='1.0' encoding='utf-8'"
                + " standalone='yes' ?>\n<map>\n"
                + "    <boolean name=\"user_intent_set\""
                + " value=\"true\" />\n"
                + "    <long name=\"user_intent_wall_ms\""
                + " value=\"1794000000000\" />\n"
                + "    <boolean name=\"user_intent_enabled\""
                + " value=\"false\" />\n"
                + "</map>\n";
        DepinUserIntent.Record r = DepinUserIntent.parse(off);
        check("intent: an OFF record parses",
                r.present && !r.enabled);
        checkEq("intent: the wall time parses",
                1794000000000L, r.wallMs);

        String on = off.replace(
                "name=\"user_intent_enabled\""
                        + " value=\"false\"",
                "name=\"user_intent_enabled\""
                        + " value=\"true\"");
        r = DepinUserIntent.parse(on);
        check("intent: an ON record parses",
                r.present && r.enabled);

        check("intent: null is absent",
                !DepinUserIntent.parse(null).present);
        check("intent: garbage is absent",
                !DepinUserIntent.parse("not xml at all").present);
        check("intent: an unset record is absent",
                !DepinUserIntent.parse(off.replace(
                        "name=\"user_intent_set\""
                                + " value=\"true\"",
                        "name=\"user_intent_set\""
                                + " value=\"false\"")).present);
        check("intent: a torn record (no value) is absent",
                !DepinUserIntent.parse(off.replace(
                        "    <boolean"
                                + " name=\"user_intent_enabled\""
                                + " value=\"false\" />\n",
                        "")).present);
        // Element order on disk is the writer's choice; the
        // parse must not depend on it.
        String reordered = "<map>"
                + "<boolean name=\"user_intent_enabled\""
                + " value=\"false\" />"
                + "<boolean name=\"user_intent_set\""
                + " value=\"true\" />"
                + "</map>";
        r = DepinUserIntent.parse(reordered);
        check("intent: element order does not matter",
                r.present && !r.enabled);
        checkEq("intent: a missing wall time reads 0",
                0L, r.wallMs);
    }

    // ---------------- attempt log (G-11) ----------------

    private static void attemptLogRules() throws Exception {
        // G-11 (final check round 2): the writer and the reader
        // of RpcAttemptLog disagreed. consume() admitted a
        // record for slot maxSlot-1 AFTER records for maxSlot
        // existed, so the file's slots went out of order and
        // the NEXT start's strict load called the log corrupt
        // — with no in-app way back (resetCorrupt was never
        // called). The audit's fuzz reached a corrupt reload in
        // 2,039 of 4,000 tick-legal sequences. The rules are
        // now one: a slot behind the recorded maximum starts no
        // round — consume() refuses it as stale and the tick
        // skips it on the same condition — and a torn final
        // line (an append interrupted by power loss) counts as
        // the one started attempt it was and is repaired in
        // place, instead of corrupting the log. A prefix whose
        // slot can no longer be recovered still fails closed.
        final long m = 3_000_000L;
        File dir = Files.createTempDirectory("attemptlog-check").toFile();

        // (a) The M -> M-1 order the audit reproduced.
        File f = new File(dir, "rpc-attempts.jsonl");
        RpcAttemptLog log = new RpcAttemptLog(f);
        log.consume(m, "system_syncState");
        log.consume(m, "system_syncState");
        boolean staleRefused = false;
        try {
            log.consume(m - 1, "system_syncState");
        } catch (RpcAttemptLog.AttemptLogException e) {
            staleRefused = e.kind == RpcAttemptLog.KIND_STALE_SLOT;
        }
        check("attempt log: a round behind the recorded maximum is refused as stale",
                staleRefused);
        RpcAttemptLog reloaded = new RpcAttemptLog(f);
        check("attempt log: the refused write leaves the log healthy",
                !reloaded.isCorrupt() && reloaded.attemptsInSlot(m) == 2);

        // (b) The audit's fuzz (same seed and clock-movement
        // mix, 500 runs): tick-legal sequences — judged by the
        // OLD tick guards, the stricter refusal now living in
        // consume() itself — must never reload as corrupt.
        java.util.Random rng = new java.util.Random(11);
        int corruptRuns = 0;
        for (int r = 0; r < 500; r++) {
            File ff = new File(
                    Files.createTempDirectory("al-fuzz").toFile(),
                    "rpc-attempts.jsonl");
            RpcAttemptLog fl = new RpcAttemptLog(ff);
            long slot = m;
            boolean bad = false;
            for (int step = 0; step < 30 && !bad; step++) {
                int mv = rng.nextInt(10);
                if (mv < 6) {
                    slot += 1;
                } else if (mv == 6) {
                    slot += 2 + rng.nextInt(3);
                } else if (mv == 7) {
                    slot -= 1;
                } else if (mv == 8) {
                    slot -= 2;
                }
                long max = fl.maxSlot();
                if (max >= 0 && slot < max - 1) {
                    continue;
                }
                if (fl.hasAttempts(slot)) {
                    continue;
                }
                int attempts = 1 + rng.nextInt(6);
                try {
                    for (int k = 0; k < attempts; k++) {
                        fl.consume(slot, "system_syncState");
                    }
                } catch (RpcAttemptLog.AttemptLogException e) {
                    // Refused (stale slot): the round wrote
                    // nothing, exactly as a skipped round.
                }
                RpcAttemptLog re = new RpcAttemptLog(ff);
                if (re.isCorrupt()) {
                    bad = true;
                    corruptRuns++;
                }
            }
        }
        checkEq("attempt log fuzz: corrupt reloads in 500 tick-legal runs",
                0, corruptRuns);

        // (c) Torn final line: counted, repaired, durable.
        File g = new File(dir, "torn.jsonl");
        String valid = "{\"slot\":" + m
                + ",\"seq\":1,\"method\":\"system_health\""
                + ",\"result\":\"started\"}\n";
        String torn = "{\"slot\":" + m
                + ",\"seq\":2,\"method\":\"system_health\""
                + ",\"result\":\"star";
        Files.write(g.toPath(),
                (valid + torn).getBytes(StandardCharsets.UTF_8));
        RpcAttemptLog tornLog = new RpcAttemptLog(g);
        check("attempt log: a torn final line is not corruption",
                !tornLog.isCorrupt());
        checkEq("attempt log: the torn line counts as its one started attempt",
                2, tornLog.attemptsInSlot(m));
        check("attempt log: the torn tail was repaired whole",
                tornLog.tornTailRecovered());
        RpcAttemptLog tornReloaded = new RpcAttemptLog(g);
        check("attempt log: the repair is durable across a reload",
                !tornReloaded.isCorrupt()
                        && tornReloaded.attemptsInSlot(m) == 2);
        tornReloaded.consume(m, "system_health");
        RpcAttemptLog afterNext = new RpcAttemptLog(g);
        check("attempt log: the slot continues at the next seq after a torn tail",
                !afterNext.isCorrupt() && afterNext.attemptsInSlot(m) == 3);

        // (d) A prefix too short to name its slot is not a
        // recoverable tear: the log still fails closed, and the
        // maintenance reset (the G-11 repair path) is the way
        // back — bytes preserved, state healthy-empty.
        File h = new File(dir, "unrecoverable.jsonl");
        Files.write(h.toPath(),
                (valid + "{\"slo").getBytes(StandardCharsets.UTF_8));
        RpcAttemptLog broken = new RpcAttemptLog(h);
        check("attempt log: an unrecoverable prefix still fails closed",
                broken.isCorrupt());
        File aside = broken.resetCorrupt();
        check("attempt log: the reset sets the corrupt bytes aside",
                aside != null && aside.exists() && !broken.isCorrupt()
                        && broken.maxSlot() == -1);
        // The settings-side record of the reset
        // (DepinSettings.recordAttemptLogReset) and the
        // observer's confirmed-repair wiring are Android-bound
        // and verified by the build + code reading, not here.
    }

    // ---------------- attempt log (H-04, round 3) ----------------

    private static void attemptLogH04() throws Exception {
        // H-04 (final check round 3): the torn-tail recovery
        // accepted ANY prefix-shaped tail and rewrote it as a
        // "started" record — including byte strings no writer
        // could ever produce, and a tail naming a far-future
        // slot, which then poisoned maxSlot and refused every
        // real slot as stale. Recovery now rebuilds the only
        // lines the writer can produce for the expected
        // (slot, seq) and accepts the tail only when it is a
        // prefix of one of them (the wall-clock slot bounds a
        // next-slot tear). Anything else fails closed.
        final long m = 3_000_000L;
        File dir = Files.createTempDirectory("attemptlog-h04").toFile();
        String valid = "{\"slot\":" + m
                + ",\"seq\":1,\"method\":\"system_syncState\",\"result\":\"started\"}\n";
        String[] badTails = {
            "{\"slot\":" + m + ",\"seq\":abc",
            "{\"slot\":" + m + ",\"seq\":2}garbage{{{",
            "{\"slot\":" + m + ",XXXXXXXX",
            "{\"slot\":999999999999,",
            "{\"slot\":" + m
                + ",\"seq\":2,\"method\":\"evil_method\",\"result\":\"nope\"",
        };
        for (int i = 0; i < badTails.length; i++) {
            File f = new File(dir, "bad" + i + ".jsonl");
            Files.write(f.toPath(),
                    (valid + badTails[i]).getBytes(StandardCharsets.UTF_8));
            RpcAttemptLog bad = new RpcAttemptLog(f);
            check("attempt log H-04: non-producible tail #" + (i + 1)
                    + " fails closed", bad.isCorrupt()
                    && !bad.tornTailRecovered());
        }
        // The far-future tail must not poison the maximum:
        // the log is corrupt, so consume refuses as corrupt,
        // never as "stale" against a forged maximum.
        File pf = new File(dir, "poison.jsonl");
        Files.write(pf.toPath(),
                (valid + "{\"slot\":999999999999,")
                        .getBytes(StandardCharsets.UTF_8));
        RpcAttemptLog poison = new RpcAttemptLog(pf);
        boolean corruptRefusal = false;
        try {
            poison.consume(m + 1, "system_syncState");
        } catch (RpcAttemptLog.AttemptLogException e) {
            corruptRefusal = e.kind == RpcAttemptLog.KIND_CORRUPT;
        }
        check("attempt log H-04: a far-future tail refuses as corrupt, not stale",
                corruptRefusal);

        long cur = System.currentTimeMillis() / 1000L / 600L;
        String rowCur = "{\"slot\":" + cur
                + ",\"seq\":1,\"method\":\"system_syncState\",\"result\":\"started\"}\n";
        // A genuine next-slot tear naming exactly one method
        // is repaired to that method's full line.
        File nf = new File(dir, "nextslot.jsonl");
        Files.write(nf.toPath(),
                (rowCur + "{\"slot\":" + (cur + 1)
                        + ",\"seq\":1,\"method\":\"chain_getBlockH")
                        .getBytes(StandardCharsets.UTF_8));
        RpcAttemptLog next = new RpcAttemptLog(nf);
        String repaired = new String(Files.readAllBytes(nf.toPath()),
                StandardCharsets.UTF_8);
        check("attempt log H-04: a next-slot tear is recovered",
                !next.isCorrupt() && next.tornTailRecovered()
                        && next.attemptsInSlot(cur + 1) == 1);
        check("attempt log H-04: the repair completes the one matching line",
                repaired.contains("\"method\":\"chain_getBlockHash\"")
                        && !repaired.contains("recovered_torn"));
        // A tear that prefixes several methods is kept as a
        // recovered_torn marker: the attempt counts, the
        // method is not invented.
        File af = new File(dir, "ambiguous.jsonl");
        Files.write(af.toPath(),
                (rowCur + "{\"slot\":" + (cur + 1)
                        + ",\"seq\":1,\"method\":\"system_")
                        .getBytes(StandardCharsets.UTF_8));
        RpcAttemptLog amb = new RpcAttemptLog(af);
        String ambRepaired = new String(Files.readAllBytes(af.toPath()),
                StandardCharsets.UTF_8);
        check("attempt log H-04: an ambiguous tear becomes recovered_torn",
                !amb.isCorrupt() && amb.attemptsInSlot(cur + 1) == 1
                        && ambRepaired.contains("recovered_torn"));
        // The read side is size-capped: an unbounded file is
        // corrupt, never an unbounded in-memory load.
        File big = new File(dir, "big.jsonl");
        StringBuilder sb = new StringBuilder();
        long s = cur;
        while (sb.length() <= 1024 * 1024) {
            sb.append("{\"slot\":").append(s++)
                    .append(",\"seq\":1,\"method\":\"system_health\",\"result\":\"started\"}\n");
        }
        Files.write(big.toPath(),
                sb.toString().getBytes(StandardCharsets.UTF_8));
        check("attempt log H-04: an oversized log fails closed",
                new RpcAttemptLog(big).isCorrupt());
    }

    // ---------------- tick breaker (H-09, round 3) ----------------

    private static void tickBreakerRules() {
        // H-09 (final check round 3): the breaker counts
        // failed ROUNDS (slots that actually attempted and
        // ended in an exception), not raw tick signals —
        // signal-free ticks (already recorded, withheld) do
        // not dilute the count, and one round signalling
        // twice counts once.
        DepinTickBreaker b = new DepinTickBreaker(3);
        check("tick breaker: the first two failed rounds do not trip",
                !b.roundFailed(100) && !b.roundFailed(101)
                        && b.consecutiveFailedRounds() == 2);
        check("tick breaker: the third consecutive failed round trips",
                b.roundFailed(102) && b.consecutiveFailedRounds() == 3);
        DepinTickBreaker c = new DepinTickBreaker(3);
        c.roundFailed(200);
        c.roundFailed(200);
        checkEq("tick breaker: a repeated signal for one round counts once",
                1, c.consecutiveFailedRounds());
        c.roundFailed(201);
        c.roundCompleted();
        checkEq("tick breaker: a completed round resets the count",
                0, c.consecutiveFailedRounds());
        check("tick breaker: two failures after a completion do not trip",
                !c.roundFailed(202) && !c.roundFailed(203));
    }
}
