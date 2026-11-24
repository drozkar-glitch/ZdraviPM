package cz.mares.zdravipm

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return ctx as? Activity
}

private class AndroidCredentialBridge(private val activity: Activity?) : CredentialBridge {
    override val isSupported: Boolean = activity != null

    override suspend fun saveCredentials(username: String, secret: String) {
        val act = activity ?: return
        if (username.isBlank() || secret.isBlank()) return
        try {
            val cm = CredentialManager.create(act)
            cm.createCredential(act, CreatePasswordRequest(id = username, password = secret))
        } catch (_: Throwable) {
        }
    }

    override suspend fun loadCredentials(): Pair<String, String>? {
        val act = activity ?: return null
        return try {
            val cm = CredentialManager.create(act)
            val result = cm.getCredential(act, GetCredentialRequest(listOf(GetPasswordOption())))
            val cred = result.credential
            if (cred is PasswordCredential) cred.id to cred.password else null
        } catch (_: Throwable) {
            null
        }
    }
}

@Composable
actual fun rememberCredentialBridge(): CredentialBridge {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    return remember(activity) { AndroidCredentialBridge(activity) }
}
