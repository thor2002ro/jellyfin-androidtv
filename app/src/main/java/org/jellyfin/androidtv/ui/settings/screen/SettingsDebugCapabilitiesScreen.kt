package org.jellyfin.androidtv.ui.settings.screen

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import androidx.annotation.OptIn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
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
import org.jellyfin.androidtv.util.AndroidVersion
import org.jellyfin.androidtv.util.profile.DISPLAY_HDR_TYPE_DOLBY_VISION
import org.jellyfin.androidtv.util.profile.DISPLAY_HDR_TYPE_HDR10
import org.jellyfin.androidtv.util.profile.DISPLAY_HDR_TYPE_HDR10_PLUS
import org.jellyfin.androidtv.util.profile.DISPLAY_HDR_TYPE_HLG
import org.jellyfin.androidtv.util.profile.MediaCodecCapabilitiesTest
import org.jellyfin.androidtv.util.profile.codec.MediaCodecQuery
import org.jellyfin.androidtv.util.profile.getSupportedDisplayHdrTypes
import org.koin.compose.koinInject

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
			headingContent = { Text(group.title, fontSize = 16.sp, maxLines = 1) },
			captionContent = group.caption?.let { caption -> ({ CompactCapabilityText(caption, 11) }) },
		)
	}

	items(group.items) { item ->
		FocusableListControl(
			headingContent = { CompactCapabilityText(item.title, 14) },
			captionContent = item.detail?.let { detail -> ({ CompactCapabilityText(detail, 11) }) },
			trailingContent = item.supported?.let { supported -> ({ CapabilityBadge(supported) }) },
		)
	}
}

@Composable
private fun CompactCapabilityText(text: String, size: Int) = Text(
	text = text,
	fontSize = size.sp,
	maxLines = 1,
	overflow = TextOverflow.Ellipsis,
)

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
			},
			fontSize = 11.sp,
		)
	}
}

private fun buildCapabilityGroups(
	context: Context,
	softwareCodecsEnabled: Boolean,
): List<CapabilityGroup> {
	val mediaTest = MediaCodecCapabilitiesTest(softwareCodecsEnabled)
	val displayHdrTypes = getSupportedDisplayHdrTypes(context)

	return listOf(
		buildHdrCapabilities(context, mediaTest, displayHdrTypes),
		buildVideoCapabilities(context, mediaTest),
		buildAudioCapabilities(context, softwareCodecsEnabled),
		buildRawAndroidDecoderCapabilities(),
		buildFfmpegCapabilities(context),
	)
}

private fun buildRawAndroidDecoderCapabilities(): CapabilityGroup {
	val decoders = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
		.asSequence()
		.filterNot(MediaCodecInfo::isEncoder)
		.distinctBy(MediaCodecInfo::getName)
		.sortedBy(MediaCodecInfo::getName)
		.toList()

	return CapabilityGroup(
		title = "RAW",
		caption = "All decoders reported by Android MediaCodec",
		items = decoders.map { codec ->
			val type = if (codec.isSoftwareDecoder) "sw" else "hw"
			CapabilityItem("$type › ${codec.name}", detail = codec.supportedTypes.sorted().joinToString())
		},
	)
}

private val MediaCodecInfo.isSoftwareDecoder: Boolean
	get() = if (AndroidVersion.isAtLeastQ) {
		isSoftwareOnly
	} else {
		name.startsWith("OMX.google.", ignoreCase = true) ||
			name.startsWith("c2.android.", ignoreCase = true)
	}

private fun buildHdrCapabilities(
	context: Context,
	mediaTest: MediaCodecCapabilitiesTest,
	displayHdrTypes: Set<Int>,
) = CapabilityGroup(
	title = context.getString(R.string.pref_debug_capabilities_hdr_section),
	caption = context.getString(R.string.pref_debug_capabilities_hdr_section_summary),
	items = listOf(
		CapabilityItem("Display: Dolby Vision", displayHdrTypes.contains(DISPLAY_HDR_TYPE_DOLBY_VISION)),
		CapabilityItem("Display: HDR10", displayHdrTypes.contains(DISPLAY_HDR_TYPE_HDR10)),
		CapabilityItem("Display: HDR10+", displayHdrTypes.contains(DISPLAY_HDR_TYPE_HDR10_PLUS)),
		CapabilityItem("Display: HLG", displayHdrTypes.contains(DISPLAY_HDR_TYPE_HLG)),
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
		CapabilityItem("VP9: Profile 2/3 (10-bit)", mediaTest.supportsVp9Main10()),
	),
)

