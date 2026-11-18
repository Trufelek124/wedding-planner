package io.github.trufelek124.weddingplanner.signin

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/** What asking the phone for a Google account came to. */
sealed interface GoogleAnswer {

    /** The proof of who signed in, for the server to check. Never logged, never stored. */
    class IdToken(val value: String) : GoogleAnswer {
        override fun toString(): String = "IdToken[hidden]"
    }

    /** The person closed the account picker. */
    data object Cancelled : GoogleAnswer

    /** The phone has no Google account to offer. */
    data object NoAccount : GoogleAnswer

    data object Failed : GoogleAnswer
}

/** The phone's own way of choosing a Google account. Behind an interface because only a phone has it. */
interface GoogleAccounts {

    /** @param serverClientId the OAuth client the server checks the ID token against */
    suspend fun idToken(activity: Activity, serverClientId: String): GoogleAnswer

    /** After a sign-out: the phone must not sign the same account back in by itself. */
    suspend fun forget()
}

class CredentialManagerGoogleAccounts(context: Context) : GoogleAccounts {

    private val application = context.applicationContext

    override suspend fun idToken(activity: Activity, serverClientId: String): GoogleAnswer {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId).build())
            .build()
        return try {
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleAnswer.IdToken(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                GoogleAnswer.Failed
            }
        } catch (cancelled: GetCredentialCancellationException) {
            GoogleAnswer.Cancelled
        } catch (none: NoCredentialException) {
            GoogleAnswer.NoAccount
        } catch (failed: GetCredentialException) {
            GoogleAnswer.Failed
        } catch (unreadable: GoogleIdTokenParsingException) {
            GoogleAnswer.Failed
        }
    }

    override suspend fun forget() {
        try {
            CredentialManager.create(application).clearCredentialState(ClearCredentialStateRequest())
        } catch (notCleared: ClearCredentialException) {
            // Nothing the person can do about it; the next sign-in shows the account picker anyway.
        }
    }
}
