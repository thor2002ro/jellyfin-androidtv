package org.jellyfin.androidtv.ui.browsing

import androidx.leanback.widget.Presenter
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.constant.LibraryCardSpacing
import org.jellyfin.androidtv.ui.presentation.BROWSE_LIST_THUMBNAIL_HEIGHT_DP
import org.jellyfin.androidtv.ui.presentation.BrowseCardGridPresenter
import org.jellyfin.androidtv.ui.presentation.ComposeBrowseListPresenter
import org.jellyfin.androidtv.ui.presentation.ComposeVerticalGridPresenter
import org.jellyfin.androidtv.ui.presentation.HorizontalGridPresenter
import org.jellyfin.androidtv.ui.presentation.resolveBrowseGridSpacing
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber.Forest.w
import kotlin.math.abs
import kotlin.math.max

internal data class BrowseGridLayout(
	val cardHeight: Int,
	val horizontalSpacing: Int,
	val verticalSpacing: Int,
	val paddingLeft: Int,
	val paddingTop: Int,
	val cardsScreenEstimate: Int,
	val cardsScreenStride: Int,
)

internal fun calculateBrowseGridLayout(
	presenter: Presenter,
	gridWidth: Int,
	gridHeight: Int,
	cardFocusScale: Double,
	imageType: ImageType,
	folder: BaseItemDto,
	showCardTitles: Boolean,
	cardSpacing: LibraryCardSpacing,
): BrowseGridLayout {
	val minNumCards = 5
	val cardSpacingPct = 1.0
	val bannerSpacingPct = 0.5
	if (presenter is ComposeBrowseListPresenter) {
		val verticalSpacing = resolveBrowseGridSpacing(6, cardSpacing)
		val visible = max(gridHeight / (BROWSE_LIST_THUMBNAIL_HEIGHT_DP + verticalSpacing), minNumCards)
		return BrowseGridLayout(BROWSE_LIST_THUMBNAIL_HEIGHT_DP, 0, verticalSpacing, 0, 12, visible, visible)
	}
	var cardsScreenEstimate = 0
	var cardsScreenStride = 0
	val cardScaling = max(cardFocusScale - 1.0, 0.0)
	var cardHeightInt = 100
	var spacingHorizontalInt = 0
	var spacingVerticalInt = 0
	var paddingLeftInt = 0
	var paddingTopInt = 0
	var numRows = 0
	var numCols = 0

	if (presenter is HorizontalGridPresenter) {
		numRows = (presenter as BrowseCardGridPresenter).getSpanCount()
		if (numRows == 1) { // reduce size so minimal cards are shown
			numRows = 0
			numCols = minNumCards
		}
	} else if (presenter is ComposeVerticalGridPresenter) {
		numCols = (presenter as BrowseCardGridPresenter).getSpanCount()
	}

	if (numRows > 0) {
		val paddingPct = cardScaling / numRows
		val spacingPct = ((paddingPct / 2.0) * cardSpacingPct) * (numRows - 1)

		val wastedSpacePct = paddingPct + spacingPct
		val usableCardSpace = gridHeight / (1.0 + wastedSpacePct) // decrease size
		val rowHeight = usableCardSpace / numRows

		// fix any rounding errors and make pixel perfect
		val rowHeightInt = Math.round(rowHeight).toInt()
		cardHeightInt = getHorizontalBrowseCardHeight(rowHeightInt, showCardTitles)
		val cardPaddingTopBottomAdj = rowHeightInt * cardScaling
		spacingVerticalInt = max(
			(Math.round((cardPaddingTopBottomAdj / 2.0) * cardSpacingPct)).toInt(),
			0
		) // round spacing
		val paddingTopBottomInt =
			gridHeight - ((rowHeightInt * numRows) + (spacingVerticalInt * (numRows - 1)))
		paddingTopInt = max(paddingTopBottomInt / 2, 0)

		val sumSize =
			(rowHeightInt * numRows) + (spacingVerticalInt * (numRows - 1)) + (paddingTopInt * 2)
		if (abs(sumSize - gridHeight) > 2) {
			w(
				"setAutoCardGridValues calculation delta > 2, something is off GridHeight <%s> sumSize <%s>!",
				gridHeight,
				sumSize
			)
		}
		val cardWidthInt =
			getCardWidthBy(cardHeightInt.toDouble(), imageType, folder).toInt()
		paddingLeftInt = Math.round((cardWidthInt * cardScaling) / 2.0).toInt()
		spacingHorizontalInt =
			max((Math.round(paddingLeftInt * cardSpacingPct)).toInt(), 0) // round spacing
		if (imageType == ImageType.BANNER) {
			spacingHorizontalInt = max(
				(Math.round(paddingLeftInt * bannerSpacingPct)).toInt(),
				0
			) // round spacing
		}
		val cardsCol =
			Math.round((gridWidth.toDouble() / (cardWidthInt + spacingHorizontalInt)) + 0.5)
				.toInt()
		cardsScreenEstimate = numRows * cardsCol
		cardsScreenStride = numRows
	} else if (numCols > 0) {
		val paddingPct = cardScaling / numCols
		var spacingPct = ((paddingPct / 2.0) * cardSpacingPct) * (numCols - 1)
		if (imageType == ImageType.BANNER) {
			spacingPct =
				((paddingPct / 2.0) * bannerSpacingPct) * (numCols - 1)
		}

		val wastedSpacePct = paddingPct + spacingPct
		val usableCardSpace = gridWidth / (1.0 + wastedSpacePct) // decrease size
		val cardWidth = usableCardSpace / numCols

		// fix any rounding errors and make pixel perfect
		cardHeightInt = Math.round(getCardHeightBy(cardWidth, imageType, folder)).toInt()
		val cardWidthInt =
			getCardWidthBy(cardHeightInt.toDouble(), imageType, folder).toInt()
		val cardPaddingLeftRightAdj = cardWidthInt * cardScaling
		spacingHorizontalInt = max(
			(Math.round((cardPaddingLeftRightAdj / 2.0) * cardSpacingPct)).toInt(),
			0
		) // round spacing
		if (imageType == ImageType.BANNER) {
			spacingHorizontalInt = max(
				(Math.round((cardPaddingLeftRightAdj / 2.0) * bannerSpacingPct)).toInt(),
				0
			) // round spacing
		}
		val paddingLeftRightInt =
			gridWidth - ((cardWidthInt * numCols) + (spacingHorizontalInt * (numCols - 1)))
		paddingLeftInt = max(paddingLeftRightInt / 2, 0)

		val sumSize =
			(cardWidthInt * numCols) + (spacingHorizontalInt * (numCols - 1)) + (paddingLeftInt * 2)
		if (abs(sumSize - gridWidth) > 2) {
			w(
				"setAutoCardGridValues calculation delta > 2, something is off GridWidth <%s> sumSize <%s>!",
				gridWidth,
				sumSize
			)
		}
		paddingTopInt = Math.round((cardHeightInt * cardScaling) / 2.0).toInt()
		spacingVerticalInt =
			max((Math.round(paddingTopInt * cardSpacingPct)).toInt(), 0) // round spacing
		val cardsRow =
			Math.round((gridHeight.toDouble() / (cardHeightInt + spacingVerticalInt)) + 0.5)
				.toInt()
		cardsScreenEstimate = numCols * cardsRow
		cardsScreenStride = numCols
	}


	return BrowseGridLayout(
		cardHeightInt,
		resolveBrowseGridSpacing(spacingHorizontalInt, cardSpacing),
		resolveBrowseGridSpacing(spacingVerticalInt, cardSpacing),
		paddingLeftInt, paddingTopInt, cardsScreenEstimate, cardsScreenStride,
	)
}
