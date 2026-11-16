package app.quantusnode.node.depin;

import app.quantusnode.node.depin.core.CompanionStatus;
import app.quantusnode.node.depin.core.DepinCore;
import app.quantusnode.node.depin.core.Ed25519;
import app.quantusnode.node.depin.core.Hex;
import app.quantusnode.node.depin.core.JournalStore;

import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;

/**
 * Permanent checks for the S-05 observer hold token
 * (DepinRuntime): a store hold is adopted with a generation
 * token and released only by presenting that same token, so
 * a destroyed predecessor's late release can no longer clear
 * a rapidly restarted successor's hold on the same cached
 * store.
 *
 * Scenarios:
 *  (a) token1 adopt -> token2 re-adopt -> release(store,
 *      token1) leaves the hold in place and a following
 *      invalidateJournal() still defers;
 *  (b) release(store, token2) then applies the deferred
 *      invalidation;
 *  (c) token 0 and unknown tokens release nothing;
 *  (d) a correct-token release is idempotent.
 *
 * Observation: invalidateJournal() deferred by a hold returns
 * inside the lock and never clears the CompanionStatus error
 * fields, while an immediate invalidation clears them — so
 * sentinel values planted in journalError / identityError
 * distinguish deferred from immediate. The cached-store field
 * is planted via reflection (journal construction itself
 * needs an Android Context and is out of scope here); the
 * fixture JournalStore follows the core HarnessMain pattern
 * (fixed seed, EntrySigner from DepinCore.journalEntrySig).
 *
 * Run via run-observer-hold-check.sh.
 */
