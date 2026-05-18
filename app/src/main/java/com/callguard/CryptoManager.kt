package com.callguard

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoManager {
    private const val TRANSFORM = "AES/CBC/PKCS5Padding"

    fun encrypt(data: String, password: String): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv   = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key  = deriveKey(password, salt)
        val c    = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return salt + iv + c.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    fun decrypt(data: ByteArray, password: String): String? = try {
        val key = deriveKey(password, data.copyOfRange(0, 16))
        val c   = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
               IvParameterSpec(data.copyOfRange(16, 32)))
        String(c.doFinal(data.copyOfRange(32, data.size)), Charsets.UTF_8)
    } catch (e: Exception) { null }

    private fun deriveKey(pw: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pw.toCharArray(), salt, 65536, 256)).encoded

    fun hashPassword(pw: String): String =
        MessageDigest.getInstance("SHA-256").digest(pw.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
