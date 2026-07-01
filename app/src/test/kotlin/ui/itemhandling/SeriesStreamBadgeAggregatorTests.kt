package org.jellyfin.androidtv.ui.itemhandling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import java.util.UUID

class SeriesStreamBadgeAggregatorTests : FunSpec({
	test("grouped series badges enrich every series") {
		val firstSeriesId = UUID.randomUUID()
		val secondSeriesId = UUID.randomUUID()

		val items = listOf(
			BaseItemDto(id = firstSeriesId, type = BaseItemKind.SERIES),
			BaseItemDto(id = secondSeriesId, type = BaseItemKind.SERIES),
			BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE),
		).withSeriesStreamBadgeSources(mapOf(
			firstSeriesId to listOf(episode(firstSeriesId, source(stream(MediaStreamType.AUDIO, 0, "eng", isDefault = true)))),
			secondSeriesId to listOf(episode(secondSeriesId, source(stream(MediaStreamType.AUDIO, 0, "jpn", isDefault = true)))),
		))

		items[0].defaultAudioLanguage() shouldBe "eng"
		items[1].defaultAudioLanguage() shouldBe "jpn"
		items[2].mediaSources shouldBe null
	}

	test("grouped series badges aggregate episode samples") {
		val seriesId = UUID.randomUUID()

		val item = BaseItemDto(id = seriesId, type = BaseItemKind.SERIES)
			.withSeriesStreamBadgeSource(listOf(
				episode(seriesId, source(
					stream(MediaStreamType.AUDIO, 0, "eng", isDefault = true),
					stream(MediaStreamType.SUBTITLE, 1, "fre"),
				)),
				episode(seriesId, source(
					stream(MediaStreamType.AUDIO, 0, "eng", isDefault = true),
					stream(MediaStreamType.SUBTITLE, 1, "eng"),
				)),
				episode(seriesId, source(
					stream(MediaStreamType.AUDIO, 0, "jpn", isDefault = true),
				)),
			))

		val mediaSource = item.mediaSources?.single()
		mediaSource?.mediaStreams
			?.single { it.type == MediaStreamType.AUDIO && it.index == mediaSource.defaultAudioStreamIndex }
			?.language shouldBe "eng"
		mediaSource?.defaultSubtitleStreamIndex shouldBe -1
		mediaSource?.mediaStreams
			?.filter { it.type == MediaStreamType.SUBTITLE }
			?.map { it.language }
			.shouldContainExactlyInAnyOrder("fre", "eng")
	}

	test("grouped series badges keep subtitles from later season samples") {
		val seriesId = UUID.randomUUID()

		val item = BaseItemDto(id = seriesId, type = BaseItemKind.SERIES)
			.withSeriesStreamBadgeSource(listOf(
				episode(seriesId, source(stream(MediaStreamType.AUDIO, 0, "eng", isDefault = true))),
				episode(seriesId, source(stream(MediaStreamType.AUDIO, 0, "eng", isDefault = true))),
				episode(seriesId, source(
					stream(MediaStreamType.AUDIO, 0, "jpn", isDefault = true),
					stream(MediaStreamType.SUBTITLE, 1, "eng"),
				)),
			))

		item.mediaSources?.single()?.mediaStreams
			?.filter { it.type == MediaStreamType.SUBTITLE }
			?.map { it.language } shouldBe listOf("eng")
	}

	test("series badges aggregate season badge items") {
		val seriesId = UUID.randomUUID()
		val firstSeason = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.SEASON)
			.withSeriesStreamBadgeSource(listOf(
				episode(seriesId, source(stream(MediaStreamType.AUDIO, 0, "eng", isDefault = true))),
			))
		val secondSeason = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.SEASON)
			.withSeriesStreamBadgeSource(listOf(
				episode(seriesId, source(stream(MediaStreamType.SUBTITLE, 1, "spa"))),
			))

		val item = BaseItemDto(id = seriesId, type = BaseItemKind.SERIES)
			.withSeriesStreamBadgeSource(listOf(firstSeason, secondSeason))

		item.defaultAudioLanguage() shouldBe "eng"
		item.mediaSources?.single()?.mediaStreams
			?.filter { it.type == MediaStreamType.SUBTITLE }
			?.map { it.language } shouldBe listOf("spa")
	}

	test("direct latest videos copy stream badge sources") {
		val episodeId = UUID.randomUUID()
		val seriesId = UUID.randomUUID()

		val items = listOf(
			BaseItemDto(id = episodeId, type = BaseItemKind.EPISODE),
			BaseItemDto(id = seriesId, type = BaseItemKind.SERIES),
		).withDirectStreamBadgeSources(mapOf(
			episodeId to BaseItemDto(
				id = episodeId,
				type = BaseItemKind.EPISODE,
				mediaSources = listOf(source(stream(MediaStreamType.AUDIO, 0, "jpn", isDefault = true))),
			),
			seriesId to BaseItemDto(
				id = seriesId,
				type = BaseItemKind.SERIES,
				mediaSources = listOf(source(stream(MediaStreamType.AUDIO, 0, "eng", isDefault = true))),
			),
		))

		items[0].defaultAudioLanguage() shouldBe "jpn"
		items[1].mediaSources shouldBe null
	}

	test("season badges use episode samples") {
		val seasonId = UUID.randomUUID()

		val item = BaseItemDto(id = seasonId, type = BaseItemKind.SEASON)
			.withSeriesStreamBadgeSource(listOf(
				episode(UUID.randomUUID(), source(stream(MediaStreamType.AUDIO, 0, "kor", isDefault = true))),
				episode(UUID.randomUUID(), source(stream(MediaStreamType.SUBTITLE, 1, "eng"))),
			))

		item.defaultAudioLanguage() shouldBe "kor"
		item.mediaSources?.single()?.mediaStreams
			?.filter { it.type == MediaStreamType.SUBTITLE }
			?.map { it.language } shouldBe listOf("eng")
	}

	test("video-only samples do not clear existing badges") {
		val seasonId = UUID.randomUUID()
		val item = BaseItemDto(
			id = seasonId,
			type = BaseItemKind.SEASON,
			mediaSources = listOf(source(stream(MediaStreamType.AUDIO, 0, "eng", isDefault = true))),
		).withSeriesStreamBadgeSource(listOf(
			episode(UUID.randomUUID(), source(stream(MediaStreamType.VIDEO, 0, "und"))),
		))

		item.defaultAudioLanguage() shouldBe "eng"
	}

	test("season badge cache removes by related ids") {
		val seriesId = UUID.randomUUID()
		val seasonId = UUID.randomUUID()
		val sample = episode(seriesId, source(stream(MediaStreamType.AUDIO, 0, "eng", isDefault = true)))

		SeriesStreamBadgeCache.clear()
		SeriesStreamBadgeCache.save(seriesId, seasonId, listOf(sample))
		SeriesStreamBadgeCache.get(seasonId)?.single()?.defaultAudioLanguage() shouldBe "eng"

		SeriesStreamBadgeCache.remove(setOf(UUID.randomUUID()))
		SeriesStreamBadgeCache.get(seasonId)?.single()?.defaultAudioLanguage() shouldBe "eng"

		SeriesStreamBadgeCache.remove(setOf(sample.id))
		SeriesStreamBadgeCache.get(seasonId) shouldBe null

		SeriesStreamBadgeCache.save(seriesId, seasonId, listOf(sample))
		SeriesStreamBadgeCache.remove(setOf(seriesId))
		SeriesStreamBadgeCache.get(seasonId) shouldBe null
	}
})

