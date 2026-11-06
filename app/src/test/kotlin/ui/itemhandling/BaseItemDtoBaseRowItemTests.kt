package org.jellyfin.androidtv.ui.itemhandling

import android.content.Context
import android.content.res.Resources
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.androidtv.R
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSourceInfo
import java.util.UUID

class BaseItemDtoBaseRowItemTests : FunSpec({
	val context = mockk<Context>()

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

	test("recently added season shows series title and season subtitle") {
		val season = season(
			name = "Season 1",
			seriesName = "Example Series",
		)

		val rowItem = latestMediaRowItem(
			item = season,
			preferParentThumb = false,
			staticHeight = false,
		)

		rowItem.getCardName(context) shouldBe "Example Series"
		rowItem.getSubText(context) shouldBe "Season 1"
	}

	test("recently added season falls back when series title is missing") {
		val season = season(name = "Season 1")

		val rowItem = BaseItemDtoBaseRowItem(
			item = season,
			showParentTitle = true,
		)

		rowItem.getCardName(context) shouldBe "Season 1"
		rowItem.getSubText(context) shouldBe ""
	}

	test("regular season keeps season title and episode count") {
		val season = season(
			name = "Season 1",
			seriesName = "Example Series",
			childCount = 8,
		)
		val resources = mockk<Resources>()
		every { context.resources } returns resources
		every { resources.getQuantityString(R.plurals.episodes, 8, 8) } returns "8 episodes"

		val rowItem = BaseItemDtoBaseRowItem(item = season)

		rowItem.getCardName(context) shouldBe "Season 1"
		rowItem.getSubText(context) shouldBe "8 episodes"
	}

	test("copying recently added season preserves parent title presentation") {
		val season = season(
			name = "Season 1",
			seriesName = "Example Series",
		)
		val rowItem = BaseItemDtoBaseRowItem(
			item = season,
			showParentTitle = true,
		)

		val copied = rowItem.copyWithItem(
			item = season,
			streamBadgeMediaSources = emptyList(),
		)

		copied.getCardName(context) shouldBe "Example Series"
		copied.getSubText(context) shouldBe "Season 1"
	}

	test("parent title presentation participates in row content equality") {
		val season = season(
			name = "Season 1",
			seriesName = "Example Series",
		)

		BaseItemDtoBaseRowItem(item = season, showParentTitle = true) shouldNotBe
			BaseItemDtoBaseRowItem(item = season, showParentTitle = false)
	}
})

private fun season(
	name: String,
	seriesName: String? = null,
	childCount: Int? = null,
) = BaseItemDto(
	id = UUID.randomUUID(),
	type = BaseItemKind.SEASON,
	name = name,
	seriesName = seriesName,
	childCount = childCount,
)
