package io.github.guilhermefeitosa66.openpdfviewer.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guilhermefeitosa66.openpdfviewer.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagesSheet(ready: DocState.Ready, onGoTo: (Int) -> Unit, onDismiss: () -> Unit) {
    val vp = ready.viewport
    val renderer = ready.renderer
    val total = vp.pageCount
    val current = vp.currentPage
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
    var goto by remember { mutableStateOf("") }
    val thumbPx = with(LocalDensity.current) { 120.dp.roundToPx() }
    val submit = {
        goto.toIntOrNull()?.let { n -> if (n in 1..total) onGoTo(n - 1) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.pages),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(stringResource(R.string.go_to), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                OutlinedTextField(
                    value = goto,
                    onValueChange = { v -> goto = v.filter { it.isDigit() }.take(6) },
                    placeholder = { Text("${current + 1}", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.width(88.dp),
                )
                Text(stringResource(R.string.of_pages, total), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(100.dp),
                state = grid,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(total, key = { it }) { i ->
                    Thumbnail(i, ready, renderer, thumbPx, selected = i == current, onClick = { onGoTo(i) })
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(
    index: Int,
    ready: DocState.Ready,
    renderer: PageRenderer,
    widthPx: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DisposableEffect(index) {
        renderer.requestThumb(index, widthPx)
        onDispose { renderer.cancelThumb(index) }
    }
    renderer.version.intValue // recompose when the thumbnail arrives
    val img = renderer.thumb(index)
    val size = ready.doc.pageSizes[index]
    val accent = MaterialTheme.colorScheme.primary
    val label = stringResource(R.string.page_n, index + 1)
    Column(
        Modifier.clickable(onClick = onClick).semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio((size.width / size.height).coerceIn(0.3f, 3f))
                .then(if (selected) Modifier.border(3.dp, accent, RoundedCornerShape(4.dp)).padding(5.dp) else Modifier)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White),
        ) {
            if (img != null) {
                Image(img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        Text(
            "${index + 1}",
            modifier = Modifier.padding(top = 6.dp),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
