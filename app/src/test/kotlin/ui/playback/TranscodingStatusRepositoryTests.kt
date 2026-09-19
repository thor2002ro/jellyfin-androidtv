package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlayerStateInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.SessionInfoDto
import org.jellyfin.sdk.model.api.TranscodingInfo
import java.time.LocalDateTime
import java.util.UUID

class TranscodingStatusRepositoryTests : FunSpec({
	test("exact item and media source select the current session despite list order") {
		val wrongInfo = transcodingInfo("wrong")
		val currentInfo = transcodingInfo("current")
		val itemId = UUID.randomUUID()
		val sessions = listOf(
			session(
				id = "first-session",
				transcodingInfo = wrongInfo,
				mediaSourceId = "current-source",
				itemId = UUID.randomUUID(),
			),
			session(
				id = "current-session",
				transcodingInfo = currentInfo,
				mediaSourceId = "current-source",
				itemId = itemId,
			),
		)

		selectTranscodingInfo(
			sessions = sessions,
			itemId = itemId,
			mediaSourceId = "current-source",
		) shouldBe currentInfo
	}

	test("missing media identifiers do not borrow transcoding information from another session") {
		val unrelatedInfo = transcodingInfo("unrelated")
		val sessions = listOf(session("other-session", unrelatedInfo))

		selectTranscodingInfo(
			sessions = sessions,
			itemId = null,
			mediaSourceId = null,
		).shouldBeNull()
	}

	test("matching item returns its transcoding information") {
		val unrelatedInfo = transcodingInfo("unrelated")
		val currentInfo = transcodingInfo("current")
		val itemId = UUID.randomUUID()
		val sessions = listOf(
			session("other-session", unrelatedInfo),
			session("current-session", currentInfo, itemId = itemId),
		)

		selectTranscodingInfo(
			sessions = sessions,
			itemId = itemId,
			mediaSourceId = null,
		) shouldBe currentInfo
	}

	test("item and media source must match the same session") {
		val itemId = UUID.randomUUID()
		val sessions = listOf(
			session(
				id = "wrong-item",
				transcodingInfo = transcodingInfo("wrong"),
				mediaSourceId = "current-source",
				itemId = UUID.randomUUID(),
			),
		)

		selectTranscodingInfo(
			sessions = sessions,
			itemId = itemId,
			mediaSourceId = "current-source",
		).shouldBeNull()
	}

	test("matching direct-play media source returns no transcoding information") {
		val staleInfo = transcodingInfo("stale")
		val sessions = listOf(
			session("current-session", null, mediaSourceId = "shared-source"),
			session("old-session", staleInfo, mediaSourceId = "shared-source"),
		)

		selectTranscodingInfo(
			sessions = sessions,
			itemId = null,
			mediaSourceId = "shared-source",
		).shouldBeNull()
	}
})

private fun session(
	id: String,
	transcodingInfo: TranscodingInfo?,
	mediaSourceId: String? = null,
	itemId: UUID? = null,
) = SessionInfoDto(
	playState = mediaSourceId?.let {
		PlayerStateInfo(
			canSeek = true,
			isPaused = false,
			isMuted = false,
			mediaSourceId = it,
			repeatMode = RepeatMode.REPEAT_NONE,
			playbackOrder = PlaybackOrder.DEFAULT,
		)
	},
	playableMediaTypes = emptyList(),
	nowPlayingItem = itemId?.let { BaseItemDto(id = it, type = BaseItemKind.MOVIE) },
	id = id,
	userId = UUID.randomUUID(),
	lastActivityDate = LocalDateTime.MIN,
	lastPlaybackCheckIn = LocalDateTime.MIN,
	transcodingInfo = transcodingInfo,
	isActive = true,
	supportsMediaControl = true,
	supportsRemoteControl = true,
	hasCustomDeviceName = false,
	supportedCommands = emptyList(),
)

private fun transcodingInfo(videoCodec: String) = TranscodingInfo(
	videoCodec = videoCodec,
	isVideoDirect = false,
	isAudioDirect = false,
	transcodeReasons = emptyList(),
)
