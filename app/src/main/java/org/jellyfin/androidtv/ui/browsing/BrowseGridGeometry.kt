package org.jellyfin.androidtv.ui.browsing

import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType

private fun browseCardAspectRatio(imageType: ImageType, folder: BaseItemDto): Double = when (imageType) {
	ImageType.POSTER -> if (
		folder.type in setOf(BaseItemKind.AUDIO, BaseItemKind.GENRE, BaseItemKind.MUSIC_ALBUM, BaseItemKind.MUSIC_ARTIST, BaseItemKind.MUSIC_GENRE) ||
		(folder.type == BaseItemKind.COLLECTION_FOLDER && folder.collectionType == CollectionType.MUSIC)
	) 1.0 else ImageHelper.ASPECT_RATIO_2_3
	ImageType.THUMB -> ImageHelper.ASPECT_RATIO_16_9
	ImageType.BANNER -> ImageHelper.ASPECT_RATIO_BANNER
}

internal fun getCardWidthBy(cardHeight: Double, imageType: ImageType, folder: BaseItemDto) =
	cardHeight * browseCardAspectRatio(imageType, folder)

internal fun getCardHeightBy(cardWidth: Double, imageType: ImageType, folder: BaseItemDto) =
	cardWidth / browseCardAspectRatio(imageType, folder)
