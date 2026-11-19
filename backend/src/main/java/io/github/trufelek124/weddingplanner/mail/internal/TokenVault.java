package io.github.trufelek124.weddingplanner.mail.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Seals what must rest in the database but must not be readable from a copy of it: AES-256-GCM under a key that is
 * given to the server from outside. Every sealing uses a fresh random nonce, and what it is sealed for (the owner's
 * row) is authenticated with it, so that a sealed token moved to another row does not open.
 *
 * <p>What comes out is one version byte, the 12-byte nonce, then the ciphertext with its tag. The version byte lets
 * the key be changed later without guessing which key sealed what.
 *
 * <p>It does this and nothing else: it never logs, and nothing it throws or prints holds the key or the text.
 */
final class TokenVault {

    static final int KEY_BYTES = 32;
    static final byte VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    TokenVault(byte[] key) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalArgumentException("a key is 32 bytes");
        }
        this.key = new SecretKeySpec(key.clone(), "AES");
    }

    /** What the owner's row stands for: the token is sealed for this member and no other. */
    static byte[] memberAad(UUID memberId) {
        return ByteBuffer.allocate(16)
                .putLong(memberId.getMostSignificantBits())
                .putLong(memberId.getLeastSignificantBits())
                .array();
    }

    byte[] seal(String plain, byte[] additionalData) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(additionalData);
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + NONCE_BYTES + sealed.length)
                    .put(VERSION)
                    .put(nonce)
                    .put(sealed)
                    .array();
        } catch (GeneralSecurityException e) {
            // Nothing of the cause: it could only say things about the key or the text.
            throw new IllegalStateException("sealing failed");
        }
    }

    /**
     * @return the text, or nothing when this vault did not seal it for these additional data: another key, another
     *     row, bytes that were changed, a version it does not know
     */
    Optional<String> open(byte[] sealed, byte[] additionalData) {
        if (sealed == null || sealed.length < 1 + NONCE_BYTES + TAG_BITS / 8 || sealed[0] != VERSION) {
            return Optional.empty();
        }
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(
                    Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(sealed, 1, 1 + NONCE_BYTES)));
            cipher.updateAAD(additionalData);
            byte[] plain = cipher.doFinal(sealed, 1 + NONCE_BYTES, sealed.length - 1 - NONCE_BYTES);
            return Optional.of(new String(plain, StandardCharsets.UTF_8));
        } catch (GeneralSecurityException | RuntimeException cannotOpen) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return "TokenVault[]";
    }
}
