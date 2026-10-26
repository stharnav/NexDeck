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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
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
 * A console style "game card" tile for a game from a RetroArch playlist. The box art is loaded
 * lazily so that a big playlist does not have to decode every thumbnail up front.
 */
@Composable
fun RetroGameCard(
    entry: RetroGameEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailLoader: suspend (String) -> ImageBitmap? = { null },
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "retroCardScale",
    )

    val currentLoader = rememberUpdatedState(thumbnailLoader)
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, entry.thumbnailPath) {
        value = entry.thumbnailPath?.let { currentLoader.value(it) }
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
        Box(
            modifier = Modifier
                .size(ICON_SIZE)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.04f)),
            contentAlignment = Alignment.Center,
        ) {
            val art = thumbnail
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(ICON_SIZE),
                )
            } else {
                Text(
                    text = "🎮",
                    fontSize = 32.sp,
                    modifier = Modifier
                        .size(ICON_SIZE)
                        .wrapContentSize(Alignment.Center),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.titleSmall,
                color = ConsoleTextPrimary,
                maxLines = 1,
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
private val ICON_SIZE = 72.dp
