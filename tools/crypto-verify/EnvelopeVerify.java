// Vault — step-1 crypto verification harness (pure JDK, no Android).
//
// Proves the envelope format and the CTR seek-to-offset math that the real
// Kotlin implementation (app/.../crypto) mirrors. Runs on any JDK 17+:
//
//     javac EnvelopeVerify.java && java EnvelopeVerify
//
// The Android app wraps/unwraps the per-file DEK with a Keystore RSA key.
// That key is unavailable off-device, so here a *software* RSA-2048 keypair
// stands in for it via the same JCE transformation
// (RSA/ECB/OAEPWithSHA-256AndMGF1Padding). Everything else — DEK generation,
// AES-GCM, AES-CTR, header-as-AAD, and the CTR counter arithmetic — is byte-for-byte
// what runs in production, so a pass here is a real proof of the format.

import javax.crypto.*;
import javax.crypto.spec.*;
import java.math.BigInteger;
import java.security.*;
import java.util.Arrays;

public class EnvelopeVerify {

    // ---- format constants (kept in lockstep with EnvelopeCodec.kt) ----
    static final byte FORMAT_VERSION = 1;
    static final byte MODE_GCM = 1;   // images + thumbnails: integrity for free
    static final byte MODE_CTR = 2;   // video: seekable, no integrity
    static final int  WRAPPED_DEK_LEN = 256; // RSA-2048 OAEP output
    static final int  GCM_IV_LEN = 12;       // NIST-recommended 96-bit nonce
    static final int  CTR_IV_LEN = 16;       // full initial counter block
    static final int  GCM_TAG_BITS = 128;
    static final int  DEK_LEN = 32;          // AES-256

    static final SecureRandom RNG = new SecureRandom();
    static int checks = 0;

    public static void main(String[] args) throws Exception {
        KeyPair rsa = genRsa();

        gcmRoundTrip(rsa, 0);
        gcmRoundTrip(rsa, 1);
        gcmRoundTrip(rsa, 1024);
        gcmRoundTrip(rsa, 5 * 1024 * 1024);
        gcmHeaderIsAuthenticated(rsa);
        gcmCiphertextTamperFails(rsa);

        ctrRoundTrip(rsa, 100 * 1024 * 1024); // ~100 MB "video"
        ctrSeekToOffset(rsa);
        ctrSeekAlignedAndAcrossBlocks(rsa);

        System.out.println("\nALL " + checks + " CHECKS PASSED");
    }

