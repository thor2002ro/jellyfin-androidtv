package org.jellyfin.androidtv.ui.settings.screen

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Badge
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListControl
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.util.profile.MediaCodecCapabilitiesTest
import org.jellyfin.androidtv.util.profile.codec.MediaCodecQuery
import org.jellyfin.playback.media3.exoplayer.mapping.ffmpegAudioMimeTypes
import org.jellyfin.playback.media3.exoplayer.mapping.ffmpegVideoMimeTypes
import org.koin.compose.koinInject

// Values from Display.HdrCapabilities. Kept local to avoid minSdk inlined API warnings.
private const val HDR_TYPE_DOLBY_VISION = 1
private const val HDR_TYPE_HDR10 = 2
private const val HDR_TYPE_HLG = 3
private const val HDR_TYPE_HDR10_PLUS = 4

@Composable
fun SettingsDebugCapabilitiesScreen() {
	val context = LocalContext.current
	val userPreferences = koinInject<UserPreferences>()
	val softwareCodecsEnabled by rememberPreference(userPreferences, UserPreferences.softwareCodecsEnabled)
	val groups = remember(context, softwareCodecsEnabled) {
		buildCapabilityGroups(context, softwareCodecsEnabled)
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_developer_link).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_debug_capabilities_title)) },
				captionContent = { Text(stringResource(R.string.pref_debug_capabilities_summary)) },
			)
		}

		item {
			FocusableListControl(
				headingContent = { Text(stringResource(R.string.pref_debug_capabilities_codec_scope)) },
				captionContent = {
					Text(
						if (softwareCodecsEnabled) {
							stringResource(R.string.pref_debug_capabilities_codec_scope_software)
						} else {
							stringResource(R.string.pref_debug_capabilities_codec_scope_hardware)
						}
					)
				},
			)
		}

		groups.forEach { group ->
			capabilityGroup(group)
		}
	}
}

private fun LazyListScope.capabilityGroup(group: CapabilityGroup) {
	item {
		ListSection(
			headingContent = { Text(group.title) },
			captionContent = group.caption?.let { caption -> ({ Text(caption) }) },
		)
	}

	items(group.items) { item ->
		FocusableListControl(
			headingContent = { Text(item.title) },
			captionContent = item.detail?.let { detail -> ({ Text(detail) }) },
			trailingContent = item.supported?.let { supported -> ({ CapabilityBadge(supported) }) },
		)
	}
}

@Composable
private fun FocusableListControl(
	headingContent: @Composable () -> Unit,
	captionContent: (@Composable () -> Unit)? = null,
	trailingContent: (@Composable () -> Unit)? = null,
) {
	val interactionSource = remember { MutableInteractionSource() }

	ListControl(
		headingContent = headingContent,
		modifier = Modifier.focusable(interactionSource = interactionSource),
		interactionSource = interactionSource,
		captionContent = captionContent,
		trailingContent = trailingContent,
	)
}

@Composable
private fun CapabilityBadge(supported: Boolean) {
	Badge(
		containerColor = if (supported) JellyfinTheme.colorScheme.badge else JellyfinTheme.colorScheme.buttonDisabled,
		contentColor = if (supported) JellyfinTheme.colorScheme.onBadge else JellyfinTheme.colorScheme.onButtonDisabled,
	) {
		Text(
			if (supported) {
				stringResource(R.string.pref_debug_capabilities_status_yes)
			} else {
				stringResource(R.string.pref_debug_capabilities_status_no)
			}
		)
	}
}

private fun buildCapabilityGroups(
	context: Context,
	softwareCodecsEnabled: Boolean,
): List<CapabilityGroup> {
	val mediaTest = MediaCodecCapabilitiesTest(softwareCodecsEnabled)
	val displayHdrTypes = getSupportedHdrTypes(context)

	return listOf(
		buildHdrCapabilities(context, mediaTest, displayHdrTypes),
		buildVideoCapabilities(context, mediaTest),
		buildAudioCapabilities(context, softwareCodecsEnabled),
		buildFfmpegCapabilities(context),
	)
}

