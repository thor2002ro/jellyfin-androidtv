package org.jellyfin.androidtv.ui.settings.screen

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import androidx.annotation.OptIn
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Badge
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
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
import org.jellyfin.androidtv.util.profile.getMediaCodecDecoders
import org.jellyfin.androidtv.util.profile.getSupportedDisplayHdrTypes
import org.jellyfin.androidtv.util.profile.mediaCodecFeatureNames
import org.jellyfin.androidtv.util.profile.prettyFormat
import org.koin.compose.koinInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsDebugCapabilitiesScreen() {
	val context = LocalContext.current
	val userPreferences = koinInject<UserPreferences>()
	val softwareCodecsEnabled by rememberPreference(userPreferences, UserPreferences.softwareCodecsEnabled)
	var rawExpanded by rememberSaveable { mutableStateOf(false) }
	val groups by produceState<List<CapabilityGroup>>(emptyList(), context, softwareCodecsEnabled, rawExpanded) {
		value = withContext(Dispatchers.Default) {
			buildCapabilityGroups(context, softwareCodecsEnabled, rawExpanded)
		}
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
			capabilityGroup(
				group = group,
				expanded = if (group.collapsible) rawExpanded else true,
				onToggle = if (group.collapsible) ({ rawExpanded = !rawExpanded }) else null,
			)
		}
	}
}

private fun LazyListScope.capabilityGroup(
	group: CapabilityGroup,
	expanded: Boolean = true,
	onToggle: (() -> Unit)? = null,
) {
	item {
		if (onToggle == null) {
			ListSection(
				headingContent = { Text(group.title, fontSize = 16.sp, maxLines = 1) },
				captionContent = group.caption?.let { caption -> ({ CompactCapabilityText(caption, 11) }) },
			)
		} else {
			ListButton(
				onClick = onToggle,
				headingContent = { Text(group.title, fontSize = 16.sp, maxLines = 1) },
				captionContent = group.caption?.let { caption -> ({ CompactCapabilityText(caption, 11) }) },
				trailingContent = { ExpandIcon(expanded) },
			)
		}
	}

	if (!expanded) return

	group.children.forEach { child ->
		capabilityGroup(child)
	}

	items(
		items = group.items,
		key = { item -> "${group.title}:${item.title}" },
		contentType = { "capability_item" },
	) { item ->
		FocusableListControl(
			headingContent = { CompactCapabilityText(item.title, 14) },
			captionContent = item.detail?.let { detail -> ({
				CompactCapabilityText(detail, 11, item.detailMaxLines)
			}) },
			trailingContent = item.supported?.let { supported -> ({ CapabilityBadge(supported) }) },
		)
	}
}

@Composable
private fun CompactCapabilityText(text: String, size: Int, maxLines: Int = 1) = Text(
	text = text,
	fontSize = size.sp,
	maxLines = maxLines,
	overflow = TextOverflow.Ellipsis,
)

@Composable
private fun ExpandIcon(expanded: Boolean) {
	Icon(
		painter = painterResource(R.drawable.ic_arrow_up),
		contentDescription = null,
		modifier = Modifier.rotate(if (expanded) 0f else 180f),
	)
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
			},
			fontSize = 11.sp,
		)
	}
}

private fun buildCapabilityGroups(
	context: Context,
	softwareCodecsEnabled: Boolean,
	includeRawDetails: Boolean,
): List<CapabilityGroup> {
	val mediaTest = MediaCodecCapabilitiesTest(softwareCodecsEnabled)
	val displayHdrTypes = getSupportedDisplayHdrTypes(context)

	return listOf(
		buildHdrCapabilities(context, mediaTest, displayHdrTypes),
		buildVideoCapabilities(context, mediaTest),
		buildAudioCapabilities(context, softwareCodecsEnabled),
		buildRawAndroidDecoderCapabilities(includeRawDetails),
		buildFfmpegCapabilities(context),
	)
}

