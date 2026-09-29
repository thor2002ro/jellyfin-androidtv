package org.jellyfin.playback.dovi

import io.github.thor2002ro.libdovi.DoviCapability
import io.github.thor2002ro.libdovi.DoviInspection
import io.github.thor2002ro.libdovi.DoviPresentation
import io.github.thor2002ro.libdovi.DoviRepair
import io.github.thor2002ro.libdovi.DoviTarget
import io.github.thor2002ro.libdovi.DoviTransformObservation
import io.github.thor2002ro.libdovi.DoviTransformRequest
import io.github.thor2002ro.libdovi.DoviTransformStrategy
import org.jellyfin.playback.core.model.PlaybackDoviTransformStats

enum class DoviCompatibilityMode {
	AUTO,
	FAST_HDR,
	ALWAYS,
	COMPATIBILITY,
	OFF,
}

enum class DoviPlaybackBackend {
	MEDIA3,
	MPV,
	OTHER,
}

enum class DoviVideoCodec {
	HEVC,
	AV1,
	OTHER,
}

enum class DoviCmv40Workaround {
	NONE,
	REMOVE,
	ADD_SAFE_DEFAULTS,
}

data class DoviDeviceCapabilities(
	val supportsProfile5: Boolean = false,
	val supportsProfile7: Boolean = false,
	val supportsProfile8: Boolean = false,
	val supportsProfile84: Boolean = false,
	val supportsMel: Boolean = false,
	val supportsFel: Boolean = false,
	val supportsHdr10: Boolean = false,
	val supportsHdr10Plus: Boolean = false,
	val supportsHlg: Boolean = false,
)

data class DoviWorkarounds(
	val removeMapping: Boolean = false,
	val preserveMappingForProfile81: Boolean = false,
	val cmv40: DoviCmv40Workaround = DoviCmv40Workaround.NONE,
	val repairActiveArea: Boolean = false,
)

enum class DoviSourceProfile { PROFILE_5, PROFILE_7, PROFILE_8_1, PROFILE_8_4, UNKNOWN }

enum class DoviSourceLayer { NONE, UNKNOWN, MEL, FEL }

data class DoviSource(
	val profile: DoviSourceProfile,
	val layer: DoviSourceLayer = DoviSourceLayer.NONE,
	val sourceBasePresentation: DoviPresentation = DoviPresentation.UNKNOWN,
	val inspection: DoviInspection? = null,
	val dvLevel: Int? = null,
) {
	constructor(
		presentation: DoviPresentation,
		sourceBasePresentation: DoviPresentation = DoviPresentation.UNKNOWN,
		inspection: DoviInspection? = null,
		dvLevel: Int? = null,
	) : this(presentation.sourceProfile, presentation.sourceLayer, sourceBasePresentation, inspection, dvLevel)

	init {
		require(inspection == null || inspection.input.sourceProfile == profile) {
			"Inspection presentation must match the declared source"
		}
		require(profile == DoviSourceProfile.PROFILE_7 || layer == DoviSourceLayer.NONE)
		require(dvLevel == null || dvLevel in 0..63)
		require(inspection == null || layer == DoviSourceLayer.UNKNOWN || inspection.input.sourceLayer == layer)
	}
}

private val DoviPresentation.sourceProfile: DoviSourceProfile get() = when (this) {
	DoviPresentation.PROFILE_5 -> DoviSourceProfile.PROFILE_5
	DoviPresentation.PROFILE_7_MEL, DoviPresentation.PROFILE_7_FEL -> DoviSourceProfile.PROFILE_7
	DoviPresentation.PROFILE_8_1 -> DoviSourceProfile.PROFILE_8_1
	DoviPresentation.PROFILE_8_4 -> DoviSourceProfile.PROFILE_8_4
	else -> DoviSourceProfile.UNKNOWN
}

