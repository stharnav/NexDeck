package com.example.consolelauncher.ui.components

import android.view.KeyEvent
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.consolelauncher.data.GameDirectoryEntry
import com.example.consolelauncher.ui.theme.ConsoleAccent
import com.example.consolelauncher.ui.theme.ConsoleStroke
import com.example.consolelauncher.ui.theme.ConsoleSurface
import com.example.consolelauncher.ui.theme.ConsoleTextPrimary
import com.example.consolelauncher.ui.theme.ConsoleTextSecondary

/**
 * A console style "game card" tile for games from user-selected game directory.
 */
@Composable
fun GameDirectoryCard(
    entry: GameDirectoryEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "gameDirCardScale",
    )

    val thumbnail = entry.thumbnailBitmap

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
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.isSelectKey()) {
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
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = null,
                    modifier = Modifier.size(ICON_SIZE),
                )
            } else {
                // Fallback: game icon
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
                text = "Game Directory",
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