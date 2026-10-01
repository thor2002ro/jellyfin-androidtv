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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jellyfin.playback.core.backend.PlaybackError
import org.jellyfin.playback.core.backend.activate
import org.jellyfin.playback.core.backend.activePlaybackErrorOrigin
import org.jellyfin.playback.core.backend.createPlaybackErrorOrigin
import org.jellyfin.playback.core.element.ElementKey
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.liveStreamTargetOffset
import org.jellyfin.playback.dovi.DoviDecision
import org.jellyfin.playback.dovi.DoviDecisionReason
import org.jellyfin.playback.dovi.DoviRoute
import org.jellyfin.playback.dovi.DoviSourceLayer
import org.jellyfin.playback.dovi.DoviSourceProfile
import org.jellyfin.playback.dovi.DoviTransformEvidence
import org.jellyfin.playback.dovi.doviDecision
import org.jellyfin.playback.dovi.DoviRecoveryOwnership
import org.jellyfin.playback.dovi.doviRecoveryOwnership
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
		isDoviTransformationError("DOVI_TRANSFORMATION_FAILED_OK") shouldBe false
		isDoviTransformationError("DOVI_TRANSFORMATION_FAILED_UNKNOWN") shouldBe false
		isDoviTransformationError("DOVI_TRANSFORMATION_FAILED_") shouldBe false
		isDoviTransformationError("DOVI_TRANSFORMATION_FAILED_RPU__WRITE_FAILED") shouldBe false
		isDoviTransformationError("DOVI_CONVERSION_FAILED_4") shouldBe false
		isDoviTransformationError("ERROR_CODE_PARSING_CONTAINER_MALFORMED") shouldBe false
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
			val recovery = claimDoviTransformationRecovery(
				entry = entry,
				error = transformationError(entry),
				position = 91.seconds,
				playWhenReady = true,
			)

			recovery?.failedTransform shouldBe decision.transformEvidence
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
		val recovery = claimDoviTransformationRecovery(
			entry = entry,
			error = PlaybackError(
				codeName = "DOVI_VIDEO_DECODER_FAILED",
				origin = activeOrigin(entry),
			),
			position = 27.seconds,
			playWhenReady = true,
		)

		recovery?.failedTransform shouldBe null
		entry.doviTransformationRetryCount shouldBe 1
		entry.doviTransformationSuppressed shouldBe true
		entry.doviTransformFailure shouldBe null
		entry.doviVideoDecoderFailure shouldBe true
		entry.forceTranscoding shouldBe true
		entry.doviDecision?.route shouldBe DoviRoute.ServerFallback
	}

	test("recovery preserves position and playing or paused state") {
		val queueDataKey = ElementKey<String>("Task11QueueData")
		val playingEntry = transformationEntry(transformDecision(DoviTarget.PROFILE_8_1, DoviDecisionReason.PROFILE_8_1))
		playingEntry.put(queueDataKey, "audio-subtitle-selection")
		val playing = claimDoviTransformationRecovery(
			playingEntry,
			transformationError(playingEntry),
			74.seconds,
			playWhenReady = true,
		)
		playing?.position shouldBe 74.seconds
		playing?.playWhenReady shouldBe true
		playingEntry.mediaSourceId shouldBe "source-a"
		playingEntry.get(queueDataKey) shouldBe "audio-subtitle-selection"

		val pausedEntry = transformationEntry(transformDecision(DoviTarget.MEL, DoviDecisionReason.FEL_TO_MEL))
		val paused = claimDoviTransformationRecovery(
			pausedEntry,
			transformationError(pausedEntry),
			18.seconds,
			playWhenReady = false,
		)
		paused?.position shouldBe 18.seconds
		paused?.playWhenReady shouldBe false
	}

	test("Live TV recovery retains target offset and lets the server resolve its current point") {
		val entry = transformationEntry(sourceBaseDecision()).apply {
			liveStreamTargetOffset = 12.seconds
		}
		val recovery = claimDoviTransformationRecovery(
			entry,
			transformationError(entry),
			position = null,
			playWhenReady = true,
		)

		recovery?.position shouldBe null
		entry.liveStreamTargetOffset shouldBe 12.seconds
	}

	test("a second transformation failure cannot claim another retry") {
		val entry = transformationEntry(transformDecision(DoviTarget.PROFILE_8_1, DoviDecisionReason.PROFILE_8_1))
		val error = transformationError(entry)

		(claimDoviTransformationRecovery(entry, error, 2.seconds, true) != null) shouldBe true
		claimDoviTransformationRecovery(entry, error, 3.seconds, true) shouldBe null
		entry.doviTransformationRetryCount shouldBe 1
		entry.doviRecoveryOwnership shouldBe DoviRecoveryOwnership.TERMINAL
	}

	test("the first validated transformation failure is retained") {
		val entry = transformationEntry(transformDecision(DoviTarget.PROFILE_8_1, DoviDecisionReason.PROFILE_8_1))

		claimDoviTransformationRecovery(entry, transformationError(entry), 2.seconds, true)
		claimDoviTransformationRecovery(
			entry,
			PlaybackError(
				codeName = "DOVI_TRANSFORMATION_FAILED_REPAIR_FAILED",
				origin = entry.activePlaybackErrorOrigin,
			),
			3.seconds,
			true,
		)

		entry.doviTransformFailure shouldBe DoviStatus.RPU_WRITE_FAILED
	}

	test("successful fallback clears temporary ownership and permits later generic recovery") {
		val entry = transformationEntry(transformDecision(DoviTarget.PROFILE_8_1, DoviDecisionReason.PROFILE_8_1))
		claimDoviTransformationRecovery(entry, transformationError(entry), 2.seconds, true)!!
		blocksGenericRecovery(entry) shouldBe true

		completeDoviRecoveryOnPlaybackState(entry, org.jellyfin.playback.core.model.PlayState.PLAYING)

		entry.doviTransformationSuppressed shouldBe true
		entry.doviRecoveryOwnership shouldBe null
		blocksGenericRecovery(entry) shouldBe false
		isRecoverablePlaybackError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED") shouldBe true
	}

	test("concurrent duplicate errors atomically produce one recovery") {
		val entry = transformationEntry(transformDecision(DoviTarget.PROFILE_8_1, DoviDecisionReason.PROFILE_8_1))
		val error = transformationError(entry)

		val recoveries = coroutineScope {
			List(16) {
				async(Dispatchers.Default) {
					claimDoviTransformationRecovery(entry, error, 5.seconds, true)
				}
			}.awaitAll().filterNotNull()
		}

		recoveries shouldHaveSize 1
		entry.doviTransformationRetryCount shouldBe 1
	}

	test("source and entry mismatches fail conservatively") {
		val entry = transformationEntry(transformDecision(DoviTarget.MEL, DoviDecisionReason.FEL_TO_MEL))
		val otherEntry = transformationEntry(transformDecision(DoviTarget.MEL, DoviDecisionReason.FEL_TO_MEL))

		claimDoviTransformationRecovery(
			entry,
			transformationError(otherEntry),
			1.seconds,
			true,
		) shouldBe null

		val recovery = claimDoviTransformationRecovery(entry, transformationError(entry), 1.seconds, true)!!
		entry.createPlaybackErrorOrigin("replacement-source").activate()
		var reloaded = false
		DoviTransformationRecoveryRunner().run(recovery, { entry }, {}) { _, _ ->
			reloaded = true
			true
		} shouldBe false
		reloaded shouldBe false
	}

	test("late failure from an older source load on the same entry is ignored") {
		val entry = transformationEntry(transformDecision(DoviTarget.PROFILE_8_1, DoviDecisionReason.PROFILE_8_1))
		val oldLoadError = transformationError(entry)
		entry.createPlaybackErrorOrigin("source-b").activate()

		claimDoviTransformationRecovery(entry, oldLoadError, 4.seconds, true) shouldBe null
		entry.doviTransformationRetryCount shouldBe null
		entry.doviTransformationSuppressed shouldBe null
		entry.forceTranscoding shouldBe null
	}

	test("late failure from an older load generation of the same source is ignored") {
		val entry = transformationEntry(transformDecision(DoviTarget.MEL, DoviDecisionReason.FEL_TO_MEL))
		val oldLoadError = transformationError(entry)
		entry.createPlaybackErrorOrigin("source-a").activate()

		claimDoviTransformationRecovery(entry, oldLoadError, 6.seconds, true) shouldBe null
		entry.doviTransformationRetryCount shouldBe null
	}

	test("unrelated errors unbound errors and native routes do not recover") {
		val transformed = transformationEntry(transformDecision(DoviTarget.PROFILE_8_1, DoviDecisionReason.PROFILE_8_1))
		claimDoviTransformationRecovery(
			transformed,
			PlaybackError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", origin = activeOrigin(transformed)),
			1.seconds,
			true,
		) shouldBe null
		claimDoviTransformationRecovery(
			transformed,
			PlaybackError("ERROR_CODE_DECODING_FAILED", origin = activeOrigin(transformed)),
			1.seconds,
			true,
		) shouldBe null
		claimDoviTransformationRecovery(
			transformed,
			PlaybackError("DOVI_TRANSFORMATION_FAILED_INTERNAL_ERROR"),
			1.seconds,
			true,
		) shouldBe null

		val nativeEntry = transformationEntry(
			DoviDecision(DoviRoute.Native, DoviDecisionReason.NATIVE_SUPPORTED),
		)
		claimDoviTransformationRecovery(
			nativeEntry,
			transformationError(nativeEntry),
			1.seconds,
			true,
		) shouldBe null

		val externalEntry = transformationEntry(
			DoviDecision(DoviRoute.ServerFallback, DoviDecisionReason.BACKEND_UNSUPPORTED),
		)
		claimDoviTransformationRecovery(
			externalEntry,
			transformationError(externalEntry),
			1.seconds,
			true,
		) shouldBe null
	}

	test("generic network and Live TV recovery ignore the dedicated error") {
		val entry = transformationEntry(sourceBaseDecision())
		val error = transformationError(entry)
		entry.doviRecoveryOwnership = DoviRecoveryOwnership.IN_FLIGHT

		isRecoverablePlaybackError(error.codeName) shouldBe false
		shouldSuppressLiveTvGenericRecovery(error, entry) shouldBe true
		shouldSuppressLiveTvGenericRecovery(error, QueueEntry()) shouldBe false
		shouldSuppressLiveTvGenericRecovery(
			PlaybackError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", origin = activeOrigin(entry)),
			entry,
		) shouldBe false
	}

	test("runner clears its active entry when reload throws") {
		val entry = transformationEntry(transformDecision(DoviTarget.PROFILE_8_1, DoviDecisionReason.PROFILE_8_1))
		val recovery = claimDoviTransformationRecovery(entry, transformationError(entry), 8.seconds, true)!!
		val runner = DoviTransformationRecoveryRunner()

		runner.run(recovery, { entry }, {}) { _, _ -> error("playback info failed") } shouldBe false
		runner.isActive(entry) shouldBe false
		entry.doviRecoveryOwnership shouldBe DoviRecoveryOwnership.TERMINAL
	}

	test("dedicated reload waits until generic recovery cancellation completes") {
		val entry = transformationEntry(transformDecision(DoviTarget.PROFILE_8_1, DoviDecisionReason.PROFILE_8_1))
		val recovery = claimDoviTransformationRecovery(entry, transformationError(entry), 8.seconds, true)!!
		val cancellationEntered = CompletableDeferred<Unit>()
		val allowCancellationToFinish = CompletableDeferred<Unit>()
		var reloadStarted = false

		coroutineScope {
			val run = async {
				DoviTransformationRecoveryRunner().run(
					recovery,
					{ entry },
					{
						cancellationEntered.complete(Unit)
						allowCancellationToFinish.await()
					},
				) { _, _ ->
					reloadStarted = true
					true
				}
			}
			cancellationEntered.await()
			reloadStarted shouldBe false
			allowCancellationToFinish.complete(Unit)
			run.await() shouldBe true
		}
		reloadStarted shouldBe true
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
