package org.jellyfin.androidtv.ui.browsing

import android.widget.ImageView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.first
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.BaseScreen
import org.jellyfin.androidtv.ui.base.Badge
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.composable.item.ItemCard
import org.jellyfin.androidtv.util.apiclient.JellyfinImage

@Composable
internal fun LibraryGenreScreen(
	genres: List<LibraryGenre>?,
	loadFailed: Boolean,
	selectedGenre: String?,
	onRetry: () -> Unit,
	onSelect: (LibraryGenre) -> Unit,
	onClick: (LibraryGenre) -> Unit,
	loadDetails: suspend (LibraryGenre) -> LibraryGenreDetails,
) = BaseScreen {
	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(JellyfinTheme.colorScheme.background)
			.padding(horizontal = 40.dp, vertical = 24.dp),
		contentAlignment = Alignment.Center,
	) {
		when {
			loadFailed -> Column(
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(16.dp),
			) {
				Text(stringResource(R.string.library_genres_load_error), color = JellyfinTheme.colorScheme.onBackground)
				Button(onClick = onRetry) { Text(stringResource(R.string.library_genres_retry)) }
			}
			genres == null -> CircularProgressIndicator(Modifier.size(32.dp), color = JellyfinTheme.colorScheme.onBackground)
			genres.isEmpty() -> Text(stringResource(R.string.lbl_no_items), color = JellyfinTheme.colorScheme.onBackground)
			else -> GenreGrid(genres, selectedGenre, onSelect, onClick, loadDetails)
		}
	}
}

@Composable
private fun GenreGrid(
	genres: List<LibraryGenre>,
	selectedGenre: String?,
	onSelect: (LibraryGenre) -> Unit,
	onClick: (LibraryGenre) -> Unit,
	loadDetails: suspend (LibraryGenre) -> LibraryGenreDetails,
) {
	val selectedIndex = remember(genres) { genres.indexOfFirst { it.id.toString() == selectedGenre }.coerceAtLeast(0) }
	val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = selectedIndex)
	val restoreFocus = remember { FocusRequester() }
	LaunchedEffect(genres) {
		gridState.scrollToItem(selectedIndex)
		// Wait until the lazy grid has composed the selected card before requesting focus.
		snapshotFlow { gridState.layoutInfo.visibleItemsInfo.any { it.index == selectedIndex } }.first { it }
		restoreFocus.requestFocus()
	}
	LazyVerticalGrid(
		columns = GridCells.Fixed(4),
		state = gridState,
		modifier = Modifier.fillMaxSize().focusGroup(),
		contentPadding = PaddingValues(8.dp),
		horizontalArrangement = Arrangement.spacedBy(16.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		itemsIndexed(genres, key = { _, genre -> genre.id }) { index, genre ->
			val details by produceState<LibraryGenreDetails?>(null, genre.id) { value = loadDetails(genre) }
			GenreCard(
				genre = genre,
				details = details,
				onSelect = { onSelect(genre) },
				onClick = { onClick(genre) },
				modifier = if (index == selectedIndex) Modifier.focusRequester(restoreFocus) else Modifier,
			)
		}
	}
}

@Composable
private fun GenreCard(
	genre: LibraryGenre,
	details: LibraryGenreDetails?,
	onSelect: () -> Unit,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	var focused by remember(genre.id) { mutableStateOf(false) }
	val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(120), label = "genre_focus")
	val tint = remember(genre.id) { Color.hsv((genre.id.hashCode() and Int.MAX_VALUE).rem(360).toFloat(), 0.55f, 0.3f) }
	BoxWithConstraints(modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f).zIndex(if (focused) 1f else 0f)) {
		val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
		val badgeSize = (minOf(maxWidth, maxHeight) * 0.09f).coerceAtMost(9.dp)
		val count = details?.itemCount
		val countText = count?.toString() ?: if (details == null) "…" else "—"
		val countDescription = when {
			count != null -> pluralStringResource(R.plurals.items, count, countText)
			details == null -> stringResource(R.string.loading)
			else -> stringResource(R.string.library_genre_count_unavailable)
		}
		ItemCard(
			modifier = Modifier
				.fillMaxSize()
				.onFocusChanged {
					focused = it.isFocused
					if (it.isFocused) onSelect()
				}
				.clickable(role = Role.Button, onClick = onClick)
				.graphicsLayer { scaleX = scale; scaleY = scale },
			backgroundColor = tint,
			focused = focused,
			shape = JellyfinTheme.shapes.small,
			image = {
				if (!details?.images.isNullOrEmpty()) {
					GenreCollage(details.images, widthPx)
					Box(Modifier.fillMaxSize().background(tint.copy(alpha = 0.65f)))
				}
			},
			overlay = {
				Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
					Text(
						text = genre.name,
						color = JellyfinTheme.colorScheme.onBackground,
						fontSize = 20.sp,
						fontWeight = FontWeight.Bold,
						textAlign = TextAlign.Center,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
					Badge(
						modifier = Modifier
							.align(Alignment.TopEnd)
							.sizeIn(minWidth = badgeSize * 2f, minHeight = badgeSize * 2f)
							.clearAndSetSemantics { contentDescription = countDescription },
						contentPadding = badgeSize * 0.3f,
					) {
						Text(countText, fontSize = badgeSize.value.sp, lineHeight = (badgeSize.value * 1.2f).sp)
					}
				}
			},
		)
	}
}

@Composable
private fun GenreCollage(images: List<JellyfinImage>, widthPx: Int) {
	Column(Modifier.fillMaxSize()) {
		images.chunked(2).forEach { row ->
			Row(Modifier.fillMaxWidth().weight(1f)) {
				row.forEach { image ->
					AsyncImage(
						image = image,
						maxWidth = widthPx / row.size,
						modifier = Modifier.weight(1f).fillMaxHeight(),
						scaleType = ImageView.ScaleType.CENTER_CROP,
					)
				}
			}
		}
	}
}
