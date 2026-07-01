package org.jellyfin.androidtv.util

import java.util.UUID

object TrackSelectionManager {
	data class TrackSelection(
		val hasSelection: Boolean,
		val trackIndex: Int?,
	)

	private val selectedAudioTracks = mutableMapOf<UUID, Int?>()
	private val selectedSubtitleTracks = mutableMapOf<UUID, Int?>()

	fun setSelectedAudioTrack(itemId: UUID, trackIndex: Int?) {
		synchronized(this) {
			selectedAudioTracks[itemId] = trackIndex
		}
	}

	fun setSelectedAudioTracks(itemIds: Collection<UUID>, trackIndex: Int?) {
		synchronized(this) {
			itemIds.forEach { itemId -> selectedAudioTracks[itemId] = trackIndex }
		}
	}

	fun setSelectedSubtitleTrack(itemId: UUID, trackIndex: Int?) {
		synchronized(this) {
			selectedSubtitleTracks[itemId] = trackIndex
		}
	}

	fun setSelectedSubtitleTracks(itemIds: Collection<UUID>, trackIndex: Int?) {
		synchronized(this) {
			itemIds.forEach { itemId -> selectedSubtitleTracks[itemId] = trackIndex }
		}
	}

	fun getSelectedAudioTrack(itemId: UUID): Int? = synchronized(this) {
		selectedAudioTracks[itemId]
	}

	fun getSelectedAudioTrack(itemIds: Collection<UUID>): Int? = synchronized(this) {
		itemIds.firstNotNullOfOrNull { itemId -> selectedAudioTracks[itemId] }
	}

	fun getSelectedSubtitleTrack(itemId: UUID): Int? = synchronized(this) {
		selectedSubtitleTracks[itemId]
	}

	fun hasSelectedSubtitleTrack(itemId: UUID): Boolean = synchronized(this) {
		selectedSubtitleTracks.containsKey(itemId)
	}

	fun getSelectedSubtitleTrackSelection(itemId: UUID): TrackSelection = synchronized(this) {
		TrackSelection(
			hasSelection = selectedSubtitleTracks.containsKey(itemId),
			trackIndex = selectedSubtitleTracks[itemId],
		)
	}

	fun getSelectedSubtitleTrackSelection(itemIds: Collection<UUID>): TrackSelection = synchronized(this) {
		val itemId = itemIds.firstOrNull(selectedSubtitleTracks::containsKey)
		TrackSelection(
			hasSelection = itemId != null,
			trackIndex = itemId?.let(selectedSubtitleTracks::get),
		)
	}

	fun clearSelections(itemId: UUID) {
		synchronized(this) {
			selectedAudioTracks.remove(itemId)
			selectedSubtitleTracks.remove(itemId)
		}
	}

	fun clearAllSelections() {
		synchronized(this) {
			selectedAudioTracks.clear()
			selectedSubtitleTracks.clear()
		}
	}
}
