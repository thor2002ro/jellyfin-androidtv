package org.jellyfin.androidtv.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.constant.BitstreamAudioFormat
import org.jellyfin.androidtv.ui.settings.screen.playback.ExoPlayerSettingsRoutes
import org.jellyfin.androidtv.ui.settings.screen.playback.LibMPVSettingsRoutes
import org.jellyfin.androidtv.ui.settings.screen.playback.LibVLCSettingsRoutes
import org.jellyfin.androidtv.ui.settings.screen.playback.LibassSettingsRoutes

internal data class SettingsSearchResult(
	val route: String,
	val label: String,
	val path: String,
	val focusKey: String? = null,
	val initialFocusIndex: Int = 0,
)

internal data class SettingsSearchEntry(
	val route: String,
	@StringRes val label: Int,
	val path: List<Int>,
	val focusKey: String? = null,
	val initialFocusIndex: Int = 0,
)

internal val settingsSearchEntries = listOf(
	SettingsSearchEntry(Routes.AUTHENTICATION, R.string.pref_login, listOf(R.string.settings)),
	SettingsSearchEntry(Routes.AUTHENTICATION_AUTO_SIGN_IN, R.string.auto_sign_in, listOf(R.string.settings, R.string.pref_login)),
	SettingsSearchEntry(Routes.AUTHENTICATION_SORT_BY, R.string.sort_accounts_by, listOf(R.string.settings, R.string.pref_login)),
	SettingsSearchEntry(Routes.CUSTOMIZATION, R.string.pref_customization, listOf(R.string.settings)),
	SettingsSearchEntry(Routes.CUSTOMIZATION_THEME, R.string.pref_app_theme, listOf(R.string.settings, R.string.pref_customization)),
	SettingsSearchEntry(Routes.CUSTOMIZATION_CLOCK, R.string.pref_clock_display, listOf(R.string.settings, R.string.pref_customization)),
	SettingsSearchEntry(Routes.CUSTOMIZATION_WATCHED_INDICATOR, R.string.pref_watched_indicator, listOf(R.string.settings, R.string.pref_customization)),
	SettingsSearchEntry(Routes.CUSTOMIZATION_BACKDROP, R.string.lbl_show_backdrop, listOf(R.string.settings, R.string.pref_customization)),
	SettingsSearchEntry(Routes.CUSTOMIZATION_SCREENSAVER, R.string.pref_screensaver, listOf(R.string.settings, R.string.pref_customization)),
	SettingsSearchEntry(Routes.CUSTOMIZATION_SCREENSAVER_TIMEOUT, R.string.pref_screensaver_inapp_timeout, listOf(R.string.settings, R.string.pref_customization, R.string.pref_screensaver)),
	SettingsSearchEntry(Routes.CUSTOMIZATION_SCREENSAVER_AGE_RATING, R.string.pref_screensaver_ageratingmax, listOf(R.string.settings, R.string.pref_customization, R.string.pref_screensaver)),
	SettingsSearchEntry(Routes.CUSTOMIZATION_SUBTITLES, R.string.pref_customization_subtitles, listOf(R.string.settings, R.string.pref_customization)),
	SettingsSearchEntry(Routes.CUSTOMIZATION_SUBTITLES_TEXT_COLOR, R.string.lbl_subtitle_text_color, listOf(R.string.settings, R.string.pref_customization, R.string.pref_customization_subtitles)),
	SettingsSearchEntry(Routes.CUSTOMIZATION_SUBTITLES_HDR_TEXT_COLOR, R.string.lbl_subtitle_hdr_text_color, listOf(R.string.settings, R.string.pref_customization, R.string.pref_customization_subtitles)),
	SettingsSearchEntry(Routes.CUSTOMIZATION_SUBTITLES_BACKGROUND_COLOR, R.string.lbl_subtitle_background_color, listOf(R.string.settings, R.string.pref_customization, R.string.pref_customization_subtitles)),
	SettingsSearchEntry(Routes.CUSTOMIZATION_SUBTITLES_EDGE_COLOR, R.string.lbl_subtitle_text_stroke_color, listOf(R.string.settings, R.string.pref_customization, R.string.pref_customization_subtitles)),
	SettingsSearchEntry(Routes.LIBRARIES, R.string.pref_libraries, listOf(R.string.settings, R.string.pref_customization)),
	SettingsSearchEntry(Routes.HOME, R.string.home_prefs, listOf(R.string.settings, R.string.pref_customization)),
	SettingsSearchEntry(Routes.HOME, R.string.home_recently_released, listOf(R.string.settings, R.string.pref_customization, R.string.home_prefs), "home_recently_released"),
	SettingsSearchEntry(Routes.HOME, R.string.home_wide_cards, listOf(R.string.settings, R.string.pref_customization, R.string.home_prefs), "home_wide_cards"),
	SettingsSearchEntry(Routes.HOME, R.string.home_combine_continue_watching_next_up, listOf(R.string.settings, R.string.pref_customization, R.string.home_prefs), "home_combine_continue_watching_next_up"),
	SettingsSearchEntry(Routes.HOME, R.string.home_next_up_rewatching, listOf(R.string.settings, R.string.pref_customization, R.string.home_prefs), "home_next_up_rewatching"),
	SettingsSearchEntry(Routes.HOME_ROW_ITEM_LIMIT, R.string.home_row_item_limit, listOf(R.string.settings, R.string.pref_customization, R.string.home_prefs)),
	SettingsSearchEntry(Routes.HOME_NEXT_UP_CUTOFF, R.string.home_next_up_max_days, listOf(R.string.settings, R.string.pref_customization, R.string.home_prefs)),
	SettingsSearchEntry(Routes.PLAYBACK, R.string.pref_playback, listOf(R.string.settings)),
	SettingsSearchEntry(Routes.PLAYBACK_PLAYER, R.string.playback_video_player, listOf(R.string.settings, R.string.pref_playback)),
	SettingsSearchEntry(Routes.PLAYBACK_PHOTO_PLAYER, R.string.photo_player, listOf(R.string.settings, R.string.pref_playback)),
	SettingsSearchEntry(Routes.PLAYBACK_HDR_PLAYER, R.string.playback_hdr_player, listOf(R.string.settings, R.string.pref_playback)),
	SettingsSearchEntry(Routes.PLAYBACK_AUDIO_LANGUAGE, R.string.pref_preferred_audio_language, listOf(R.string.settings, R.string.pref_playback)),
	SettingsSearchEntry(Routes.PLAYBACK_SUBTITLE_LANGUAGE, R.string.pref_preferred_subtitle_language, listOf(R.string.settings, R.string.pref_playback)),
	SettingsSearchEntry(Routes.PLAYBACK_PLAYER_HEADER_LAYOUT, R.string.pref_player_header_layout, listOf(R.string.settings, R.string.pref_customization)),
	SettingsSearchEntry(Routes.PLAYBACK_NEXT_UP, R.string.pref_playback_next_up, listOf(R.string.settings, R.string.pref_playback)),
	SettingsSearchEntry(Routes.PLAYBACK_NEXT_UP_BEHAVIOR, R.string.pref_next_up_behavior_title, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_next_up)),
	SettingsSearchEntry(Routes.PLAYBACK_INACTIVITY_PROMPT, R.string.pref_playback_inactivity_prompt, listOf(R.string.settings, R.string.pref_playback)),
	SettingsSearchEntry(Routes.PLAYBACK_PREROLLS, R.string.pref_playback_prerolls, listOf(R.string.settings, R.string.pref_playback)),
	SettingsSearchEntry(Routes.PLAYBACK_MEDIA_SEGMENTS, R.string.pref_playback_media_segments, listOf(R.string.settings, R.string.pref_playback)),
	SettingsSearchEntry(Routes.PLAYBACK_ADVANCED, R.string.pref_playback_advanced, listOf(R.string.settings, R.string.pref_playback)),
	SettingsSearchEntry(Routes.PLAYBACK_ADVANCED, R.string.skip_forward_length, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced), "skip_forward_length", 3),
	SettingsSearchEntry(Routes.PLAYBACK_ADVANCED, R.string.skip_back_length, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced), "skip_back_length", 4),
	SettingsSearchEntry(Routes.PLAYBACK_ADVANCED, R.string.lbl_seek_confirmation, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced), "seek_confirmation", 5),
	SettingsSearchEntry(Routes.PLAYBACK_ADVANCED, R.string.preference_prefer_fmp4_hls_container, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced), "prefer_fmp4_hls_container", 9),
	SettingsSearchEntry(Routes.PLAYBACK_ADVANCED, R.string.lbl_refresh_switching, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced), "refresh_rate_switching", 10),
	SettingsSearchEntry(Routes.PLAYBACK_ADVANCED, R.string.pref_resolution_switching, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced), "resolution_switching", 11),
	SettingsSearchEntry(Routes.PLAYBACK_ADVANCED, R.string.pref_display_change_delay, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced), "display_change_delay", 12),
	SettingsSearchEntry(Routes.PLAYBACK_ADVANCED, R.string.video_start_delay, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced), "video_start_delay", 14),
	SettingsSearchEntry(Routes.PLAYBACK_ADVANCED, R.string.preference_enable_pgs, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced), "pgs_direct_play", 22),
	SettingsSearchEntry(Routes.PLAYBACK_ADVANCED, R.string.pref_burn_subtitles_when_transcoding, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced), "subtitles_burn_during_transcode", 23),
	SettingsSearchEntry(Routes.PLAYBACK_ADVANCED, R.string.lbl_direct_stream_live, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced), "live_tv_direct_play_enabled", 25),
	SettingsSearchEntry(Routes.PLAYBACK_ADVANCED, R.string.pref_surround_volume_boost, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced), "surround_volume_boost", 28),
	SettingsSearchEntry(Routes.PLAYBACK_ADVANCED, R.string.pref_audio_night_mode, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced), "audio_night_mode", 29),
	SettingsSearchEntry(Routes.PLAYBACK_RESUME_SUBTRACT_DURATION, R.string.lbl_resume_preroll, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced)),
	SettingsSearchEntry(Routes.PLAYBACK_MAX_BITRATE, R.string.pref_max_bitrate_title, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced)),
	SettingsSearchEntry(Routes.PLAYBACK_ZOOM_MODE, R.string.default_video_zoom, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced)),
	SettingsSearchEntry(Routes.PLAYBACK_BUFFER_LENGTH, R.string.playback_buffer_length, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced)),
	SettingsSearchEntry(Routes.PLAYBACK_HDR_OVERRIDES, R.string.preference_hdr_overrides, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced)),
	SettingsSearchEntry(Routes.PLAYBACK_DOVI_COMPATIBILITY, R.string.dovi_compatibility_title, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced)),
	SettingsSearchEntry(Routes.PLAYBACK_AUDIO_BEHAVIOR, R.string.lbl_audio_output, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced)),
	SettingsSearchEntry(Routes.PLAYBACK_CODEC, R.string.preference_codecs, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced)),
	SettingsSearchEntry(Routes.PLAYBACK_AVC_LEVEL, R.string.user_avc_level, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced, R.string.preference_codecs)),
	SettingsSearchEntry(Routes.PLAYBACK_HEVC_LEVEL, R.string.user_hevc_level, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced, R.string.preference_codecs)),
	SettingsSearchEntry(Routes.PLAYBACK_DEBUG_CAPABILITIES, R.string.pref_debug_capabilities_title, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced)),
	SettingsSearchEntry(ExoPlayerSettingsRoutes.PLAYBACK_EXOPLAYER, R.string.preference_exoplayer_options, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced)),
	SettingsSearchEntry(LibassSettingsRoutes.PLAYBACK_LIBASS, R.string.preference_libass_options, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced)),
	SettingsSearchEntry(LibassSettingsRoutes.PLAYBACK_LIBASS_RENDER_TYPE, R.string.preference_libass_render_type, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced, R.string.preference_libass_options)),
	SettingsSearchEntry(LibassSettingsRoutes.PLAYBACK_LIBASS_MAX_RENDER_PIXELS, R.string.preference_libass_max_render_pixels, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced, R.string.preference_libass_options)),
	SettingsSearchEntry(LibassSettingsRoutes.PLAYBACK_LIBASS_MAX_FPS, R.string.preference_libass_max_fps, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced, R.string.preference_libass_options)),
	SettingsSearchEntry(LibassSettingsRoutes.PLAYBACK_LIBASS_CACHE_SIZE, R.string.preference_libass_cache_size, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced, R.string.preference_libass_options)),
	SettingsSearchEntry(LibassSettingsRoutes.PLAYBACK_LIBASS_GLYPH_SIZE, R.string.preference_libass_glyph_cache_size, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced, R.string.preference_libass_options)),
	SettingsSearchEntry(LibMPVSettingsRoutes.PLAYBACK_MPV, R.string.preference_mpv_options, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced)),
	SettingsSearchEntry(LibMPVSettingsRoutes.PLAYBACK_MPV_ALL_OPTIONS, R.string.preference_mpv_all_options, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced, R.string.preference_mpv_options)),
	SettingsSearchEntry(LibVLCSettingsRoutes.PLAYBACK_LIBVLC, R.string.preference_libvlc_options, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced)),
	SettingsSearchEntry(LibVLCSettingsRoutes.PLAYBACK_LIBVLC_DECODER, R.string.preference_libvlc_decoder, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced, R.string.preference_libvlc_options)),
	SettingsSearchEntry(LibVLCSettingsRoutes.PLAYBACK_LIBVLC_DEBLOCKING, R.string.preference_libvlc_deblocking, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced, R.string.preference_libvlc_options)),
	SettingsSearchEntry(LibVLCSettingsRoutes.PLAYBACK_LIBVLC_VIDEO_OUTPUT, R.string.preference_libvlc_video_output, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced, R.string.preference_libvlc_options)),
	SettingsSearchEntry(LibVLCSettingsRoutes.PLAYBACK_LIBVLC_AUDIO_OUTPUT, R.string.preference_libvlc_audio_output, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced, R.string.preference_libvlc_options)),
	SettingsSearchEntry(LibVLCSettingsRoutes.PLAYBACK_LIBVLC_REPLAY_GAIN_MODE, R.string.preference_libvlc_replay_gain_mode, listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced, R.string.preference_libvlc_options)),
	SettingsSearchEntry(Routes.TELEMETRY, R.string.pref_telemetry_category, listOf(R.string.settings)),
	SettingsSearchEntry(Routes.BACKUP, R.string.backup_restore_title, listOf(R.string.settings)),
	SettingsSearchEntry(
		Routes.BACKUP,
		R.string.backup_create,
		listOf(R.string.settings, R.string.backup_restore_title),
		"backup_create",
	),
	SettingsSearchEntry(
		Routes.BACKUP,
		R.string.backup_restore_restore,
		listOf(R.string.settings, R.string.backup_restore_title),
		"backup_restore",
	),
	SettingsSearchEntry(Routes.DEVELOPER, R.string.pref_developer_link, listOf(R.string.settings, R.string.pref_about_title)),
	SettingsSearchEntry(Routes.APP_UPDATES, R.string.app_updates, listOf(R.string.settings)),
	SettingsSearchEntry(Routes.ABOUT, R.string.pref_about_title, listOf(R.string.settings)),
	SettingsSearchEntry(Routes.LICENSES, R.string.licenses_link, listOf(R.string.settings, R.string.pref_about_title)),
	SettingsSearchEntry(
		Routes.PLAYBACK_ADVANCED,
		R.string.pref_report_device_profile_title,
		listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced),
		"report_device_profile",
		32 + BitstreamAudioFormat.entries.size,
	),
) + BitstreamAudioFormat.entries.mapIndexed { index, format ->
	SettingsSearchEntry(
		route = Routes.PLAYBACK_ADVANCED,
		label = format.nameRes,
		path = listOf(R.string.settings, R.string.pref_playback, R.string.pref_playback_advanced),
		focusKey = "bitstream_audio_${format.name}",
		initialFocusIndex = 30 + index,
	)
}

internal fun resolveSettingsSearchResults(context: Context) = settingsSearchEntries.map { entry ->
	SettingsSearchResult(
		route = entry.route,
		label = context.getString(entry.label),
		path = entry.path.joinToString(" > ") { context.getString(it) },
		focusKey = entry.focusKey,
		initialFocusIndex = entry.initialFocusIndex,
	)
}

internal fun filterSettingsSearchResults(
	settings: Collection<SettingsSearchResult>,
	query: String,
): List<SettingsSearchResult> {
	val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
	if (terms.isEmpty()) return emptyList()

	return settings.filter { setting ->
		val values = sequenceOf(setting.label, setting.path)
		terms.all { term -> values.any { it.contains(term, ignoreCase = true) } }
	}
}
