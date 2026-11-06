package org.jellyfin.androidtv.test

import android.content.Context
import android.net.Uri
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.util.profile.createDeviceProfile
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.jellyfin.JellyfinDeviceProfileRequest
import org.jellyfin.playback.jellyfin.mediastream.JellyfinMediaStreamOptions
import org.jellyfin.playback.jellyfin.mediastream.JellyfinMediaStreamResolver
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.forceTranscoding
import org.jellyfin.playback.jellyfin.queue.mediaSourceId
import org.jellyfin.sdk.model.api.MediaStreamType
import org.koin.core.context.GlobalContext

class PlaybackNegotiationSuite(
	private val context: Context,
	private val environment: PlaybackServerEnvironment,
) {
	suspend fun run(scenarioFilter: String?, transcodingOnly: Boolean = false): List<PlaybackTestResult> {
		val koin = GlobalContext.get()
		val preferences = koin.get<UserPreferences>()
		val serverVersion = requireNotNull(koin.get<ServerRepository>().currentServer.value?.serverVersion) {
			"Current Jellyfin server version is unavailable"
		}
		val production = createDeviceProfile(context, preferences, serverVersion)
		val cases = if (transcodingOnly) {
			PlaybackTranscodingMatrix.plan(environment.fixtures.values.map(ServerPlaybackFixture::descriptor)).map { testCase ->
				NegotiationCase(
					id = testCase.id,
					descriptorId = testCase.descriptorId,
					variant = testCase.variant,
					selectedAudioCodec = testCase.selectedAudioCodec,
					selectedSubtitleCodec = testCase.selectedSubtitleCodec,
				)
			}
		} else buildList {
			for ((fixtureId, descriptor) in environment.selection.fixtures.toSortedMap()) {
				add(NegotiationCase("$fixtureId:production", descriptor.id, PlaybackProfileVariant.PRODUCTION))
			}
			fun canonical(fixtureId: String, variant: PlaybackProfileVariant) {
				environment.selection.fixtures[fixtureId]?.let { descriptor ->
					add(NegotiationCase("$fixtureId:${variant.name.lowercase()}", descriptor.id, variant))
				}
			}
			canonical("1080p-sdr-avc", PlaybackProfileVariant.DIRECT)
			canonical("1080p-sdr-avc", PlaybackProfileVariant.REMUX)
			addAll(environment.coverageNegotiationCases())
		}.distinct()

		return cases.filter { testCase ->
			scenarioFilter == null || scenarioFilter == testCase.id || testCase.id.startsWith("$scenarioFilter:")
		}.map { testCase ->
			runCase(testCase, environment.fixtures.getValue(testCase.descriptorId), production)
		}
	}

	private suspend fun runCase(
		testCase: NegotiationCase,
		fixture: ServerPlaybackFixture,
		production: org.jellyfin.sdk.model.api.DeviceProfile,
	): PlaybackTestResult = try {
		val configured = testCase.variant.configure(
			production = production,
			sourceContainer = fixture.descriptor.container,
			sourceVideoCodec = fixture.descriptor.videoCodec,
			sourceVideoProfile = fixture.descriptor.videoProfile,
		)
		val subtitleIndex = if (configured.burnSubtitles) {
			fixture.source.mediaStreams.orEmpty().firstOrNull { stream ->
				stream.type == MediaStreamType.SUBTITLE && stream.codec.equals(testCase.selectedSubtitleCodec, true)
			}?.index
		} else null
		check(!configured.burnSubtitles || subtitleIndex != null) {
			"${testCase.selectedSubtitleCodec} subtitle stream is unavailable"
		}
		val audioIndex = testCase.selectedAudioCodec?.let { selectedCodec ->
			fixture.source.mediaStreams.orEmpty().firstOrNull { stream ->
				stream.type == MediaStreamType.AUDIO && stream.codec.equals(selectedCodec, true)
			}?.index
		}
		check(testCase.selectedAudioCodec == null || audioIndex != null) {
			"${testCase.selectedAudioCodec} audio stream is unavailable"
		}

		val entry = QueueEntry().apply {
			baseItem = fixture.item
			mediaSourceId = fixture.source.id
			forceTranscoding = configured.forceTranscoding
		}
		val resolver = JellyfinMediaStreamResolver(
			api = environment.session.testApi,
			deviceProfileBuilder = { JellyfinDeviceProfileRequest(configured.profile, 0) },
			mediaStreamOptionsProvider = { _, _ ->
				JellyfinMediaStreamOptions(
					audioStreamIndex = audioIndex,
					subtitleStreamIndex = subtitleIndex,
					alwaysBurnInSubtitleWhenTranscoding = configured.burnSubtitles,
				)
			},
		)
		val stream = requireNotNull(resolver.getStream(entry, null)) { "Server returned no playable stream" }
		val requiredReason = testCase.variant.requiredTranscodeReason()
		check(requiredReason == null || requiredReason in transcodeReasons(stream.url)) {
			"Expected reason $requiredReason, got ${transcodeReasons(stream.url)}"
		}
		val matches = stream.conversionMethod in configured.expectedMethods
		val status = if (matches) PlaybackTestStatus.PASS else configured.mismatchStatus
		val expected = configured.expectedMethods.joinToString("|") { it.label() }
		PlaybackTestResult(
			status = status,
			suite = if (testCase.variant.isForcedTranscoding()) "transcode" else "negotiation",
			scenario = testCase.id,
			detail = "expected=$expected ${stream.observation(fixture)}",
		)
	} catch (error: Throwable) {
		PlaybackTestResult(PlaybackTestStatus.FAIL, "negotiation", scenario = testCase.id, detail = error.message ?: error.toString())
	}
}