private fun BaseItemDto.defaultAudioLanguage(): String? {
	val mediaSource = mediaSources?.single()
	return mediaSource?.mediaStreams
		?.single { it.type == MediaStreamType.AUDIO && it.index == mediaSource.defaultAudioStreamIndex }
		?.language
}

private fun episode(seriesId: UUID, source: MediaSourceInfo) = BaseItemDto(
	id = UUID.randomUUID(),
	type = BaseItemKind.EPISODE,
	seriesId = seriesId,
	mediaSources = listOf(source),
)

private fun source(vararg streams: MediaStream) = MediaSourceInfo(
	protocol = MediaProtocol.FILE,
	type = MediaSourceType.DEFAULT,
	isRemote = false,
	readAtNativeFramerate = false,
	ignoreDts = false,
	ignoreIndex = false,
	genPtsInput = false,
	supportsTranscoding = false,
	supportsDirectStream = true,
	supportsDirectPlay = true,
	isInfiniteStream = false,
	requiresOpening = false,
	requiresClosing = false,
	requiresLooping = false,
	supportsProbing = false,
	mediaStreams = streams.toList(),
	transcodingSubProtocol = MediaStreamProtocol.HTTP,
	defaultAudioStreamIndex = 0,
	defaultSubtitleStreamIndex = -1,
	hasSegments = false,
)

private fun stream(
	type: MediaStreamType,
	index: Int,
	language: String,
	isDefault: Boolean = false,
) = MediaStream(
	language = language,
	isInterlaced = false,
	isDefault = isDefault,
	isForced = false,
	isHearingImpaired = false,
	type = type,
	index = index,
	isExternal = false,
	isTextSubtitleStream = type == MediaStreamType.SUBTITLE,
	supportsExternalStream = false,
)