private val DoviPresentation.sourceLayer: DoviSourceLayer get() = when (this) {
	DoviPresentation.PROFILE_7_MEL -> DoviSourceLayer.MEL
	DoviPresentation.PROFILE_7_FEL -> DoviSourceLayer.FEL
	else -> DoviSourceLayer.NONE
}

sealed interface DoviRoute {
	data object Native : DoviRoute

	data class Transform(
		val request: DoviTransformRequest,
	) : DoviRoute

	data class SourceBase(
		val request: DoviTransformRequest,
		val strategy: DoviTransformStrategy = DoviTransformStrategy.LIBDOVI,
	) : DoviRoute

	data object ServerFallback : DoviRoute
}

enum class DoviDecisionReason {
	NATIVE_SUPPORTED,
	DISABLED_BY_USER,
	PROFILE_8_1,
	REPAIRED_NATIVE,
	FEL_TO_MEL,
	PROFILE_8_4,
	SOURCE_BASE,
	INVALID_AV1_PROFILE_7,
	INVALID_RPU,
	INSPECTION_REQUIRED,
	REQUIRED_CAPABILITY_UNAVAILABLE,
	BRIDGE_UNAVAILABLE,
	BACKEND_UNSUPPORTED,
	CONTAINER_UNSUPPORTED,
	RETRY_SUPPRESSED,
	NO_COMPATIBLE_ROUTE,
}

data class DoviDecision(
	val route: DoviRoute,
	val reason: DoviDecisionReason,
	val invalidStream: Boolean = false,
	val transformEvidence: DoviTransformEvidence? = null,
) {
	val request: DoviTransformRequest?
		get() = when (route) {
			is DoviRoute.SourceBase -> route.request
			is DoviRoute.Transform -> route.request
			DoviRoute.Native,
			DoviRoute.ServerFallback,
			-> null
		}
}

data class DoviTransformEvidence(
	val inputPresentation: DoviPresentation,
	val sourceBasePresentation: DoviPresentation,
	val sourceProfile: DoviSourceProfile,
	val sourceLayer: DoviSourceLayer,
	val dvLevel: Int? = null,
)

object DoviCompatibilityPolicy {
	private val supportedContainers = setOf(
		"mp4",
		"fmp4",
		"mov",
		"m4v",
		"ts",
		"mpegts",
		"m2ts",
		"hls",
		"mkv",
		"matroska",
	)

	data class Input(
		val mode: DoviCompatibilityMode,
		val backend: DoviPlaybackBackend,
		val codec: DoviVideoCodec,
		val container: String?,
		val source: DoviSource,
		val device: DoviDeviceCapabilities,
		val bridgeAvailable: Boolean,
		val nativeCapabilities: Set<DoviCapability>,
		val workarounds: DoviWorkarounds = DoviWorkarounds(),
		val builtInPlayerSelected: Boolean = true,
		val externalPlayerSelected: Boolean = false,
		val retrySuppressed: Boolean = false,
	)

	fun decide(input: Input): DoviDecision {
		if (input.retrySuppressed) return serverFallback(DoviDecisionReason.RETRY_SUPPRESSED)
		if (input.codec == DoviVideoCodec.AV1) {
			return if (input.source.profile == DoviSourceProfile.PROFILE_7) {
				serverFallback(DoviDecisionReason.INVALID_AV1_PROFILE_7, invalidStream = true)
			} else {
				native()
			}
		}
		if (input.codec != DoviVideoCodec.HEVC) {
			return serverFallback(DoviDecisionReason.NO_COMPATIBLE_ROUTE)
		}

		if (input.mode == DoviCompatibilityMode.OFF) {
			return if (input.device.supportsNative(input.source, requireLayerEvidence = false)) {
				native(DoviDecisionReason.DISABLED_BY_USER)
			} else {
				serverFallback(DoviDecisionReason.DISABLED_BY_USER)
			}
		}
		if (!input.builtInPlayerSelected || input.externalPlayerSelected || !input.backend.isSupported()) {
			return serverFallback(DoviDecisionReason.BACKEND_UNSUPPORTED)
		}
		if (input.container?.lowercase() !in supportedContainers) {
			return serverFallback(DoviDecisionReason.CONTAINER_UNSUPPORTED)
		}

		return when (input.mode) {
			DoviCompatibilityMode.AUTO -> decideAuto(input)
			DoviCompatibilityMode.FAST_HDR -> decideFastHdr(input)
			DoviCompatibilityMode.ALWAYS -> decideAlways(input)
			DoviCompatibilityMode.COMPATIBILITY -> decideCompatibility(input)
			DoviCompatibilityMode.OFF -> error("Handled above")
		}.withTransformEvidence(input.source)
	}