private fun buildHdrCapabilities(
	context: Context,
	mediaTest: MediaCodecCapabilitiesTest,
	displayHdrTypes: Set<Int>,
) = CapabilityGroup(
	title = context.getString(R.string.pref_debug_capabilities_hdr_section),
	caption = context.getString(R.string.pref_debug_capabilities_hdr_section_summary),
	items = listOf(
		CapabilityItem("Display: Dolby Vision", displayHdrTypes.contains(HDR_TYPE_DOLBY_VISION)),
		CapabilityItem("Display: HDR10", displayHdrTypes.contains(HDR_TYPE_HDR10)),
		CapabilityItem("Display: HDR10+", displayHdrTypes.contains(HDR_TYPE_HDR10_PLUS)),
		CapabilityItem("Display: HLG", displayHdrTypes.contains(HDR_TYPE_HLG)),
		CapabilityItem("HEVC: Dolby Vision", mediaTest.supportsHevcDolbyVision()),
		CapabilityItem("HEVC: Dolby Vision Profile 5", mediaTest.supportsHevcDolbyVisionProfile5(), "dvhe.05"),
		CapabilityItem("HEVC: Dolby Vision Profile 7", mediaTest.supportsHevcDolbyVisionProfile7(), "dvhe.07"),
		CapabilityItem("HEVC: Dolby Vision Profile 8", mediaTest.supportsHevcDolbyVisionProfile8(), "dvhe.08"),
		CapabilityItem("HEVC: Dolby Vision EL", mediaTest.supportsHevcDolbyVisionEL()),
		CapabilityItem("HEVC: HDR10", mediaTest.supportsHevcHDR10()),
		CapabilityItem("HEVC: HDR10+", mediaTest.supportsHevcHDR10Plus()),
		CapabilityItem("AV1: Dolby Vision", mediaTest.supportsAV1DolbyVision()),
		CapabilityItem("AV1: HDR10", mediaTest.supportsAV1HDR10()),
		CapabilityItem("AV1: HDR10+", mediaTest.supportsAV1HDR10Plus()),
	),
)

private fun buildVideoCapabilities(
	context: Context,
	mediaTest: MediaCodecCapabilitiesTest,
): CapabilityGroup {
	val avcHigh10Level = mediaTest.getAVCHigh10Level().takeIf { it > 0 }?.let {
		context.getString(R.string.pref_debug_capabilities_codec_level, it)
	}
	val hevcMain10Level = mediaTest.getHevcMain10Level().takeIf { it > 0 }?.let {
		context.getString(R.string.pref_debug_capabilities_codec_level, it)
	}

	return CapabilityGroup(
		title = context.getString(R.string.pref_debug_capabilities_video_section),
		caption = context.getString(R.string.pref_debug_capabilities_video_section_summary),
		items = listOf(
			CapabilityItem("H.264 / AVC", mediaTest.supportsAVC(), maxResolutionDetail(context, mediaTest, MimeTypes.VIDEO_H264)),
			CapabilityItem("H.264 High 10", mediaTest.supportsAVCHigh10(), avcHigh10Level),
			CapabilityItem("HEVC / H.265", mediaTest.supportsHevc(), maxResolutionDetail(context, mediaTest, MimeTypes.VIDEO_H265)),
			CapabilityItem("HEVC Main 10", mediaTest.supportsHevcMain10(), hevcMain10Level),
			CapabilityItem("AV1", mediaTest.supportsAV1(), maxResolutionDetail(context, mediaTest, MimeTypes.VIDEO_AV1)),
			CapabilityItem("AV1 Main 10", mediaTest.supportsAV1Main10()),
			CapabilityItem("VC-1", mediaTest.supportsVc1(), maxResolutionDetail(context, mediaTest, MimeTypes.VIDEO_VC1)),
		),
	)
}

