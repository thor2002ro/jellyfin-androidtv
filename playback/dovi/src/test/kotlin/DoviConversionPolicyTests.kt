package org.jellyfin.playback.dovi

import io.github.thor2002ro.libdovi.DoviCapability
import io.github.thor2002ro.libdovi.DoviFraming
import io.github.thor2002ro.libdovi.DoviInspection
import io.github.thor2002ro.libdovi.DoviPresentation
import io.github.thor2002ro.libdovi.DoviTransformObservation
import io.github.thor2002ro.libdovi.DoviTransformStrategy
import io.github.thor2002ro.libdovi.DoviRepair
import io.github.thor2002ro.libdovi.DoviTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jellyfin.playback.core.model.PlaybackDoviTransformStats

class DoviConversionPolicyTests : FunSpec({
	test("server fallback is not guarded as local Dolby Vision playback") {
		DoviDecision(DoviRoute.Native, DoviDecisionReason.NATIVE_SUPPORTED)
			.requiresHardwareVideoDecoder shouldBe true
		DoviDecision(DoviRoute.ServerFallback, DoviDecisionReason.RETRY_SUPPRESSED)
			.requiresHardwareVideoDecoder shouldBe false
	}

	test("native transform observation maps to stable playback diagnostics") {
		DoviTransformObservation(
			input = DoviPresentation.PROFILE_7_FEL,
			output = DoviPresentation.PROFILE_8_1,
		).toPlaybackDoviTransformStats() shouldBe PlaybackDoviTransformStats(
			inputPresentation = "DV P7 FEL",
			outputPresentation = "DV P8.1",
		)
	}
	val allNativeCapabilities = DoviCapability.entries.toSet()
	val profile8Device = DoviDeviceCapabilities(
		supportsProfile8 = true,
		supportsHdr10 = true,
	)

	fun inspectedSource(
		presentation: DoviPresentation,
		base: DoviPresentation = DoviPresentation.UNKNOWN,
		mappingPresent: Boolean = false,
		cmv40Present: Boolean = false,
	): DoviSource = DoviSource(
		presentation = presentation,
		sourceBasePresentation = base,
		inspection = DoviInspection(
			input = presentation,
			rpuCount = 1,
			enhancementNalCount = 0,
			videoNalCount = 1,
			framing = DoviFraming.ANNEX_B,
			nalLengthSize = 0,
			mappingPresent = mappingPresent,
			cmv40Present = cmv40Present,
		),
	)

	fun decide(
		mode: DoviCompatibilityMode = DoviCompatibilityMode.AUTO,
		source: DoviSource = DoviSource(
			presentation = DoviPresentation.PROFILE_7_FEL,
			sourceBasePresentation = DoviPresentation.HDR10,
		),
		device: DoviDeviceCapabilities = profile8Device,
		workarounds: DoviWorkarounds = DoviWorkarounds(),
		capabilities: Set<DoviCapability> = allNativeCapabilities,
		backend: DoviPlaybackBackend = DoviPlaybackBackend.MEDIA3,
		codec: DoviVideoCodec = DoviVideoCodec.HEVC,
		container: String? = "mkv",
		bridgeAvailable: Boolean = true,
		builtInPlayerSelected: Boolean = true,
		retrySuppressed: Boolean = false,
	): DoviDecision = DoviCompatibilityPolicy.decide(
		DoviCompatibilityPolicy.Input(
			mode = mode,
			backend = backend,
			codec = codec,
			container = container,
			source = source,
			device = device,
			bridgeAvailable = bridgeAvailable,
			nativeCapabilities = capabilities,
			workarounds = workarounds,
			builtInPlayerSelected = builtInPlayerSelected,
			retrySuppressed = retrySuppressed,
		),
	)

	test("Auto keeps reported Profile 7 support unchanged") {
		decide(
			device = DoviDeviceCapabilities(supportsProfile7 = true),
		).route shouldBe DoviRoute.Native
	}

	test("Auto keeps Profile 5-only device support unchanged") {
		decide(
			source = DoviSource(DoviPresentation.PROFILE_5),
			device = DoviDeviceCapabilities(supportsProfile5 = true),
		).route shouldBe DoviRoute.Native
	}

	test("Auto converts unsupported Profile 5 and Profile 7 to Profile 8.1") {
		listOf(
			DoviPresentation.PROFILE_5 to DoviPresentation.HDR10,
			DoviPresentation.PROFILE_7_MEL to DoviPresentation.HDR10,
			DoviPresentation.PROFILE_7_FEL to DoviPresentation.HDR10_PLUS,
		).forEach { (presentation, base) ->
			decide(source = DoviSource(presentation, base)).request?.target shouldBe DoviTarget.PROFILE_8_1
		}
	}

	test("transform decision snapshots the exact negotiated source evidence") {
		val decision = decide(
			source = DoviSource(
				presentation = DoviPresentation.PROFILE_7_FEL,
				sourceBasePresentation = DoviPresentation.HDR10_PLUS,
				dvLevel = 9,
			),
		)

		decision.transformEvidence shouldBe DoviTransformEvidence(
			inputPresentation = DoviPresentation.PROFILE_7_FEL,
			sourceBasePresentation = DoviPresentation.HDR10_PLUS,
			sourceProfile = DoviSourceProfile.PROFILE_7,
			sourceLayer = DoviSourceLayer.FEL,
			dvLevel = 9,
		)
	}

	test("pass-through and server fallback carry no transform evidence") {
		decide(device = DoviDeviceCapabilities(supportsProfile7 = true)).transformEvidence shouldBe null
		decide(device = DoviDeviceCapabilities()).transformEvidence shouldBe null
	}

	test("Auto does not convert Profile 8.1 that the device supports") {
		decide(source = DoviSource(DoviPresentation.PROFILE_8_1)).route shouldBe DoviRoute.Native
	}

	test("Auto requires Profile 8 and its exact native target capability for Profile 8 conversion") {
		decide(device = DoviDeviceCapabilities()).route shouldBe DoviRoute.ServerFallback
		decide(
			capabilities = allNativeCapabilities - DoviCapability.PROFILE_8_1,
		).request?.target shouldBe DoviTarget.SOURCE_BASE_PRESENTATION
	}

	test("Auto never applies compatibility repairs") {
		val decision = decide(
			source = inspectedSource(
				presentation = DoviPresentation.PROFILE_7_FEL,
				base = DoviPresentation.HDR10,
				mappingPresent = true,
				cmv40Present = true,
			),
			workarounds = DoviWorkarounds(
				removeMapping = true,
				cmv40 = DoviCmv40Workaround.REMOVE,
				repairActiveArea = true,
			),
		)

		decision.request?.target shouldBe DoviTarget.PROFILE_8_1
		decision.request?.repairs shouldBe emptySet()
	}

	test("Auto exposes a supported encoded HDR base when no Dolby Vision route is playable") {
		listOf(
			DoviPresentation.HDR10 to DoviDeviceCapabilities(supportsHdr10 = true),
			DoviPresentation.HDR10_PLUS to DoviDeviceCapabilities(supportsHdr10Plus = true),
		).forEach { (base, device) ->
			decide(
				source = DoviSource(DoviPresentation.PROFILE_7_FEL, base),
				device = device,
			).route shouldBe DoviRoute.SourceBase(
				io.github.thor2002ro.libdovi.DoviTransformRequest(DoviTarget.SOURCE_BASE_PRESENTATION),
			)
		}
	}

	test("Auto selects fast HDR base fallback only for Media3 Profile 8.1 PQ bases") {
		listOf(
			DoviPresentation.HDR10 to DoviDeviceCapabilities(supportsHdr10 = true),
			DoviPresentation.HDR10_PLUS to DoviDeviceCapabilities(supportsHdr10Plus = true),
		).forEach { (base, device) ->
			val decision = decide(
				source = DoviSource(DoviPresentation.PROFILE_8_1, base),
				device = device,
			)

			(decision.route as DoviRoute.SourceBase).strategy shouldBe
				DoviTransformStrategy.FAST_SOURCE_BASE_FALLBACK
		}
	}

	test("Fast HDR forces an eligible Media3 Profile 8.1 source base") {
		val decision = decide(
			mode = DoviCompatibilityMode.FAST_HDR,
			source = DoviSource(DoviPresentation.PROFILE_8_1, DoviPresentation.HDR10),
			device = DoviDeviceCapabilities(supportsProfile8 = true, supportsHdr10 = true),
		)

		(decision.route as DoviRoute.SourceBase).strategy shouldBe
			DoviTransformStrategy.FAST_SOURCE_BASE_FALLBACK
	}

	test("Fast HDR behaves exactly like Auto with MPV") {
		listOf(
			DoviSource(DoviPresentation.PROFILE_8_1, DoviPresentation.HDR10) to
				DoviDeviceCapabilities(supportsProfile8 = true, supportsHdr10 = true),
			DoviSource(DoviPresentation.PROFILE_8_1, DoviPresentation.HDR10_PLUS) to
				DoviDeviceCapabilities(supportsHdr10Plus = true),
			DoviSource(DoviPresentation.PROFILE_7_FEL, DoviPresentation.HDR10) to profile8Device,
		).forEach { (source, device) ->
			decide(
				mode = DoviCompatibilityMode.FAST_HDR,
				backend = DoviPlaybackBackend.MPV,
				source = source,
				device = device,
			) shouldBe decide(
				mode = DoviCompatibilityMode.AUTO,
				backend = DoviPlaybackBackend.MPV,
				source = source,
				device = device,
			)
		}
	}

	test("Auto keeps non-eligible source-base conversions on libdovi") {
		val profile7 = decide(
			source = DoviSource(DoviPresentation.PROFILE_7_FEL, DoviPresentation.HDR10),
			device = DoviDeviceCapabilities(supportsHdr10 = true),
		)
		val hlg = decide(
			source = DoviSource(DoviPresentation.PROFILE_8_1, DoviPresentation.HLG),
			device = DoviDeviceCapabilities(supportsHlg = true),
		)
		val mpv = decide(
			backend = DoviPlaybackBackend.MPV,
			source = DoviSource(DoviPresentation.PROFILE_8_1, DoviPresentation.HDR10_PLUS),
			device = DoviDeviceCapabilities(supportsHdr10Plus = true),
		)

		listOf(profile7, hlg, mpv).forEach { decision ->
			(decision.route as DoviRoute.SourceBase).strategy shouldBe DoviTransformStrategy.LIBDOVI
		}
	}

	test("Always and Compatibility keep source-base conversions on libdovi") {
		val always = decide(
			mode = DoviCompatibilityMode.ALWAYS,
			source = DoviSource(DoviPresentation.PROFILE_8_1, DoviPresentation.HDR10),
			device = DoviDeviceCapabilities(supportsHdr10 = true),
		)
		val compatibility = decide(
			mode = DoviCompatibilityMode.COMPATIBILITY,
			source = DoviSource(DoviPresentation.PROFILE_8_1, DoviPresentation.HDR10_PLUS),
			device = DoviDeviceCapabilities(supportsHdr10Plus = true),
			workarounds = DoviWorkarounds(repairActiveArea = true),
			capabilities = allNativeCapabilities - DoviCapability.REPAIR_ZERO_ACTIVE_AREA,
		)

		listOf(always, compatibility).forEach { decision ->
			(decision.route as DoviRoute.SourceBase).strategy shouldBe DoviTransformStrategy.LIBDOVI
		}
	}

	test("Always converts Profile 5 and Profile 7 even when their native profiles are reported") {
		listOf(
			DoviPresentation.PROFILE_5 to profile8Device.copy(supportsProfile5 = true),
			DoviPresentation.PROFILE_7_MEL to profile8Device.copy(supportsProfile7 = true),
			DoviPresentation.PROFILE_7_FEL to profile8Device.copy(supportsProfile7 = true),
		).forEach { (presentation, device) ->
			decide(
				mode = DoviCompatibilityMode.ALWAYS,
				source = DoviSource(presentation, DoviPresentation.HDR10),
				device = device,
			).request?.target shouldBe DoviTarget.PROFILE_8_1
		}
	}

	test("Always exposes an encoded compatible base only when Profile 8 is unavailable") {
		val decision = decide(
			mode = DoviCompatibilityMode.ALWAYS,
			device = DoviDeviceCapabilities(supportsHdr10 = true),
		)

		decision.route shouldBe DoviRoute.SourceBase(
			io.github.thor2002ro.libdovi.DoviTransformRequest(DoviTarget.SOURCE_BASE_PRESENTATION),
		)
	}

	test("Always does not claim a source base that the device cannot play") {
		decide(
			mode = DoviCompatibilityMode.ALWAYS,
			device = DoviDeviceCapabilities(),
		).route shouldBe DoviRoute.ServerFallback
	}

	test("Always never adds compatibility repairs") {
		val decision = decide(
			mode = DoviCompatibilityMode.ALWAYS,
			source = inspectedSource(
				presentation = DoviPresentation.PROFILE_7_FEL,
				base = DoviPresentation.HDR10,
				mappingPresent = true,
				cmv40Present = true,
			),
			workarounds = DoviWorkarounds(
				removeMapping = true,
				cmv40 = DoviCmv40Workaround.REMOVE,
				repairActiveArea = true,
			),
		)

		decision.request?.repairs shouldBe emptySet()
	}

	test("Compatibility retains a supported source with the exact repair combination") {
		val decision = decide(
			mode = DoviCompatibilityMode.COMPATIBILITY,
			source = inspectedSource(
				presentation = DoviPresentation.PROFILE_7_FEL,
				base = DoviPresentation.HDR10,
				mappingPresent = true,
				cmv40Present = true,
			),
			device = DoviDeviceCapabilities(supportsProfile7 = true, supportsFel = true),
			workarounds = DoviWorkarounds(
				removeMapping = true,
				cmv40 = DoviCmv40Workaround.REMOVE,
				repairActiveArea = true,
			),
		)

		decision.request?.target shouldBe DoviTarget.LOSSLESS_REWRITE
		decision.request?.repairs?.shouldContainExactlyInAnyOrder(
			DoviRepair.REMOVE_MAPPING,
			DoviRepair.ZERO_ACTIVE_AREA,
			DoviRepair.REMOVE_CMV40,
		)
	}

	test("Compatibility can add CM v4.0 safe defaults and repair active area by workaround") {
		val decision = decide(
			mode = DoviCompatibilityMode.COMPATIBILITY,
			source = inspectedSource(
				presentation = DoviPresentation.PROFILE_5,
				cmv40Present = true,
			),
			device = DoviDeviceCapabilities(supportsProfile5 = true),
			workarounds = DoviWorkarounds(
				cmv40 = DoviCmv40Workaround.ADD_SAFE_DEFAULTS,
				repairActiveArea = true,
			),
		)

		(decision.route is DoviRoute.Transform) shouldBe true
		decision.request?.target shouldBe DoviTarget.LOSSLESS_REWRITE
		decision.request?.repairs?.shouldContainExactlyInAnyOrder(
			DoviRepair.ADD_CMV40_SAFE_DEFAULTS,
			DoviRepair.ZERO_ACTIVE_AREA,
		)
	}

	test("Compatibility gives mapping removal precedence over mapping preservation") {
		val decision = decide(
			mode = DoviCompatibilityMode.COMPATIBILITY,
			source = inspectedSource(
				presentation = DoviPresentation.PROFILE_7_MEL,
				base = DoviPresentation.HDR10,
				mappingPresent = true,
			),
			workarounds = DoviWorkarounds(
				removeMapping = true,
				preserveMappingForProfile81 = true,
			),
		)

		decision.request?.target shouldBe DoviTarget.PROFILE_8_1
		decision.request?.repairs shouldBe setOf(DoviRepair.REMOVE_MAPPING)
	}

	test("Compatibility selects MEL only with positive MEL evidence and no FEL support") {
		val decision = decide(
			mode = DoviCompatibilityMode.COMPATIBILITY,
			device = DoviDeviceCapabilities(
				supportsProfile7 = true,
				supportsMel = true,
				supportsFel = false,
			),
		)

		decision.request?.target shouldBe DoviTarget.MEL
	}

	test("Compatibility does not infer MEL support from generic Profile 7 support") {
		decide(
			mode = DoviCompatibilityMode.COMPATIBILITY,
			device = DoviDeviceCapabilities(supportsProfile7 = true),
			source = DoviSource(DoviPresentation.PROFILE_7_FEL),
		).route shouldBe DoviRoute.ServerFallback
	}

	test("Compatibility converts Profile 5 to Profile 8.1 for a Profile 8 device") {
		decide(
			mode = DoviCompatibilityMode.COMPATIBILITY,
			source = DoviSource(DoviPresentation.PROFILE_5, DoviPresentation.HDR10),
		).request?.target shouldBe DoviTarget.PROFILE_8_1
	}

	test("Compatibility repairs Profile 8.1 without changing its presentation") {
		val decision = decide(
			mode = DoviCompatibilityMode.COMPATIBILITY,
			source = DoviSource(
				presentation = DoviPresentation.PROFILE_8_1,
				sourceBasePresentation = DoviPresentation.HDR10,
			),
			workarounds = DoviWorkarounds(repairActiveArea = true),
		)

		decision.request?.target shouldBe DoviTarget.LOSSLESS_REWRITE
		decision.request?.repairs shouldBe setOf(DoviRepair.ZERO_ACTIVE_AREA)
	}

	test("Compatibility preserves mapping for Profile 8.1 only when explicitly required") {
		val source = inspectedSource(
			DoviPresentation.PROFILE_7_MEL,
			base = DoviPresentation.HDR10,
			mappingPresent = true,
		)
		decide(
			mode = DoviCompatibilityMode.COMPATIBILITY,
			source = source,
		).request?.target shouldBe DoviTarget.PROFILE_8_1
		decide(
			mode = DoviCompatibilityMode.COMPATIBILITY,
			source = source,
			workarounds = DoviWorkarounds(preserveMappingForProfile81 = true),
		).request?.target shouldBe DoviTarget.PROFILE_8_1_PRESERVE_MAPPING
	}

	test("Compatibility selects Profile 8.4 only for an encoded HLG base") {
		val hlgDevice = DoviDeviceCapabilities(supportsProfile84 = true, supportsHlg = true)
		decide(
			mode = DoviCompatibilityMode.COMPATIBILITY,
			source = DoviSource(
				DoviPresentation.PROFILE_8_1,
				DoviPresentation.HLG,
			),
			device = hlgDevice,
		).request?.target shouldBe DoviTarget.PROFILE_8_4

		decide(
			mode = DoviCompatibilityMode.COMPATIBILITY,
			source = DoviSource(
				DoviPresentation.PROFILE_8_1,
				DoviPresentation.HDR10,
			),
			device = hlgDevice,
		).route shouldBe DoviRoute.ServerFallback
	}

	test("Compatibility falls back to existing HDR10 Plus and HLG bases") {
		listOf(
			DoviPresentation.HDR10_PLUS to DoviDeviceCapabilities(supportsHdr10Plus = true),
			DoviPresentation.HLG to DoviDeviceCapabilities(supportsHlg = true),
		).forEach { (base, device) ->
			decide(
				mode = DoviCompatibilityMode.COMPATIBILITY,
				source = DoviSource(DoviPresentation.PROFILE_7_MEL, base),
				device = device,
			).request?.target shouldBe DoviTarget.SOURCE_BASE_PRESENTATION
		}
	}

	test("Compatibility fails closed when a required repair is unavailable") {
		val decision = decide(
			mode = DoviCompatibilityMode.COMPATIBILITY,
			source = DoviSource(
				presentation = DoviPresentation.PROFILE_7_FEL,
				sourceBasePresentation = DoviPresentation.UNKNOWN,
			),
			device = DoviDeviceCapabilities(supportsProfile7 = true, supportsFel = true),
			workarounds = DoviWorkarounds(repairActiveArea = true),
			capabilities = allNativeCapabilities - DoviCapability.REPAIR_ZERO_ACTIVE_AREA,
		)

		decision.route shouldBe DoviRoute.ServerFallback
	}

	test("Compatibility exposes safe encoded bases when a repair capability is unavailable") {
		listOf(
			DoviPresentation.HDR10 to DoviDeviceCapabilities(
				supportsProfile7 = true,
				supportsFel = true,
				supportsHdr10 = true,
			),
			DoviPresentation.HDR10_PLUS to DoviDeviceCapabilities(
				supportsProfile7 = true,
				supportsFel = true,
				supportsHdr10Plus = true,
			),
			DoviPresentation.HLG to DoviDeviceCapabilities(
				supportsProfile7 = true,
				supportsFel = true,
				supportsHlg = true,
			),
		).forEach { (base, device) ->
			val decision = decide(
				mode = DoviCompatibilityMode.COMPATIBILITY,
				source = DoviSource(DoviPresentation.PROFILE_7_FEL, base),
				device = device,
				workarounds = DoviWorkarounds(repairActiveArea = true),
				capabilities = allNativeCapabilities - DoviCapability.REPAIR_ZERO_ACTIVE_AREA,
			)
			decision.request?.target shouldBe DoviTarget.SOURCE_BASE_PRESENTATION
			(decision.route is DoviRoute.SourceBase) shouldBe true
		}
	}

	test("Compatibility falls back to the server when source-base capability is also unavailable") {
		val decision = decide(
			mode = DoviCompatibilityMode.COMPATIBILITY,
			source = DoviSource(DoviPresentation.PROFILE_7_FEL, DoviPresentation.HDR10),
			device = DoviDeviceCapabilities(
				supportsProfile7 = true,
				supportsFel = true,
				supportsHdr10 = true,
			),
			workarounds = DoviWorkarounds(repairActiveArea = true),
			capabilities = allNativeCapabilities -
				DoviCapability.REPAIR_ZERO_ACTIVE_AREA -
				DoviCapability.SOURCE_BASE_PRESENTATION,
		)
		decision.route shouldBe DoviRoute.ServerFallback
	}

	test("Compatibility source-base escape also covers CM and mapping capability gaps") {
		listOf(
			DoviWorkarounds(removeMapping = true) to DoviCapability.REPAIR_REMOVE_MAPPING,
			DoviWorkarounds(cmv40 = DoviCmv40Workaround.REMOVE) to DoviCapability.REPAIR_REMOVE_CMV40,
		).forEach { (workaround, missingCapability) ->
			val decision = decide(
				mode = DoviCompatibilityMode.COMPATIBILITY,
				source = inspectedSource(
					DoviPresentation.PROFILE_7_FEL,
					base = DoviPresentation.HDR10,
					mappingPresent = true,
					cmv40Present = true,
				),
				device = DoviDeviceCapabilities(
					supportsProfile7 = true,
					supportsFel = true,
					supportsHdr10 = true,
				),
				workarounds = workaround,
				capabilities = allNativeCapabilities - missingCapability,
			)
			decision.request?.target shouldBe DoviTarget.SOURCE_BASE_PRESENTATION
		}
	}

	test("Compatibility source-base escape covers unavailable MEL and Profile 8 targets") {
		val source = inspectedSource(
			DoviPresentation.PROFILE_7_FEL,
			base = DoviPresentation.HDR10,
			mappingPresent = true,
		)
		listOf(
			DoviDeviceCapabilities(
				supportsProfile7 = true,
				supportsMel = true,
				supportsHdr10 = true,
			) to (allNativeCapabilities - DoviCapability.MEL),
			profile8Device to (allNativeCapabilities - DoviCapability.PROFILE_8_1),
			profile8Device to (allNativeCapabilities - DoviCapability.PROFILE_8_1_PRESERVE_MAPPING),
		).forEachIndexed { index, (device, capabilities) ->
			val decision = decide(
				mode = DoviCompatibilityMode.COMPATIBILITY,
				source = source,
				device = device,
				workarounds = if (index == 2) {
					DoviWorkarounds(preserveMappingForProfile81 = true)
				} else {
					DoviWorkarounds()
				},
				capabilities = capabilities,
			)
			decision.request?.target shouldBe DoviTarget.SOURCE_BASE_PRESENTATION
		}
	}

	test("Compatibility distinguishes no CM v4 repair from unavailable required repair") {
		val device = DoviDeviceCapabilities(supportsProfile7 = true, supportsFel = true)
		val workaround = DoviWorkarounds(cmv40 = DoviCmv40Workaround.REMOVE)
		decide(
			mode = DoviCompatibilityMode.COMPATIBILITY,
			source = inspectedSource(DoviPresentation.PROFILE_7_FEL, cmv40Present = false),
			device = device,
			workarounds = workaround,
			bridgeAvailable = false,
		).route shouldBe DoviRoute.Native

		val required = decide(
			mode = DoviCompatibilityMode.COMPATIBILITY,
			source = inspectedSource(DoviPresentation.PROFILE_7_FEL, cmv40Present = true),
			device = device,
			workarounds = workaround,
			bridgeAvailable = false,
		)
		required.route shouldBe DoviRoute.ServerFallback
		required.reason shouldBe DoviDecisionReason.BRIDGE_UNAVAILABLE
	}

	test("Compatibility requires inspection before conditional mapping or CM v4 repair") {
		listOf(
			DoviWorkarounds(removeMapping = true),
			DoviWorkarounds(cmv40 = DoviCmv40Workaround.REMOVE),
			DoviWorkarounds(preserveMappingForProfile81 = true),
		).forEach { workaround ->
			decide(
				mode = DoviCompatibilityMode.COMPATIBILITY,
				workarounds = workaround,
			).reason shouldBe DoviDecisionReason.INSPECTION_REQUIRED
		}
	}

	test("MPV transformations require MPV state while Media3 transformations do not") {
		val withoutMpvState = allNativeCapabilities - DoviCapability.MPV_STATE
		decide(
			backend = DoviPlaybackBackend.MPV,
			capabilities = withoutMpvState,
		).route shouldBe DoviRoute.ServerFallback
		decide(
			backend = DoviPlaybackBackend.MEDIA3,
			capabilities = withoutMpvState,
		).request?.target shouldBe DoviTarget.PROFILE_8_1
		decide(
			mode = DoviCompatibilityMode.ALWAYS,
			backend = DoviPlaybackBackend.MPV,
			device = DoviDeviceCapabilities(supportsHdr10 = true),
			capabilities = withoutMpvState,
		).route shouldBe DoviRoute.ServerFallback
	}

	test("policy target predicates mirror native rejected profile and base pairs") {
		val pqBases = listOf(DoviPresentation.HDR10, DoviPresentation.HDR10_PLUS)
		val nonPqBases = listOf(DoviPresentation.UNKNOWN, DoviPresentation.HLG)
		val profile5 = DoviPresentation.PROFILE_5
		val profile7 = DoviPresentation.PROFILE_7_FEL
		val profile8 = DoviPresentation.PROFILE_8_1
		listOf(profile5, profile7, profile8).forEach { profile ->
			DoviSource(profile).accepts(DoviTarget.LOSSLESS_REWRITE) shouldBe true
		}

		listOf(profile5, profile7, profile8).forEach { profile ->
			pqBases.forEach { base ->
				DoviSource(profile, base).accepts(DoviTarget.PROFILE_8_1) shouldBe true
			}
			nonPqBases.forEach { base ->
				DoviSource(profile, base).accepts(DoviTarget.PROFILE_8_1) shouldBe false
			}
		}

		pqBases.forEach { base ->
			DoviSource(profile5, base).accepts(DoviTarget.MEL) shouldBe false
			DoviSource(profile7, base).accepts(DoviTarget.MEL) shouldBe true
			DoviSource(profile8, base).accepts(DoviTarget.MEL) shouldBe true
			DoviSource(profile5, base).accepts(DoviTarget.PROFILE_8_1_PRESERVE_MAPPING) shouldBe false
			DoviSource(profile7, base).accepts(DoviTarget.PROFILE_8_1_PRESERVE_MAPPING) shouldBe true
			DoviSource(profile8, base).accepts(DoviTarget.PROFILE_8_1_PRESERVE_MAPPING) shouldBe true
		}
		nonPqBases.forEach { base ->
			listOf(profile5, profile7, profile8).forEach { profile ->
				DoviSource(profile, base).accepts(DoviTarget.MEL) shouldBe false
				DoviSource(profile, base).accepts(DoviTarget.PROFILE_8_1_PRESERVE_MAPPING) shouldBe false
			}
		}

		DoviSource(profile8, DoviPresentation.HLG).accepts(DoviTarget.PROFILE_8_4) shouldBe true
		listOf(profile5, profile7).forEach { profile ->
			DoviSource(profile, DoviPresentation.HLG).accepts(DoviTarget.PROFILE_8_4) shouldBe false
		}
		pqBases.forEach { base ->
			DoviSource(profile8, base).accepts(DoviTarget.PROFILE_8_4) shouldBe false
		}
		DoviSource(profile8, DoviPresentation.UNKNOWN).accepts(DoviTarget.PROFILE_8_4) shouldBe false

		DoviSource(profile5, DoviPresentation.HDR10).accepts(DoviTarget.SOURCE_BASE_PRESENTATION) shouldBe false
		DoviSource(profile7, DoviPresentation.UNKNOWN).accepts(DoviTarget.SOURCE_BASE_PRESENTATION) shouldBe false
		DoviSource(profile8, DoviPresentation.UNKNOWN).accepts(DoviTarget.SOURCE_BASE_PRESENTATION) shouldBe false
		listOf(profile7, profile8).forEach { profile ->
			listOf(DoviPresentation.HDR10, DoviPresentation.HDR10_PLUS, DoviPresentation.HLG).forEach { base ->
				DoviSource(profile, base).accepts(DoviTarget.SOURCE_BASE_PRESENTATION) shouldBe true
			}
		}
	}

	test("Always never selects native-rejected Profile 5 source-base output") {
		decide(
			mode = DoviCompatibilityMode.ALWAYS,
			source = DoviSource(DoviPresentation.PROFILE_5, DoviPresentation.HDR10),
			device = DoviDeviceCapabilities(supportsHdr10 = true),
		).route shouldBe DoviRoute.ServerFallback
	}

	test("Off leaves supported sources native and unsupported sources to the server") {
		decide(
			mode = DoviCompatibilityMode.OFF,
			device = DoviDeviceCapabilities(supportsProfile7 = true),
		).route shouldBe DoviRoute.Native
		decide(
			mode = DoviCompatibilityMode.OFF,
			device = DoviDeviceCapabilities(),
		).route shouldBe DoviRoute.ServerFallback
	}

	test("built-in Media3 and MPV cover supported MP4 TS HLS and Matroska aliases") {
		listOf(DoviPlaybackBackend.MEDIA3, DoviPlaybackBackend.MPV).forEach { backend ->
			listOf("mp4", "fmp4", "mov", "m4v", "ts", "mpegts", "m2ts", "hls", "mkv", "matroska")
				.forEach { container ->
					decide(backend = backend, container = container).request?.target shouldBe DoviTarget.PROFILE_8_1
				}
		}
	}

	test("unsupported containers backends and unselected built-in players fail closed") {
		decide(container = "avi").route shouldBe DoviRoute.ServerFallback
		decide(builtInPlayerSelected = false).route shouldBe DoviRoute.ServerFallback
		decide(backend = DoviPlaybackBackend.OTHER).route shouldBe DoviRoute.ServerFallback
	}

	test("retry suppression prevents every client transform mode") {
		DoviCompatibilityMode.entries.forEach { mode ->
			val decision = decide(
				mode = mode,
				retrySuppressed = true,
				device = DoviDeviceCapabilities(supportsProfile7 = true, supportsProfile8 = true),
			)
			decision.route shouldBe DoviRoute.ServerFallback
			decision.reason shouldBe DoviDecisionReason.RETRY_SUPPRESSED
		}
	}

	test("impossible AV1 Profile 7 is rejected while other AV1 remains untouched") {
		val invalid = decide(codec = DoviVideoCodec.AV1)
		invalid.route shouldBe DoviRoute.ServerFallback
		invalid.invalidStream shouldBe true
		decide(
			codec = DoviVideoCodec.AV1,
			source = DoviSource(DoviPresentation.PROFILE_8_1),
		).route shouldBe DoviRoute.Native
	}

	test("the exact request is stable after mutable capability input changes") {
		val capabilities = allNativeCapabilities.toMutableSet()
		val decision = decide(capabilities = capabilities)
		capabilities.clear()

		decision.request?.target shouldBe DoviTarget.PROFILE_8_1
		decision.request?.repairs shouldBe emptySet()
	}
})
