package org.jellyfin.androidtv.test

data class PlaybackWatchState(
	val played: Boolean,
	val playCount: Int,
	val positionTicks: Long,
	val favorite: Boolean,
	val likes: Boolean?,
	val rating: Double?,
	val lastPlayed: String?,
)

data class PlaybackWatchDifference(val field: String, val before: Any?, val after: Any?)

fun diffPlaybackWatchState(before: PlaybackWatchState, after: PlaybackWatchState): List<PlaybackWatchDifference> = buildList {
	fun compare(field: String, old: Any?, new: Any?) {
		if (old != new) add(PlaybackWatchDifference(field, old, new))
	}
	compare("played", before.played, after.played)
	compare("playCount", before.playCount, after.playCount)
	compare("positionTicks", before.positionTicks, after.positionTicks)
	compare("favorite", before.favorite, after.favorite)
	compare("likes", before.likes, after.likes)
	compare("rating", before.rating, after.rating)
	compare("lastPlayed", before.lastPlayed, after.lastPlayed)
}
