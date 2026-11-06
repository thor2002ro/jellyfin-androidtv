package org.jellyfin.androidtv.ui.itemhandling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.util.sdk.videoBadgeResolutionText
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

	test("grouped series badges dedupe languages by rendered badge") {
		val seriesId = UUID.randomUUID()

		val item = BaseItemDto(id = seriesId, type = BaseItemKind.SERIES)
			.withSeriesStreamBadgeSource(listOf(
				episode(seriesId, source(stream(MediaStreamType.AUDIO, 0, "und", isDefault = true))),
				episode(seriesId, source(stream(MediaStreamType.AUDIO, 0, "unknown", isDefault = true))),
				episode(seriesId, source(stream(MediaStreamType.SUBTITLE, 1, "undefined"))),
				episode(seriesId, source(stream(MediaStreamType.SUBTITLE, 1, "undetermined"))),
			))

		val streams = item.mediaSources.orEmpty().flatMap { it.mediaStreams.orEmpty() }
		streams.filter { it.type == MediaStreamType.AUDIO }.map { it.language } shouldBe listOf("und")
		streams.filter { it.type == MediaStreamType.SUBTITLE }.map { it.language } shouldBe listOf("undefined")
	}

	test("grouped series badges aggregate video metadata") {
		val seriesId = UUID.randomUUID()

		val item = BaseItemDto(id = seriesId, type = BaseItemKind.SERIES)
			.withSeriesStreamBadgeSource(listOf(
				episode(seriesId, source(stream(MediaStreamType.VIDEO, 0, "und", codec = "h264", width = 1920, height = 1080))),
				episode(seriesId, source(stream(MediaStreamType.VIDEO, 0, "und", codec = "hevc", width = 3840, height = 2160))),
			))

		item.mediaSources?.single()?.mediaStreams
			?.filter { it.type == MediaStreamType.VIDEO }
			?.map { "${it.width}x${it.height}:${it.codec}" } shouldBe listOf("1920x1080:h264", "3840x2160:hevc")
	}

	test("grouped series badges aggregate multiple media sources from one sample") {
		val seriesId = UUID.randomUUID()

		val item = BaseItemDto(id = seriesId, type = BaseItemKind.SERIES)
			.withSeriesStreamBadgeSource(listOf(
				episode(
					seriesId,
					source(stream(MediaStreamType.VIDEO, 0, "und", codec = "h264", width = 1920, height = 1080)),
					source(stream(MediaStreamType.VIDEO, 0, "und", codec = "hevc", width = 3840, height = 2160)),
				),
			))

		item.mediaSources?.single()?.mediaStreams
			?.filter { it.type == MediaStreamType.VIDEO }
			?.map { "${it.width}x${it.height}:${it.codec}" } shouldBe listOf("1920x1080:h264", "3840x2160:hevc")
	}

	test("grouped series badges dedupe video by rendered badge") {
		val seriesId = UUID.randomUUID()

		val item = BaseItemDto(id = seriesId, type = BaseItemKind.SERIES)
			.withSeriesStreamBadgeSource(listOf(
				episode(seriesId, source(stream(MediaStreamType.VIDEO, 0, "und", codec = "h264", width = 1920, height = 1080))),
				episode(seriesId, source(stream(MediaStreamType.VIDEO, 0, "und", codec = "H264", width = 1840, height = 1040))),
			))

		item.mediaSources?.single()?.mediaStreams
			?.filter { it.type == MediaStreamType.VIDEO }
			?.map { "${it.videoBadgeResolutionText()}:${it.codec}" } shouldBe listOf("1080:h264")
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

	test("direct latest videos copy stream badge sources for all direct video item types") {
		val ids = listOf(
			BaseItemKind.EPISODE,
			BaseItemKind.MOVIE,
			BaseItemKind.MUSIC_VIDEO,
			BaseItemKind.TRAILER,
			BaseItemKind.VIDEO,
		).associateWith { UUID.randomUUID() }

		val items = ids.map { (type, id) -> BaseItemDto(id = id, type = type) }
			.withDirectStreamBadgeSources(ids.map { (type, id) ->
				id to BaseItemDto(
					id = id,
					type = type,
					mediaSources = listOf(source(
						stream(MediaStreamType.VIDEO, 0, "und", codec = "h264", width = 1920, height = 1080),
						stream(MediaStreamType.AUDIO, 1, "eng", isDefault = true),
					)),
				)
			}.toMap())

		items.forEach { item ->
			item.defaultAudioLanguage() shouldBe "eng"
			item.mediaSources.orEmpty()
				.flatMap { it.mediaStreams.orEmpty() }
				.single { it.type == MediaStreamType.VIDEO }
				.codec shouldBe "h264"
		}
	}

	test("direct latest videos refresh when existing video badges are missing language badges") {
		val itemId = UUID.randomUUID()
		val existingSources = listOf(source(stream(MediaStreamType.VIDEO, 0, "und", codec = "h264", width = 1920, height = 1080)))

		val items = listOf(
			BaseItemDto(
				id = itemId,
				type = BaseItemKind.MOVIE,
				mediaSources = existingSources,
			),
		).withDirectStreamBadgeSources(mapOf(
			itemId to BaseItemDto(
				id = itemId,
				type = BaseItemKind.MOVIE,
				mediaSources = listOf(source(
					stream(MediaStreamType.VIDEO, 0, "und", codec = "h264", width = 1920, height = 1080),
					stream(MediaStreamType.AUDIO, 0, "eng", isDefault = true),
				)),
			),
		))

		items.single().defaultAudioLanguage() shouldBe "eng"
		items.single().mediaSources.orEmpty()
			.flatMap { it.mediaStreams.orEmpty() }
			.single { it.type == MediaStreamType.VIDEO }
			.codec shouldBe "h264"
	}

	test("direct latest videos refresh when existing badge source has partial video metadata") {
		val itemId = UUID.randomUUID()

		val items = listOf(
			BaseItemDto(
				id = itemId,
				type = BaseItemKind.MOVIE,
				mediaSources = listOf(source(
					stream(MediaStreamType.VIDEO, 0, "und", width = 1920, height = 1080),
					stream(MediaStreamType.AUDIO, 1, "eng", isDefault = true),
				)),
			),
		).withDirectStreamBadgeSources(mapOf(
			itemId to BaseItemDto(
				id = itemId,
				type = BaseItemKind.MOVIE,
				mediaSources = listOf(source(
					stream(MediaStreamType.VIDEO, 0, "und", codec = "h264", width = 1920, height = 1080),
					stream(MediaStreamType.AUDIO, 1, "eng", isDefault = true),
				)),
			),
		))

		items.single().mediaSources?.single()?.mediaStreams
			?.filter { it.type == MediaStreamType.VIDEO }
			?.map { it.codec } shouldBe listOf("h264")
	}

	test("direct latest videos keep existing audio when refresh only has video") {
		val itemId = UUID.randomUUID()

		val items = listOf(
			BaseItemDto(
				id = itemId,
				type = BaseItemKind.MOVIE,
				mediaSources = listOf(source(stream(MediaStreamType.AUDIO, 0, "eng", isDefault = true))),
			),
		).withDirectStreamBadgeSources(mapOf(
			itemId to BaseItemDto(
				id = itemId,
				type = BaseItemKind.MOVIE,
				mediaSources = listOf(source(stream(MediaStreamType.VIDEO, 0, "und", codec = "h264", width = 1920, height = 1080))),
			),
		))

		val streams = items.single().mediaSources.orEmpty().flatMap { it.mediaStreams.orEmpty() }
		streams.filter { it.type == MediaStreamType.AUDIO }.map { it.language } shouldBe listOf("eng")
		streams.filter { it.type == MediaStreamType.VIDEO }.map { it.codec } shouldBe listOf("h264")
	}

	test("direct latest videos skip refresh when existing badge sources are split but complete") {
		val itemId = UUID.randomUUID()
		val existingSources = listOf(
			source(stream(MediaStreamType.VIDEO, 0, "und", codec = "h264", width = 1920, height = 1080)),
			source(stream(MediaStreamType.AUDIO, 0, "eng", isDefault = true)),
		)

		val items = listOf(
			BaseItemDto(
				id = itemId,
				type = BaseItemKind.MOVIE,
				mediaSources = existingSources,
			),
		).withDirectStreamBadgeSources(mapOf(
			itemId to BaseItemDto(
				id = itemId,
				type = BaseItemKind.MOVIE,
				mediaSources = listOf(source(stream(MediaStreamType.AUDIO, 0, "jpn", isDefault = true))),
			),
		))

		items.single().mediaSources shouldBe existingSources
	}

	test("direct latest videos refresh when video badges are complete and language is unbadgeable") {
		val itemId = UUID.randomUUID()
		val existingSources = listOf(source(
			stream(MediaStreamType.VIDEO, 0, "und", codec = "h264", width = 1920, height = 1080),
			stream(MediaStreamType.AUDIO, 1, ""),
			stream(MediaStreamType.SUBTITLE, 2, ""),
		))

		val items = listOf(
			BaseItemDto(
				id = itemId,
				type = BaseItemKind.MOVIE,
				mediaSources = existingSources,
			),
		).withDirectStreamBadgeSources(mapOf(
			itemId to BaseItemDto(
				id = itemId,
				type = BaseItemKind.MOVIE,
				mediaSources = listOf(source(stream(MediaStreamType.AUDIO, 0, "jpn", isDefault = true))),
			),
		))

		val streams = items.single().mediaSources.orEmpty().flatMap { it.mediaStreams.orEmpty() }
		streams.any { it.type == MediaStreamType.AUDIO && it.language == "jpn" } shouldBe true
		streams.single { it.type == MediaStreamType.VIDEO }.codec shouldBe "h264"
	}

	test("direct badge cache keeps partial and empty fetch results") {
		val partialItemId = UUID.randomUUID()
		val emptyItemId = UUID.randomUUID()
		val partialSources = listOf(source(stream(MediaStreamType.VIDEO, 0, "und", codec = "h264", width = 1920, height = 1080)))

		DirectStreamBadgeCache.clear()
		DirectStreamBadgeCache.save(partialItemId, partialSources)
		DirectStreamBadgeCache.save(emptyItemId, emptyList())

		DirectStreamBadgeCache.get(partialItemId) shouldBe partialSources
		DirectStreamBadgeCache.get(emptyItemId) shouldBe emptyList()

		DirectStreamBadgeCache.remove(setOf(partialItemId))
		DirectStreamBadgeCache.get(partialItemId) shouldBe null
		DirectStreamBadgeCache.get(emptyItemId) shouldBe emptyList()

		DirectStreamBadgeCache.clear()
	}

	test("series season sampling is spread across large series") {
		val seasons = List(10) {
			BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.SEASON)
		}

		seasons.spreadSeriesStreamBadgeSampleIds(4) shouldBe setOf(
			seasons[0].id,
			seasons[3].id,
			seasons[6].id,
			seasons[9].id,
		)
		seasons.spreadSeriesStreamBadgeSampleIds(1) shouldBe setOf(seasons[0].id)
		seasons.spreadSeriesStreamBadgeSampleIds(0) shouldBe emptySet()
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
			episode(UUID.randomUUID(), source(stream(MediaStreamType.VIDEO, 0, "und", codec = "h264", width = 1920, height = 1080))),
		))

		item.defaultAudioLanguage() shouldBe "eng"
	}

	test("video samples without badge metadata do not clear existing video badges") {
		val seasonId = UUID.randomUUID()
		val item = BaseItemDto(
			id = seasonId,
			type = BaseItemKind.SEASON,
			mediaSources = listOf(source(stream(MediaStreamType.VIDEO, 0, "und", codec = "h264", width = 1920, height = 1080))),
		).withSeriesStreamBadgeSource(listOf(
			episode(UUID.randomUUID(), source(
				stream(MediaStreamType.AUDIO, 0, "eng", isDefault = true),
				stream(MediaStreamType.VIDEO, 1, "und"),
			)),
		))

		item.defaultAudioLanguage() shouldBe "eng"
		item.mediaSources?.single()?.mediaStreams
			?.filter { it.type == MediaStreamType.VIDEO }
			?.map { "${it.width}x${it.height}:${it.codec}" } shouldBe listOf("1920x1080:h264")
	}

	test("video samples with partial metadata keep existing video badge data") {
		val seasonId = UUID.randomUUID()
		val item = BaseItemDto(
			id = seasonId,
			type = BaseItemKind.SEASON,
			mediaSources = listOf(source(stream(MediaStreamType.VIDEO, 0, "und", codec = "h264", width = 1920, height = 1080))),
		).withSeriesStreamBadgeSource(listOf(
			episode(UUID.randomUUID(), source(stream(MediaStreamType.VIDEO, 1, "und", codec = "hevc"))),
		))

		val videoStreams = item.mediaSources?.single()?.mediaStreams.orEmpty()
			.filter { it.type == MediaStreamType.VIDEO }
		videoStreams.mapNotNull { it.videoBadgeResolutionText() } shouldBe listOf("1080")
		videoStreams.mapNotNull { it.codec } shouldBe listOf("hevc", "h264")
	}

	test("complete video samples keep different existing video badge values") {
		val seasonId = UUID.randomUUID()
		val item = BaseItemDto(
			id = seasonId,
			type = BaseItemKind.SEASON,
			mediaSources = listOf(source(stream(MediaStreamType.VIDEO, 0, "und", codec = "hevc", width = 3840, height = 2160))),
		).withSeriesStreamBadgeSource(listOf(
			episode(UUID.randomUUID(), source(stream(MediaStreamType.VIDEO, 1, "und", codec = "h264", width = 1920, height = 1080))),
		))

		val videoStreams = item.mediaSources?.single()?.mediaStreams.orEmpty()
			.filter { it.type == MediaStreamType.VIDEO }
		videoStreams.mapNotNull { it.videoBadgeResolutionText() } shouldBe listOf("1080", "4k")
		videoStreams.mapNotNull { it.codec } shouldBe listOf("h264", "hevc")
	}

	test("video samples with blank audio language keep existing audio badges") {
		val seasonId = UUID.randomUUID()
		val item = BaseItemDto(
			id = seasonId,
			type = BaseItemKind.SEASON,
			mediaSources = listOf(source(stream(MediaStreamType.AUDIO, 0, "eng", isDefault = true))),
		).withSeriesStreamBadgeSource(listOf(
			episode(UUID.randomUUID(), source(
				stream(MediaStreamType.VIDEO, 0, "und", codec = "h264", width = 1920, height = 1080),
				stream(MediaStreamType.AUDIO, 1, ""),
			)),
		))

		item.defaultAudioLanguage() shouldBe "eng"
		item.mediaSources?.single()?.mediaStreams
			?.filter { it.type == MediaStreamType.VIDEO }
			?.map { "${it.width}x${it.height}:${it.codec}" } shouldBe listOf("1920x1080:h264")
	}

	test("complete language samples keep different existing language badge values") {
		val seasonId = UUID.randomUUID()
		val item = BaseItemDto(
			id = seasonId,
			type = BaseItemKind.SEASON,
			mediaSources = listOf(source(stream(MediaStreamType.AUDIO, 0, "jpn", isDefault = true))),
		).withSeriesStreamBadgeSource(listOf(
			episode(UUID.randomUUID(), source(stream(MediaStreamType.AUDIO, 1, "eng", isDefault = true))),
		))

		item.mediaSources?.single()?.mediaStreams
			?.filter { it.type == MediaStreamType.AUDIO }
			?.map { it.language } shouldBe listOf("eng", "jpn")
	}

	test("season badge cache removes by related ids") {
		val seriesId = UUID.randomUUID()
		val seasonId = UUID.randomUUID()
		val sample = completeBadgeEpisode(seriesId)

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

	test("season badge cache keeps language-only badge samples") {
		val seriesId = UUID.randomUUID()
		val seasonId = UUID.randomUUID()

		SeriesStreamBadgeCache.clear()
		SeriesStreamBadgeCache.save(seriesId, seasonId, listOf(
			episode(seriesId, source(stream(MediaStreamType.AUDIO, 0, "eng", isDefault = true))),
		))

		SeriesStreamBadgeCache.get(seasonId)?.single()?.defaultAudioLanguage() shouldBe "eng"
	}

	test("season badge cache keeps video-complete samples with unbadgeable languages") {
		val seriesId = UUID.randomUUID()
		val seasonId = UUID.randomUUID()

		SeriesStreamBadgeCache.clear()
		SeriesStreamBadgeCache.save(seriesId, seasonId, listOf(
			episode(seriesId, source(
				stream(MediaStreamType.VIDEO, 0, "und", codec = "h264", width = 1920, height = 1080),
				stream(MediaStreamType.AUDIO, 1, ""),
				stream(MediaStreamType.SUBTITLE, 2, ""),
			)),
		))

		val streams = SeriesStreamBadgeCache.get(seasonId)
			?.single()
			?.mediaSources
			.orEmpty()
			.flatMap { it.mediaStreams.orEmpty() }

		streams.single { it.type == MediaStreamType.VIDEO }.codec shouldBe "h264"
	}
})

