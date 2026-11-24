package io.github.guilhermefeitosa66.openpdfviewer.viewer

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guilhermefeitosa66.openpdfviewer.R
import io.github.guilhermefeitosa66.openpdfviewer.data.Certificates
import io.github.guilhermefeitosa66.openpdfviewer.data.StoredCertificate
import io.github.guilhermefeitosa66.openpdfviewer.signing.SignatureCheck
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Results, the signed-file "Save as" and the sheet, wired from the viewer screen. */
@Composable
fun DigitalSignatureEffects(vm: ViewerViewModel) {
    val ctx = LocalContext.current
    vm.signResult?.let { outcome ->
        val msg = stringResource(
            when (outcome) {
                ViewerViewModel.SignOutcome.SIGNED -> R.string.signed_ok
                ViewerViewModel.SignOutcome.WRONG_PASSWORD -> R.string.wrong_certificate_password
                ViewerViewModel.SignOutcome.FAILED -> R.string.sign_failed
            }
        )
        LaunchedEffect(outcome) {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            vm.signResult = null
        }
    }
    val exporter = rememberLauncherForActivityResult(io.github.guilhermefeitosa66.openpdfviewer.ui.CreateTypedDocument()) { vm.exportPending(it) }
    vm.fileToExport?.let { pending ->
        LaunchedEffect(pending) {
            val name = (vm.state as? DocState.Ready)?.name?.removeSuffix(".pdf")?.removeSuffix(".PDF") ?: "document"
            exporter.launch(io.github.guilhermefeitosa66.openpdfviewer.ui.FileActions.safeName("$name (${pending.second})") to "application/pdf")
        }
    }
    (vm.state as? DocState.Ready)?.let { r ->
        if (vm.organizeOpen) PageOrganizer(vm, r)
        vm.cropPage?.let { CropScreen(vm, r, it) }
    }
    vm.zipToShare?.let { zip ->
        LaunchedEffect(zip) {
            vm.zipToShare = null
            io.github.guilhermefeitosa66.openpdfviewer.ui.FileActions.shareFileFrom(ctx, zip, "application/zip")
        }
    }
    vm.splitCount?.let { n ->
        val msg = androidx.compose.ui.res.pluralStringResource(R.plurals.split_saved, n, n)
        LaunchedEffect(n) {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            vm.splitCount = null
        }
    }
    if (vm.digitalSignOpen) DigitalSignatureSheet(vm)
    ToolEffects(vm)
    vm.signatureChecks?.let { SignatureChecksDialog(it) { vm.signatureChecks = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DigitalSignatureSheet(vm: ViewerViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { Certificates(context) }
    val certs = remember { mutableStateListOf<StoredCertificate>() }
    var reload by remember { mutableStateOf(0) }
    var importing by remember { mutableStateOf<Uri?>(null) }
    var signingWith by remember { mutableStateOf<StoredCertificate?>(null) }
    LaunchedEffect(reload) {
        certs.clear()
        certs += store.list()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importing = it }
    ModalBottomSheet(
        onDismissRequest = { vm.digitalSignOpen = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.digital_signature_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.digital_signature_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (certs.isEmpty()) Text(stringResource(R.string.no_certificates), fontSize = 14.sp)
            val fmt = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
            for (c in certs) {
                Row(Modifier.fillMaxWidth().clickable { signingWith = c }.padding(vertical = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontWeight = FontWeight.SemiBold)
                        val expired = c.notAfter < System.currentTimeMillis()
                        Text(
                            stringResource(if (expired) R.string.certificate_expired else R.string.certificate_valid_until, c.issuer, fmt.format(Date(c.notAfter))),
                            fontSize = 12.sp,
                            color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { scope.launch { store.delete(c.id); reload++ } }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.delete))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    picker.launch(arrayOf("application/x-pkcs12", "application/pkcs12", "application/octet-stream", "*/*"))
                }) { Text(stringResource(R.string.import_certificate)) }
                TextButton(onClick = { vm.digitalSignOpen = false; vm.checkSignatures() }) { Text(stringResource(R.string.check_signatures)) }
            }
        }
    }
    importing?.let { uri ->
        PasswordPrompt(
            title = stringResource(R.string.import_certificate),
            onDismiss = { importing = null },
        ) { pw, onWrong ->
            scope.launch {
                try {
                    store.import(context, uri, pw)
                    importing = null
                    reload++
                } catch (e: Certificates.WrongPassword) {
                    onWrong()
                } catch (e: Exception) {
                    importing = null
                    Toast.makeText(context, context.getString(R.string.certificate_unreadable), Toast.LENGTH_LONG).show()
                } finally {
                    pw.fill(' ')
                }
            }
        }
    }
    signingWith?.let { cert -> SignDialog(cert, vm) { signingWith = null } }
}

@Composable
private fun PasswordPrompt(title: String, onDismiss: () -> Unit, onSubmit: (CharArray, onWrong: () -> Unit) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = pw, onValueChange = { pw = it; wrong = false }, singleLine = true,
                label = { Text(stringResource(R.string.certificate_password)) },
                isError = wrong,
                supportingText = if (wrong) ({ Text(stringResource(R.string.wrong_certificate_password)) }) else null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        },
        confirmButton = { TextButton(onClick = { onSubmit(pw.toCharArray()) { wrong = true } }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SignDialog(cert: StoredCertificate, vm: ViewerViewModel, onDismiss: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sign_as, cert.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it }, singleLine = true,
                    label = { Text(stringResource(R.string.certificate_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(value = reason, onValueChange = { reason = it.take(100) }, singleLine = true, label = { Text(stringResource(R.string.sign_reason)) })
                OutlinedTextField(value = location, onValueChange = { location = it.take(100) }, singleLine = true, label = { Text(stringResource(R.string.sign_location)) })
                Text(stringResource(R.string.sign_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = {
                vm.signDigitally(cert.id, pw.toCharArray(), reason, location)
                onDismiss()
            }) { Text(stringResource(R.string.sign)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SignatureChecksDialog(checks: List<SignatureCheck>, onDismiss: () -> Unit) {
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.check_signatures)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (checks.isEmpty()) Text(stringResource(R.string.no_signatures_in_document))
                for ((i, c) in checks.withIndex()) {
                    if (i > 0) HorizontalDivider()
                    Text(c.signer, fontWeight = FontWeight.SemiBold)
                    c.date?.let { Text(fmt.format(it), fontSize = 12.sp) }
                    c.reason?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp) }
                    val ok = Color(0xFF2E7D32)
                    val bad = MaterialTheme.colorScheme.error
                    val warn = Color(0xFF8A5A00)
                    Text(stringResource(if (c.intact) R.string.sig_intact else R.string.sig_broken), color = if (c.intact) ok else bad, fontSize = 13.sp)
                    if (c.intact) {
                        Text(stringResource(if (c.coversWholeFile) R.string.sig_covers_all else R.string.sig_changed_after), color = if (c.coversWholeFile) ok else warn, fontSize = 13.sp)
                    }
                    Text(stringResource(if (c.trusted) R.string.sig_trusted else R.string.sig_untrusted), color = if (c.trusted) ok else warn, fontSize = 13.sp)
                }
                Text(stringResource(R.string.sig_offline_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}
