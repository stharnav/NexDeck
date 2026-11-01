package com.example.consolelauncher.ui.components

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.consolelauncher.data.GalleryImage
import com.example.consolelauncher.ui.theme.ConsoleAccent
import com.example.consolelauncher.ui.theme.ConsoleBackground
import com.example.consolelauncher.ui.theme.ConsoleStroke
import com.example.consolelauncher.ui.theme.ConsoleSurface
import com.example.consolelauncher.ui.theme.ConsoleTextPrimary
import com.example.consolelauncher.ui.theme.ConsoleTextSecondary
import kotlinx.coroutines.delay

/** The tabs of the SCREENSHOTS menu. */
enum class ScreenshotsTab(val title: String) {
    /** The in-game screenshots that were fetched for the roms of the RetroArch playlists. */
    IMAGES("IMAGES"),

    /** The screenshots RetroArch saved while a game ran (its `screenshots` folder). */
    SCREENSHOTS("SCREENSHOTS"),
}

private fun imagesFor(
    tab: ScreenshotsTab,
    playlistImages: List<GalleryImage>,
    savedScreenshots: List<GalleryImage>,
): List<GalleryImage> = when (tab) {
    ScreenshotsTab.IMAGES -> playlistImages
    ScreenshotsTab.SCREENSHOTS -> savedScreenshots
}

/**
 * The SCREENSHOTS menu: an image gallery fed by the RetroArch playlist data. The IMAGES tab
 * shows the in-game screenshots RetroArch fetched for the roms of every playlist, the
 * SCREENSHOTS tab the screenshots RetroArch saved during play. Every image opens in a fullscreen
 * viewer that D-pad left / right browses and Back closes.
 */
