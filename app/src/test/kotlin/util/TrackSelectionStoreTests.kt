package org.jellyfin.androidtv.util

import android.content.Context
import android.content.SharedPreferences
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.model.api.MediaStreamType
import java.util.UUID

class TrackSelectionStoreTests : FunSpec({
	test("updating and removing one choice preserve unrelated saved entries") {
		val fixture = selectionPreferences()
		val first = UUID.randomUUID()
		val second = UUID.randomUUID()
		val choice = TrackChoice(4, "en", "ass")
		fixture.store.updateTrackChoices("user", MediaStreamType.SUBTITLE, listOf(first, second), choice)
		fixture.store.updateTrackChoices("user", MediaStreamType.SUBTITLE, listOf(first), TrackChoice(-1))
		fixture.store.getTrackChoices("user", MediaStreamType.SUBTITLE) shouldBe mapOf(first to TrackChoice(-1), second to choice)
		fixture.store.updateTrackChoices("user", MediaStreamType.SUBTITLE, listOf(first), null)
		fixture.store.getTrackChoices("user", MediaStreamType.SUBTITLE) shouldBe mapOf(second to choice)
	}

	test("track attributes round trip independently of legacy indices and subtitle off") {
		val fixture = selectionPreferences()
		val itemId = UUID.randomUUID()
		val choice = TrackChoice(9, "en", "ass", "English = SDH / 日本語", hearingImpaired = true, channels = 6)
		fixture.store.setSelectedAudioTracks("user", mapOf(itemId to 3))
		fixture.store.updateTrackChoices("user", MediaStreamType.AUDIO, listOf(itemId), choice)
		fixture.store.updateTrackChoices("user", MediaStreamType.SUBTITLE, listOf(itemId), TrackChoice(-1))

		fixture.store.getTrackChoices("user", MediaStreamType.AUDIO) shouldBe mapOf(itemId to choice)
		fixture.store.getTrackChoices("user", MediaStreamType.SUBTITLE) shouldBe mapOf(itemId to TrackChoice(-1))
		fixture.store.getSelectedAudioTracks("user") shouldBe mapOf(itemId to 3)
	}

	test("malformed saved choices do not hide valid entries") {
		val fixture = selectionPreferences()
		val itemId = UUID.randomUUID()
		fixture.store.updateTrackChoices("user", MediaStreamType.SUBTITLE, listOf(itemId), TrackChoice(-1))
		fixture.values["user:subtitle_choices"] = fixture.values.getValue("user:subtitle_choices") +
			setOf("broken", "${UUID.randomUUID()}={bad json}", "not-a-uuid={\"index\":2}")
		fixture.store.getTrackChoices("user", MediaStreamType.SUBTITLE) shouldBe mapOf(itemId to TrackChoice(-1))
	}

	test("manager reloads persisted choices without crossing user and server scopes") {
		val fixture = selectionPreferences()
		val firstScope = "server-one:${UUID.randomUUID()}"
		val secondScope = "server-two:${UUID.randomUUID()}"
		val itemId = UUID.randomUUID()
		val choice = TrackChoice(5, "ja", "aac", channels = 2)
		TrackSelectionManager.initialize(fixture.store)
		try {
			TrackSelectionManager.setScope(firstScope)
			TrackSelectionManager.setTrackChoices(listOf(itemId), MediaStreamType.AUDIO, choice)
			val writesAfterSelection = fixture.writeCount()
			TrackSelectionManager.setTrackChoices(listOf(itemId), MediaStreamType.AUDIO, choice)
			fixture.writeCount() shouldBe writesAfterSelection
			TrackSelectionManager.setScope(secondScope)
			TrackSelectionManager.getTrackChoice(itemId, MediaStreamType.AUDIO) shouldBe null
			TrackSelectionManager.setScope(null)
			TrackSelectionManager.getTrackChoice(itemId, MediaStreamType.AUDIO) shouldBe null
			TrackSelectionManager.setScope(firstScope)
			TrackSelectionManager.getTrackChoice(itemId, MediaStreamType.AUDIO) shouldBe choice
		} finally {
			TrackSelectionManager.setScope(null)
		}
	}
})

private data class SelectionPreferences(
	val store: TrackSelectionStore,
	val values: MutableMap<String, Set<String>>,
	val writeCount: () -> Int,
)

private fun selectionPreferences(): SelectionPreferences {
	val values = mutableMapOf<String, Set<String>>()
	var writes = 0
	val editor = mockk<SharedPreferences.Editor>(relaxed = true)
	every { editor.putStringSet(any(), any()) } answers {
		values[firstArg()] = secondArg<Set<String>>().toSet()
		writes++
		editor
	}
	val preferences = mockk<SharedPreferences> {
		every { getStringSet(any(), any()) } answers {
			(values[firstArg()] ?: secondArg<Set<String>?>())?.toMutableSet()
		}
		every { edit() } returns editor
	}
	val context = mockk<Context> {
		every { applicationContext } returns this
		every { getSharedPreferences("track_selection", Context.MODE_PRIVATE) } returns preferences
	}
	return SelectionPreferences(TrackSelectionStore(context), values) { writes }
}