	private fun decideFastHdr(input: Input): DoviDecision =
		if (input.isFastHdrEligible()) {
			input.sourceBaseDecision(DoviTransformStrategy.FAST_SOURCE_BASE_FALLBACK)
		} else {
			decideAuto(input)
		}

	private fun DoviDecision.withTransformEvidence(source: DoviSource): DoviDecision =
		if (request == null) this else copy(
			transformEvidence = DoviTransformEvidence(
				inputPresentation = source.presentation,
				sourceBasePresentation = source.sourceBasePresentation,
				sourceProfile = source.profile,
				sourceLayer = source.layer,
				dvLevel = source.dvLevel,
			),
		)

	private fun decideAuto(input: Input): DoviDecision {
		if (input.device.supportsNative(input.source, requireLayerEvidence = false)) {
			return native()
		}
		if (!input.bridgeAvailable) return serverFallback(DoviDecisionReason.BRIDGE_UNAVAILABLE)

		return if (
			input.source.accepts(DoviTarget.PROFILE_8_1) &&
			input.device.supportsProfile8 &&
			input.supports(DoviTarget.PROFILE_8_1)
		) {
			transform(DoviTarget.PROFILE_8_1, reason = DoviDecisionReason.PROFILE_8_1)
		} else {
			input.sourceBaseDecision(input.autoSourceBaseStrategy())
		}
	}

	private fun Input.autoSourceBaseStrategy(): DoviTransformStrategy =
		if (isFastHdrEligible()) {
			DoviTransformStrategy.FAST_SOURCE_BASE_FALLBACK
		} else {
			DoviTransformStrategy.LIBDOVI
		}

	private fun Input.isFastHdrEligible(): Boolean =
		backend == DoviPlaybackBackend.MEDIA3 &&
			codec == DoviVideoCodec.HEVC &&
			source.profile == DoviSourceProfile.PROFILE_8_1 &&
			source.sourceBasePresentation in setOf(DoviPresentation.HDR10, DoviPresentation.HDR10_PLUS)

	private fun decideAlways(input: Input): DoviDecision {
		if (!input.source.profile.isProfile5Or7()) {
			return if (input.device.supportsNative(input.source, requireLayerEvidence = false)) {
				native()
			} else {
				input.sourceBaseDecision()
			}
		}
		if (!input.bridgeAvailable) return serverFallback(DoviDecisionReason.BRIDGE_UNAVAILABLE)
		if (
			input.source.accepts(DoviTarget.PROFILE_8_1) &&
			input.device.supportsProfile8 &&
			input.supports(DoviTarget.PROFILE_8_1)
		) {
			return transform(DoviTarget.PROFILE_8_1, reason = DoviDecisionReason.PROFILE_8_1)
		}

		return input.sourceBaseDecision()
	}