private fun buildAudioCapabilities(
	context: Context,
	softwareCodecsEnabled: Boolean,
): CapabilityGroup {
	val query = MediaCodecQuery(MediaCodecList(MediaCodecList.REGULAR_CODECS), softwareCodecsEnabled)

	return CapabilityGroup(
		title = context.getString(R.string.pref_debug_capabilities_audio_section),
		caption = context.getString(R.string.pref_debug_capabilities_audio_section_summary),
		items = listOf(
			CapabilityItem("AAC", query.hasCodecForMime(MimeTypes.AUDIO_AAC)),
			CapabilityItem("AC3", query.hasCodecForMime(MimeTypes.AUDIO_AC3)),
			CapabilityItem("EAC3", query.hasCodecForMime(MimeTypes.AUDIO_E_AC3)),
			CapabilityItem("EAC3-JOC", query.hasCodecForMime(MimeTypes.AUDIO_E_AC3_JOC)),
			CapabilityItem("AC4", query.hasCodecForMime(MimeTypes.AUDIO_AC4)),
			CapabilityItem("DTS", query.hasCodecForMime(MimeTypes.AUDIO_DTS)),
			CapabilityItem("DTS-HD", query.hasCodecForMime(MimeTypes.AUDIO_DTS_HD)),
			CapabilityItem("TrueHD", query.hasCodecForMime(MimeTypes.AUDIO_TRUEHD)),
			CapabilityItem("FLAC", query.hasCodecForMime(MimeTypes.AUDIO_FLAC)),
			CapabilityItem("Opus", query.hasCodecForMime(MimeTypes.AUDIO_OPUS)),
		),
	)
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun buildFfmpegCapabilities(context: Context): CapabilityGroup {
	val version = FfmpegLibrary.getVersion()
	val caption = when {
		version != null -> context.getString(R.string.pref_debug_capabilities_ffmpeg_section_summary_version, version)
		FfmpegLibrary.isAvailable() -> context.getString(R.string.pref_debug_capabilities_ffmpeg_section_summary_available)
		else -> context.getString(R.string.pref_debug_capabilities_ffmpeg_section_summary_unavailable)
	}

	return CapabilityGroup(
		title = context.getString(R.string.pref_debug_capabilities_ffmpeg_section),
		caption = caption,
		items = buildFfmpegCodecItems("Video", ffmpegVideoMimeTypes) +
			buildFfmpegCodecItems("Audio", ffmpegAudioMimeTypes),
	)
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun buildFfmpegCodecItems(
	prefix: String,
	codecs: Map<String, String>,
) = codecs.entries
	.filter { (_, mimeType) -> FfmpegLibrary.supportsFormat(mimeType) }
	.sortedBy { (codec, _) -> codec }
	.map { (codec, mimeType) ->
		CapabilityItem(
			title = "$prefix: ${codec.displayFfmpegCodecName()}",
			detail = mimeType,
		)
	}

private fun String.displayFfmpegCodecName() = replace('_', '-').uppercase()

private fun getSupportedHdrTypes(context: Context): Set<Int> {
	if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptySet()

	val display = ContextCompat.getDisplayOrDefault(context)
	@Suppress("DEPRECATION")
	return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
		display.mode.supportedHdrTypes.toSet()
	} else {
		display.hdrCapabilities?.supportedHdrTypes?.toSet().orEmpty()
	}
}

private fun maxResolutionDetail(
	context: Context,
	mediaTest: MediaCodecCapabilitiesTest,
	mime: String,
): String? = mediaTest.getMaxResolution(mime).takeIf { it.width > 0 && it.height > 0 }?.let { size ->
	context.getString(R.string.pref_debug_capabilities_max_resolution, size.width, size.height)
}

private data class CapabilityGroup(
	val title: String,
	val caption: String?,
	val items: List<CapabilityItem>,
)

private data class CapabilityItem(
	val title: String,
	val supported: Boolean? = null,
	val detail: String? = null,
)
