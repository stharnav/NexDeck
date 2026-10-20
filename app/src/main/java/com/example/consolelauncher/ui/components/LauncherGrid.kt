package com.example.consolelauncher.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.consolelauncher.data.AppEntry
import com.example.consolelauncher.ui.theme.ConsoleAccent
import com.example.consolelauncher.ui.theme.ConsoleTextSecondary
import kotlinx.coroutines.delay

/** A single row of the launcher grid: either a section title or an app tile. */
sealed interface LauncherRow {
    data class Section(val title: String) : LauncherRow
    data class Letter(val letter: String) : LauncherRow

    /** [key] has to be unique within the screen, an app can show up in more than one section. */
    data class App(val key: String, val entry: AppEntry) : LauncherRow
    data class Message(val text: String) : LauncherRow
}

/**
 * The grid of app tiles. Section titles and alphabet letters span the full width so that the
 * focus traversal of a D-pad naturally skips over them.
 */
@Composable
fun LauncherGrid(
    rows: List<LauncherRow>,
    onLaunch: (AppEntry) -> Unit,
    autoFocusFirstItem: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val firstItemRequester = remember { FocusRequester() }
    val firstAppKey = remember(rows) {
        rows.filterIsInstance<LauncherRow.App>().firstOrNull()?.entry?.packageName
    }

    LaunchedEffect(firstAppKey) {
        if (!autoFocusFirstItem || firstAppKey == null) return@LaunchedEffect
        repeat(10) {
            delay(150)
            if (firstItemRequester.requestFocus()) return@LaunchedEffect
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(
            items = rows,
            key = { row ->
                when (row) {
                    is LauncherRow.Section -> "section_${row.title}"
                    is LauncherRow.Letter -> "letter_${row.letter}"
                    is LauncherRow.App -> row.key
                    is LauncherRow.Message -> "message_${row.text}"
                }
            },
            span = { row ->
                if (row is LauncherRow.App) GridItemSpan(1) else GridItemSpan(maxLineSpan)
            },
        ) { row ->
            when (row) {
                is LauncherRow.Section -> SectionTitle(row.title)
                is LauncherRow.Letter -> LetterTitle(row.letter)
                is LauncherRow.Message -> MessageText(row.text)
                is LauncherRow.App -> GameCard(
                    entry = row.entry,
                    onClick = { onLaunch(row.entry) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CARD_HEIGHT)
                        .then(
                            if (row.entry.packageName == firstAppKey && autoFocusFirstItem) {
                                Modifier.focusRequester(firstItemRequester)
                            } else {
                                Modifier
                            }
                        ),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        color = ConsoleTextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun LetterTitle(letter: String) {
    Text(
        text = letter,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = ConsoleAccent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    )
}

@Composable
private fun MessageText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = ConsoleTextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    )
}

private const val COLUMNS = 4
private val CARD_HEIGHT = 100.dp