private fun BaseItemDto.defaultAudioLanguage(): String? {
	val mediaSource = mediaSources?.single()
	return mediaSource?.mediaStreams
		?.single { it.type == MediaStreamType.AUDIO && it.index == mediaSource.defaultAudioStreamIndex }
		?.language
}

private fun completeBadgeEpisode(seriesId: UUID) = episode(
	seriesId,
	source(
		stream(MediaStreamType.VIDEO, 0, "und", codec = "h264", width = 1920, height = 1080),
		stream(MediaStreamType.AUDIO, 1, "eng", isDefault = true),
	),
)

private fun episode(seriesId: UUID, vararg sources: MediaSourceInfo) = BaseItemDto(
	id = UUID.randomUUID(),
	type = BaseItemKind.EPISODE,
	seriesId = seriesId,
	mediaSources = sources.toList(),
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
	defaultAudioStreamIndex = streams.firstOrNull { it.type == MediaStreamType.AUDIO && it.isDefault }?.index
		?: streams.firstOrNull { it.type == MediaStreamType.AUDIO }?.index
		?: 0,
	defaultSubtitleStreamIndex = -1,
	hasSegments = false,
)

private fun stream(
	type: MediaStreamType,
	index: Int,
	language: String,
	isDefault: Boolean = false,
	codec: String? = null,
	width: Int? = null,
	height: Int? = null,
) = MediaStream(
	language = language,
	codec = codec,
	width = width,
	height = height,
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