private fun buildRawAndroidDecoderCapabilities(includeDetails: Boolean): CapabilityGroup {
	val decoders = getMediaCodecDecoders()
		.asSequence()
		.distinctBy(MediaCodecInfo::getName)
		.sortedBy(MediaCodecInfo::getName)
		.toList()

	return CapabilityGroup(
		title = "RAW",
		caption = "All decoders reported by Android MediaCodec",
		children = listOf(
			rawDecoderGroup("Video", decoders, "video/", includeDetails),
			rawDecoderGroup("Audio", decoders, "audio/", includeDetails),
			rawOtherDecoderGroup(decoders, includeDetails),
		).filter { group -> group.items.isNotEmpty() },
		collapsible = true,
	)
}

private fun rawDecoderGroup(
	title: String,
	decoders: List<MediaCodecInfo>,
	typePrefix: String,
	includeDetails: Boolean,
) = CapabilityGroup(
	title = title,
	items = decoders.mapNotNull { codec ->
		codec.supportedTypes
			.filter { type -> type.startsWith(typePrefix) }
			.sorted()
			.takeIf { types -> types.isNotEmpty() }
			?.let { types -> rawDecoderItem(codec, types, includeDetails) }
	},
)

private fun rawOtherDecoderGroup(decoders: List<MediaCodecInfo>, includeDetails: Boolean) = CapabilityGroup(
	title = "Other",
	items = decoders.mapNotNull { codec ->
		codec.supportedTypes
			.sorted()
			.takeIf { types -> types.none { type -> type.startsWith("video/") || type.startsWith("audio/") } }
			?.let { types -> rawDecoderItem(codec, types, includeDetails) }
	},
)

private fun rawDecoderItem(
	codec: MediaCodecInfo,
	types: List<String>,
	includeDetails: Boolean,
): CapabilityItem {
	val type = if (codec.isSoftwareDecoder) "sw" else "hw"
	return CapabilityItem(
		title = "$type: ${codec.name}",
		detail = if (includeDetails) rawDecoderDetail(codec, types) else null,
		detailMaxLines = Int.MAX_VALUE,
	)
}

