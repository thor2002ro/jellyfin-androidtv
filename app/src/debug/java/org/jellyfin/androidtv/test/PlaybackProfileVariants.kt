package org.jellyfin.androidtv.test

import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.CodecProfile
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.ProfileCondition
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod

data class ConfiguredPlaybackProfile(
	val profile: DeviceProfile,
	val forceTranscoding: Boolean = false,
	val burnSubtitles: Boolean = false,
	val expectedMethods: Set<MediaConversionMethod>,
	val mismatchStatus: PlaybackTestStatus = PlaybackTestStatus.FAIL,
)

enum class PlaybackProfileVariant {
	PRODUCTION,
	DIRECT,
	REMUX,
	VIDEO_TRANSCODE,
	VIDEO_PROFILE_TRANSCODE,
	AUDIO_TRANSCODE,
	SUBTITLE_TRANSCODE;

	fun configure(
		production: DeviceProfile,
		sourceContainer: String? = null,
		sourceVideoCodec: String? = null,
		sourceVideoProfile: String? = null,
	): ConfiguredPlaybackProfile = when (this) {
		PRODUCTION -> ConfiguredPlaybackProfile(
			profile = production,
			expectedMethods = setOf(
				MediaConversionMethod.None,
				MediaConversionMethod.Remux,
				MediaConversionMethod.Transcode,
			),
		)
		DIRECT -> ConfiguredPlaybackProfile(
			profile = production,
			expectedMethods = setOf(MediaConversionMethod.None),
		)
		REMUX -> ConfiguredPlaybackProfile(
			profile = production.copy(
				directPlayProfiles = production.directPlayProfiles.map { directPlay ->
					if (directPlay.type == DlnaProfileType.VIDEO && sourceContainer != null) {
						directPlay.copy(container = directPlay.container.withoutContainer(sourceContainer))
					} else directPlay
				},
			),
			expectedMethods = setOf(MediaConversionMethod.Remux),
			mismatchStatus = PlaybackTestStatus.WARN,
		)
		VIDEO_TRANSCODE -> ConfiguredPlaybackProfile(
			profile = production.copy(directPlayProfiles = emptyList()),
			forceTranscoding = true,
			expectedMethods = setOf(MediaConversionMethod.Transcode),
		)
		VIDEO_PROFILE_TRANSCODE -> {
			require(!sourceContainer.isNullOrBlank()) { "Source container is required" }
			require(!sourceVideoCodec.isNullOrBlank()) { "Source video codec is required" }
			require(!sourceVideoProfile.isNullOrBlank()) { "Source video profile is required" }
			val advertisesSource = production.directPlayProfiles.any { directPlay ->
				directPlay.type == DlnaProfileType.VIDEO &&
					directPlay.container.containsDeclaration(sourceContainer) &&
					directPlay.videoCodec.containsDeclaration(sourceVideoCodec)
			}
			ConfiguredPlaybackProfile(
				profile = production.copy(
					directPlayProfiles = production.directPlayProfiles + if (advertisesSource) emptyList() else listOf(
						DirectPlayProfile(
							container = sourceContainer,
							audioCodec = null,
							videoCodec = sourceVideoCodec,
							type = DlnaProfileType.VIDEO,
						)
					),
					codecProfiles = production.codecProfiles + CodecProfile(
						type = CodecType.VIDEO,
						conditions = listOf(
							ProfileCondition(
								condition = ProfileConditionType.NOT_EQUALS,
								property = ProfileConditionValue.VIDEO_PROFILE,
								value = sourceVideoProfile,
								isRequired = true,
							)
						),
						applyConditions = emptyList(),
						codec = sourceVideoCodec,
					),
				),
				expectedMethods = setOf(MediaConversionMethod.Transcode),
			)
		}
		AUDIO_TRANSCODE -> ConfiguredPlaybackProfile(
			profile = production.copy(
				directPlayProfiles = production.directPlayProfiles.map { directPlay ->
					if (directPlay.type == DlnaProfileType.VIDEO) {
						directPlay.copy(audioCodec = "__playback_test_unsupported__")
					} else directPlay
				},
			),
			expectedMethods = setOf(MediaConversionMethod.Transcode),
		)
		SUBTITLE_TRANSCODE -> ConfiguredPlaybackProfile(
			profile = production.copy(
				subtitleProfiles = production.subtitleProfiles.map { it.copy(method = SubtitleDeliveryMethod.ENCODE) },
			),
			burnSubtitles = true,
			expectedMethods = setOf(MediaConversionMethod.Transcode),
		)
	}
}

private fun String?.containsDeclaration(value: String): Boolean = this
	?.split(',')
	?.any { it.equals(value, ignoreCase = true) }
	?: false

private fun String.withoutContainer(sourceContainer: String): String {
	val aliases = when (sourceContainer.lowercase()) {
		"mp4", "mov", "m4v" -> setOf("mp4", "mov", "m4v")
		else -> setOf(sourceContainer.lowercase())
	}
	return split(',')
	.filterNot { it.lowercase() in aliases }
	.joinToString(",")
}
