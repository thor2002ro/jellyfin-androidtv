package org.jellyfin.playback.mpv

import java.io.File

private val ANIME_PRESET_OPTIONS = mapOf(
	"scale" to "spline64",
	"cscale" to "spline64",
	"dscale" to "spline64",
	"deband" to "yes",
	"deband-iterations" to "2",
	"deband-threshold" to "48",
	"deband-range" to "24",
	"deband-grain" to "2",
)

private val LIVE_ACTION_PRESET_OPTIONS = mapOf(
	"scale" to "spline64",
	"cscale" to "spline64",
	"dscale" to "spline64",
	"deband" to "no",
)

/**
 * Anime and live-action presets inherit the selected GPU API. mpv/libplacebo compiles
 * these GLSL hooks for Vulkan or OpenGL; renderer fallback is configured separately.
 */
enum class LibMPVVideoPreset(
	internal val shaders: List<String> = emptyList(),
	internal val options: Map<String, String> = emptyMap(),
) {
	OFF,
	ANIME_FAST(listOf("SSimSuperRes.glsl"), ANIME_PRESET_OPTIONS),
	ANIME_BALANCED(listOf("FSRCNNX_x2_8-0-4-1_LineArt.glsl", "SSimSuperRes.glsl"), ANIME_PRESET_OPTIONS),
	ANIME_HQ(listOf("FSRCNNX_x2_8-0-4-1_LineArt.glsl", "SSimSuperRes.glsl", "SSimDownscaler.glsl"), ANIME_PRESET_OPTIONS),
	LIVE_ACTION_FAST(listOf("SSimSuperRes.glsl"), LIVE_ACTION_PRESET_OPTIONS),
	LIVE_ACTION_BALANCED(listOf("FSRCNNX_x2_8-0-4-1.glsl", "SSimSuperRes.glsl"), LIVE_ACTION_PRESET_OPTIONS),
	LIVE_ACTION_HQ(listOf("FSRCNNX_x2_8-0-4-1.glsl", "SSimSuperRes.glsl", "SSimDownscaler.glsl"), LIVE_ACTION_PRESET_OPTIONS),
	BATTERY_SAVER(
		options = mapOf(
			"glsl-shaders" to "",
			"dither" to "no",
			"deband" to "no",
			"scale" to "bilinear",
			"cscale" to "bilinear",
			"dscale" to "bilinear",
			"correct-downscaling" to "no",
			"interpolation" to "no",
		)
	),
	HDR_HIGH_QUALITY(
		options = mapOf(
			"correct-downscaling" to "yes",
			"gamma" to "0.0",
			"contrast" to "0.0",
			"saturation" to "0.0",
			"brightness" to "0.0",
			"tone-mapping" to "clip",
		)
	),
	// Match upstream: avoid shader passes and force Android OpenGL for the 8K profile.
	OPTIMIZED_8K(
		options = mapOf(
			"gpu-api" to "opengl",
			"gpu-context" to "android",
			"hwdec" to "mediacodec",
			"vd-lavc-dr" to "yes",
			"vd-queue-enable" to "no",
			"profile" to "high-quality",
			"dither" to "no",
			"deband" to "no",
			"interpolation" to "no",
			"glsl-shaders" to "",
			"vf" to "",
		)
	),
}

enum class LibMPVAudioPreset(internal val options: Map<String, String> = emptyMap()) {
	OFF,
	STANDARD(mapOf("audio-channels" to "auto-safe", "af" to "")),
	CINEMA_SPATIAL(
		mapOf(
			"audio-channels" to "7.1",
			"audio-spdif" to "",
			"af" to "format=channels=7.1,lavfi=[surround],lavfi=[bass=g=3]",
		)
	),
}

internal fun LibMPVPlaybackOptions.presetOptions(shaderDirectory: File): Map<String, String> = linkedMapOf<String, String>().apply {
	putAll(videoPreset.options)
	if (videoPreset.shaders.isNotEmpty()) {
		put(
			"glsl-shaders",
			videoPreset.shaders.joinToString(File.pathSeparator) { name -> shaderDirectory.resolve(name).absolutePath },
		)
	}
	putAll(audioPreset.options)
}

internal val BUNDLED_PRESET_SHADERS: List<String>
	get() = LibMPVVideoPreset.entries
		.flatMap(LibMPVVideoPreset::shaders)
		.distinct()