private data class NegotiationCase(
	val id: String,
	val descriptorId: String,
	val variant: PlaybackProfileVariant,
	val selectedAudioCodec: String? = null,
	val selectedSubtitleCodec: String? = null,
)

private fun PlaybackProfileVariant.isForcedTranscoding() = this == PlaybackProfileVariant.VIDEO_TRANSCODE ||
	this == PlaybackProfileVariant.VIDEO_PROFILE_TRANSCODE || this == PlaybackProfileVariant.AUDIO_TRANSCODE ||
	this == PlaybackProfileVariant.SUBTITLE_TRANSCODE

private fun PlaybackProfileVariant.requiredTranscodeReason() = when (this) {
	PlaybackProfileVariant.VIDEO_PROFILE_TRANSCODE -> "VideoProfileNotSupported"
	PlaybackProfileVariant.AUDIO_TRANSCODE -> "AudioCodecNotSupported"
	PlaybackProfileVariant.SUBTITLE_TRANSCODE -> "SubtitleCodecNotSupported"
	else -> null
}

private fun PlaybackServerEnvironment.coverageNegotiationCases(): List<NegotiationCase> {
	val fixtures = fixtures.values.sortedBy { it.descriptor.id }
	val cases = mutableListOf<NegotiationCase>()
	fun add(kind: String, key: String, fixture: ServerPlaybackFixture) {
		cases += NegotiationCase("coverage-$kind-${key.scenarioKey()}", fixture.descriptor.id, PlaybackProfileVariant.PRODUCTION)
	}
	fixtures.distinctBy { fixture ->
		fixture.descriptor.run { listOf(videoCodec, videoProfile, videoLevel, bitDepth).joinToString("/") }
	}.forEach { fixture -> add("video", fixture.descriptor.run { "$videoCodec-$videoProfile-l$videoLevel-$bitDepth-bit" }, fixture) }
	for (codec in selection.coverage.audio) {
		fixtures.firstOrNull { codec in it.descriptor.audioCodecs }?.let { add("audio", codec, it) }
	}
	for (container in selection.coverage.containers) {
		fixtures.firstOrNull { it.descriptor.container == container }?.let { add("container", container, it) }
	}
	fixtures.filter { it.descriptor.videoRange != null }.distinctBy { it.descriptor.videoRange to it.descriptor.doviProfile }
		.forEach { fixture -> add("range", "${fixture.descriptor.videoRange}-dv${fixture.descriptor.doviProfile}", fixture) }
	val subtitleCodecs = fixtures.flatMap { it.descriptor.subtitleCodecs }.toSortedSet()
	for (codec in subtitleCodecs) {
		fixtures.firstOrNull { codec in it.descriptor.subtitleCodecs }?.let { add("subtitle", codec, it) }
	}
	return cases
}

private fun String.scenarioKey() = lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

private fun PlayableMediaStream.observation(fixture: ServerPlaybackFixture): String {
	val codecs = tracks.map { it.codec }.distinct().sorted()
	val source = fixture.descriptor
	val uri = Uri.parse(url)
	fun parameter(name: String) = uri.queryParameterNames.firstOrNull { it.equals(name, ignoreCase = true) }
		?.let(uri::getQueryParameter)
	return "method=${conversionMethod.label()} container=${container.format} codecs=$codecs url=${url.isNotBlank()} " +
		"path=${uri.lastPathSegment} videoOutput=${parameter("VideoCodec")} audioOutput=${parameter("AudioCodec")} " +
		"reasons=${parameter("TranscodeReasons")} source=${source.videoCodec}/${source.videoProfile}/level-${source.videoLevel} " +
		"range=${source.videoRange} dv=${source.doviProfile}"
}

private fun MediaConversionMethod.label() = when (this) {
	MediaConversionMethod.None -> "direct"
	MediaConversionMethod.Remux -> "remux"
	MediaConversionMethod.Transcode -> "transcode"
}