public final class ObserverHoldCheckMain {
    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("PASS " + name); }
        else { failed++; System.out.println("FAIL " + name); }
    }

    private static void setField(String name, Object value) throws Exception {
        Field f = DepinRuntime.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }
    private static Object getField(String name) throws Exception {
        Field f = DepinRuntime.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }
    private static void setBool(String name, boolean v) throws Exception {
        Field f = DepinRuntime.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setBoolean(null, v);
    }
    private static void setLong(String name, long v) throws Exception {
        Field f = DepinRuntime.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setLong(null, v);
    }
    private static long getLong(String name) throws Exception {
        Field f = DepinRuntime.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getLong(null);
    }

    private static byte[] seed;
    private static byte[] pub;
    private static String deviceId;

    private static JournalStore fixture() throws Exception {
        seed = new byte[32];
        for (int i = 0; i < 32; i++) seed[i] = (byte) (i + 1);
        pub = Ed25519.publickeyFromSeed(seed);
        deviceId = DepinCore.deviceIdFromPubkey(pub);
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "observer-hold-node-" + System.nanoTime());
        dir.mkdirs();
        File file = new File(dir, "journal.json");
        JournalStore.EntrySigner signer =
                (hash, seq) -> DepinCore.journalEntrySig(seed, hash, seq);
        JournalStore.EntryVerifier verifier =
                (hash, seq, sig) -> DepinCore.verifyJournalEntrySig(pub, hash, seq, sig);
        JournalStore store = new JournalStore(file, deviceId, Hex.encode(pub), signer, verifier);
        store.bindIdentity(deviceId, Hex.encode(pub));
        return store;
    }

    private static void reset(JournalStore store) throws Exception {
        setField("journal", store);
        setField("journalFailure", null);
        setField("journalSoftFailure", null);
        setField("journalGateReason", null);
        setField("journalLatch", new CountDownLatch(1));
        setBool("journalInitStarted", false);
        setField("journalDeviceId", null);
        setField("journalPubkey", null);
        setField("observerHeldStore", null);
        setLong("observerHoldToken", 0L);
        setLong("observerHoldSequence", 0L);
        setBool("journalInvalidationDeferred", false);
        CompanionStatus.journalError = null;
        CompanionStatus.identityError = null;
    }

    private static void setSentinels() {
        CompanionStatus.journalError = "SENTINEL_J";
        CompanionStatus.identityError = "SENTINEL_I";
    }
    private static boolean sentinelsAlive() {
        return "SENTINEL_J".equals(CompanionStatus.journalError)
                && "SENTINEL_I".equals(CompanionStatus.identityError);
    }
    private static boolean sentinelsCleared() {
        return CompanionStatus.journalError == null
                && CompanionStatus.identityError == null;
    }

    public static void main(String[] args) throws Exception {
        JournalStore store = fixture();
        check("fixture: store created empty", store.count() == 0);

        // JournalWithIdentity carries the token (observer) or 0.
        DepinRuntime.JournalWithIdentity withToken =
                new DepinRuntime.JournalWithIdentity(store, deviceId, pub.clone(), 42L);
        check("jwi: holdToken carried", withToken.holdToken == 42L);
        DepinRuntime.JournalWithIdentity noToken =
                new DepinRuntime.JournalWithIdentity(store, deviceId, pub.clone(), 0L);
        check("jwi: non-observer holdToken is 0", noToken.holdToken == 0L);

        // (a) stale token release leaves the successor's hold.
        reset(store);
        long token1 = DepinRuntime.observerAdoptedStore(store);
        long token2 = DepinRuntime.observerAdoptedStore(store);
        check("a: tokens are non-zero", token1 != 0L && token2 != 0L);
        check("a: re-adoption mints a fresh token", token2 != token1);
        check("a: runtime records the latest token", getLong("observerHoldToken") == token2);
        DepinRuntime.observerReleasedStore(store, token1); // stale predecessor
        check("a: stale release leaves hold in place", getField("observerHeldStore") == store);
        setSentinels();
        DepinRuntime.invalidateJournal();
        check("a: invalidate still defers (sentinels survive)", sentinelsAlive());

        // (b) the current token's release applies the deferral.
        DepinRuntime.observerReleasedStore(store, token2);
        check("b: correct release clears hold", getField("observerHeldStore") == null);
        check("b: deferred invalidation fires (sentinels cleared)", sentinelsCleared());
        check("b: deferred invalidation drops the cached store", getField("journal") == null);

        // (c) token 0 / unknown tokens release nothing.
        reset(store);
        long token = DepinRuntime.observerAdoptedStore(store);
        DepinRuntime.observerReleasedStore(store, 0L);
        check("c: token 0 releases nothing", getField("observerHeldStore") == store);
        DepinRuntime.observerReleasedStore(store, token + 999L);
        check("c: unknown token releases nothing", getField("observerHeldStore") == store);
        setSentinels();
        DepinRuntime.invalidateJournal();
        check("c: invalidate still defers after bogus releases", sentinelsAlive());
        DepinRuntime.observerReleasedStore(store, token);
        check("c: cleanup release fires deferred invalidation", sentinelsCleared());

        // (d) correct-token release is idempotent.
        reset(store);
        long t = DepinRuntime.observerAdoptedStore(store);
        DepinRuntime.observerReleasedStore(store, t);
        check("d: first release clears hold", getField("observerHeldStore") == null);
        boolean secondSafe = true;
        try {
            DepinRuntime.observerReleasedStore(store, t);
        } catch (Throwable e) {
            secondSafe = false;
        }
        check("d: second release is a safe no-op", secondSafe && getField("observerHeldStore") == null);
        reset(store);
        long t2 = DepinRuntime.observerAdoptedStore(store);
        setSentinels();
        DepinRuntime.invalidateJournal();
        DepinRuntime.observerReleasedStore(store, t2);
        boolean repeatSafe = true;
        try {
            DepinRuntime.observerReleasedStore(store, t2);
        } catch (Throwable e) {
            repeatSafe = false;
        }
        check("d: repeat release after deferred firing is safe and does not resurrect hold",
                repeatSafe && sentinelsCleared() && getField("observerHeldStore") == null);

        System.out.println("----");
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
