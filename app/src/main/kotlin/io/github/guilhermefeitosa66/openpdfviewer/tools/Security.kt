package io.github.guilhermefeitosa66.openpdfviewer.tools

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Password protection with AES-256 (PdfBox), and removing it with the right password. */
object Security {

    data class Restrictions(val print: Boolean = true, val copy: Boolean = true, val edit: Boolean = true)

    class NotProtected : Exception()
    class WrongPassword : Exception()

    /**
     * Encrypts [input] with AES-256. [openPassword] is needed to open it (may be empty for
     * "anyone can open, but with restrictions"); [ownerPassword] lifts the [restrictions].
     */
    suspend fun protect(
        context: Context,
        input: File,
        output: File,
        openPassword: String,
        ownerPassword: String,
        restrictions: Restrictions,
        currentPassword: String?,
    ) = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(input, currentPassword ?: "", MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            val perms = AccessPermission().apply {
                setCanPrint(restrictions.print)
                setCanPrintFaithful(restrictions.print)
                setCanExtractContent(restrictions.copy)
                setCanExtractForAccessibility(true)
                setCanModify(restrictions.edit)
                setCanModifyAnnotations(restrictions.edit)
                setCanFillInForm(restrictions.edit)
                setCanAssembleDocument(restrictions.edit)
            }
            // Without a separate owner password, a random one keeps the restrictions in place.
            val owner = ownerPassword.ifEmpty { java.util.UUID.randomUUID().toString() }
            val policy = StandardProtectionPolicy(owner, openPassword, perms).apply {
                encryptionKeyLength = 256
            }
            doc.protect(policy)
            doc.save(output)
        }
    }

    /**
     * Writes an unencrypted copy. [password] must be the password that opens the file or, for a
     * file anyone can open, the owner password that lifts its restrictions.
     */
    suspend fun removePassword(context: Context, input: File, output: File, password: String) = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context.applicationContext)
        // Can it be opened without any password?
        val opensFreely = runCatching {
            PDDocument.load(input, "", MemoryUsageSetting.setupTempFileOnly()).use { it.isEncrypted }
        }
        if (opensFreely.getOrNull() == false) throw NotProtected()
        val doc = try {
            PDDocument.load(input, password, MemoryUsageSetting.setupTempFileOnly())
        } catch (e: InvalidPasswordException) {
            throw WrongPassword()
        }
        doc.use {
            // A file anyone can open needs the owner password to drop its restrictions.
            if (opensFreely.isSuccess && !it.currentAccessPermission.isOwnerPermission) throw WrongPassword()
            it.isAllSecurityToBeRemoved = true
            it.save(output)
        }
    }
}

/** Document properties (title, author, subject, keywords) and hidden metadata. */
object Metadata {
    data class Fields(val title: String, val author: String, val subject: String, val keywords: String)

    /**
     * Writes [fields] (empty ones are removed). [stripHidden] also removes the XMP metadata,
     * the creating app, the producer, the dates and any custom entries.
     */
    suspend fun write(context: Context, input: File, output: File, fields: Fields, stripHidden: Boolean, password: String?) =
        withContext(Dispatchers.IO) {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(input, password ?: "", MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                val info = if (stripHidden) com.tom_roush.pdfbox.pdmodel.PDDocumentInformation() else doc.documentInformation
                info.title = fields.title.trim().ifEmpty { null }
                info.author = fields.author.trim().ifEmpty { null }
                info.subject = fields.subject.trim().ifEmpty { null }
                info.keywords = fields.keywords.trim().ifEmpty { null }
                if (!stripHidden) info.modificationDate = java.util.Calendar.getInstance()
                doc.documentInformation = info
                if (stripHidden) {
                    doc.documentCatalog.metadata = null
                    for (page in doc.pages) page.metadata = null
                }
                doc.save(output)
            }
        }
}
