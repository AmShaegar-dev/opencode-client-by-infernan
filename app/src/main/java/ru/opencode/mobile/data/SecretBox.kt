package ru.opencode.mobile.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES/GCM-шифрование локальных данных ключом из Android Keystore.
 * Ключ не покидает устройство, поэтому файл SharedPreferences бесполезен
 * при копировании на другое устройство.
 */
object SecretBox {

    private const val ALIAS = "opencode_mobile_secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val packed = ByteArray(1 + iv.size + encrypted.size)
        packed[0] = iv.size.toByte()
        iv.copyInto(packed, 1)
        encrypted.copyInto(packed, 1 + iv.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    fun decrypt(data: String): String {
        val packed = Base64.decode(data, Base64.NO_WRAP)
        if (packed.isEmpty()) return ""
        val ivSize = packed[0].toInt()
        require(ivSize in 12..32 && packed.size > 1 + ivSize) { "Повреждённые данные" }
        val iv = packed.copyOfRange(1, 1 + ivSize)
        val encrypted = packed.copyOfRange(1 + ivSize, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
