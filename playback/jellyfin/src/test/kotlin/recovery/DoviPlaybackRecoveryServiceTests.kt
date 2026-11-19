package org.jellyfin.playback.jellyfin.recovery

import io.github.thor2002ro.libdovi.DoviPresentation
import io.github.thor2002ro.libdovi.DoviRepair
import io.github.thor2002ro.libdovi.DoviStatus
import io.github.thor2002ro.libdovi.DoviTarget
import io.github.thor2002ro.libdovi.DoviTransformRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jellyfin.playback.core.backend.HARDWARE_VIDEO_DECODER_ERROR_CODE
import org.jellyfin.playback.core.backend.PlaybackError
import org.jellyfin.playback.core.backend.activate
import org.jellyfin.playback.core.backend.activePlaybackErrorOrigin
import org.jellyfin.playback.core.backend.createPlaybackErrorOrigin
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.liveStreamTargetOffset
import org.jellyfin.playback.dovi.DoviDecision
import org.jellyfin.playback.dovi.DoviDecisionReason
import org.jellyfin.playback.dovi.DoviRoute
import org.jellyfin.playback.dovi.DoviSourceLayer
import org.jellyfin.playback.dovi.DoviSourceProfile
import org.jellyfin.playback.dovi.DoviTransformEvidence
import org.jellyfin.playback.dovi.doviDecision
import org.jellyfin.playback.dovi.doviTransformFailure
import org.jellyfin.playback.dovi.doviTransformationRetryCount
import org.jellyfin.playback.dovi.doviTransformationSuppressed
import org.jellyfin.playback.dovi.doviVideoDecoderFailure
import org.jellyfin.playback.jellyfin.livetv.shouldSuppressLiveTvGenericRecovery
import org.jellyfin.playback.jellyfin.queue.forceTranscoding
import org.jellyfin.playback.jellyfin.queue.mediaSourceId
import kotlin.time.Duration.Companion.seconds

