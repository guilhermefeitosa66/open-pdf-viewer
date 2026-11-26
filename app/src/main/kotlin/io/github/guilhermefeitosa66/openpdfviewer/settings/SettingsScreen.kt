package io.github.guilhermefeitosa66.openpdfviewer.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import io.github.guilhermefeitosa66.openpdfviewer.R
import io.github.guilhermefeitosa66.openpdfviewer.data.UpdateStore
import io.github.guilhermefeitosa66.openpdfviewer.ui.AppTheme
import io.github.guilhermefeitosa66.openpdfviewer.ui.AppThemes
import io.github.guilhermefeitosa66.openpdfviewer.ui.BackgroundCache
import io.github.guilhermefeitosa66.openpdfviewer.ui.ThemeSettings

private val BrandLight = listOf(Color(0xFFF7EEDF), Color(0xFFB5502B), Color(0xFF2A1A12))
private val BrandDark = listOf(Color(0xFF1E1612), Color(0xFFD9973A), Color(0xFFF3E7D8))

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val settings = remember { ThemeSettings.get(context) }
    val extras = remember { AppThemes.extras(context) }
    val current = settings.current
    val hasBackground = current?.background != null

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.settings), fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }

        SectionLabel(stringResource(R.string.theme))
        // Two rows of three: the brand theme first, then the shared libre-apps themes.
        val cards: List<AppTheme?> = listOf<AppTheme?>(null) + extras
        cards.chunked(3).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { theme ->
                    val id = theme?.id ?: AppThemes.DEFAULT_ID
                    ThemeCard(
                        theme = theme,
                        selected = settings.themeId == id,
                        showBackground = settings.backgroundEnabled,
                        onClick = { settings.setTheme(theme) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        SectionLabel(stringResource(R.string.annotations))
        var author by remember { androidx.compose.runtime.mutableStateOf(settings.authorName) }
        androidx.compose.material3.OutlinedTextField(
            value = author,
            onValueChange = { author = it.take(60); settings.chooseAuthorName(author) },
            label = { Text(stringResource(R.string.author_name)) },
            supportingText = { Text(stringResource(R.string.author_name_help)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = settings.backgroundEnabled,
                    enabled = hasBackground,
                    role = Role.Switch,
                    onValueChange = settings::setBackground,
                )
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.background_image), fontSize = 16.sp)
                Text(
                    stringResource(if (hasBackground) R.string.background_image_desc else R.string.background_image_none),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = settings.backgroundEnabled && hasBackground, onCheckedChange = null, enabled = hasBackground)
        }

        SectionLabel(stringResource(R.string.updates))
        val updates = remember { UpdateStore.get(context) }
        val updatesOn by updates.enabled.collectAsState()
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = updatesOn, role = Role.Switch, onValueChange = updates::setEnabled)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.update_check), fontSize = 16.sp)
                Text(
                    stringResource(R.string.update_check_desc),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = updatesOn, onCheckedChange = null)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A miniature of the theme: its background (or colors), a bar, a page and the open button. */
@Composable
private fun ThemeCard(
    theme: AppTheme?,
    selected: Boolean,
    showBackground: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val name = theme?.name ?: stringResource(R.string.theme_libre_apps)
    val ring = MaterialTheme.colorScheme.primary
    val thumb by produceState<ImageBitmap?>(theme?.thumbnail?.let { BackgroundCache.peek(it) }, theme?.thumbnail, showBackground) {
        val asset = theme?.thumbnail
        value = if (asset != null && showBackground) BackgroundCache.load(context, asset, 108) else null
    }
    Column(
        modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.62f)
                .then(if (selected) Modifier.border(3.dp, ring, RoundedCornerShape(16.dp)).padding(5.dp) else Modifier)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
        ) {
            if (theme == null) {
                // The brand theme follows the system: half light, half dark.
                Row(Modifier.fillMaxSize()) {
                    MiniUi(BrandLight[0], BrandLight[1], BrandLight[2], Modifier.weight(1f).fillMaxHeight())
                    MiniUi(BrandDark[0], BrandDark[1], BrandDark[2], Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Box(Modifier.fillMaxSize().background(theme.ui.bg)) {
                    thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    MiniUi(Color.Transparent, theme.ui.accent, theme.ui.text, Modifier.fillMaxSize(), bar = theme.ui.surface.copy(alpha = 0.85f))
                }
            }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).background(ring, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)) }
            }
        }
        Text(
            name,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2,
        )
    }
}

@Composable
private fun MiniUi(bg: Color, accent: Color, text: Color, modifier: Modifier, bar: Color = bg) {
    Box(modifier.background(bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(18.dp).background(bar).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.width(24.dp).height(3.dp).background(text, RoundedCornerShape(2.dp)))
            }
            Box(Modifier.padding(6.dp).fillMaxWidth().aspectRatio(0.75f).background(Color.White, RoundedCornerShape(2.dp)).padding(5.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(4) { Box(Modifier.fillMaxWidth(if (it == 3) 0.6f else 1f).height(2.dp).background(Color(0xFFD6D0CA))) }
                }
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(6.dp).width(26.dp).height(10.dp).background(accent, RoundedCornerShape(4.dp))
        )
    }
}
