package app.quantusnode.node.depin.core;

import java.nio.charset.StandardCharsets;

/**
 * The one-shot handover's confirmation number (design
 * consultation E4): an 8-digit decimal code that binds the
 * sender's seed erasure to the receiver's completed intake,
 * without any file travelling back.
 *
 *   key  = SHA-256(transfer code, UTF-8)
 *   code = the first 8 bytes of
 *          HMAC-SHA256(key, "easy-commit" || manifest hash),
 *          accumulated into a SIGNED long (big-endian, sign
 *          bit included) and reduced with floorMod 10^8,
 *          zero-padded to 8 digits
 *
 * (Audit Low, doc aligned to the implementation: an earlier
 * wording said "unsigned"; the accumulation has always been
 * the signed-long form below. The arithmetic is NOT changed
 * — both apps share this implementation, and changing it
 * would silently renumber every in-flight handover between
 * builds.)
 *
 * The manifest hash is the IntakeResult's: SHA-256 over the
 * JCS of the manifest map, hex — a value both sides derive
 * from the package itself (nothing new is stored in the
 * package; the format is unchanged).
 *
 * The receiver computes the number from the code the user
 * typed and shows it ONLY after its confirm completes. The
 * sender never stores the code itself: at export it stores
 * the DERIVED KEY (a one-way derivative; the code cannot be
 * recovered from it) in its vault next to the generation's
 * other material, and erasure requires the user to type the
 * number the receiver displayed, which the sender recomputes
 * and compares. The person carries 8 digits; the transfer
 * code itself never travels back.
 *
 * v4.2.4 (audit L-1): the key above is the v1 derivation, and
 * it made the number a fast offline test for the transfer code.
 * Packages are now marked "commit_v": 2 in their manifest and
 * keyed by {@link #deriveKeyFromConfirmSecret} instead — the
 * number's arithmetic is unchanged, only its key. A package
 * without the mark (from an older sender) keeps the v1 number,
 * so handovers already in flight between builds still verify.
 */
public final class TransferCommitCode {
    private TransferCommitCode() {}

    /** The HMAC key derived from a canonical transfer code. */
    public static byte[] deriveKey(String transferCode) {
        return DepinCore.sha256(
                transferCode.getBytes(StandardCharsets.UTF_8));
    }

    /** The v2 derivation's domain label (v4.2.4, audit L-1). */
    private static final byte[] V2_LABEL =
            "easy-commit/v2".getBytes(StandardCharsets.UTF_8);

    /**
     * The v2 HMAC key (v4.2.4, audit L-1): SHA-256("easy-commit/v2"
     * || confirm_secret), from the 128-bit secret both sides already
     * hold — the sender in its vault entry for the generation, the
     * receiver wrapped in its staging record. The v1 key, a plain
     * SHA-256 of the transfer code, made the 8-digit number a fast
     * offline test for the code: anyone holding a package and its
     * number could check a candidate code with one hash and one HMAC
     * instead of the 600,000-iteration PBKDF2 that guards the
     * package. Without the confirm secret — which only an opened
     * package yields — the v2 number says nothing about the code.
     */
    public static byte[] deriveKeyFromConfirmSecret(byte[] confirmSecret) {
        byte[] msg = new byte[V2_LABEL.length + confirmSecret.length];
        System.arraycopy(V2_LABEL, 0, msg, 0, V2_LABEL.length);
        System.arraycopy(confirmSecret, 0, msg, V2_LABEL.length,
                confirmSecret.length);
        try {
            return DepinCore.sha256(msg);
        } finally {
            java.util.Arrays.fill(msg, (byte) 0);
        }
    }

    /** The manifest field that marks a v2 package (v4.2.4). */
    public static final String MANIFEST_COMMIT_VERSION = "commit_v";

    /**
     * Which derivation a package's number uses: 2 when its manifest
     * carries "commit_v": 2 (every package since v4.2.4), 1 when the
     * field is absent (a package from an older sender, whose number
     * is still derived from the transfer code).
     */
    public static int commitVersionOf(java.util.Map<String, Object> manifest) {
        Object v = manifest == null ? null
                : manifest.get(MANIFEST_COMMIT_VERSION);
        return (v instanceof Long && (Long) v == 2L) ? 2 : 1;
    }

    /** The 8-digit confirmation number for a derived key and
     *  a manifest hash (hex, as IntakeResult.manifestHash). */
    public static String codeFor(byte[] derivedKey,
                                 String manifestHashHex) {
        byte[] label = "easy-commit".getBytes(StandardCharsets.UTF_8);
        byte[] hash = Hex.decode(manifestHashHex);
        byte[] msg = new byte[label.length + hash.length];
        System.arraycopy(label, 0, msg, 0, label.length);
        System.arraycopy(hash, 0, msg, label.length, hash.length);
        byte[] mac;
        try {
            mac = TransferCodec.hmacSha256(derivedKey, msg);
        } catch (java.security.GeneralSecurityException e) {
            // HmacSHA256 is a mandatory JCA algorithm; its
            // absence is a platform defect, not a data error.
            throw new IllegalStateException("HMAC unavailable", e);
        }
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (mac[i] & 0xffL);
        }
        long digits = Math.floorMod(v, 100_000_000L);
        String s = Long.toString(digits);
        StringBuilder sb = new StringBuilder(8);
        for (int i = s.length(); i < 8; i++) {
            sb.append('0');
        }
        return sb.append(s).toString();
    }

    /** The manifest hash both sides derive from a manifest
     *  map: SHA-256 over its JCS form, hex. */
    public static String manifestHash(
            java.util.Map<String, Object> manifest) {
        return Hex.encode(DepinCore.sha256(
                TransferCodec.jcs(manifest)));
    }
}
