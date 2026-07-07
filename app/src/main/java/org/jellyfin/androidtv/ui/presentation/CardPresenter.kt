package org.jellyfin.androidtv.ui.presentation

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.leanback.widget.Presenter
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.composable.item.ItemCard
import org.jellyfin.androidtv.ui.composable.item.ItemCardBaseItemOverlay
import org.jellyfin.androidtv.ui.composable.item.ItemPreview
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowType
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.apiclient.JellyfinImage
import org.jellyfin.androidtv.util.apiclient.channelPrimaryImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.getTimeFormatter
import org.jellyfin.design.Tokens
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject

class CardPresenter(
	val showInfo: Boolean,
	val imageType: ImageType,
	val staticHeight: Int,
	val uniformAspect: Boolean,
	private val onLongClick: ((item: Any?, view: View) -> Boolean)? = null,
) : Presenter() {
	constructor(showInfo: Boolean, imageType: ImageType, staticHeight: Int, uniformAspect: Boolean) : this(showInfo, imageType, staticHeight, uniformAspect, null)
	constructor(showInfo: Boolean, imageType: ImageType, staticHeight: Int) : this(showInfo, imageType, staticHeight, false)
	constructor(showInfo: Boolean, imageType: ImageType, staticHeight: Int, onLongClick: ((item: Any?, view: View) -> Boolean)?) : this(showInfo, imageType, staticHeight, false, onLongClick)
	constructor(showInfo: Boolean, staticHeight: Int) : this(showInfo, ImageType.POSTER, staticHeight)
	constructor(showInfo: Boolean) : this(showInfo, 150)
	constructor() : this(true)

	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		val view = ComposeView(parent.context).apply {
			setParentCompositionContext(parent.findViewTreeCompositionContext())
			setViewTreeLifecycleOwner(parent.findViewTreeLifecycleOwner())
			setViewTreeSavedStateRegistryOwner(parent.findViewTreeSavedStateRegistryOwner())
			isFocusable = true
		}

		return CardViewHolder(view)
	}

	override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
		if (viewHolder !is CardViewHolder) return

		val rowItem = when (item) {
			is BaseRowItem -> item
			is BaseItemDto -> BaseItemDtoBaseRowItem(item, staticHeight = true)
			else -> return
		}

		viewHolder.bind(rowItem, item)
	}

	override fun onUnbindViewHolder(viewHolder: ViewHolder) {
		if (viewHolder !is CardViewHolder) return

		viewHolder.unbind()
	}

	private inner class CardViewHolder(private val composeView: ComposeView) : ViewHolder(composeView) {
		private val _item = MutableStateFlow<BaseRowItem?>(null)
		private val _focused = MutableStateFlow(false)

		init {
			composeView.setOnFocusChangeListener { _, hasFocus -> _focused.value = hasFocus }
			composeView.setContent {
				val item by _item.collectAsState()
				val focused by _focused.collectAsState()

				CardViewHolderContent(
					item = item,
					focused = focused,
					showInfo = showInfo,
					imageType = imageType,
					staticHeight = staticHeight,
					uniformAspect = uniformAspect,
				)
			}
		}

		fun bind(item: BaseRowItem, originalItem: Any?) {
			_item.value = item
			composeView.tag = item.itemId
			composeView.setOnLongClickListener(
				onLongClick?.let { handler ->
					View.OnLongClickListener { view -> handler(originalItem, view) }
				}
			)
		}

		fun unbind() {
			_item.value = null
			composeView.tag = null
			composeView.setOnLongClickListener(null)
		}
	}
}

private data class BaseRowItemDisplayConfig(
	val image: JellyfinImage?,
	val iconRes: Int,
	val aspectRatio: Float,
	val backgroundColor: Color = Tokens.Color.colorBluegrey900,
	val overrideShowInfo: Boolean? = null,
	val scaleType: ImageView.ScaleType? = null,
	val imageHeightRes: Int? = null,
)

