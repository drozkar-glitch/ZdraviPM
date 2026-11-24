package cz.mares.zdravipm

import androidx.compose.runtime.Composable

interface CredentialBridge {
    val isSupported: Boolean
    suspend fun saveCredentials(username: String, secret: String)
    suspend fun loadCredentials(): Pair<String, String>?
}

@Composable
expect fun rememberCredentialBridge(): CredentialBridge
