package cz.mares.zdravipm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

private class IosCredentialBridge : CredentialBridge {
    override val isSupported: Boolean = false
    override suspend fun saveCredentials(username: String, secret: String) = Unit
    override suspend fun loadCredentials(): Pair<String, String>? = null
}

@Composable
actual fun rememberCredentialBridge(): CredentialBridge = remember { IosCredentialBridge() }