private fun BaseRowItem.getDisplayConfig(imageType: ImageType, uniformAspect: Boolean): BaseRowItemDisplayConfig = when (baseRowType) {
	BaseRowType.BaseItem -> {
		val preferSeriesPoster = this is BaseItemDtoBaseRowItem && preferSeriesPoster
		val primaryAspectRatio = baseItem?.primaryImageAspectRatio?.toFloat()
		val defaultAspectRatio = when {
			preferParentThumb && (baseItem?.parentThumbItemId != null || baseItem?.seriesThumbImageTag != null) -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			baseItem?.type == BaseItemKind.EPISODE && primaryAspectRatio != null -> primaryAspectRatio
			baseItem?.type == BaseItemKind.EPISODE && (baseItem.parentThumbItemId != null || baseItem.seriesThumbImageTag != null) -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			baseItem?.type == BaseItemKind.USER_VIEW -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			else -> primaryAspectRatio ?: ImageHelper.ASPECT_RATIO_7_9.toFloat()
		}

		val base = BaseRowItemDisplayConfig(
			aspectRatio = when (imageType) {
				ImageType.BANNER -> ImageHelper.ASPECT_RATIO_BANNER.toFloat()
				ImageType.THUMB -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
				else -> defaultAspectRatio
			},
			image = getImage(imageType),
			iconRes = R.drawable.ic_clapperboard,
		)

		when (baseItem?.type) {
			BaseItemKind.AUDIO, BaseItemKind.MUSIC_ALBUM -> base.copy(
				iconRes = R.drawable.ic_music_album,
				aspectRatio = if (uniformAspect || base.aspectRatio < 0.8f) 1f else base.aspectRatio,
			)

			BaseItemKind.PERSON,
			BaseItemKind.MUSIC_ARTIST -> base.copy(
				iconRes = R.drawable.ic_user,
				aspectRatio = if (uniformAspect || base.aspectRatio < 0.8f) 1f else base.aspectRatio,
			)

			BaseItemKind.SEASON, BaseItemKind.SERIES -> base.copy(
				aspectRatio = if (imageType == ImageType.POSTER) ImageHelper.ASPECT_RATIO_2_3.toFloat() else base.aspectRatio,
				iconRes = R.drawable.ic_tv,
				backgroundColor = Tokens.Color.colorBlue850,
			)

			BaseItemKind.EPISODE -> when (preferSeriesPoster) {
				true -> base.copy(
					aspectRatio = ImageHelper.ASPECT_RATIO_2_3.toFloat(),
					iconRes = R.drawable.ic_tv,
					backgroundColor = Tokens.Color.colorBlue850,
				)

				false -> base.copy(
					aspectRatio = ImageHelper.ASPECT_RATIO_16_9.toFloat(),
					iconRes = R.drawable.ic_tv,
					backgroundColor = Tokens.Color.colorBlue850,
					overrideShowInfo = true,
				)
			}

			BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW -> base.copy(
				aspectRatio = ImageHelper.ASPECT_RATIO_16_9.toFloat(),
				iconRes = R.drawable.ic_folder,
				backgroundColor = Tokens.Color.colorBlue800,
			)

			BaseItemKind.FOLDER, BaseItemKind.GENRE, BaseItemKind.MUSIC_GENRE -> base.copy(
				iconRes = R.drawable.ic_folder,
				backgroundColor = Tokens.Color.colorBlue800,
			)

			BaseItemKind.PHOTO -> base.copy(
				iconRes = R.drawable.ic_photo
			)

			BaseItemKind.PHOTO_ALBUM, BaseItemKind.PLAYLIST -> base.copy(
				iconRes = R.drawable.ic_folder,
				backgroundColor = Tokens.Color.colorBlue800,
			)

			BaseItemKind.MOVIE, BaseItemKind.VIDEO -> base.copy(
				aspectRatio = when (imageType) {
					ImageType.POSTER -> ImageHelper.ASPECT_RATIO_2_3.toFloat()
					else -> base.aspectRatio
				},
				iconRes = R.drawable.ic_clapperboard,
				backgroundColor = Tokens.Color.colorBlue900,
			)

			else -> base
		}
	}

	BaseRowType.LiveTvChannel -> BaseRowItemDisplayConfig(
		aspectRatio = when (imageType) {
			ImageType.BANNER -> ImageHelper.ASPECT_RATIO_BANNER.toFloat()
			ImageType.THUMB -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			else -> baseItem?.primaryImageAspectRatio?.toFloat() ?: 1f
		},
		image = getImage(imageType),
		scaleType = ImageView.ScaleType.FIT_CENTER,
		imageHeightRes = R.dimen.live_tv_card_logo_height,
		iconRes = R.drawable.ic_tv,
	)

	BaseRowType.LiveTvProgram -> BaseRowItemDisplayConfig(
		aspectRatio = when (imageType) {
			ImageType.BANNER -> ImageHelper.ASPECT_RATIO_BANNER.toFloat()
			ImageType.THUMB -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			else -> baseItem?.primaryImageAspectRatio?.toFloat() ?: ImageHelper.ASPECT_RATIO_7_9.toFloat()
		},
		image = baseItem?.channelPrimaryImage ?: getImage(imageType),
		scaleType = ImageView.ScaleType.FIT_CENTER,
		imageHeightRes = R.dimen.live_tv_card_logo_height,
		iconRes = R.drawable.ic_tv,
		overrideShowInfo = true,
	)

	BaseRowType.LiveTvRecording -> BaseRowItemDisplayConfig(
		aspectRatio = when (imageType) {
			ImageType.BANNER -> ImageHelper.ASPECT_RATIO_BANNER.toFloat()
			ImageType.THUMB -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			else -> baseItem?.primaryImageAspectRatio?.toFloat() ?: ImageHelper.ASPECT_RATIO_7_9.toFloat()
		},
		image = getImage(imageType),
		iconRes = R.drawable.ic_tv,
	)

	BaseRowType.SeriesTimer -> BaseRowItemDisplayConfig(
		aspectRatio = ImageHelper.ASPECT_RATIO_16_9.toFloat(),
		iconRes = R.drawable.ic_tv_timer,
		image = getImage(imageType),
		overrideShowInfo = true,
	)

	BaseRowType.Person -> BaseRowItemDisplayConfig(
		aspectRatio = ImageHelper.ASPECT_RATIO_7_9.toFloat(),
		image = getImage(imageType),
		iconRes = R.drawable.ic_user,
	)

	BaseRowType.Chapter -> BaseRowItemDisplayConfig(
		aspectRatio = ImageHelper.ASPECT_RATIO_16_9.toFloat(),
		image = getImage(imageType),
		iconRes = R.drawable.ic_clapperboard,
	)

	BaseRowType.GridButton -> BaseRowItemDisplayConfig(
		aspectRatio = ImageHelper.ASPECT_RATIO_7_9.toFloat(),
		image = getImage(imageType),
		iconRes = R.drawable.ic_clapperboard,
	)
}

