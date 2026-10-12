package org.jellyfin.androidtv.ui.itemhandling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSourceInfo
import java.util.UUID
import org.jellyfin.androidtv.constant.ImageType as BaseRowImageType
import org.jellyfin.androidtv.util.apiclient.JellyfinImageSource
import org.jellyfin.sdk.model.api.ImageType

class BaseItemDtoBaseRowItemTests : FunSpec({
	test("stream badge media is separate from the card item") {
		val item = BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.MOVIE,
		)
		val badgeSources = emptyList<MediaSourceInfo>()
		val rowItem = BaseItemDtoBaseRowItem(
			item = item,
			streamBadgeMediaSources = badgeSources,
		)

		rowItem.baseItem shouldBe item
		rowItem.baseItem?.mediaSources shouldBe null
		rowItem.streamBadgeItem?.mediaSources shouldBe badgeSources
		(rowItem.streamBadgeItem === rowItem.streamBadgeItem) shouldBe true
	}

	test("stream badge media changes row equality") {
		val item = BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.MOVIE,
		)

		BaseItemDtoBaseRowItem(item) shouldNotBe BaseItemDtoBaseRowItem(
			item = item,
			streamBadgeMediaSources = emptyList(),
		)
	}

	test("only resume row items request remaining time badges") {
		val item = BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.MOVIE,
		)

		BaseItemDtoBaseRowItem(item).showRemainingTimeBadge shouldBe false
		ResumeItemBaseRowItem(item, preferParentThumb = false, staticHeight = true).showRemainingTimeBadge shouldBe true
	}

	test("remaining time badge changes row equality") {
		val item = BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.MOVIE,
		)

		BaseItemDtoBaseRowItem(item) shouldNotBe ResumeItemBaseRowItem(item, preferParentThumb = false, staticHeight = true)
	}

	test("base item row factory can request remaining time badges") {
		val item = BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.MOVIE,
		)

		item.toBaseItemRowItem(
			preferParentThumb = false,
			staticHeight = true,
			showRemainingTimeBadge = true,
		).showRemainingTimeBadge shouldBe true
		item.toBaseItemRowItem(
			preferParentThumb = false,
			staticHeight = true,
		).showRemainingTimeBadge shouldBe false
	}

	test("copy with item preserves resume row marker") {
		val item = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE)
		val updatedBadgeSources = emptyList<MediaSourceInfo>()
		val rowItem = ResumeItemBaseRowItem(
			item = item,
			preferParentThumb = false,
			staticHeight = true,
			selectAction = BaseRowItemSelectAction.Play,
			preferSeriesPoster = true,
		)
		val copiedRowItem = rowItem.copyWithItem(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE))
		val updatedStreamBadgeRowItem = rowItem.copyWithItem(
			item = item,
			streamBadgeMediaSources = updatedBadgeSources,
		)

		copiedRowItem.showRemainingTimeBadge shouldBe true
		copiedRowItem.selectAction shouldBe BaseRowItemSelectAction.Play
		copiedRowItem.preferSeriesPoster shouldBe true
		copiedRowItem.streamBadgeMediaSources shouldBe null
		updatedStreamBadgeRowItem.showRemainingTimeBadge shouldBe true
		updatedStreamBadgeRowItem.streamBadgeMediaSources shouldBe updatedBadgeSources
	}

	test("wide episode cards prefer the series thumbnail over episode artwork") {
		val seriesId = UUID.randomUUID()
		val episode = BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.EPISODE,
			seriesId = seriesId,
			seriesThumbImageTag = "series-thumb",
			seriesPrimaryImageTag = "series-poster",
			imageTags = mapOf(
				ImageType.THUMB to "episode-thumb",
				ImageType.PRIMARY to "episode-primary",
			),
		)

		val image = BaseItemDtoBaseRowItem(
			item = episode,
			preferParentThumb = true,
			preferSeriesPoster = true,
		).getImage(BaseRowImageType.THUMB)

		image?.source shouldBe JellyfinImageSource.SERIES
		image?.item shouldBe seriesId
		image?.type shouldBe ImageType.THUMB
		image?.tag shouldBe "series-thumb"
	}

	test("wide season cards prefer the series thumbnail over season artwork") {
		val seriesId = UUID.randomUUID()
		val season = BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.SEASON,
			seriesId = seriesId,
			seriesThumbImageTag = "series-thumb",
			seriesPrimaryImageTag = "series-poster",
			imageTags = mapOf(
				ImageType.THUMB to "season-thumb",
				ImageType.PRIMARY to "season-primary",
			),
		)

		val image = BaseItemDtoBaseRowItem(
			item = season,
			preferParentThumb = true,
		).getImage(BaseRowImageType.THUMB)

		image?.source shouldBe JellyfinImageSource.SERIES
		image?.item shouldBe seriesId
		image?.type shouldBe ImageType.THUMB
		image?.tag shouldBe "series-thumb"
	}

	test("wide season cards use the parent series thumbnail returned by latest media") {
		val seasonId = UUID.randomUUID()
		val seriesId = UUID.randomUUID()
		val season = BaseItemDto(
			id = seasonId,
			type = BaseItemKind.SEASON,
			seriesId = seriesId,
			parentThumbItemId = seriesId,
			parentThumbImageTag = "parent-series-thumb",
			seriesPrimaryImageTag = "series-poster",
			imageTags = mapOf(ImageType.PRIMARY to "season-primary"),
			primaryImageAspectRatio = 2.0 / 3.0,
		)

		val image = BaseItemDtoBaseRowItem(
			item = season,
			preferParentThumb = true,
		).getImage(BaseRowImageType.THUMB)

		image?.source shouldBe JellyfinImageSource.PARENT
		image?.item shouldBe seriesId
		image?.type shouldBe ImageType.THUMB
		image?.tag shouldBe "parent-series-thumb"
	}

})