class DoviPlaybackRecoveryServiceTests : FunSpec({
	test("Media3 and MPV typed transformation statuses are eligible") {
		DoviStatus.entries.filterNot { it == DoviStatus.OK }.forEach { status ->
			doviTransformationFailureStatus("DOVI_TRANSFORMATION_FAILED_${status.name}") shouldBe status
		}
		doviTransformationFailureStatus("DOVI_TRANSFORMATION_FAILED_OK") shouldBe null
		doviTransformationFailureStatus("DOVI_TRANSFORMATION_FAILED_UNKNOWN") shouldBe null
		doviTransformationFailureStatus("DOVI_TRANSFORMATION_FAILED_") shouldBe null
		doviTransformationFailureStatus("DOVI_TRANSFORMATION_FAILED_RPU__WRITE_FAILED") shouldBe null
		doviTransformationFailureStatus("DOVI_CONVERSION_FAILED_4") shouldBe null
		doviTransformationFailureStatus("ERROR_CODE_PARSING_CONTAINER_MALFORMED") shouldBe null
	}

	test("MEL Profile 8.1 metadata repair and source-base routes each claim one server fallback") {
		val decisions = listOf(
			transformDecision(DoviTarget.MEL, DoviDecisionReason.FEL_TO_MEL),
			transformDecision(DoviTarget.PROFILE_8_1, DoviDecisionReason.PROFILE_8_1),
			transformDecision(
				target = DoviTarget.LOSSLESS_REWRITE,
				reason = DoviDecisionReason.REPAIRED_NATIVE,
				repairs = setOf(DoviRepair.ZERO_ACTIVE_AREA, DoviRepair.REMOVE_CMV40),
			),
			sourceBaseDecision(),
		)

		decisions.forEach { decision ->
			val entry = transformationEntry(decision)
			val recovery = switchToTranscoding(
				entry = entry,
				error = transformationError(entry),
			)

			recovery shouldBe true
			entry.doviTransformationRetryCount shouldBe 1
			entry.doviTransformationSuppressed shouldBe true
			entry.doviTransformFailure shouldBe DoviStatus.RPU_WRITE_FAILED
			entry.forceTranscoding shouldBe true
			entry.doviDecision?.route shouldBe DoviRoute.ServerFallback
			entry.doviDecision?.reason shouldBe DoviDecisionReason.RETRY_SUPPRESSED
		}
	}

	test("native Dolby Vision video decoder failure claims one server fallback") {
		val entry = transformationEntry(
			DoviDecision(DoviRoute.Native, DoviDecisionReason.NATIVE_SUPPORTED),
		)
		val recovery = switchToTranscoding(
			entry = entry,
			error = PlaybackError(
				codeName = "DOVI_VIDEO_DECODER_FAILED",
				origin = activeOrigin(entry),
			),
		)

		recovery shouldBe true
		entry.doviTransformationRetryCount shouldBe 1
		entry.doviTransformationSuppressed shouldBe true
		entry.doviTransformFailure shouldBe null
		entry.doviVideoDecoderFailure shouldBe true
		entry.forceTranscoding shouldBe true
		entry.doviDecision?.route shouldBe DoviRoute.ServerFallback
	}

	test("switching Live TV to transcoding retains the target offset") {
		val entry = transformationEntry(sourceBaseDecision()).apply {
			liveStreamTargetOffset = 12.seconds
		}
		val recovery = switchToTranscoding(
			entry,
			transformationError(entry),
		)

		recovery shouldBe true
		entry.liveStreamTargetOffset shouldBe 12.seconds
	}

	test("a second transformation failure cannot claim another retry") {
		val entry = transformationEntry(transformDecision(DoviTarget.PROFILE_8_1, DoviDecisionReason.PROFILE_8_1))
		val error = transformationError(entry)

		switchToTranscoding(entry, error) shouldBe true
		switchToTranscoding(entry, error) shouldBe false
		entry.doviTransformationRetryCount shouldBe 1
	}

	test("the first validated transformation failure is retained") {
		val entry = transformationEntry(transformDecision(DoviTarget.PROFILE_8_1, DoviDecisionReason.PROFILE_8_1))

		switchToTranscoding(entry, transformationError(entry))
		switchToTranscoding(
			entry,
			PlaybackError(
				codeName = "DOVI_TRANSFORMATION_FAILED_REPAIR_FAILED",
				origin = entry.activePlaybackErrorOrigin,
			),
		)

		entry.doviTransformFailure shouldBe DoviStatus.RPU_WRITE_FAILED
	}

	test("concurrent duplicate errors atomically produce one recovery") {
		val entry = transformationEntry(transformDecision(DoviTarget.PROFILE_8_1, DoviDecisionReason.PROFILE_8_1))
		val error = transformationError(entry)

		val recoveries = coroutineScope {
			List(16) {
				async(Dispatchers.Default) {
					switchToTranscoding(entry, error)
				}
			}.awaitAll().filter { it }
		}

		recoveries shouldHaveSize 1
		entry.doviTransformationRetryCount shouldBe 1
	}

	test("source and entry mismatches fail conservatively") {
		val entry = transformationEntry(transformDecision(DoviTarget.MEL, DoviDecisionReason.FEL_TO_MEL))
		val otherEntry = transformationEntry(transformDecision(DoviTarget.MEL, DoviDecisionReason.FEL_TO_MEL))

		switchToTranscoding(
			entry,
			transformationError(otherEntry),
		) shouldBe false

	}

	test("late failure from an older source load on the same entry is ignored") {
		val entry = transformationEntry(transformDecision(DoviTarget.PROFILE_8_1, DoviDecisionReason.PROFILE_8_1))
		val oldLoadError = transformationError(entry)
		entry.createPlaybackErrorOrigin("source-b").activate()

		switchToTranscoding(entry, oldLoadError) shouldBe false
		entry.doviTransformationRetryCount shouldBe null
		entry.doviTransformationSuppressed shouldBe null
		entry.forceTranscoding shouldBe null
	}

	test("late failure from an older load generation of the same source is ignored") {
		val entry = transformationEntry(transformDecision(DoviTarget.MEL, DoviDecisionReason.FEL_TO_MEL))
		val oldLoadError = transformationError(entry)
		entry.createPlaybackErrorOrigin("source-a").activate()

		switchToTranscoding(entry, oldLoadError) shouldBe false
		entry.doviTransformationRetryCount shouldBe null
	}

	test("unrelated errors unbound errors and native routes do not recover") {
		val transformed = transformationEntry(transformDecision(DoviTarget.PROFILE_8_1, DoviDecisionReason.PROFILE_8_1))
		switchToTranscoding(
			transformed,
			PlaybackError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", origin = activeOrigin(transformed)),
		) shouldBe false
		switchToTranscoding(
			transformed,
			PlaybackError("ERROR_CODE_DECODING_FAILED", origin = activeOrigin(transformed)),
		) shouldBe false
		switchToTranscoding(
			transformed,
			PlaybackError("DOVI_TRANSFORMATION_FAILED_INTERNAL_ERROR"),
		) shouldBe false

		val nativeEntry = transformationEntry(
			DoviDecision(DoviRoute.Native, DoviDecisionReason.NATIVE_SUPPORTED),
		)
		switchToTranscoding(
			nativeEntry,
			transformationError(nativeEntry),
		) shouldBe false

		val externalEntry = transformationEntry(
			DoviDecision(DoviRoute.ServerFallback, DoviDecisionReason.BACKEND_UNSUPPORTED),
		)
		switchToTranscoding(
			externalEntry,
			transformationError(externalEntry),
		) shouldBe false
	}

	test("generic network and Live TV recovery ignore the dedicated error") {
		val entry = transformationEntry(sourceBaseDecision())
		val error = transformationError(entry)

		isRecoverablePlaybackError(error.codeName) shouldBe false
		shouldSuppressLiveTvGenericRecovery(error, entry) shouldBe true
		shouldSuppressLiveTvGenericRecovery(error, QueueEntry()) shouldBe false
		shouldSuppressLiveTvGenericRecovery(
			PlaybackError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", origin = activeOrigin(entry)),
			entry,
		) shouldBe false
	}

	test("a confirmed hardware decoder failure requests transcoding without a DV decision") {
		val entry = QueueEntry()
		val error = PlaybackError(HARDWARE_VIDEO_DECODER_ERROR_CODE, origin = activeOrigin(entry))

		switchToTranscoding(entry, error) shouldBe true
		entry.forceTranscoding shouldBe true
		entry.doviTransformationSuppressed shouldBe null
		switchToTranscoding(entry, error) shouldBe false
	}
})

