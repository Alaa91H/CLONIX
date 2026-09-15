package com.clonix.app;

import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Password encryption for archives (Neo-style, offline, no dependencies).
 * AES-256-GCM + PBKDF2-HMAC-SHA256, fully streaming (no OOM on big apps).
 *
 * Format: magic "CPB1" + iter(int BE) + salt(16B) + iv(12B) + ciphertext.
 * Password lives ONLY in memory for the session (never persisted);
 * wrong password surfaces as a SecurityException on decrypt (GCM tag).
 *
 * Why Java instead of `openssl enc`: the device has no openssl binary.
 * Root produces/consumes the tar stream; Java encrypts/decrypts mid-pipe.
 */
public final class CryptoVault {
    private CryptoVault() {}

    private static final byte[] MAGIC = {'C', 'P', 'B', '1'};
    private static final int ITER = 65536;
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;
    public static final int MIN_PW = 4;

    // ---------------- session password (memory only) ----------------

    private static volatile char[] sessionPw = null;

    public static synchronized void setSessionPassword(char[] pw) {
        clearSessionPassword();
        sessionPw = pw != null ? Arrays.copyOf(pw, pw.length) : null;
    }

    public static synchronized char[] sessionPassword() {
        return sessionPw != null ? Arrays.copyOf(sessionPw, sessionPw.length) : null;
    }

    public static synchronized boolean hasSessionPassword() {
        return sessionPw != null && sessionPw.length >= MIN_PW;
    }

    public static synchronized void clearSessionPassword() {
        if (sessionPw != null) {
            Arrays.fill(sessionPw, '\0');
            sessionPw = null;
        }
    }

    public static void checkPassword(char[] pw) throws Exception {
        if (pw == null || pw.length < MIN_PW) {
            throw new Exception("password too short (min " + MIN_PW + ")");
        }
    }

    // ---------------- primitives ----------------

    private static SecretKeySpec derive(char[] pw, byte[] salt, int iter)
            throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pw, salt, iter, KEY_BITS);
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] key = f.generateSecret(spec).getEncoded();
            return new SecretKeySpec(key, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static void writeInt(OutputStream o, int v) throws Exception {
        o.write((v >>> 24) & 0xFF);
        o.write((v >>> 16) & 0xFF);
        o.write((v >>> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    private static int readInt(InputStream in) throws Exception {
        int a = in.read(), b = in.read(), c = in.read(), d = in.read();
        if ((a | b | c | d) < 0) throw new EOFException("short header");
        return (a << 24) | (b << 16) | (c << 8) | d;
    }

    private static void readFully(InputStream in, byte[] buf) throws Exception {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new EOFException("short header");
            off += n;
        }
    }

    /** Encrypt plain stream -> out (header + ciphertext). Caller closes streams. */
    public static void encryptStream(InputStream plain, OutputStream out, char[] pw)
            throws Exception {
        checkPassword(pw);
        SecureRandom rnd = new SecureRandom();
        byte[] salt = new byte[SALT_LEN];
        byte[] iv = new byte[IV_LEN];
        rnd.nextBytes(salt);
        rnd.nextBytes(iv);
        SecretKeySpec key = derive(pw, salt, ITER);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        out.write(MAGIC);
        writeInt(out, ITER);
        out.write(salt);
        out.write(iv);
        CipherOutputStream cos = new CipherOutputStream(out, cipher);
        byte[] buf = new byte[65536];
        int n;
        while ((n = plain.read(buf)) > 0) cos.write(buf, 0, n);
        cos.flush();
        // CipherOutputStream.close() writes the GCM tag: close ONLY the wrapper
        // would close `out` too — acceptable: encryption output is complete here.
        cos.close();
    }

    /**
     * Decrypt archive stream -> plain out. Throws SecurityException on wrong
     * password / tampered data (GCM tag), EOFException on truncated files.
     */
    public static void decryptStream(InputStream in, OutputStream out, char[] pw)
            throws Exception {
        checkPassword(pw);
        byte[] magic = new byte[4];
        readFully(in, magic);
        if (magic[0] != 'C' || magic[1] != 'P' || magic[2] != 'B' || magic[3] != '1') {
            throw new Exception("not an encrypted CLONIX archive");
        }
        int iter = readInt(in);
        if (iter < 1000 || iter > 5000000) throw new Exception("bad header");
        byte[] salt = new byte[SALT_LEN];
        byte[] iv = new byte[IV_LEN];
        readFully(in, salt);
        readFully(in, iv);
        SecretKeySpec key = derive(pw, salt, iter);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        CipherInputStream cis = new CipherInputStream(in, cipher);
        byte[] buf = new byte[65536];
        try {
            int n;
            while ((n = cis.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } catch (java.io.IOException ioe) {
            Throwable cause = ioe.getCause();
            if (cause instanceof javax.crypto.AEADBadTagException) {
                throw new SecurityException("wrong password or corrupted data");
            }
            // CipherInputStream wraps tag failures as IOException("...Tag mismatch...")
            String m = String.valueOf(ioe.getMessage());
            if (m.contains("Tag mismatch") || m.contains("mac check")) {
                throw new SecurityException("wrong password or corrupted data");
            }
            throw ioe;
        }
    }

    /** SHA-256 hex of a stream (for .sha256 sidecars without extra passes). */
    public static String sha256Hex(InputStream in) throws Exception {
        java.security.MessageDigest md =
            java.security.MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        byte[] h = md.digest();
        StringBuilder sb = new StringBuilder(h.length * 2);
        for (byte x : h) sb.append(Character.forDigit((x >> 4) & 0xF, 16))
            .append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }
}
