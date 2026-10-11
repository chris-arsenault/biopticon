package io.ahara.biopticon.probe

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class Authentication(private val context: Context) {
    private val prefs = context.getSharedPreferences("delivery", Context.MODE_PRIVATE)
    val service = AuthorizationService(context)
    var api: String get() = prefs.getString("api", "https://api.biopticon.ahara.io")!!; set(value) { prefs.edit().putString("api", value.trimEnd('/')).apply() }
    var oauth: String get() = prefs.getString("oauth", "")!!; set(value) { prefs.edit().putString("oauth", value.trimEnd('/')).apply() }
    var client: String get() = prefs.getString("client", "")!!; set(value) { prefs.edit().putString("client", value).apply() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("probe-auth", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("probe-auth", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun state(): AuthState? = runCatching { prefs.getString("state", null)?.let { encoded ->
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        AuthState.jsonDeserialize(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    } }.getOrElse { prefs.edit().remove("state").putString("status", "Sign in again: saved session unavailable").apply(); null }

    private fun save(state: AuthState) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.iv + cipher.doFinal(state.jsonSerializeString().toByteArray(Charsets.UTF_8))
        prefs.edit().putString("state", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    fun login() {
        val uri = java.net.URI(oauth)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null && client.isNotBlank()) { "Configure OAuth URL and client ID first" }
        val config = AuthorizationServiceConfiguration(Uri.parse("$oauth/oauth2/authorize"), Uri.parse("$oauth/oauth2/token"))
        val request = AuthorizationRequest.Builder(config, client, ResponseTypeValues.CODE, Uri.parse("io.ahara.biopticon.probe:/oauth2redirect"))
            .setScope("openid profile email").build()
        val callback = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).setAction("AUTH_RESULT"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        service.performAuthorizationRequest(request, callback)
    }

    suspend fun complete(intent: Intent): Boolean = suspendCancellableCoroutine { continuation ->
        val response = AuthorizationResponse.fromIntent(intent)
        val exception = AuthorizationException.fromIntent(intent)
        if (response == null || exception != null) { continuation.resume(false); return@suspendCancellableCoroutine }
        val state = AuthState(response, null)
        service.performTokenRequest(response.createTokenExchangeRequest()) { token, error ->
            if (token != null && error == null) { state.update(token, null); save(state); if (continuation.isActive) continuation.resume(true) }
            else if (continuation.isActive) continuation.resume(false)
        }
    }

    suspend fun token(): String? {
        val state = state() ?: return null
        return suspendCancellableCoroutine { continuation ->
            state.performActionWithFreshTokens(service) { access, _, error ->
                save(state)
                if (continuation.isActive) continuation.resume(if (error == null) access else null)
            }
        }
    }

    fun logout() { prefs.edit().remove("state").apply() }
}