@Suppress("DEPRECATION")
private val mediaCodecColorFormatNames = mapOf(
	CodecCapabilities.COLOR_FormatMonochrome to "Monochrome",
	CodecCapabilities.COLOR_Format8bitRGB332 to "RGB 3:3:2 (8-bit)",
	CodecCapabilities.COLOR_Format12bitRGB444 to "RGB 4:4:4 (12-bit)",
	CodecCapabilities.COLOR_Format16bitARGB4444 to "ARGB 4:4:4:4 (16-bit)",
	CodecCapabilities.COLOR_Format16bitARGB1555 to "ARGB 1:5:5:5 (16-bit)",
	CodecCapabilities.COLOR_Format16bitRGB565 to "RGB 5:6:5 (16-bit)",
	CodecCapabilities.COLOR_Format16bitBGR565 to "BGR 5:6:5 (16-bit)",
	CodecCapabilities.COLOR_Format18bitRGB666 to "RGB 6:6:6 (18-bit)",
	CodecCapabilities.COLOR_Format18bitARGB1665 to "ARGB 1:6:6:5 (18-bit)",
	CodecCapabilities.COLOR_Format19bitARGB1666 to "ARGB 1:6:6:6 (19-bit)",
	CodecCapabilities.COLOR_Format24bitRGB888 to "RGB 8:8:8 (24-bit)",
	CodecCapabilities.COLOR_Format24bitBGR888 to "BGR 8:8:8 (24-bit)",
	CodecCapabilities.COLOR_Format24bitARGB1887 to "ARGB 1:8:8:7 (24-bit)",
	CodecCapabilities.COLOR_Format25bitARGB1888 to "ARGB 1:8:8:8 (25-bit)",
	CodecCapabilities.COLOR_Format32bitBGRA8888 to "BGRA 8:8:8:8 (32-bit)",
	CodecCapabilities.COLOR_Format32bitARGB8888 to "ARGB 8:8:8:8 (32-bit)",
	CodecCapabilities.COLOR_FormatYUV411Planar to "YUV 4:1:1 planar",
	CodecCapabilities.COLOR_FormatYUV411PackedPlanar to "YUV 4:1:1 packed planar",
	CodecCapabilities.COLOR_FormatYUV420Planar to "YUV 4:2:0 planar",
	CodecCapabilities.COLOR_FormatYUV420PackedPlanar to "YUV 4:2:0 packed planar",
	CodecCapabilities.COLOR_FormatYUV420SemiPlanar to "YUV 4:2:0 semi-planar",
	CodecCapabilities.COLOR_FormatYUV422Planar to "YUV 4:2:2 planar",
	CodecCapabilities.COLOR_FormatYUV422PackedPlanar to "YUV 4:2:2 packed planar",
	CodecCapabilities.COLOR_FormatYUV422SemiPlanar to "YUV 4:2:2 semi-planar",
	CodecCapabilities.COLOR_FormatYCbYCr to "YCbYCr 4:2:2 packed",
	CodecCapabilities.COLOR_FormatYCrYCb to "YCrYCb 4:2:2 packed",
	CodecCapabilities.COLOR_FormatCbYCrY to "CbYCrY 4:2:2 packed",
	CodecCapabilities.COLOR_FormatCrYCbY to "CrYCbY 4:2:2 packed",
	CodecCapabilities.COLOR_FormatYUV444Interleaved to "YUV 4:4:4 interleaved",
	CodecCapabilities.COLOR_FormatRawBayer8bit to "Raw Bayer (8-bit)",
	CodecCapabilities.COLOR_FormatRawBayer10bit to "Raw Bayer (10-bit)",
	CodecCapabilities.COLOR_FormatRawBayer8bitcompressed to "Raw Bayer compressed (8-bit)",
	CodecCapabilities.COLOR_FormatL2 to "Luminance (2-bit)",
	CodecCapabilities.COLOR_FormatL4 to "Luminance (4-bit)",
	CodecCapabilities.COLOR_FormatL8 to "Luminance (8-bit)",
	CodecCapabilities.COLOR_FormatL16 to "Luminance (16-bit)",
	CodecCapabilities.COLOR_FormatL24 to "Luminance (24-bit)",
	CodecCapabilities.COLOR_FormatL32 to "Luminance (32-bit)",
	CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar to "YUV 4:2:0 packed semi-planar",
	CodecCapabilities.COLOR_FormatYUV422PackedSemiPlanar to "YUV 4:2:2 packed semi-planar",
	CodecCapabilities.COLOR_Format18BitBGR666 to "BGR 6:6:6 (18-bit)",
	CodecCapabilities.COLOR_Format24BitARGB6666 to "ARGB 6:6:6:6 (24-bit)",
	CodecCapabilities.COLOR_Format24BitABGR6666 to "ABGR 6:6:6:6 (24-bit)",
	CodecCapabilities.COLOR_FormatYUVP010 to "YUV P010 4:2:0 semi-planar (10-bit)",
	CodecCapabilities.COLOR_FormatYUVP210 to "YUV P210 4:2:2 semi-planar (10-bit)",
	CodecCapabilities.COLOR_Format64bitABGRFloat to "ABGR float (64-bit)",
	CodecCapabilities.COLOR_FormatSurface to "Surface",
	CodecCapabilities.COLOR_Format32bitABGR8888 to "ABGR 8:8:8:8 (32-bit)",
	CodecCapabilities.COLOR_Format32bitABGR2101010 to "ABGR 2:10:10:10 (32-bit)",
	CodecCapabilities.COLOR_FormatRGBAFlexible to "RGBA flexible",
	CodecCapabilities.COLOR_FormatRGBFlexible to "RGB flexible",
	CodecCapabilities.COLOR_FormatYUV420Flexible to "YUV 4:2:0 flexible",
	CodecCapabilities.COLOR_FormatYUV422Flexible to "YUV 4:2:2 flexible",
	CodecCapabilities.COLOR_FormatYUV444Flexible to "YUV 4:4:4 flexible",
	CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar to "TI YUV 4:2:0 packed semi-planar",
	CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar to "Qualcomm YUV 4:2:0 semi-planar",
)

private fun Int.prettyColorFormat(): String {
	val name = mediaCodecColorFormatNames[this] ?: "Vendor/unknown"
	return "$name (0x${toUInt().toString(16).uppercase()})"
}

