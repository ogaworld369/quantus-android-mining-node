package app.quantusnode.node.depin.core;

import app.quantusnode.node.depin.TransferFlow;


import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Standalone JVM verification for the transfer UI state
 * derivation (TransferFlow.classifyReceiver) around permit
 * intake. Builds real sender/receiver stores with the core
 * machines (same scaffolding as the core transfer harness),
 * then reads the derivation back at each step:
 *
 *   stage + complete -> STAGED_NOTIFIED
 *   + intakePermit (no trial exists yet) -> PERMIT_IN
 *   expired permit intaken (latched)     -> PERMIT_EXPIRED
 *   + fresh permit re-issued + intaken   -> PERMIT_IN
 *
 * All identities are throwaway test identities.
 */
public final class TransferFlowVerifyMain {
    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private static SecretKey testWrapKey() {
        byte[] k = new byte[32];
        for (int i = 0; i < 32; i++) k[i] = (byte) (0xA0 + i);
        return new SecretKeySpec(k, "AES");
    }

    private static final class TestClock
            implements TransferSender.Clock, TransferReceiver.Clock {
        long wall = 1762003600L;
        public long wallUnix() { return wall; }
        public long monoNanos() { return System.nanoTime(); }
    }

    private static final class TestFinalized
            implements TransferSender.FinalizedSource,
                    TransferReceiver.FinalizedSource {
        long value = 400000L;
        public long readFinalized() { return value; }
    }

    private static final class TestTimeSource
            implements Reverification.TimeSource {
        public String bootId() { return "boot-test"; }
        public long monoMs() { return System.nanoTime() / 1_000_000L; }
        public long threadCpuMs() {
            return java.lang.management.ManagementFactory
                    .getThreadMXBean().getCurrentThreadCpuTime()
                    / 1_000_000L;
        }
    }

    private static final class TestFreeze
            implements TransferSender.FreezeControl {
        public void drainAndFreeze() {}
        public void unfreeze() {}
    }

    private static byte[] testSeed() {
        byte[] seed = new byte[32];
        for (int i = 0; i < 32; i++) seed[i] = (byte) (i + 1);
        return seed;
    }