@Composable
@Stable
private fun CardViewHolderContent(
	item: BaseRowItem?,
	focused: Boolean,
	showInfo: Boolean,
	imageType: ImageType,
	staticHeight: Int,
	uniformAspect: Boolean,
) {
	val context = LocalContext.current
	val localDensity = LocalDensity.current

	val title = remember(item, context) { item?.getCardName(context) }
	val subtitle = remember(item, context) { item?.getSubText(context) }
	val liveTvText = remember(item, context) { item?.getLiveTvCardText(context) }
	val displayConfig = remember(item, imageType, uniformAspect) { item?.getDisplayConfig(imageType, uniformAspect) }
	if (item == null || displayConfig == null) return

	val image = displayConfig.image
	val aspectRatio = displayConfig.aspectRatio.takeIf { it >= 0.1f }
		?: image?.aspectRatio?.takeIf { it >= 0.1f } ?: 1f

	val size = when (item.staticHeight) {
		true -> DpSize(staticHeight.dp * aspectRatio, staticHeight.dp)
		false if (aspectRatio > 1f) -> DpSize(130.dp * aspectRatio, 130.dp)
		else -> DpSize(150.dp * aspectRatio, 150.dp)
	}

	val usePreview = if (liveTvText != null) false else displayConfig.overrideShowInfo ?: showInfo

	val card = @Composable {
		ItemCard(
			backgroundColor = displayConfig.backgroundColor,
			focused = focused,
			image = {
				if (image != null) {
					val api = koinInject<ApiClient>()
					AsyncImage(
						url = image.getUrl(
							api,
							maxWidth = with(localDensity) { size.width.roundToPx() },
							maxHeight = with(localDensity) { size.height.roundToPx() },
						),
						blurHash = image.blurHash,
						aspectRatio = aspectRatio,
						scaleType = displayConfig.scaleType ?: ImageView.ScaleType.CENTER_CROP,
						modifier = displayConfig.imageHeightRes?.let { heightRes ->
							Modifier
								.fillMaxWidth()
								.height(dimensionResource(heightRes))
								.align(Alignment.Center)
						} ?: Modifier
							.fillMaxSize()
							.align(Alignment.Center)
					)
				} else if (item is GridButtonBaseRowItem && item.gridButton.imageRes != null) {
					Image(
						painter = painterResource(item.gridButton.imageRes),
						contentDescription = null,
						modifier = Modifier
							.fillMaxSize()
					)
				} else {
					Image(
						painter = painterResource(displayConfig.iconRes),
						contentDescription = null,
						modifier = Modifier
							.fillMaxSize(0.4f)
							.align(Alignment.Center)
					)
				}
			},
			overlay = {
				val showInfo = !usePreview && item.showCardInfoOverlay
				item.baseItem?.let { baseItem ->
					ItemCardBaseItemOverlay(
						item = baseItem,
						streamBadgeItem = (item as? BaseItemDtoBaseRowItem)?.streamBadgeItem ?: baseItem,
						footer = {
							if (liveTvText != null) {
								LiveTvCardFooter(liveTvText)
							} else if (showInfo && title != null) {
								val focusModifier = if (focused) Modifier.basicMarquee(
									iterations = Int.MAX_VALUE,
									initialDelayMillis = 0,
								) else Modifier

								Box(
									modifier = Modifier
										.fillMaxWidth()
										.background(Tokens.Color.colorBluegrey900.copy(alpha = 0.6f), JellyfinTheme.shapes.extraSmall),
								) {
									Text(
										text = title,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis,
										textAlign = TextAlign.Center,
										color = Tokens.Color.colorWhite,
										modifier = Modifier
											.then(focusModifier)
											.padding(Tokens.Space.spaceXs),
									)
								}
							}
						}
					)
				}
			},
			modifier = Modifier
				.size(size)
		)
	}

	if (usePreview) {
		val focusModifier = if (focused) Modifier.basicMarquee(
			iterations = Int.MAX_VALUE,
			initialDelayMillis = 0,
		) else Modifier

		ItemPreview(
			card = { card() },
			title = title?.let { text ->
				{
					Text(
						text = text,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						textAlign = TextAlign.Center,
						modifier = Modifier.then(focusModifier),
					)
				}
			},
			subtitle = subtitle?.let { text ->
				{
					Text(
						text = text,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						textAlign = TextAlign.Center,
						modifier = Modifier.then(focusModifier),
					)
				}
			},
		)
	} else {
		card()
	}
}