@Suppress("CyclomaticComplexMethod")
internal fun rawDecoderDetail(codec: MediaCodecInfo, types: List<String>) = buildList {
	if (AndroidVersion.isAtLeastQ) {
		runCatching { codec.canonicalName }.getOrNull()?.let { add("canonicalName: $it") }
		runCatching { codec.isVendor }.getOrNull()?.let { add("isVendor: $it") }
		runCatching { codec.isHardwareAccelerated }.getOrNull()?.let { add("isHardwareAccelerated: $it") }
		runCatching { codec.isSoftwareOnly }.getOrNull()?.let { add("isSoftwareOnly: $it") }
		runCatching { codec.isAlias }.getOrNull()?.let { add("isAlias: $it") }
	}

	for (type in types) {
		add(type)
		val capabilities = runCatching { codec.getCapabilitiesForType(type) }.getOrNull() ?: continue

		runCatching { capabilities.audioCapabilities }.getOrNull()?.let { audio ->
			if (AndroidVersion.isAtLeastS) {
				runCatching { audio.minInputChannelCount }.getOrNull()?.let { add("minInputChannelCount: $it") }
				runCatching { audio.inputChannelCountRanges }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { ranges ->
					add("inputChannelCountRanges: ${ranges.joinToString { it.prettyFormat() }}")
				}
			}
			runCatching { audio.maxInputChannelCount }.getOrNull()?.let { add("maxInputChannelCount: $it") }
			runCatching { audio.bitrateRange }.getOrNull()?.let { add("bitrateRange: ${it.prettyFormat()}") }
			runCatching { audio.supportedSampleRates }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { rates ->
				add("supportedSampleRates: ${rates.joinToString()}")
			}
			runCatching { audio.supportedSampleRateRanges }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { ranges ->
				add("supportedSampleRateRanges: ${ranges.joinToString { it.prettyFormat() }}")
			}
		}

		runCatching { capabilities.videoCapabilities }.getOrNull()?.let { video ->
			runCatching { video.bitrateRange }.getOrNull()?.let { add("bitrateRange: ${it.prettyFormat()}") }
			runCatching { video.supportedFrameRates }.getOrNull()?.let { add("supportedFrameRates: ${it.prettyFormat()}") }
			runCatching { video.supportedWidths }.getOrNull()?.let { add("supportedWidths: ${it.prettyFormat()}") }
			runCatching { video.supportedHeights }.getOrNull()?.let { add("supportedHeights: ${it.prettyFormat()}") }
			runCatching { video.widthAlignment }.getOrNull()?.let { add("widthAlignment: $it") }
			runCatching { video.heightAlignment }.getOrNull()?.let { add("heightAlignment: $it") }
			if (AndroidVersion.isAtLeastQ) {
				runCatching { video.supportedPerformancePoints }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { points ->
					add("supportedPerformancePoints: ${points.joinToString()}")
				}
			}
		}

		runCatching { capabilities.colorFormats }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { formats ->
			add("colorFormats: ${formats.joinToString { it.prettyColorFormat() }}")
		}
		runCatching { capabilities.profileLevels }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { levels ->
			add("profileLevels: ${levels.joinToString { "${it.profile}: ${it.level}" }}")
		}
		mediaCodecFeatureNames.mapNotNull { name ->
			when {
				runCatching { capabilities.isFeatureRequired(name) }.getOrDefault(false) -> "$name (required)"
				runCatching { capabilities.isFeatureSupported(name) }.getOrDefault(false) -> name
				else -> null
			}
		}.takeIf { it.isNotEmpty() }?.let { features ->
			add("features: ${features.joinToString()}")
		}
	}
}.joinToString("\n")

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
	val caption: String? = null,
	val items: List<CapabilityItem> = emptyList(),
	val children: List<CapabilityGroup> = emptyList(),
	val collapsible: Boolean = false,
)

private data class CapabilityItem(
	val title: String,
	val supported: Boolean? = null,
	val detail: String? = null,
	val detailMaxLines: Int = 1,
)