	private fun decideCompatibility(input: Input): DoviDecision {
		val repairs = input.selectedRepairs()
			?: return serverFallback(DoviDecisionReason.INSPECTION_REQUIRED)
		val repairsRequired = repairs.isNotEmpty()
		if (!input.bridgeAvailable) {
			if (repairsRequired || input.requiresMappingEvidence()) {
				return serverFallback(DoviDecisionReason.BRIDGE_UNAVAILABLE)
			}
			return if (input.device.supportsNative(input.source, requireLayerEvidence = true)) {
				native()
			} else {
				serverFallback(DoviDecisionReason.BRIDGE_UNAVAILABLE)
			}
		}

		val repairsSupported = input.supports(repairs)
		if (repairsRequired && !repairsSupported) {
			return input.sourceBaseDecision()
		}
		val nativeSupported = input.device.supportsNative(
			input.source,
			requireLayerEvidence = true,
		)

		if (nativeSupported) {
			if (repairs.isEmpty()) return native()
			if (repairsSupported && input.supports(DoviTarget.LOSSLESS_REWRITE)) {
				return transform(
				target = DoviTarget.LOSSLESS_REWRITE,
				repairs = repairs,
				reason = DoviDecisionReason.REPAIRED_NATIVE,
			)
			}
		}

		if (
			input.source.profile == DoviSourceProfile.PROFILE_7 &&
			input.source.layer == DoviSourceLayer.FEL &&
			input.source.accepts(DoviTarget.MEL) &&
			input.device.supportsProfile7 &&
			!input.device.supportsFel &&
			input.device.supportsMel &&
			repairsSupported &&
			input.supports(DoviTarget.MEL)
		) {
			return transform(DoviTarget.MEL, repairs, DoviDecisionReason.FEL_TO_MEL)
		}

		if (
			input.source.accepts(DoviTarget.PROFILE_8_1) &&
			input.device.supportsProfile8 &&
			repairsSupported
		) {
			val target = input.profile81Target(repairs)
				?: return serverFallback(DoviDecisionReason.INSPECTION_REQUIRED)
			if (input.supports(target)) {
				return transform(target, repairs, DoviDecisionReason.PROFILE_8_1)
			}
			if (target == DoviTarget.PROFILE_8_1_PRESERVE_MAPPING) {
				return input.sourceBaseDecision()
			}
		}

		if (
			input.source.accepts(DoviTarget.PROFILE_8_4) &&
			input.device.supportsProfile84 &&
			repairsSupported &&
			input.supports(DoviTarget.PROFILE_8_4)
		) {
			return transform(DoviTarget.PROFILE_8_4, repairs, DoviDecisionReason.PROFILE_8_4)
		}

		return input.sourceBaseDecision()
	}

	private fun Input.selectedRepairs(): Set<DoviRepair>? {
		if ((workarounds.removeMapping || workarounds.cmv40 == DoviCmv40Workaround.REMOVE) &&
			source.inspection == null
		) {
			return null
		}
		return buildSet {
			if (source.inspection?.mappingPresent == true && workarounds.removeMapping) {
				add(DoviRepair.REMOVE_MAPPING)
			}
			if (workarounds.repairActiveArea) add(DoviRepair.ZERO_ACTIVE_AREA)
			when (workarounds.cmv40) {
				DoviCmv40Workaround.NONE -> Unit
				DoviCmv40Workaround.REMOVE -> if (source.inspection?.cmv40Present == true) {
					add(DoviRepair.REMOVE_CMV40)
				}
				DoviCmv40Workaround.ADD_SAFE_DEFAULTS -> add(DoviRepair.ADD_CMV40_SAFE_DEFAULTS)
			}
		}
	}

	private fun Input.sourceBaseDecision(
		strategy: DoviTransformStrategy = DoviTransformStrategy.LIBDOVI,
	): DoviDecision {
		val baseSupported = when (source.sourceBasePresentation) {
			DoviPresentation.HDR10 -> device.supportsHdr10
			DoviPresentation.HDR10_PLUS -> device.supportsHdr10Plus
			DoviPresentation.HLG -> device.supportsHlg
			else -> false
		}
		return if (
			bridgeAvailable &&
			baseSupported &&
			source.accepts(DoviTarget.SOURCE_BASE_PRESENTATION) &&
			supports(DoviTarget.SOURCE_BASE_PRESENTATION)
		) {
			DoviDecision(
				route = DoviRoute.SourceBase(
					request = DoviTransformRequest(DoviTarget.SOURCE_BASE_PRESENTATION),
					strategy = strategy,
				),
				reason = DoviDecisionReason.SOURCE_BASE,
			)
		} else {
			serverFallback(DoviDecisionReason.NO_COMPATIBLE_ROUTE)
		}
	}

