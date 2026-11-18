package io.github.trufelek124.weddingplanner.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Makes the refresh token unreadable before it is written to the phone's storage, and readable again. */
interface TokenCipher {

    fun encrypt(plain: String): String

    /** Null when it cannot be read: another key, or something that is no token of ours. */
    fun decrypt(stored: String): String?
}

/**
 * AES-GCM with a key that is asked for each time. What is stored is the nonce followed by the
 * encrypted token, as text; a fresh nonce is used for every encryption.
 */
class AesGcmCipher(private val key: () -> SecretKey) : TokenCipher {

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val nonce = cipher.iv
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(byteArrayOf(nonce.size.toByte()) + nonce + sealed)
    }

    override fun decrypt(stored: String): String? =
        try {
            val bytes = Base64.getDecoder().decode(stored)
            val nonceLength = bytes[0].toInt()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 1, nonceLength))
            String(cipher.doFinal(bytes, 1 + nonceLength, bytes.size - 1 - nonceLength), Charsets.UTF_8)
        } catch (unreadable: GeneralSecurityException) {
            null
        } catch (unreadable: IllegalArgumentException) {
            null
        } catch (unreadable: IndexOutOfBoundsException) {
            null
        }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}

/**
 * The key lives in the Android Keystore: it never leaves the phone's secure hardware, and it is not
 * part of a backup. A phone that was restored from another one has no such key, cannot read the
 * stored token, and asks for the sign-in again.
 */
object KeystoreKey {

    private const val STORE = "AndroidKeyStore"
    private const val ALIAS = "wedding-planner-session"

    fun get(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
