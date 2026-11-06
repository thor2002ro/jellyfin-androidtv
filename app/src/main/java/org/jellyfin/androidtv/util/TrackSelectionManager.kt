package org.jellyfin.androidtv.util

import org.jellyfin.sdk.model.api.MediaStreamType
import java.util.UUID

object TrackSelectionManager {
	data class TrackSelection(
		val hasSelection: Boolean,
		val trackIndex: Int?,
	)

	private val selectedAudioTracks = mutableMapOf<UUID, Int?>()
	private val selectedSubtitleTracks = mutableMapOf<UUID, Int?>()
	private val trackChoices = mapOf(
		MediaStreamType.AUDIO to mutableMapOf<UUID, TrackChoice>(),
		MediaStreamType.SUBTITLE to mutableMapOf<UUID, TrackChoice>(),
	)
	private var store: TrackSelectionStore? = null
	private var scope: String? = null

	fun getTrackChoice(itemId: UUID, type: MediaStreamType): TrackChoice? = synchronized(this) {
		trackChoices.getValue(type)[itemId]
	}

	fun setTrackChoices(itemIds: Collection<UUID>, type: MediaStreamType, choice: TrackChoice?) {
		synchronized(this) {
			val choices = trackChoices.getValue(type)
			val changedIds = itemIds.filter { choices[it] != choice }
			if (changedIds.isEmpty()) return
			changedIds.forEach { id ->
				if (choice == null) choices.remove(id) else choices[id] = choice
			}
			scope?.let { store?.updateTrackChoices(it, type, changedIds, choice) }
		}
	}

	fun initialize(store: TrackSelectionStore) {
		synchronized(this) {
			if (this.store != null) return

			this.store = store
			loadScope(scope)
		}
	}

	fun setScope(scope: String?) {
		synchronized(this) {
			if (this.scope == scope) return

			this.scope = scope
			loadScope(scope)
		}
	}

	fun setSelectedAudioTracks(itemIds: Collection<UUID>, trackIndex: Int?) {
		synchronized(this) {
			if (itemIds.all { if (trackIndex == null) !selectedAudioTracks.containsKey(it) else selectedAudioTracks[it] == trackIndex }) return
			itemIds.forEach { itemId ->
				if (trackIndex == null) selectedAudioTracks.remove(itemId)
				else selectedAudioTracks[itemId] = trackIndex
			}
			persistAudioTracks()
		}
	}

	fun setSelectedSubtitleTracks(itemIds: Collection<UUID>, trackIndex: Int?) {
		synchronized(this) {
			if (itemIds.all { if (trackIndex == null) !selectedSubtitleTracks.containsKey(it) else selectedSubtitleTracks[it] == trackIndex }) return
			itemIds.forEach { itemId ->
				if (trackIndex == null) selectedSubtitleTracks.remove(itemId)
				else selectedSubtitleTracks[itemId] = trackIndex
			}
			persistSubtitleTracks()
		}
	}

	fun getSelectedAudioTrack(itemIds: Collection<UUID>): Int? = synchronized(this) {
		itemIds.firstNotNullOfOrNull { itemId -> selectedAudioTracks[itemId] }
	}

	fun getSelectedSubtitleTrackSelection(itemIds: Collection<UUID>): TrackSelection = synchronized(this) {
		val itemId = itemIds.firstOrNull(selectedSubtitleTracks::containsKey)
		TrackSelection(
			hasSelection = itemId != null,
			trackIndex = itemId?.let(selectedSubtitleTracks::get),
		)
	}

	private fun loadScope(scope: String?) {
		selectedAudioTracks.clear()
		selectedSubtitleTracks.clear()
		trackChoices.values.forEach(MutableMap<UUID, TrackChoice>::clear)

		val store = store ?: return
		if (scope == null) return

		selectedAudioTracks.putAll(store.getSelectedAudioTracks(scope))
		selectedSubtitleTracks.putAll(store.getSelectedSubtitleTracks(scope))
		trackChoices.forEach { (type, choices) -> choices.putAll(store.getTrackChoices(scope, type)) }
	}

	private fun persistAudioTracks() {
		scope?.let { store?.setSelectedAudioTracks(it, selectedAudioTracks) }
	}

	private fun persistSubtitleTracks() {
		scope?.let { store?.setSelectedSubtitleTracks(it, selectedSubtitleTracks) }
	}
}
