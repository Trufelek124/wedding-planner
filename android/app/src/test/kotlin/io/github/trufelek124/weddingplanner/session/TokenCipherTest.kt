package io.github.trufelek124.weddingplanner.session

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The sealing itself, with a key made here; on a phone the key comes from the Android Keystore. */
class TokenCipherTest {

    private fun aKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = aKey()
    private val cipher = AesGcmCipher { key }

    @Test
    fun `what was sealed is read again, and is not readable in between`() {
        val sealed = cipher.encrypt("refresh-token-of-anna")

        assertFalse(sealed.contains("refresh"))
        assertEquals("refresh-token-of-anna", cipher.decrypt(sealed))
    }

    @Test
    fun `the same token is sealed differently each time`() {
        assertNotEquals(cipher.encrypt("refresh-1"), cipher.encrypt("refresh-1"))
    }

    @Test
    fun `what was changed, what is no token of ours and what another key sealed cannot be read`() {
        val sealed = cipher.encrypt("refresh-1")
        val changed = sealed.dropLast(4) + (if (sealed.endsWith("AAAA")) "BBBB" else "AAAA")

        assertNull(cipher.decrypt(changed))
        assertNull(cipher.decrypt("not a sealed token"))
        assertNull(cipher.decrypt(""))
        assertNull(AesGcmCipher { aKey() }.decrypt(sealed))
    }
}
