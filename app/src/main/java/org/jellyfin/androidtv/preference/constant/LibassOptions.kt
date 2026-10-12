package org.jellyfin.androidtv.preference.constant

import io.github.peerless2012.ass.media.type.AssRenderType
import org.jellyfin.androidtv.R
import org.jellyfin.preference.PreferenceEnum

enum class LibassRenderType(
	override val nameRes: Int,
	val descriptionRes: Int,
	val assRenderType: AssRenderType,
) : PreferenceEnum {
	OVERLAY_OPEN_GL(
		nameRes = R.string.preference_libass_render_type_opengl,
		descriptionRes = R.string.preference_libass_render_type_opengl_description,
		assRenderType = AssRenderType.OVERLAY_OPEN_GL,
	),
	OVERLAY_CANVAS(
		nameRes = R.string.preference_libass_render_type_canvas,
		descriptionRes = R.string.preference_libass_render_type_canvas_description,
		assRenderType = AssRenderType.OVERLAY_CANVAS,
	),
	CUES(
		nameRes = R.string.preference_libass_render_type_cues,
		descriptionRes = R.string.preference_libass_render_type_cues_description,
		assRenderType = AssRenderType.CUES,
	),
}

enum class LibassMaxRenderPixels(
	override val nameRes: Int,
	val descriptionRes: Int,
	val pixels: Int,
) : PreferenceEnum {
	FULL_SURFACE(
		nameRes = R.string.preference_libass_max_render_pixels_off,
		descriptionRes = R.string.preference_libass_max_render_pixels_off_description,
		pixels = 0,
	),
	HD_720(
		nameRes = R.string.preference_libass_max_render_pixels_720,
		descriptionRes = R.string.preference_libass_max_render_pixels_720_description,
		pixels = 1280 * 720,
	),
	FULL_HD_1080(
		nameRes = R.string.preference_libass_max_render_pixels_1080,
		descriptionRes = R.string.preference_libass_max_render_pixels_1080_description,
		pixels = 1920 * 1080,
	),
	QHD_1440(
		nameRes = R.string.preference_libass_max_render_pixels_1440,
		descriptionRes = R.string.preference_libass_max_render_pixels_1440_description,
		pixels = 2560 * 1440,
	),
	UHD_4K(
		nameRes = R.string.preference_libass_max_render_pixels_4k,
		descriptionRes = R.string.preference_libass_max_render_pixels_4k_description,
		pixels = 3840 * 2160,
	),
}

enum class LibassMaxFps(
	override val nameRes: Int,
	val descriptionRes: Int,
	val framesPerSecond: Float,
) : PreferenceEnum {
	FPS_24(R.string.preference_libass_max_fps_24, R.string.preference_libass_max_fps_24_description, 24f),
	FPS_30(R.string.preference_libass_max_fps_30, R.string.preference_libass_max_fps_30_description, 30f),
	FPS_35(R.string.preference_libass_max_fps_35, R.string.preference_libass_max_fps_35_description, 35f),
	FPS_45(R.string.preference_libass_max_fps_45, R.string.preference_libass_max_fps_45_description, 45f),
	FPS_60(R.string.preference_libass_max_fps_60, R.string.preference_libass_max_fps_60_description, 60f),
}

enum class LibassCacheSize(
	override val nameRes: Int,
	val descriptionRes: Int,
	val megabytes: Int,
) : PreferenceEnum {
	MB_32(
		nameRes = R.string.preference_libass_cache_size_32,
		descriptionRes = R.string.preference_libass_cache_size_32_description,
		megabytes = 32,
	),
	MB_64(
		nameRes = R.string.preference_libass_cache_size_64,
		descriptionRes = R.string.preference_libass_cache_size_64_description,
		megabytes = 64,
	),
	MB_128(
		nameRes = R.string.preference_libass_cache_size_128,
		descriptionRes = R.string.preference_libass_cache_size_128_description,
		megabytes = 128,
	),
	MB_256(
		nameRes = R.string.preference_libass_cache_size_256,
		descriptionRes = R.string.preference_libass_cache_size_256_description,
		megabytes = 256,
	),
}

enum class LibassGlyphSize(
	override val nameRes: Int,
	val descriptionRes: Int,
	val glyphs: Int,
) : PreferenceEnum {
	GLYPHS_2500(
		nameRes = R.string.preference_libass_glyph_cache_2500,
		descriptionRes = R.string.preference_libass_glyph_cache_2500_description,
		glyphs = 2_500,
	),
	GLYPHS_5000(
		nameRes = R.string.preference_libass_glyph_cache_5000,
		descriptionRes = R.string.preference_libass_glyph_cache_5000_description,
		glyphs = 5_000,
	),
	GLYPHS_10000(
		nameRes = R.string.preference_libass_glyph_cache_10000,
		descriptionRes = R.string.preference_libass_glyph_cache_10000_description,
		glyphs = 10_000,
	),
	GLYPHS_20000(
		nameRes = R.string.preference_libass_glyph_cache_20000,
		descriptionRes = R.string.preference_libass_glyph_cache_20000_description,
		glyphs = 20_000,
	),
}