    private static File tmpDir(String name) {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "depin-flowverify-" + name + "-" + System.nanoTime());
        dir.mkdirs();
        return dir;
    }

    private static void writeJournal(File storeRoot, byte[] seed,
                                     int count) throws Exception {
        byte[] pub = Ed25519.publickeyFromSeed(seed);
        String deviceId = DepinCore.deviceIdFromPubkey(pub);
        JournalStore j = JournalStore.forFile(
                new File(storeRoot, JournalStore.FILE_NAME), deviceId,
                Hex.encode(pub),
                (hash, seq) -> DepinCore.journalEntrySig(seed, hash, seq),
                (hash, seq, sig) ->
                        DepinCore.verifyJournalEntrySig(pub, hash, seq, sig));
        j.bindIdentity(deviceId, Hex.encode(pub));
        List<DepinCore.Observation> obs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long number = 350000L + i * 100L;
            DepinCore.Observation o = new DepinCore.Observation();
            o.timestampUnix = 1762000000L + i * 600L;
            o.bestBlockNumber = number;
            o.bestBlockHash = DepinCore.sha256(
                    ("quantus-synthetic-block-" + number)
                            .getBytes(StandardCharsets.US_ASCII));
            o.observedPeers = 8 + (i % 5);
            o.finalizedBlockNumber = number - 10;
            obs.add(o);
        }
        for (int i = 0; i < count; i++) {
            j.append(DepinCore.makeCheckpoint(i, obs.get(i),
                    j.nextPrevState(), j.nextPrevCheckpointHash(), 50, pub));
        }
    }

    private static final class Pair {
        File senderRoot;
        File receiverRoot;
        byte[] seed;
        String deviceId;
        TestClock clock = new TestClock();
        TestFinalized senderFin = new TestFinalized();
        TestFinalized receiverFin = new TestFinalized();
        TransferSender sender;
        TransferReceiver receiver;
    }

    private static Pair newPair() throws Exception {
        Pair p = new Pair();
        p.seed = testSeed();
        p.deviceId = DepinCore.deviceIdFromPubkey(
                Ed25519.publickeyFromSeed(p.seed));
        p.senderRoot = tmpDir("sender");
        p.receiverRoot = tmpDir("receiver");
        writeJournal(p.senderRoot, p.seed, 6);
        TransferVault senderVault =
                new TransferVault(p.senderRoot, testWrapKey());
        TransferInit.initialise(p.senderRoot, senderVault,
                TransferInit.MODE_NEW, p.seed, RANDOM);
        TransferVault receiverVault =
                new TransferVault(p.receiverRoot, testWrapKey());
        TransferInit.initialise(p.receiverRoot, receiverVault,
                TransferInit.MODE_FIRST_RECEIVE, null, RANDOM);
        p.sender = new TransferSender(p.senderRoot, senderVault, RANDOM,
                "test-app", new TestFreeze(), p.senderFin, p.clock,
                24L * 3600, 1000, 100);
        p.receiver = new TransferReceiver(p.receiverRoot, receiverVault,
                testWrapKey(), p.receiverFin, p.clock,
                new TestTimeSource());
        return p;
    }

    /** Write a legacy (v0.4) archive as a sidecar file, in the
     *  shape JournalStore keeps (same fixture as the core
     *  transfer harness). */
    private static void writeLegacyArchive(File storeRoot, byte[] seed,
                                           int count) throws Exception {
        byte[] pub = Ed25519.publickeyFromSeed(seed);
        String deviceId = DepinCore.deviceIdFromPubkey(pub);
        StringBuilder sb = new StringBuilder();
        Map<String, Object> header = new java.util.LinkedHashMap<>();
        header.put("type", "header");
        header.put("format", "quantus-depin-journal");
        header.put("version", "0.4");
        header.put("device_id", deviceId);
        header.put("pubkey", Hex.encode(pub));
        sb.append(new String(TransferCodec.jcs(header),
                StandardCharsets.UTF_8)).append('\n');
        byte[] prevState = DepinCore.ZERO32;
        byte[] prevHash = DepinCore.ZERO32;
        List<DepinCore.Observation> obs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long number = 350000L + i * 100L;
            DepinCore.Observation o = new DepinCore.Observation();
            o.timestampUnix = 1762000000L + i * 600L;
            o.bestBlockNumber = number;
            o.bestBlockHash = DepinCore.sha256(
                    ("quantus-synthetic-block-" + number)
                            .getBytes(StandardCharsets.US_ASCII));
            o.observedPeers = 8 + (i % 5);
            o.finalizedBlockNumber = number - 10;
            obs.add(o);
        }
        for (int i = 0; i < count; i++) {
            DepinCore.Checkpoint cp = DepinCore.makeCheckpoint(
                    DepinCore.FMT_V04, i, obs.get(i), prevState, prevHash,
                    50, pub);
            Map<String, Object> line =
                    new java.util.LinkedHashMap<>(cp.toMap());
            line.put("entry_sig", Hex.encode(
                    DepinCore.journalEntrySig(seed, cp.checkpointHash, i)));
            sb.append(new String(TransferCodec.jcs(line),
                    StandardCharsets.UTF_8)).append('\n');
            prevState = cp.chainState;
            prevHash = cp.checkpointHash;
        }
        java.nio.file.Files.write(new File(storeRoot,
                JournalStore.ARCHIVE_V04_FILE_NAME).toPath(),
                sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static TransferFlow.ReceiverState deriveReceiver(Pair p) {
        TransferFlow.Snapshot s = TransferFlow.derive(p.receiverRoot);
        if (s.loadFailed) {
            throw new IllegalStateException("derivation load failed");
        }
        return s.receiver;
    }

    public static void main(String[] args) throws Exception {
        // ---- scenario 1+2: notified, then permit in (no trial) ----
        Pair p = newPair();
        TransferSender.ExportResult ex = p.sender.exportPackage(p.seed);
        p.receiver.intakePackage(ex.packageFile, ex.transferCode);
        check("notified: derivation is STAGED_NOTIFIED",
                deriveReceiver(p)
                        == TransferFlow.ReceiverState.STAGED_NOTIFIED);
        Map<String, Object> notice = p.receiver.buildNotice(p.deviceId, 1);
        Map<String, Object> permit = p.sender.issuePermit(p.seed, notice);
        check("permit-in: intake commits",
                p.receiver.intakePermit(permit)
                        == TransferReceiver.PermitIntakeOutcome.COMMITTED);
        TransferState st = TransferState.load(p.receiverRoot);
        Map<String, Object> rec = st.receiver(p.deviceId, 1);
        check("permit-in: record has no trial yet",
                rec != null && TransferState.child(rec, "trial") == null);
        check("permit-in: derivation is PERMIT_IN (ACT_CONFIRM)",
                deriveReceiver(p)
                        == TransferFlow.ReceiverState.PERMIT_IN);

        // ---- scenario 3+4: expired permit latched, then re-issue ----
        Pair q = newPair();
        TransferSender.ExportResult exq = q.sender.exportPackage(q.seed);
        q.receiver.intakePackage(exq.packageFile, exq.transferCode);
        Map<String, Object> noticeQ = q.receiver.buildNotice(q.deviceId, 1);
        Map<String, Object> permitQ = q.sender.issuePermit(q.seed, noticeQ);
        long expWall = ((Number) permitQ.get("expires_at_unix")).longValue();
        q.clock.wall = expWall + 10; // past the permit's wall expiry
        check("expired: intake reports EXPIRED_LATCHED",
                q.receiver.intakePermit(permitQ)
                        == TransferReceiver.PermitIntakeOutcome.EXPIRED_LATCHED);
        check("expired: derivation is PERMIT_EXPIRED (re-import offered)",
                deriveReceiver(q)
                        == TransferFlow.ReceiverState.PERMIT_EXPIRED);
        q.clock.wall += 60; // fresh permit must differ from the latched one
        Map<String, Object> permitQ2 = q.sender.issuePermit(q.seed, noticeQ);
        check("expired: sender can re-issue a fresh permit",
                permitQ2 != null
                        && !permitQ2.get("signature")
                                .equals(permitQ.get("signature")));
        check("expired: fresh permit commits",
                q.receiver.intakePermit(permitQ2)
                        == TransferReceiver.PermitIntakeOutcome.COMMITTED);
        check("expired: derivation recovers to PERMIT_IN",
                deriveReceiver(q)
                        == TransferFlow.ReceiverState.PERMIT_IN);

        // ---- scenario 5: one-shot derivation, both roles ----
        Pair r = newPair();
        TransferSender.ExportResult exr =
                r.sender.exportPackageOneshot(r.seed);
        TransferFlow.Snapshot ss = TransferFlow.derive(r.senderRoot);
        check("oneshot: sender derives FROZEN_ONESHOT with count",
                !ss.loadFailed
                        && ss.sender
                                == TransferFlow.SenderState.FROZEN_ONESHOT
                        && ss.senderOneshot
                        && ss.senderCheckpointCount == 6);
        TransferReceiver.IntakeResult inr =
                r.receiver.intakePackage(exr.packageFile, exr.transferCode);
        check("oneshot: receiver notified derives STAGED_NOTIFIED",
                deriveReceiver(r)
                        == TransferFlow.ReceiverState.STAGED_NOTIFIED);
        r.receiver.confirmOneshot(r.deviceId, 1);
        check("oneshot: receiver derives DONE without an ack",
                deriveReceiver(r) == TransferFlow.ReceiverState.DONE);
        String expectedCode = TransferCommitCode.codeFor(
                TransferCommitCode.deriveKey(exr.transferCode),
                inr.manifestHash);
        TransferFlow.Snapshot rs = TransferFlow.derive(r.receiverRoot);
        check("oneshot-e4: DONE snapshot carries the confirmation number",
                expectedCode != null
                        && expectedCode.equals(rs.receiverCommitCode));
        r.sender.closeOneshotHandover(r.deviceId, expectedCode);
        TransferFlow.Snapshot ss2 = TransferFlow.derive(r.senderRoot);
        check("oneshot: closed sender DONE, no ack re-presentation",
                ss2.sender == TransferFlow.SenderState.DONE
                        && ss2.completedSenderGeneration == -1);
        Pair t = newPair();
        t.sender.exportPackageOneshot(t.seed);
        t.sender.cancelOneshotHandover(t.deviceId);
        check("oneshot: cancelled sender derives CLOSED",
                TransferFlow.derive(t.senderRoot).sender
                        == TransferFlow.SenderState.CLOSED);

        // ---- scenario 6 (audit M2): the unnotified staging's
        // archive count reads the manifest's "count" key ----
        Pair u = newPair();
        writeLegacyArchive(u.senderRoot, u.seed, 3);
        TransferSender.ExportResult exu = u.sender.exportPackage(u.seed);
        u.receiver.stagePackage(exu.packageFile, exu.transferCode);
        TransferFlow.Snapshot us = TransferFlow.derive(u.receiverRoot);
        check("m2: unnotified staging is derived",
                us.unnotifiedStaging != null
                        && us.receiver
                                == TransferFlow.ReceiverState
                                        .STAGED_UNNOTIFIED);
        check("m2: archive count comes from the manifest count key",
                us.unnotifiedStaging != null
                        && us.unnotifiedStaging.archiveCheckpointCount
                                == 3);

        // ---- scenario 7 (audit M7): with two open sender
        // records, the ACTIVE identity's record is classified,
        // not the one whose id sorts first ----
        {
            Pair v = newPair();
            byte[] seedB = new byte[32];
            for (int i = 0; i < 32; i++) seedB[i] = (byte) (0x40 + i);
            String deviceB = DepinCore.deviceIdFromPubkey(
                    Ed25519.publickeyFromSeed(seedB));
            TransferState vst = TransferState.load(v.senderRoot);
            // An open S1 for identity B alongside A's (A's own
            // S1 is opened by the export below).
            Map<String, Object> idB = vst.identityOrCreate(deviceB);
            Map<String, Object> senderB =
                    new java.util.LinkedHashMap<>();
            senderB.put("state", "S1");
            senderB.put("generation", 1L);
            senderB.put("mode", "oneshot");
            Map<String, Object> mfB = new java.util.LinkedHashMap<>();
            mfB.put("checkpoint_count", 4L);
            senderB.put("manifest", mfB);
            senderB.put("issued_permits", new ArrayList<>());
            idB.put("sender", senderB);
            vst.save();
            v.sender.exportPackageOneshot(v.seed);
            // The active binding names A (the export set it).
            // Whichever of A/B sorts later must not matter:
            // force the OTHER order by re-deriving — the
            // classification must follow the ACTIVE identity.
            String larger = v.deviceId.compareTo(deviceB) > 0
                    ? v.deviceId : deviceB;
            TransferFlow.Snapshot vs = TransferFlow.derive(v.senderRoot);
            check("m7: classification follows the active identity",
                    v.deviceId.equals(vs.senderDeviceId)
                            && vs.sender
                                    == TransferFlow.SenderState
                                            .FROZEN_ONESHOT);
            check("m7: fixture really has two open records "
                            + "(active is the later-sorting one: "
                            + larger.equals(v.deviceId) + ")",
                    TransferState.load(v.senderRoot)
                                    .sender(deviceB) != null);
        }

        // ---- scenario 8 (audit M1): a staged track for a
        // DIFFERENT identity than the live one flags the
        // one-shot confirm as blocked, at stage time ----
        {
            Pair w = newPair();
            TransferSender.ExportResult exw =
                    w.sender.exportPackageOneshot(w.seed);
            w.receiver.intakePackage(exw.packageFile, exw.transferCode);
            w.receiver.confirmOneshot(w.deviceId, 1);
            // w's identity is now live on the receiver store.
            Pair x = newPair();
            byte[] seedX = new byte[32];
            for (int i = 0; i < 32; i++) seedX[i] = (byte) (0x60 + i);
            File xRoot = tmpDir("m1-sender");
            writeJournal(xRoot, seedX, 6);
            TransferVault xVault = new TransferVault(xRoot, testWrapKey());
            TransferInit.initialise(xRoot, xVault,
                    TransferInit.MODE_NEW, seedX, RANDOM);
            TransferSender xSender = new TransferSender(
                    xRoot, xVault, RANDOM, "test-app", new TestFreeze(),
                    w.senderFin, w.clock, 24L * 3600, 1000, 100);
            TransferSender.ExportResult exx =
                    xSender.exportPackageOneshot(seedX);
            w.receiver.stagePackage(exx.packageFile, exx.transferCode);
            TransferFlow.Snapshot ws = TransferFlow.derive(w.receiverRoot);
            check("m1: foreign staging flags the one-shot confirm "
                            + "blocked",
                    ws.oneshotBlockedByLiveIdentity);
            check("m1: the live identity is still the active one",
                    w.deviceId.equals(ws.activeDeviceId));
            check("m1: same-identity staging does not flag it",
                    !TransferFlow.derive(w.senderRoot)
                            .oneshotBlockedByLiveIdentity);
        }

        System.out.println("----");
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
