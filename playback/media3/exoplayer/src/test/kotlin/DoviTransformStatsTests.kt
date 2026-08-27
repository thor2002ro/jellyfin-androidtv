package org.jellyfin.playback.media3.exoplayer

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.jellyfin.playback.core.model.PlaybackDoviTransformStats
import org.jellyfin.playback.core.model.PlaybackDoviTransformProcessor
import org.jellyfin.playback.core.queue.QueueEntry

class DoviTransformStatsTests : FunSpec({
	test("fatal Dolby Vision video decoder failures receive dedicated recovery evidence") {
		listOf(
			PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
			PlaybackException.ERROR_CODE_DECODING_FAILED,
			PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
			PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
		).forEach { errorCode ->
			doviVideoDecoderPlaybackErrorCode(
				errorCode = errorCode,
				isVideoRendererError = true,
				hasDoviDecision = true,
			) shouldBe "DOVI_VIDEO_DECODER_FAILED"
		}

		listOf(
			Triple(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, true, true),
			Triple(PlaybackException.ERROR_CODE_DECODING_FAILED, false, true),
			Triple(PlaybackException.ERROR_CODE_DECODING_FAILED, true, false),
		).forEach { (errorCode, isVideoRendererError, hasDoviDecision) ->
			doviVideoDecoderPlaybackErrorCode(errorCode, isVideoRendererError, hasDoviDecision) shouldBe null
		}
	}

	test("replacement media item cannot inherit a stale transform observation") {
		val oldTag = PlaybackMediaItemTag(QueueEntry(), null)
		val replacementTag = PlaybackMediaItemTag(oldTag.queueEntry, null)
		val uri = mockk<Uri>()
		val oldItem = MediaItem.Builder().setUri(uri).setMediaId("old").setTag(oldTag).build()
		val replacementItem = MediaItem.Builder().setUri(uri).setMediaId("replacement").setTag(replacementTag).build()
		val oldObservation = PlaybackDoviTransformStats("DV P7 MEL", "DV P8.1")
		val replacementObservation = PlaybackDoviTransformStats("DV P7 FEL", "DV P8.1")

		oldTag.doviTransformStats.record(oldObservation)
		oldItem.doviTransformStatsTag shouldBe oldObservation
		replacementItem.doviTransformStatsTag shouldBe null

		oldTag.doviTransformStats.record(replacementObservation)
		replacementItem.doviTransformStatsTag shouldBe null
		replacementTag.doviTransformStats.record(replacementObservation)
		replacementItem.doviTransformStatsTag shouldBe replacementObservation
	}

	test("transform stats report the processor currently handling samples") {
		val holder = DoviTransformStatsHolder()
		holder.record(PlaybackDoviTransformStats("DV P8.1", "HDR10"))

		holder.recordProcessor(PlaybackDoviTransformProcessor.FAST_HDR_BASE)
		holder.get()?.processor shouldBe PlaybackDoviTransformProcessor.FAST_HDR_BASE

		holder.recordProcessor(PlaybackDoviTransformProcessor.LIBDOVI)
		holder.get()?.processor shouldBe PlaybackDoviTransformProcessor.LIBDOVI
	}
})
