package org.jellyfin.androidtv.util

import android.content.Context
import java.util.UUID

class TrackSelectionStore(context: Context) {
	private val preferences = context.applicationContext.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)

	fun getSelectedAudioTracks(scope: String): Map<UUID, Int?> = preferences
		.getStringSet(key(scope, KEY_AUDIO_TRACKS), emptySet())
		.orEmpty()
		.toTrackSelectionMap()

	fun getSelectedSubtitleTracks(scope: String): Map<UUID, Int?> = preferences
		.getStringSet(key(scope, KEY_SUBTITLE_TRACKS), emptySet())
		.orEmpty()
		.toTrackSelectionMap()

	fun setSelectedAudioTracks(scope: String, tracks: Map<UUID, Int?>) {
		preferences.edit()
			.putStringSet(key(scope, KEY_AUDIO_TRACKS), tracks.toStringSet())
			.apply()
	}

	fun setSelectedSubtitleTracks(scope: String, tracks: Map<UUID, Int?>) {
		preferences.edit()
			.putStringSet(key(scope, KEY_SUBTITLE_TRACKS), tracks.toStringSet())
			.apply()
	}

	private fun Set<String>.toTrackSelectionMap() = mapNotNull { entry ->
		val id = entry.substringBefore(SEPARATOR).takeIf { it != entry } ?: return@mapNotNull null
		val value = entry.substringAfter(SEPARATOR)
		val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return@mapNotNull null
		val trackIndex = when (value) {
			NULL_TRACK_INDEX -> null
			else -> value.toIntOrNull() ?: return@mapNotNull null
		}

		uuid to trackIndex
	}.toMap()

	private fun Map<UUID, Int?>.toStringSet() = map { (id, trackIndex) ->
		"$id$SEPARATOR${trackIndex?.toString() ?: NULL_TRACK_INDEX}"
	}.toSet()

	private fun key(scope: String, name: String) = "$scope:$name"

	private companion object {
		const val SHARED_PREFERENCES_NAME = "track_selection"
		const val KEY_AUDIO_TRACKS = "audio_tracks"
		const val KEY_SUBTITLE_TRACKS = "subtitle_tracks"
		const val SEPARATOR = "="
		const val NULL_TRACK_INDEX = "null"
	}
}
