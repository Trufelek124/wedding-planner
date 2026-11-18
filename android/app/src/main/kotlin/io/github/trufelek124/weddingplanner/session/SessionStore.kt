package io.github.trufelek124.weddingplanner.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/** Who is signed in on this phone. The name is for "Signed in as …"; the address is not kept. */
data class SignedInUser(val id: String, val displayName: String) {

    /** A name is personal data: what gets printed is only which user it is. */
    override fun toString(): String = "SignedInUser[id=$id]"
}

/** What the phone remembers between starts: who is signed in, and the token that renews the session. */
data class StoredSession(val user: SignedInUser, val refreshToken: String) {

    override fun toString(): String = "StoredSession[user=${user.id}]"
}

/**
 * Keeps the session on the phone. The refresh token is written encrypted; the access token is never
 * written at all.
 */
class SessionStore(private val store: DataStore<Preferences>, private val cipher: TokenCipher) {

    suspend fun read(): StoredSession? {
        val saved = store.data.first()
        val id = saved[USER_ID] ?: return null
        val name = saved[USER_NAME] ?: return null
        // A token that cannot be read is as good as none: the phone asks for the sign-in again.
        val token = saved[REFRESH_TOKEN]?.let(cipher::decrypt) ?: return null
        return StoredSession(SignedInUser(id, name), token)
    }

    suspend fun write(session: StoredSession) {
        val sealed = cipher.encrypt(session.refreshToken)
        store.edit { saved ->
            saved[USER_ID] = session.user.id
            saved[USER_NAME] = session.user.displayName
            saved[REFRESH_TOKEN] = sealed
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        val USER_ID = stringPreferencesKey("user_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    }
}