	private fun Input.supports(target: DoviTarget): Boolean =
		nativeCapabilities.contains(DoviCapability.INSPECT) &&
			nativeCapabilities.contains(DoviCapability.VALIDATE) &&
			nativeCapabilities.contains(target.capability) &&
			(backend != DoviPlaybackBackend.MPV || nativeCapabilities.contains(DoviCapability.MPV_STATE))

	private fun Input.supports(repairs: Set<DoviRepair>): Boolean =
		repairs.all { nativeCapabilities.contains(it.capability) }

	private fun DoviDeviceCapabilities.supportsNative(
		source: DoviSource,
		requireLayerEvidence: Boolean,
	): Boolean = when (source.profile) {
		DoviSourceProfile.PROFILE_5 -> supportsProfile5
		DoviSourceProfile.PROFILE_7 -> supportsProfile7 && (!requireLayerEvidence || when (source.layer) {
			DoviSourceLayer.MEL -> supportsMel
			DoviSourceLayer.FEL -> supportsFel
			DoviSourceLayer.NONE, DoviSourceLayer.UNKNOWN -> false
		})
		DoviSourceProfile.PROFILE_8_1 -> supportsProfile8
		DoviSourceProfile.PROFILE_8_4 -> supportsProfile84
		else -> false
	}

	private fun DoviPlaybackBackend.isSupported(): Boolean =
		this == DoviPlaybackBackend.MEDIA3 || this == DoviPlaybackBackend.MPV

	private fun DoviSourceProfile.isProfile5Or7(): Boolean =
		this == DoviSourceProfile.PROFILE_5 || this == DoviSourceProfile.PROFILE_7

	private fun Input.requiresMappingEvidence(): Boolean =
		workarounds.preserveMappingForProfile81 && source.inspection == null

	private fun Input.profile81Target(repairs: Set<DoviRepair>): DoviTarget? {
		if (!workarounds.preserveMappingForProfile81 || DoviRepair.REMOVE_MAPPING in repairs) {
			return DoviTarget.PROFILE_8_1
		}
		val inspection = source.inspection ?: return null
		return if (inspection.mappingPresent && source.profile != DoviSourceProfile.PROFILE_5) {
			DoviTarget.PROFILE_8_1_PRESERVE_MAPPING
		} else {
			DoviTarget.PROFILE_8_1
		}
	}

	private val DoviTarget.capability: DoviCapability
		get() = when (this) {
			DoviTarget.LOSSLESS_REWRITE -> DoviCapability.LOSSLESS_REWRITE
			DoviTarget.MEL -> DoviCapability.MEL
			DoviTarget.PROFILE_8_1 -> DoviCapability.PROFILE_8_1
			DoviTarget.PROFILE_8_1_PRESERVE_MAPPING -> DoviCapability.PROFILE_8_1_PRESERVE_MAPPING
			DoviTarget.PROFILE_8_4 -> DoviCapability.PROFILE_8_4
			DoviTarget.SOURCE_BASE_PRESENTATION -> DoviCapability.SOURCE_BASE_PRESENTATION
		}

	private val DoviRepair.capability: DoviCapability
		get() = when (this) {
			DoviRepair.REMOVE_MAPPING -> DoviCapability.REPAIR_REMOVE_MAPPING
			DoviRepair.ZERO_ACTIVE_AREA -> DoviCapability.REPAIR_ZERO_ACTIVE_AREA
			DoviRepair.ADD_CMV40_SAFE_DEFAULTS -> DoviCapability.REPAIR_ADD_CMV40_SAFE_DEFAULTS
			DoviRepair.REMOVE_CMV40 -> DoviCapability.REPAIR_REMOVE_CMV40
		}

