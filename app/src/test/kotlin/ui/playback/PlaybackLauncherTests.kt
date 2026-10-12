package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.playbackPlayerPreferences
import org.jellyfin.androidtv.preference.constant.PlaybackBackend
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRangeType
import java.util.UUID

class PlaybackLauncherTests : FunSpec({
	test("HDR player selection follows the video player by default") {
		val userPreferences = mockk<UserPreferences>()
		val normalPreferences = UserPreferences.playbackPlayerPreferences(hdr = false)
		val hdrPreferences = UserPreferences.playbackPlayerPreferences(hdr = true)
		every { userPreferences[normalPreferences.useExternalPlayer] } returns false
		every { userPreferences[normalPreferences.playbackRewriteVideoEnabled] } returns true
		every { userPreferences[normalPreferences.playbackBackend] } returns PlaybackBackend.LIBVLC
		every { userPreferences[hdrPreferences.useExternalPlayer] } returns false
		every { userPreferences[hdrPreferences.playbackRewriteVideoEnabled] } returns true
		every { userPreferences[hdrPreferences.playbackBackend] } returns PlaybackBackend.SAME_VIDEO_PLAYER

		val launcher = PlaybackLauncher(
			mediaManager = mockk(relaxed = true),
			videoQueueManager = mockk(relaxed = true),
			navigationRepository = mockk<NavigationRepository>(relaxed = true),
			userPreferences = userPreferences,
		)
		val queue = listOf(videoItem(VideoRangeType.SDR), videoItem(VideoRangeType.HDR10))

		launcher.getVideoPlayerSelection(queue, 0) shouldBe PlaybackLauncher.VideoPlayerSelection(
			player = PlaybackLauncher.VideoPlayer.NEW,
			backend = PlaybackBackend.LIBVLC,
		)
		launcher.getVideoPlayerSelection(queue, 1) shouldBe PlaybackLauncher.VideoPlayerSelection(
			player = PlaybackLauncher.VideoPlayer.NEW,
			backend = PlaybackBackend.LIBVLC,
		)
	}

	test("HDR player selection can use dedicated preferences") {
		val userPreferences = mockk<UserPreferences>()
		val hdrPreferences = UserPreferences.playbackPlayerPreferences(hdr = true)
		every { userPreferences[hdrPreferences.useExternalPlayer] } returns false
		every { userPreferences[hdrPreferences.playbackRewriteVideoEnabled] } returns true
		every { userPreferences[hdrPreferences.playbackBackend] } returns PlaybackBackend.MPV

		val launcher = PlaybackLauncher(
			mediaManager = mockk(relaxed = true),
			videoQueueManager = mockk(relaxed = true),
			navigationRepository = mockk<NavigationRepository>(relaxed = true),
			userPreferences = userPreferences,
		)

		launcher.getVideoPlayerSelection(listOf(videoItem(VideoRangeType.HDR10)), 0) shouldBe PlaybackLauncher.VideoPlayerSelection(
			player = PlaybackLauncher.VideoPlayer.NEW,
			backend = PlaybackBackend.MPV,
		)
	}
})

private fun videoItem(rangeType: VideoRangeType): BaseItemDto {
	val itemId = UUID.randomUUID()
	val stream = mockk<MediaStream> {
		every { type } returns MediaStreamType.VIDEO
		every { videoRangeType } returns rangeType
	}
	val source = mockk<MediaSourceInfo> {
		every { id } returns itemId.toString()
		every { mediaStreams } returns listOf(stream)
	}
	return mockk {
		every { id } returns itemId
		every { type } returns BaseItemKind.MOVIE
		every { mediaSources } returns listOf(source)
	}
}
