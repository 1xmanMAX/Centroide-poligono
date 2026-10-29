package com.scannerpromax.ai.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Claves de API del usuario, CIFRADAS con una clave AES-GCM del Android Keystore (no extraíble: nunca sale del
 * hardware/TEE). El texto cifrado se guarda en `shared_prefs/cloud_secrets.xml`, EXCLUIDO de las copias de seguridad
 * (res/xml/backup_rules.xml y data_extraction_rules.xml); aunque se copiara, sin la clave del Keystore de este
 * teléfono no se puede descifrar. Nunca se registra la clave en el log.
 */
class CloudKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun has(id: CloudProviderId): Boolean = has(id.name)
    fun get(id: CloudProviderId): String? = get(id.name)
    fun set(id: CloudProviderId, key: String) = set(id.name, key)
    fun remove(id: CloudProviderId) = remove(id.name)

    /** Claves que no son de un [CloudProviderId] (p. ej. [JEV]: Jev de TypeSafe, que no reconoce páginas). */
    fun has(name: String): Boolean = prefs.contains(name)

    fun get(name: String): String? = synchronized(lock) {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, IV_LEN)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, iv))
            String(c.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
        } catch (t: Throwable) {
            // Keystore regenerado (restauración, cambio de bloqueo…): la clave guardada ya no sirve.
            Log.w(TAG, "No se pudo descifrar la clave de $name (${t.javaClass.simpleName}); se borra")
            prefs.edit().remove(name).apply()
            null
        }
    }

    fun set(name: String, key: String) = synchronized(lock) {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, secret())
        val enc = c.doFinal(key.trim().toByteArray(Charsets.UTF_8))
        val iv = c.iv
        require(iv.size == IV_LEN)
        prefs.edit().putString(name, Base64.encodeToString(iv + enc, Base64.NO_WRAP)).apply()
    }

    fun remove(name: String) = synchronized(lock) { prefs.edit().remove(name).apply() }

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    companion object {
        private const val TAG = "CloudKeyStore"
        const val PREFS = "cloud_secrets"
        /** Nombre de la clave de Jev (TypeSafe). */
        const val JEV = "JEV"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "epm_cloud_api_keys"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_LEN = 12

        /** "sk-…abcd" para mostrar sin revelar la clave. */
        fun masked(key: String): String = if (key.length <= 8) "••••" else key.take(3) + "…" + key.takeLast(4)
    }
}
