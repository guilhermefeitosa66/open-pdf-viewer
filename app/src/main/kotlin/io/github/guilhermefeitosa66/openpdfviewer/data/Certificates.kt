package io.github.guilhermefeitosa66.openpdfviewer.data

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A certificate the user imported, as shown in the list. */
data class StoredCertificate(val id: String, val name: String, val issuer: String, val notAfter: Long)

/**
 * Personal certificates (.p12 / .pfx) for digital signatures. The file is kept encrypted with an
 * AES key that lives in the Android Keystore (it never leaves the device's secure hardware when
 * there is one); the file's password is never stored and is asked for each signature.
 */
class Certificates(context: Context) {
    private val dir = File(context.filesDir, "certs")

    class WrongPassword : Exception()
    class NoKey : Exception()

    /** Checks the password, then stores the encrypted file. */
    suspend fun import(context: Context, uri: Uri, password: CharArray): StoredCertificate = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw NoKey()
        val (_, chain) = open(bytes, password)
        val cert = chain.first()
        val id = "cert-" + System.currentTimeMillis()
        dir.mkdirs()
        File(dir, "$id.p12.enc").writeBytes(encrypt(bytes))
        val meta = StoredCertificate(id, commonName(cert), commonName(cert, issuer = true), cert.notAfter.time)
        File(dir, "$id.json").writeText(
            JSONObject().put("name", meta.name).put("issuer", meta.issuer).put("notAfter", meta.notAfter).toString()
        )
        meta
    }

    suspend fun list(): List<StoredCertificate> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.name.endsWith(".json") }?.mapNotNull { f ->
            runCatching {
                val o = JSONObject(f.readText())
                StoredCertificate(f.name.removeSuffix(".json"), o.optString("name"), o.optString("issuer"), o.optLong("notAfter"))
            }.getOrNull()
        }?.sortedBy { it.name }.orEmpty()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
        File(dir, "$id.p12.enc").delete()
    }

    /** The private key and certificate chain, unlocked with the file's password. */
    suspend fun unlock(id: String, password: CharArray): Pair<PrivateKey, List<X509Certificate>> = withContext(Dispatchers.IO) {
        val enc = File(dir, "$id.p12.enc")
        if (!enc.exists()) throw NoKey()
        open(decrypt(enc.readBytes()), password)
    }

    private fun open(bytes: ByteArray, password: CharArray): Pair<PrivateKey, List<X509Certificate>> {
        val ks = KeyStore.getInstance("PKCS12")
        try {
            ks.load(ByteArrayInputStream(bytes), password)
        } catch (e: Exception) {
            throw WrongPassword()
        }
        for (alias in ks.aliases()) {
            if (!ks.isKeyEntry(alias)) continue
            val key = ks.getKey(alias, password) as? PrivateKey ?: continue
            val chain = ks.getCertificateChain(alias)?.filterIsInstance<X509Certificate>().orEmpty()
            if (chain.isNotEmpty()) return key to chain
        }
        throw NoKey()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        return byteArrayOf(c.iv.size.toByte()) + c.iv + c.doFinal(plain)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val ivLen = data[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, data, 1, ivLen))
        return c.doFinal(data, 1 + ivLen, data.size - 1 - ivLen)
    }

    companion object {
        private const val KEY_ALIAS = "openpdfviewer-certificates"

        /** CN of the subject (or issuer), falling back to the whole name. */
        fun commonName(cert: X509Certificate, issuer: Boolean = false): String {
            val dn = (if (issuer) cert.issuerX500Principal else cert.subjectX500Principal).name
            return Regex("(?:^|,)CN=([^,]+)").find(dn)?.groupValues?.get(1)?.trim() ?: dn
        }
    }
}
