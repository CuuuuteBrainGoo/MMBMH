package com.bro.lotteryledger.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 安全存储（§1.2）。
 *
 * 要求：API Key 用 Android 系统安全存储，不明文写进普通配置或数据库。
 *
 * 做法：AES-256-GCM，密钥本体由 Android Keystore 持有（永不出安全硬件/系统），
 * 数据库里只存 `iv + ciphertext` 的 Base64。
 * 换手机后 Keystore 密钥不存在 → 密文解不开 → 用户重填（符合 §17）。
 */
class ApiKeyStore(private val context: Context) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "lottery_ledger_api_key_v1"
        private const val PREFS = "secure_api_keys"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_LENGTH = 12
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 刻意不要求用户认证：账本 App 每次识别都要用，加锁会烦到没法用。
                // 安全性依靠 Keystore 不导出密钥 + 密文存储，已满足交接稿要求。
                .setUserAuthenticationRequired(false)
                .build()
        )
        return gen.generateKey()
    }

    /** 存入一个 API Key，返回引用名（用于写进 ai_providers.api_key_ref）。 */
    fun put(ref: String, apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val iv = cipher.iv
        val ct = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, blob, 0, iv.size)
        System.arraycopy(ct, 0, blob, iv.size, ct.size)

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ref, Base64.encodeToString(blob, Base64.NO_WRAP))
            .apply()
    }

    /** 取出 API Key；不存在或解密失败返回 null。 */
    fun get(ref: String): String? {
        val b64 = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ref, null)
            ?: return null
        return try {
            val blob = Base64.decode(b64, Base64.NO_WRAP)
            if (blob.size <= IV_LENGTH) return null
            val iv = blob.copyOfRange(0, IV_LENGTH)
            val ct = blob.copyOfRange(IV_LENGTH, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun remove(ref: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(ref).apply()
    }

    fun has(ref: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(ref)
}