@Composable
fun ScreenshotsScreen(
    tab: ScreenshotsTab,
    playlistImages: List<GalleryImage>,
    savedScreenshots: List<GalleryImage>,
    loading: Boolean,
    available: Boolean,
    hasPlaylistAccess: Boolean,
    viewerIndex: Int,
    onLoadImage: suspend (String) -> ImageBitmap?,
    onLoadFullImage: suspend (String) -> ImageBitmap?,
    onOpenViewer: (Int) -> Unit,
    onViewerClosed: () -> Unit,
    onAction: (LauncherRow.Action) -> Unit,
    autoFocusFirstItem: Boolean = false,
    focusEpoch: Int = 0,
    modifier: Modifier = Modifier,
) {
    val images = imagesFor(tab, playlistImages, savedScreenshots)
    val firstItemRequester = remember { FocusRequester() }

    // On entry and on every tab switch the screen grabs the D-pad focus onto its first control.
    LaunchedEffect(tab, autoFocusFirstItem, focusEpoch) {
        if (!autoFocusFirstItem) return@LaunchedEffect
        repeat(10) {
            delay(150)
            if (firstItemRequester.requestFocus()) return@LaunchedEffect
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                !available -> {
                    item(key = "unavailable") {
                        MessageText(
                            "RetroArch is not installed - install it and grant playlist access " +
                                "in the GAMES tab to see ${tab.title.lowercase()} here",
                        )
                    }
                }

                !hasPlaylistAccess -> {
                    item(key = "no_access") {
                        MessageText(
                            "Playlist access is not granted yet, so there are no images to show",
                        )
                    }
                    item(key = "grant") {
                        ActionTile(
                            label = "GRANT PLAYLIST ACCESS",
                            description = "Pick the RetroArch playlists folder",
                            onClick = {
                                onAction(
                                    LauncherRow.Action(
                                        key = ACTION_GRANT_PLAYLISTS,
                                        label = "GRANT PLAYLIST ACCESS",
                                        description = "Pick the RetroArch playlists folder",
                                    ),
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .thenIf(autoFocusFirstItem) { focusRequester(firstItemRequester) },
                        )
                    }
                }

                loading -> {
                    item(key = "loading") {
                        LoadingRow("Reading RetroArch ${tab.title.lowercase()}...")
                    }
                }

                else -> {
                    if (images.isEmpty()) {
                        item(key = "empty") {
                            MessageText(
                                when (tab) {
                                    ScreenshotsTab.IMAGES ->
                                        "No images yet - in RetroArch run ONLINE UPDATER > " +
                                            "THUMBNAILS DOWNLOAD to fetch screenshots for your roms"
                                    ScreenshotsTab.SCREENSHOTS ->
                                        "No screenshots saved yet - take one in a game with " +
                                            "RetroArch's screenshot hotkey and it appears here"
                                },
                            )
                        }
                    } else {
                        gallerySections(
                            tab = tab,
                            images = images,
                            onLoadImage = onLoadImage,
                            onOpenViewer = onOpenViewer,
                            firstItemRequester = if (viewerIndex < 0) firstItemRequester else null,
                        )
                    }
                }
            }
        }

        // A viewer index that points past the current tab's images (the lists changed while it
        // was open) closes it instead of showing nothing.
        LaunchedEffect(images.size, viewerIndex) {
            if (viewerIndex >= images.size) onViewerClosed()
        }

        // The fullscreen viewer: D-pad left / right browse through the same tab's images, Back
        // closes. All the key handling lives in the launcher root, which sees the keys first.
        AnimatedVisibility(
            visible = viewerIndex >= 0,
            enter = fadeIn(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(150)),
        ) {
            when (val image = images.getOrNull(viewerIndex)) {
                null -> Unit
                else -> GalleryViewer(
                    image = image,
                    index = viewerIndex,
                    total = images.size,
                    onLoadFullImage = onLoadFullImage,
                )
            }
        }
    }
}

/**
 * Adds one section per playlist: a title row and a horizontally scrollable row of image tiles.
 * The flat list index decides which image the fullscreen viewer opens, so the offset of every
 * section within [images] is tracked while the sections are added.
 */
private fun LazyListScope.gallerySections(
    tab: ScreenshotsTab,
    images: List<GalleryImage>,
    onLoadImage: suspend (String) -> ImageBitmap?,
    onOpenViewer: (Int) -> Unit,
    firstItemRequester: FocusRequester?,
) {
    var groupStart = 0
    images.groupBy { it.playlist }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (playlist, list) ->
        val offset = groupStart
        groupStart += list.size
        item(key = "section_${tab.name}_$playlist") {
            SectionTitle(playlist.uppercase())
        }
        item(key = "row_${tab.name}_$playlist") {
            GalleryRow(
                tab = tab,
                images = list,
                startOffset = offset,
                onLoadImage = onLoadImage,
                onOpenViewer = onOpenViewer,
                firstItemRequester = firstItemRequester,
                firstImageId = list.first().id,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One horizontally scrollable row of gallery tiles. */
@Composable
private fun GalleryRow(
    tab: ScreenshotsTab,
    images: List<GalleryImage>,
    startOffset: Int,
    onLoadImage: suspend (String) -> ImageBitmap?,
    onOpenViewer: (Int) -> Unit,
    firstItemRequester: FocusRequester?,
    firstImageId: String,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.offset(x = -16.dp),
        // The row clips along its scroll axis, which would cut the focused tile's scale and
        // glow. Bleed room on both ends plus a matching shift left keep the tiles aligned with
        // the section titles while the focus effects stay visible.
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(images, key = { index, image -> "${tab.name}_${image.id}_$index" }) { index, image ->
            GalleryTile(
                image = image,
                onClick = { onOpenViewer(startOffset + index) },
                onLoadImage = onLoadImage,
                modifier = Modifier.thenIf(
                    firstImageId == image.id && firstItemRequester != null,
                ) { focusRequester(firstItemRequester!!) },
            )
        }
    }
}

/** One gallery tile: the image on a dark slot with its title below, accent focus ring. */
@Composable
private fun GalleryTile(
    image: GalleryImage,
    onClick: () -> Unit,
    onLoadImage: suspend (String) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val bitmap by produceState<ImageBitmap?>(initialValue = null, image.uri) {
        value = onLoadImage(image.uri)
    }

    Column(
        modifier = modifier
            .width(GALLERY_TILE_WIDTH)
            .scale(if (focused) 1.02f else 1f)
            .clip(shape)
            .background(ConsoleSurface)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) ConsoleAccent else ConsoleStroke,
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.isSelectKey()) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(GALLERY_IMAGE_HEIGHT),
        ) {
            Crossfade(
                targetState = bitmap,
                animationSpec = tween(150),
                label = "galleryTile",
            ) { art ->
                if (art != null) {
                    Image(
                        bitmap = art,
                        contentDescription = image.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    PlaceholderArt()
                }
            }
        }
        Text(
            text = image.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = if (focused) ConsoleAccent else ConsoleTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}

/** The empty tile art shown while a thumbnail decodes (or when the file is unreadable). */
@Composable
private fun PlaceholderArt() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ConsoleBackground),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = ConsoleAccent,
            strokeWidth = 2.dp,
            modifier = Modifier
                .width(22.dp)
                .height(22.dp),
        )
    }
}

/**
 * The fullscreen image viewer. Opened from a gallery tile with Enter; the launcher root routes
 * D-pad left / right here (it consumes them, so no tile behind the viewer moves the focus) and
 * Back closes it. A Crossfade keeps the transition between two images smooth.
 */
@Composable
private fun GalleryViewer(
    image: GalleryImage,
    index: Int,
    total: Int,
    onLoadFullImage: suspend (String) -> ImageBitmap?,
) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(image.uri) {
        bitmap = onLoadFullImage(image.uri)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f)),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = bitmap, animationSpec = tween(150), label = "viewer") { art ->
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = image.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp),
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = image.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = ConsoleTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${index + 1} / $total",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 2.sp,
                color = ConsoleTextSecondary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "L / R BROWSE    BACK CLOSE",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 2.sp,
                color = ConsoleTextSecondary,
            )
        }
    }
}

/** A message with the gallery's spinner next to it, shown over the whole screen while loading. */
@Composable
private fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            color = ConsoleAccent,
            strokeWidth = 2.dp,
            modifier = Modifier
                .width(20.dp)
                .height(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = ConsoleTextSecondary,
        )
    }
}

private val GALLERY_TILE_WIDTH = 280.dp
private val GALLERY_IMAGE_HEIGHT = 172.dp