	private fun native(reason: DoviDecisionReason = DoviDecisionReason.NATIVE_SUPPORTED) =
		DoviDecision(DoviRoute.Native, reason)

	private fun transform(
		target: DoviTarget,
		repairs: Set<DoviRepair> = emptySet(),
		reason: DoviDecisionReason,
	) = DoviDecision(DoviRoute.Transform(DoviTransformRequest(target, repairs)), reason)

	private fun serverFallback(reason: DoviDecisionReason, invalidStream: Boolean = false) =
		DoviDecision(DoviRoute.ServerFallback, reason, invalidStream)
}

private val DoviSource.presentation: DoviPresentation
	get() = when (profile) {
		DoviSourceProfile.PROFILE_5 -> DoviPresentation.PROFILE_5
		DoviSourceProfile.PROFILE_7 -> when (layer) {
			DoviSourceLayer.MEL -> DoviPresentation.PROFILE_7_MEL
			DoviSourceLayer.FEL -> DoviPresentation.PROFILE_7_FEL
			DoviSourceLayer.NONE, DoviSourceLayer.UNKNOWN -> DoviPresentation.UNKNOWN
		}
		DoviSourceProfile.PROFILE_8_1 -> DoviPresentation.PROFILE_8_1
		DoviSourceProfile.PROFILE_8_4 -> DoviPresentation.PROFILE_8_4
		DoviSourceProfile.UNKNOWN -> DoviPresentation.UNKNOWN
	}

internal fun DoviSource.accepts(target: DoviTarget): Boolean {
	val pq = sourceBasePresentation == DoviPresentation.HDR10 ||
		sourceBasePresentation == DoviPresentation.HDR10_PLUS
	val hlg = sourceBasePresentation == DoviPresentation.HLG
	return when (target) {
		DoviTarget.LOSSLESS_REWRITE -> true
		DoviTarget.MEL -> pq && profile in setOf(DoviSourceProfile.PROFILE_7, DoviSourceProfile.PROFILE_8_1)
		DoviTarget.PROFILE_8_1 -> pq && profile != DoviSourceProfile.UNKNOWN
		DoviTarget.PROFILE_8_1_PRESERVE_MAPPING -> pq && profile in setOf(DoviSourceProfile.PROFILE_7, DoviSourceProfile.PROFILE_8_1)
		DoviTarget.PROFILE_8_4 -> hlg && profile in setOf(DoviSourceProfile.PROFILE_8_1, DoviSourceProfile.PROFILE_8_4)
		DoviTarget.SOURCE_BASE_PRESENTATION ->
			sourceBasePresentation != DoviPresentation.UNKNOWN && profile != DoviSourceProfile.PROFILE_5
	}
}

val DoviDecision.requiresHardwareVideoDecoder: Boolean
	get() = route != DoviRoute.ServerFallback

fun DoviTransformObservation.toPlaybackDoviTransformStats() = PlaybackDoviTransformStats(
	inputPresentation = input.playbackDiagnosticLabel(),
	outputPresentation = output.playbackDiagnosticLabel(),
)

fun DoviPresentation.playbackDiagnosticLabel() = when (this) {
	DoviPresentation.PROFILE_5 -> "DV P5"
	DoviPresentation.PROFILE_7_MEL -> "DV P7 MEL"
	DoviPresentation.PROFILE_7_FEL -> "DV P7 FEL"
	DoviPresentation.PROFILE_8_1 -> "DV P8.1"
	DoviPresentation.PROFILE_8_4 -> "DV P8.4"
	DoviPresentation.HDR10 -> "HDR10"
	DoviPresentation.HDR10_PLUS -> "HDR10+"
	DoviPresentation.HLG -> "HLG"
	DoviPresentation.UNKNOWN -> "Unknown"
}