@OptIn(UnstableApi::class)
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
			CapabilityItem("AV2", mediaTest.supportsMimeType("video/av02") || mediaTest.supportsMimeType("video/av2")),
			videoCapability(context, mediaTest, "VP9", MimeTypes.VIDEO_VP9),
			videoCapability(context, mediaTest, "VP8", MimeTypes.VIDEO_VP8),
			videoCapability(context, mediaTest, "MPEG-2", MimeTypes.VIDEO_MPEG2),
			videoCapability(context, mediaTest, "MPEG-1", MimeTypes.VIDEO_MPEG),
			videoCapability(context, mediaTest, "H.263", MimeTypes.VIDEO_H263),
			videoCapability(context, mediaTest, "MJPEG", MimeTypes.VIDEO_MJPEG),
			CapabilityItem("VC-1", mediaTest.supportsVc1(), maxResolutionDetail(context, mediaTest, MimeTypes.VIDEO_VC1)),
		),
	)
}

private fun videoCapability(
	context: Context,
	mediaTest: MediaCodecCapabilitiesTest,
	title: String,
	mime: String,
) = CapabilityItem(title, mediaTest.supportsMimeType(mime), maxResolutionDetail(context, mediaTest, mime))

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
			CapabilityItem("Dolby Digital Plus (EAC3)", query.hasCodecForMime(MimeTypes.AUDIO_E_AC3)),
			CapabilityItem("Dolby Atmos (EAC3-JOC)", query.hasCodecForMime(MimeTypes.AUDIO_E_AC3_JOC)),
			CapabilityItem("Dolby AC-4", query.hasCodecForMime(MimeTypes.AUDIO_AC4)),
			CapabilityItem("DTS", query.hasCodecForMime(MimeTypes.AUDIO_DTS)),
			CapabilityItem("DTS-HD", query.hasCodecForMime(MimeTypes.AUDIO_DTS_HD)),
			CapabilityItem("TrueHD", query.hasCodecForMime(MimeTypes.AUDIO_TRUEHD)),
			CapabilityItem("FLAC", query.hasCodecForMime(MimeTypes.AUDIO_FLAC)),
			CapabilityItem("Opus", query.hasCodecForMime(MimeTypes.AUDIO_OPUS)),
			CapabilityItem("Vorbis", query.hasCodecForMime(MimeTypes.AUDIO_VORBIS)),
			CapabilityItem("ALAC", query.hasCodecForMime(MimeTypes.AUDIO_ALAC)),
			CapabilityItem("MP3", query.hasCodecForMime(MimeTypes.AUDIO_MPEG)),
			CapabilityItem("MP2", query.hasCodecForMime(MimeTypes.AUDIO_MPEG_L2)),
			CapabilityItem("MP1", query.hasCodecForMime(MimeTypes.AUDIO_MPEG_L1)),
			CapabilityItem("AMR-NB", query.hasCodecForMime(MimeTypes.AUDIO_AMR_NB)),
			CapabilityItem("AMR-WB", query.hasCodecForMime(MimeTypes.AUDIO_AMR_WB)),
			CapabilityItem("PCM", query.hasCodecForMime(MimeTypes.AUDIO_RAW)),
			CapabilityItem("PCM A-law", query.hasCodecForMime(MimeTypes.AUDIO_ALAW)),
			CapabilityItem("PCM µ-law", query.hasCodecForMime(MimeTypes.AUDIO_MLAW)),
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
		items = buildFfmpegCodecItems(),
	)
}

private fun buildFfmpegCodecItems() = FfmpegCodecCapabilities.getCodecs()
	.map { codec ->
		CapabilityItem(
			title = "${codec.section}: ${codec.displayName}",
			supported = codec.included,
			detail = codec.detail,
		)
	}
	.ifEmpty {
		listOf(
			CapabilityItem(
				title = "No FFmpeg decoders detected",
				detail = "The extension is unavailable or did not report any known decoders",
			)
		)
	}

private val FfmpegCodecCapability.detail
	get() = listOfNotNull(
		matchedDecoderName?.let { decoder -> "Matched decoder: $decoder" },
		decoderNames.takeIf { decoders -> decoders.isNotEmpty() }?.joinToString(
			prefix = "Checked decoders: ",
			separator = ", ",
		),
		mimeType,
	).joinToString(" / ")

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