private data class LiveTvCardText(
	val channelNumber: String?,
	val channelName: String?,
	val program: String?,
	val time: String?,
)

private fun BaseRowItem.getLiveTvCardText(context: Context): LiveTvCardText? {
	val item = baseItem ?: return null
	val program = when (baseRowType) {
		BaseRowType.LiveTvChannel -> item.currentProgram
		BaseRowType.LiveTvProgram -> item
		else -> return null
	}
	val channelNumber = when (baseRowType) {
		BaseRowType.LiveTvChannel -> item.number
		BaseRowType.LiveTvProgram -> item.channelNumber
	}.takeIf { !it.isNullOrBlank() }
	val channelName = when (baseRowType) {
		BaseRowType.LiveTvChannel -> item.name
		BaseRowType.LiveTvProgram -> item.channelName
	}.takeIf { !it.isNullOrBlank() }
	val time = program?.let { currentProgram ->
		val startDate = currentProgram.startDate ?: return@let null
		val endDate = currentProgram.endDate ?: return@let null
		val formatter = context.getTimeFormatter()
		"${formatter.format(startDate)}-${formatter.format(endDate)}"
	}

	return LiveTvCardText(
		channelNumber = channelNumber,
		channelName = channelName,
		program = program?.name ?: context.getString(R.string.no_program_data),
		time = time,
	)
}

@Composable
private fun LiveTvCardFooter(
	text: LiveTvCardText,
) {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.background(Tokens.Color.colorBluegrey900.copy(alpha = 0.72f), JellyfinTheme.shapes.extraSmall)
			.padding(horizontal = Tokens.Space.spaceSm, vertical = Tokens.Space.spaceXs),
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(Tokens.Space.spaceSm),
			verticalAlignment = Alignment.Top,
		) {
			Column(
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.Center,
				modifier = Modifier.size(width = 32.dp, height = 42.dp),
			) {
				text.channelNumber?.let { channelNumber ->
					Text(
						text = channelNumber,
						color = Tokens.Color.colorWhite,
						fontSize = 10.sp,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						textAlign = TextAlign.Center,
						modifier = Modifier.fillMaxWidth(),
					)
				}
			}
			Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
				text.channelName?.let { channelName ->
					Text(
						text = channelName,
						color = Tokens.Color.colorWhite,
						fontSize = 11.sp,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
				text.program?.let { program ->
					Text(
						text = program,
						color = Tokens.Color.colorWhite,
						fontSize = 12.sp,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
				text.time?.let { time ->
					Text(
						text = time,
						color = Tokens.Color.colorWhite,
						fontSize = 10.sp,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
		}
	}
}