private fun transformationError(entry: QueueEntry) = PlaybackError(
	codeName = "DOVI_TRANSFORMATION_FAILED_RPU_WRITE_FAILED",
	origin = activeOrigin(entry),
)

private fun transformationEntry(decision: DoviDecision) = QueueEntry().apply {
	mediaSourceId = "source-a"
	doviDecision = decision
}

private fun activeOrigin(entry: QueueEntry) =
	entry.createPlaybackErrorOrigin("source-a").also { it.activate() }

private fun transformDecision(
	target: DoviTarget,
	reason: DoviDecisionReason,
	repairs: Set<DoviRepair> = emptySet(),
) = DoviDecision(
	route = DoviRoute.Transform(DoviTransformRequest(target, repairs)),
	reason = reason,
	transformEvidence = transformEvidence(),
)

private fun sourceBaseDecision() = DoviDecision(
	route = DoviRoute.SourceBase(DoviTransformRequest(DoviTarget.SOURCE_BASE_PRESENTATION)),
	reason = DoviDecisionReason.SOURCE_BASE,
	transformEvidence = transformEvidence(),
)

private fun transformEvidence() = DoviTransformEvidence(
	inputPresentation = DoviPresentation.PROFILE_7_FEL,
	sourceBasePresentation = DoviPresentation.HDR10,
	sourceProfile = DoviSourceProfile.PROFILE_7,
	sourceLayer = DoviSourceLayer.FEL,
)
