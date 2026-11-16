package app.quantusnode.node;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;

import app.quantusnode.node.depin.core.Json;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The QTC price for the vision home (design section 2.4, spec
 * section 4.1): one HTTPS GET to CoinGecko's public API (asset
 * id "quantus", USD, with the 24 h change in the same response),
 * fetched at most once an hour while the app runs — the
 * callers are the activity's UI tick and, since internal.23,
 * the node service's poller, so the price keeps refreshing
 * while the node runs even with no UI open — with a failure
 * backoff of 15, then 30, then 60 minutes (see
 * {@link PriceLogic}). Scheduling and staleness run on the
 * monotonic clock, persisted next to the wall time of each
 * fetch, with the boot count beside it: a reboot is recognised
 * by the count changing, and the wall clock takes over for the
 * age (v4.2.2 audit, L2). A wall-clock jump can therefore
 * neither trigger a fetch storm nor hide a stale value
 * (v4.2.2, gap inventory B-4).
 * The last successful value and its fetch time persist
 * on-device, so a cold start shows the old value WITH its age,
 * never as if it were new (design section 2.5), and the last
 * failure persists too, so a stopped price is diagnosable.
 *
 * The price is never estimated from anything else the app knows:
 * no value means the UI shows a dash. This class is the app's
 * only outbound HTTP path of its own; the node process's traffic
 * is separate and untouched by it.
 */
public final class PriceStore {

    private static final String PREFS = "price_store";
    private static final String KEY_USD = "usd";
    private static final String KEY_CHANGE = "change24h";
    private static final String KEY_FETCHED_MS = "fetched_wall_ms";
    /** The monotonic reading taken with the fetch above
     *  (v4.2.2, gap inventory B-4): staleness is judged on this
     *  clock, which no wall-clock setting can move. Absent in
     *  saves written before v4.2.2 — those fall back to the
     *  wall age, as they always did. */
    private static final String KEY_FETCHED_ELAPSED_MS =
            "fetched_elapsed_ms";
    /** The boot the fetch above was taken in
     *  (Settings.Global.BOOT_COUNT, v4.2.2 audit L2): the
     *  monotonic reading is only comparable within one boot.
     *  Absent in saves written before this — those fall back
     *  to the wall age, as they always did. */
    private static final String KEY_FETCHED_BOOT = "fetched_boot_count";
    private static final String KEY_HAS_CHANGE = "has_change";
    private static final String KEY_UPDATED_UNIX = "updated_unix";
    /** The most recent fetch failure, kept until the next
     *  success: what failed (exception kind, with the HTTP
     *  status when there was one) and when. Until internal.23
     *  a failure was recorded NOWHERE — the exception was
     *  swallowed by the scheduling catch — so an on-device
     *  failure (a CoinGecko rate limit, a network error) was
     *  indistinguishable from the app never trying. */
    private static final String KEY_LAST_ERROR = "last_error";
    private static final String KEY_LAST_ERROR_MS = "last_error_wall_ms";
    /** The user's price-fetch switch (UI/UX phase 1, decided in
     *  14-2): default on, shown in the price sheet and in
     *  Settings. Off means no connection is ever made. */
    private static final String KEY_ENABLED = "enabled";

    private static final String ENDPOINT =
            "https://api.coingecko.com/api/v3/simple/price"
                    + "?ids=quantus&vs_currencies=usd"
                    + "&include_24hr_change=true"
                    + "&include_last_updated_at=true";
    /** Foreground refresh cadence after a success. One hour
     *  since the UI/UX phase 1 work (decided in 14-2); it was
     *  five minutes before, twelve times the intended rate. */
    private static final long REFRESH_MS = 60L * 60 * 1000;
    /** A cached value older than this is presented as stale. */
    private static final long STALE_MS = 2 * REFRESH_MS;

    private static final java.util.concurrent.atomic.AtomicBoolean
            IN_FLIGHT = new java.util.concurrent.atomic.AtomicBoolean();
    /** When the next attempt is due, on the monotonic clock —
     *  a wall-clock jump can neither rush nor delay it (B-4). */
    private static volatile long nextAttemptElapsedMs;
    private static volatile int failures;
    /** Whether the most recent attempt of THIS process failed. */
    private static volatile boolean lastAttemptFailed;
    /** The User-Agent sent with the fetch: "QuantusNode" until
     *  a caller provides the app's version (v4.2.2, B-9) — it
     *  said "QuantusNode/0.3" long after 0.3 was history. */
    private static volatile String userAgent = "QuantusNode";

