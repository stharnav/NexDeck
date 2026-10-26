package com.example.consolelauncher.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.consolelauncher.data.RetroGameEntry
import com.example.consolelauncher.ui.theme.ConsoleAccent
import com.example.consolelauncher.ui.theme.ConsoleStroke
import com.example.consolelauncher.ui.theme.ConsoleSurface
import com.example.consolelauncher.ui.theme.ConsoleTextPrimary
import com.example.consolelauncher.ui.theme.ConsoleTextSecondary

/**
 * A console style "game card" tile for a game from a RetroArch playlist. The tile shows the box
 * art from RetroArch, loaded lazily so that a big playlist does not have to decode every
 * thumbnail up front.
 */
@Composable
fun RetroGameCard(
    entry: RetroGameEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailLoader: suspend (List<String>) -> ImageBitmap? = { null },
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "retroCardScale",
    )

    val currentLoader = rememberUpdatedState(thumbnailLoader)
    val artwork by produceState<ImageBitmap?>(initialValue = null, entry.thumbnailPaths) {
        value = if (entry.thumbnailPaths.isEmpty()) {
            null
        } else {
            currentLoader.value(entry.thumbnailPaths)
        }
    }

    Row(
        modifier = modifier
            .scale(scale)
            .shadow(
                elevation = if (focused) 16.dp else 0.dp,
                shape = RoundedCornerShape(CARD_CORNER),
                ambientColor = ConsoleAccent,
                spotColor = ConsoleAccent,
            )
            .clip(RoundedCornerShape(CARD_CORNER))
            .background(ConsoleSurface)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) ConsoleAccent else ConsoleStroke,
                shape = RoundedCornerShape(CARD_CORNER),
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN && event.isSelectKey()) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The box art keeps the portrait aspect ratio of the artwork RetroArch ships, so the
        // cover is shown as a cover instead of being squeezed into a square icon.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(ARTWORK_ASPECT_RATIO)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.04f)),
            contentAlignment = Alignment.Center,
        ) {
            val art = artwork
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = "🎮",
                    fontSize = 22.sp,
                    color = Color.White.copy(alpha = 0.25f),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.titleSmall,
                color = ConsoleTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.coreName,
                style = MaterialTheme.typography.bodySmall,
                color = ConsoleTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val CARD_CORNER = 14.dp

/** Portrait ratio of the box art RetroArch stores in `Named_Boxarts`. */
private const val ARTWORK_ASPECT_RATIO = 2f / 3f