    // ---------- envelope: encrypt ----------
    static byte[] encrypt(KeyPair rsa, byte mode, byte[] plaintext) throws Exception {
        byte[] dek = new byte[DEK_LEN];
        RNG.nextBytes(dek);
        byte[] wrapped = wrapDek(rsa.getPublic(), dek);

        int ivLen = (mode == MODE_GCM) ? GCM_IV_LEN : CTR_IV_LEN;
        byte[] iv = new byte[ivLen];
        RNG.nextBytes(iv);

        byte[] header = buildHeader(mode, wrapped, iv); // version+mode+wrapped+iv

        Cipher c;
        if (mode == MODE_GCM) {
            c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            c.updateAAD(header); // bind version+mode+wrapped-DEK+iv into the tag
        } else {
            c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), new IvParameterSpec(iv));
        }
        byte[] body = c.doFinal(plaintext);
        Arrays.fill(dek, (byte) 0);

        byte[] out = new byte[header.length + body.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(body, 0, out, header.length, body.length);
        return out;
    }

    // ---------- envelope: full decrypt (GCM or CTR) ----------
    static byte[] decrypt(KeyPair rsa, byte[] file) throws Exception {
        byte mode = file[1];
        int ivLen = (mode == MODE_GCM) ? GCM_IV_LEN : CTR_IV_LEN;
        int headerLen = 2 + WRAPPED_DEK_LEN + ivLen;

        byte[] wrapped = Arrays.copyOfRange(file, 2, 2 + WRAPPED_DEK_LEN);
        byte[] iv = Arrays.copyOfRange(file, 2 + WRAPPED_DEK_LEN, headerLen);
        byte[] body = Arrays.copyOfRange(file, headerLen, file.length);
        byte[] dek = unwrapDek(rsa.getPrivate(), wrapped);

        Cipher c;
        if (mode == MODE_GCM) {
            c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            c.updateAAD(Arrays.copyOfRange(file, 0, headerLen));
        } else {
            c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), new IvParameterSpec(iv));
        }
        byte[] out = c.doFinal(body);
        Arrays.fill(dek, (byte) 0);
        return out;
    }

    // ---------- the important one: CTR random-access decrypt ----------
    // Mirrors Media3's AesCipherDataSource / AesFlushingCipher so ExoPlayer
    // scrubbing lands on the same keystream: counter = IV + (offset / 16),
    // then discard (offset % 16) keystream bytes to realign to the byte offset.
    static byte[] ctrDecryptWindow(KeyPair rsa, byte[] file, long offset, int len) throws Exception {
        int headerLen = 2 + WRAPPED_DEK_LEN + CTR_IV_LEN;
        byte[] wrapped = Arrays.copyOfRange(file, 2, 2 + WRAPPED_DEK_LEN);
        byte[] iv = Arrays.copyOfRange(file, 2 + WRAPPED_DEK_LEN, headerLen);
        byte[] dek = unwrapDek(rsa.getPrivate(), wrapped);

        long blockIndex = offset / 16;
        int  skip = (int) (offset % 16);
        byte[] counter = addToCounter(iv, blockIndex);

        Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), new IvParameterSpec(counter));
        if (skip > 0) c.update(new byte[skip]); // consume partial-block keystream

        int from = (int) (headerLen + offset);
        byte[] slice = Arrays.copyOfRange(file, from, from + len);
        byte[] out = c.update(slice);
        Arrays.fill(dek, (byte) 0);
        return out;
    }

    // counter = (iv_as_128bit_bigint + delta) mod 2^128, big-endian, 16 bytes
    static byte[] addToCounter(byte[] iv16, long delta) {
        BigInteger max = BigInteger.ONE.shiftLeft(128);
        BigInteger v = new BigInteger(1, iv16).add(BigInteger.valueOf(delta)).mod(max);
        byte[] raw = v.toByteArray();
        byte[] out = new byte[16];
        if (raw.length <= 16) {
            System.arraycopy(raw, 0, out, 16 - raw.length, raw.length);
        } else { // leading sign byte
            System.arraycopy(raw, raw.length - 16, out, 0, 16);
        }
        return out;
    }

    // ---------- RSA wrap/unwrap (software stand-in for the Keystore key) ----------
    static byte[] wrapDek(PublicKey pub, byte[] dek) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        c.init(Cipher.ENCRYPT_MODE, pub);
        return c.doFinal(dek);
    }
    static byte[] unwrapDek(PrivateKey priv, byte[] wrapped) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        c.init(Cipher.DECRYPT_MODE, priv);
        return c.doFinal(wrapped);
    }

    static byte[] buildHeader(byte mode, byte[] wrapped, byte[] iv) {
        byte[] h = new byte[2 + WRAPPED_DEK_LEN + iv.length];
        h[0] = FORMAT_VERSION;
        h[1] = mode;
        System.arraycopy(wrapped, 0, h, 2, WRAPPED_DEK_LEN);
        System.arraycopy(iv, 0, h, 2 + WRAPPED_DEK_LEN, iv.length);
        return h;
    }

    static KeyPair genRsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    // ---------------- test cases ----------------
    static void gcmRoundTrip(KeyPair rsa, int size) throws Exception {
        byte[] pt = rand(size);
        byte[] file = encrypt(rsa, MODE_GCM, pt);
        require(file[0] == FORMAT_VERSION && file[1] == MODE_GCM, "gcm header bytes");
        require(Arrays.equals(decrypt(rsa, file), pt), "gcm round-trip size=" + size);
    }

    static void gcmHeaderIsAuthenticated(KeyPair rsa) throws Exception {
        byte[] file = encrypt(rsa, MODE_GCM, rand(4096));
        file[5] ^= 0x01; // flip a byte inside the wrapped-DEK region (part of AAD)
        boolean threw = false;
        try { decrypt(rsa, file); } catch (Exception e) { threw = true; }
        // Note: mutating the wrapped DEK can fail either at RSA unwrap or the GCM tag;
        // both are rejections. What matters: a tampered header never decrypts.
        require(threw, "gcm header tamper rejected");
    }

    static void gcmCiphertextTamperFails(KeyPair rsa) throws Exception {
        byte[] file = encrypt(rsa, MODE_GCM, rand(4096));
        file[file.length - 1] ^= 0x01; // flip a ciphertext/tag byte
        boolean threw = false;
        try { decrypt(rsa, file); } catch (AEADBadTagException e) { threw = true; }
        require(threw, "gcm ciphertext tamper -> bad tag");
    }

    static void ctrRoundTrip(KeyPair rsa, int size) throws Exception {
        byte[] pt = rand(size);
        byte[] file = encrypt(rsa, MODE_CTR, pt);
        require(file[1] == MODE_CTR, "ctr header mode");
        require(Arrays.equals(decrypt(rsa, file), pt), "ctr full round-trip size=" + size);
    }

    static void ctrSeekToOffset(KeyPair rsa) throws Exception {
        byte[] pt = rand(100 * 1024 * 1024);
        byte[] file = encrypt(rsa, MODE_CTR, pt);
        int window = 64 * 1024;
        for (int i = 0; i < 200; i++) {
            long offset = (Math.abs(RNG.nextLong()) % (pt.length - window));
            byte[] got = ctrDecryptWindow(rsa, file, offset, window);
            byte[] want = Arrays.copyOfRange(pt, (int) offset, (int) offset + window);
            require(Arrays.equals(got, want), "ctr seek offset=" + offset);
        }
    }

    // explicit block-aligned, +1, and near-end offsets — the ones that expose off-by-one
    static void ctrSeekAlignedAndAcrossBlocks(KeyPair rsa) throws Exception {
        byte[] pt = rand(1 << 20);
        byte[] file = encrypt(rsa, MODE_CTR, pt);
        long[] offsets = { 0, 1, 15, 16, 17, 31, 32, 4095, 4096, 4097, pt.length - 16, pt.length - 1 };
        for (long off : offsets) {
            int len = (int) Math.min(4096, pt.length - off);
            byte[] got = ctrDecryptWindow(rsa, file, off, len);
            byte[] want = Arrays.copyOfRange(pt, (int) off, (int) off + len);
            require(Arrays.equals(got, want), "ctr boundary offset=" + off);
        }
    }

    // ---------------- helpers ----------------
    static byte[] rand(int n) { byte[] b = new byte[n]; RNG.nextBytes(b); return b; }
    static void require(boolean cond, String label) {
        checks++;
        if (!cond) throw new AssertionError("FAILED: " + label);
        System.out.println("  ok  " + label);
    }
}