    /** Provide the version the fetch identifies itself with,
     *  read from the app's own package info. Called by the
     *  service and the activity at creation; a failure leaves
     *  the plain default in place. */
    public static void initUserAgent(Context ctx) {
        try {
            String version = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
            if (version != null && !version.isEmpty()) {
                userAgent = "QuantusNode/" + version;
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            // The app's own package is always found in practice;
            // a dying package manager must not take onCreate
            // down with it (v4.2.2 audit, I6).
        }
    }

    /** This boot's count (Settings.Global.BOOT_COUNT), or -1
     *  when it cannot be read: the staleness judgement treats
     *  -1 as "unknown boot" and falls back to the wall age. */
    static int bootCount(Context ctx) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(),
                    Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private PriceStore() {
    }

    /** An immutable view of what the UI may show right now. */
    public static final class Snapshot {
        public final boolean hasValue;
        public final double usd;
        public final double change24h;
        /** Whether the source actually provided a 24 h change
         *  with the fetch that produced this value. "Not
         *  provided" and "0.0%" are different facts and the UI
         *  must be able to tell them apart (spec S1-08): when
         *  this is false the change renders as a dash, never
         *  as a computed-looking 0.0. Saves written before this
         *  field existed load as true (the value they hold was
         *  presented as a change), per the spec's migration
         *  rule. */
        public final boolean hasChange;
        /** CoinGecko's own last-update time for the price
         *  (unix seconds), for the source tag; -1 when the
         *  fetch did not carry one. The staleness judgement
         *  keeps using the device fetch time below. */
        public final long updatedUnix;
        /** Wall-clock millis of the successful fetch; 0 if none. */
        public final long fetchedWallMs;
        /** True when the value should be presented as not fresh
         *  (the last attempt failed, or the value is old). */
        public final boolean stale;

        Snapshot(boolean hasValue, double usd, double change24h,
                 boolean hasChange, long updatedUnix,
                 long fetchedWallMs, boolean stale) {
            this.hasValue = hasValue;
            this.usd = usd;
            this.change24h = change24h;
            this.hasChange = hasChange;
            this.updatedUnix = updatedUnix;
            this.fetchedWallMs = fetchedWallMs;
            this.stale = stale;
        }
    }

    /** Whether the user lets the app fetch the price at all
     *  (default on; the switch lives in the price sheet and in
     *  Settings, over this one preference). */
    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, on).apply();
    }

    public static Snapshot snapshot(Context ctx) {
        SharedPreferences p =
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.contains(KEY_USD)) {
            return new Snapshot(false, 0, 0, true, -1, 0, false);
        }
        double usd = Double.longBitsToDouble(p.getLong(KEY_USD, 0));
        double change =
                Double.longBitsToDouble(p.getLong(KEY_CHANGE, 0));
        boolean hasChange = p.getBoolean(KEY_HAS_CHANGE, true);
        long updatedUnix = p.getLong(KEY_UPDATED_UNIX, -1);
        long fetched = p.getLong(KEY_FETCHED_MS, 0);
        long fetchedElapsed = p.getLong(KEY_FETCHED_ELAPSED_MS, 0);
        int fetchedBoot = p.getInt(KEY_FETCHED_BOOT, -1);
        boolean stale = PriceLogic.isStale(fetched, fetchedElapsed,
                fetchedBoot, System.currentTimeMillis(),
                SystemClock.elapsedRealtime(), bootCount(ctx),
                STALE_MS, lastAttemptFailed);
        return new Snapshot(true, usd, change, hasChange,
                updatedUnix, fetched, stale);
    }

    /** The most recent fetch failure's KIND — the exception's
     *  class name alone (e.g. "SocketTimeoutException"), or
     *  null when the last attempt succeeded or none has run
     *  yet. Persisted, so the diagnostic summary can show it
     *  after the fact. The exception MESSAGE is deliberately
     *  never persisted (internal.26, F-09): a network failure
     *  message can carry the device's own address, and this
     *  text is bound for a shareable summary. Values written
     *  by earlier builds ("ClassName: message") are reduced
     *  to the kind again at render time (DiagShare). */
    public static String lastErrorText(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_LAST_ERROR, null);
        } catch (ClassCastException e) {
            return null;
        }
    }

    /** Wall-clock millis of the most recent fetch failure;
     *  0 when {@link #lastErrorText} is null. */
    public static long lastErrorWallMs(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong(KEY_LAST_ERROR_MS, 0);
        } catch (ClassCastException e) {
            return 0;
        }
    }

    /**
     * Fetch if due. Called from the activity's 1-second tick
     * and from the node service's poller; the cadence and the
     * backoff live here, so callers never think about them.
     * At most one request is ever in flight.
     */
    public static void maybeFetch(Context ctx) {
        if (!isEnabled(ctx)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        // Residual F-25: the in-flight flag used to be a
        // volatile check-then-set, so two callers (the UI
        // tick and the service poller) could both pass and
        // fetch twice. The claim is atomic now.
        if (now < nextAttemptElapsedMs
                || !IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                Fetched v = fetch();
                SharedPreferences p = app.getSharedPreferences(
                        PREFS, Context.MODE_PRIVATE);
                p.edit()
                        .putLong(KEY_USD, Double.doubleToLongBits(v.usd))
                        .putLong(KEY_CHANGE,
                                Double.doubleToLongBits(v.change24h))
                        .putBoolean(KEY_HAS_CHANGE, v.hasChange)
                        .putLong(KEY_UPDATED_UNIX, v.updatedUnix)
                        .putLong(KEY_FETCHED_MS,
                                System.currentTimeMillis())
                        .putLong(KEY_FETCHED_ELAPSED_MS,
                                SystemClock.elapsedRealtime())
                        .putInt(KEY_FETCHED_BOOT, bootCount(app))
                        .remove(KEY_LAST_ERROR)
                        .remove(KEY_LAST_ERROR_MS)
                        .apply();
                failures = 0;
                lastAttemptFailed = false;
                nextAttemptElapsedMs =
                        SystemClock.elapsedRealtime() + REFRESH_MS;
            } catch (Exception e) {
                failures++;
                lastAttemptFailed = true;
                // The failure is a fact the diagnostic summary
                // (and only it) needs later; persist it before
                // scheduling the retry. Only the exception's
                // KIND is kept — never its message (F-09): a
                // connect failure's message names the host it
                // dialed and the device's own address it came
                // from, and this record is printed into a
                // shareable text.
                String errorText = e.getClass().getSimpleName();
                try {
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putString(KEY_LAST_ERROR, errorText)
                            .putLong(KEY_LAST_ERROR_MS,
                                    System.currentTimeMillis())
                            .apply();
                } catch (Throwable ignored) {
                    // Recording the failure must never become
                    // a second failure.
                }
                nextAttemptElapsedMs = SystemClock.elapsedRealtime()
                        + PriceLogic.backoffMs(failures);
            } finally {
                IN_FLIGHT.set(false);
            }
        }, "price-fetch");
        t.setDaemon(true);
        try {
            t.start();
        } catch (Throwable startFailure) {
            // Final check round 4 (I-12): the in-flight claim
            // is released by the worker's finally — which
            // never runs when the worker cannot start (thread
            // creation can fail under memory pressure). The
            // flag then stayed set for the rest of the
            // process and no fetch ever ran again.
            IN_FLIGHT.set(false);
        }
    }

    /** One fetch's parsed result. */
    private static final class Fetched {
        double usd;
        double change24h;
        boolean hasChange;
        long updatedUnix = -1;
    }

    /** One request; returns the parsed values or throws. */
    private static Fetched fetch() throws Exception {
        HttpURLConnection c =
                (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            c.setRequestProperty("User-Agent", userAgent);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) {
                throw new IllegalStateException(
                        "HTTP " + c.getResponseCode());
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(
                            c.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[2048];
                int n;
                while ((n = r.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                    if (sb.length() > 64 * 1024) {
                        throw new IllegalStateException("oversized body");
                    }
                }
            }
            Map<String, Object> root = Json.parseObject(sb.toString());
            Object q = root.get("quantus");
            if (!(q instanceof Map)) {
                throw new IllegalStateException("no quantus entry");
            }
            Object usd = ((Map<?, ?>) q).get("usd");
            Object change = ((Map<?, ?>) q).get("usd_24h_change");
            Object updated = ((Map<?, ?>) q).get("last_updated_at");
            if (!(usd instanceof Number)) {
                throw new IllegalStateException("no usd price");
            }
            Fetched f = new Fetched();
            f.usd = ((Number) usd).doubleValue();
            // A missing change is NOT 0.0: keep the distinction
            // (the old code substituted 0.0 here, which made
            // "not provided" indistinguishable from a real flat
            // reading — spec S1-08).
            f.hasChange = change instanceof Number;
            f.change24h = f.hasChange
                    ? ((Number) change).doubleValue() : 0.0;
            if (updated instanceof Number) {
                f.updatedUnix = ((Number) updated).longValue();
            }
            return f;
        } finally {
            c.disconnect();
        }
    }
}
