package io.github.guilhermefeitosa66.openpdfviewer.signing

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import io.github.guilhermefeitosa66.openpdfviewer.data.Certificates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.AttributeTable
import org.bouncycastle.asn1.ess.ESSCertIDv2
import org.bouncycastle.asn1.ess.SigningCertificateV2
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date

/** What validation found for one signature in a document. */
data class SignatureCheck(
    val signer: String,
    val date: Date?,
    val reason: String?,
    /** The signed bytes are unchanged and the cryptographic signature matches the certificate. */
    val intact: Boolean,
    /** The signature covers the whole file as it is now (nothing was added after signing). */
    val coversWholeFile: Boolean,
    /** The signer's certificate chains to a certificate authority this device trusts. */
    val trusted: Boolean,
    val problem: String?,
)

/**
 * PAdES-B digital signatures and their validation, done on the device: PdfBox-Android writes the
 * incremental update with the signature field, BouncyCastle builds and checks the CMS. Nothing is
 * looked up online (no revocation lists, no timestamps), and the UI says so.
 */
object PdfSigner {

    /**
     * Signs [input] into [output] as an incremental update. [password] opens an encrypted PDF.
     * The signature is invisible; a visible box, when wanted, is added to the page beforehand.
     */
    suspend fun sign(
        context: Context,
        input: File,
        output: File,
        key: PrivateKey,
        chain: List<X509Certificate>,
        reason: String?,
        location: String?,
        password: String? = null,
    ) = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(input, password ?: "").use { doc ->
            val sig = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED)
                name = Certificates.commonName(chain.first())
                if (!reason.isNullOrBlank()) this.reason = reason
                if (!location.isNullOrBlank()) this.location = location
                signDate = Calendar.getInstance()
            }
            SignatureOptions().use { options ->
                options.preferredSignatureSize = 32 * 1024
                doc.addSignature(sig, options)
                FileOutputStream(output).use { os ->
                    val external = doc.saveIncrementalForExternalSigning(os)
                    val content = external.content.readBytes()
                    external.setSignature(cms(content, key, chain))
                }
            }
        }
    }

    /** Detached CMS (CAdES) with the signing-certificate-v2 attribute required by PAdES-B. */
    private fun cms(content: ByteArray, key: PrivateKey, chain: List<X509Certificate>): ByteArray {
        val cert = chain.first()
        val algorithm = if (key.algorithm.equals("EC", true) || key.algorithm.equals("ECDSA", true)) "SHA256withECDSA" else "SHA256withRSA"
        val certHash = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        val essCert = ESSCertIDv2(AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256), certHash)
        val attrs = ASN1EncodableVector().apply {
            add(Attribute(PKCSObjectIdentifiers.id_aa_signingCertificateV2, DERSet(SigningCertificateV2(arrayOf(essCert)))))
        }
        val signerInfo = JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().build())
            .setSignedAttributeGenerator(DefaultSignedAttributeTableGenerator(AttributeTable(attrs)))
            .build(JcaContentSignerBuilder(algorithm).build(key), cert)
        val gen = CMSSignedDataGenerator().apply {
            addSignerInfoGenerator(signerInfo)
            addCertificates(JcaCertStore(chain))
        }
        return gen.generate(CMSProcessableByteArray(content), false).encoded
    }

    /** Checks every signature of [file]. */
    suspend fun validate(context: Context, file: File, password: String? = null): List<SignatureCheck> = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context.applicationContext)
        val length = file.length()
        PDDocument.load(file, password ?: "").use { doc ->
            doc.signatureDictionaries.map { sig -> check(file, length, sig) }
        }
    }

    private fun check(file: File, length: Long, sig: PDSignature): SignatureCheck {
        val name = sig.name ?: "?"
        val date = sig.signDate?.time
        return runCatching {
            val range = sig.byteRange
            val signed = readRange(file, range)
            // /Contents is zero-padded to its reserved size: read just the one DER object.
            val contents = org.bouncycastle.asn1.ASN1InputStream(sig.contents).use { it.readObject() }
            val cms = CMSSignedData(CMSProcessableByteArray(signed), org.bouncycastle.asn1.cms.ContentInfo.getInstance(contents))
            val signer = cms.signerInfos.signers.first()
            @Suppress("UNCHECKED_CAST")
            val all = cms.certificates.getMatches(null) as Collection<X509CertificateHolder>
            val holder = all.first { signer.sid.match(it) }
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(holder.encoded.inputStream()) as X509Certificate
            val intact = signer.verify(JcaSimpleSignerInfoVerifierBuilder().build(cert))
            val covers = range.size == 4 && range[2].toLong() + range[3] == length
            val chain = all.map {
                CertificateFactory.getInstance("X.509").generateCertificate(it.encoded.inputStream()) as X509Certificate
            }
            SignatureCheck(Certificates.commonName(cert), date, sig.reason, intact, covers, trusted(cert, chain), null)
        }.getOrElse { e ->
            android.util.Log.w("OpenPdfViewer", "signature check failed", e)
            SignatureCheck(name, date, sig.reason, intact = false, coversWholeFile = false, trusted = false, problem = e.javaClass.simpleName)
        }
    }

    private fun readRange(file: File, range: IntArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        RandomAccessFile(file, "r").use { raf ->
            var i = 0
            while (i + 1 < range.size) {
                val buf = ByteArray(range[i + 1])
                raf.seek(range[i].toLong())
                raf.readFully(buf)
                out.write(buf)
                i += 2
            }
        }
        return out.toByteArray()
    }

    /** Chains the certificate to the device's trusted CAs, offline (no revocation check). */
    private fun trusted(cert: X509Certificate, chain: List<X509Certificate>): Boolean = runCatching {
        val anchors = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val path = CertificateFactory.getInstance("X.509").generateCertPath(listOf(cert) + chain.filter { it != cert })
        val params = PKIXParameters(anchors).apply { isRevocationEnabled = false }
        CertPathValidator.getInstance("PKIX").validate(path, params)
        true
    }.getOrDefault(false)
}
