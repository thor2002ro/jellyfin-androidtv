package org.jellyfin.androidtv.util.profile

import android.util.Size
import io.github.thor2002ro.libdovi.DoviCapability
import io.github.thor2002ro.libdovi.DoviPresentation
import io.github.thor2002ro.libdovi.DoviTarget
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.constant.Codec
import org.jellyfin.androidtv.preference.constant.PlaybackResolution
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.playbackPlayerPreferences
import org.jellyfin.androidtv.preference.constant.DoviCompatibilityMode as AppDoviCompatibilityMode
import org.jellyfin.androidtv.preference.constant.HdrFormat
import org.jellyfin.androidtv.preference.constant.HdrOverrideMode
import org.jellyfin.androidtv.preference.constant.PlaybackBackend
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.dovi.DoviCmv40Workaround
import org.jellyfin.playback.dovi.DoviCompatibilityMode
import org.jellyfin.playback.dovi.DoviDecisionReason
import org.jellyfin.playback.dovi.DoviDeviceCapabilities
import org.jellyfin.playback.dovi.DoviPlaybackBackend
import org.jellyfin.playback.dovi.DoviRoute
import org.jellyfin.playback.dovi.DoviSource
import org.jellyfin.playback.dovi.DoviSourceLayer
import org.jellyfin.playback.dovi.DoviSourceProfile
import org.jellyfin.playback.dovi.DoviTransformEvidence
import org.jellyfin.playback.dovi.DoviVideoCodec
import org.jellyfin.playback.dovi.DoviWorkarounds
import org.jellyfin.playback.dovi.doviDecision
import org.jellyfin.playback.jellyfin.DoviNegotiationOwner
import org.jellyfin.sdk.model.api.VideoRangeType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

class DoviPlaybackSupportTests : FunSpec({
	val nativeCapabilities = DoviCapability.entries.toSet()
	val bridge = DoviBridgeEvidence(true, nativeCapabilities)
	afterTest { unmockkConstructor(Size::class) }

	fun request(
		mode: DoviCompatibilityMode = DoviCompatibilityMode.AUTO,
		backend: DoviPlaybackBackend = DoviPlaybackBackend.MEDIA3,
		codec: DoviVideoCodec = DoviVideoCodec.HEVC,
		container: String = "mkv",
		range: VideoRangeType = VideoRangeType.DOVI_WITH_EL,
		source: DoviSource = DoviSource(
			DoviPresentation.PROFILE_7_FEL,
			DoviPresentation.HDR10,
		),
		device: DoviDeviceCapabilities = DoviDeviceCapabilities(supportsProfile8 = true),
		bridgeEvidence: DoviBridgeEvidence = bridge,
		workarounds: DoviWorkarounds = DoviWorkarounds(),
		builtIn: Boolean = true,
	) = DoviPlaybackRequest(
		mode = mode,
		backend = backend,
		codec = codec,
		container = container,
		sourceRangeType = range,
		source = source,
		device = device,
		bridge = bridgeEvidence,
		workarounds = workarounds,
		builtInPlayerSelected = builtIn,
	)

	test("Auto advertises supported Profile 5 and Profile 7 unchanged") {
		val profile5 = decideDoviPlayback(
			request(
				range = VideoRangeType.DOVI,
				source = DoviSource(DoviPresentation.PROFILE_5),
				device = DoviDeviceCapabilities(supportsProfile5 = true),
			),
		)
		val profile7 = decideDoviPlayback(
			request(device = DoviDeviceCapabilities(supportsProfile7 = true)),
		)

		profile5.decision.route shouldBe DoviRoute.Native
		profile5.advertisedHevcRangeTypes.shouldContainExactly(VideoRangeType.DOVI)
		profile7.decision.route shouldBe DoviRoute.Native
		profile7.advertisedHevcRangeTypes.shouldContainExactly(VideoRangeType.DOVI_WITH_EL)
	}

	test("Auto advertises Profile 5 and Profile 7 conversion only with Profile 8 and native target support") {
		listOf(
			VideoRangeType.DOVI to DoviSource(DoviPresentation.PROFILE_5, DoviPresentation.HDR10),
			VideoRangeType.DOVI_WITH_EL to DoviSource(
				DoviPresentation.PROFILE_7_FEL,
				DoviPresentation.HDR10,
			),
		).forEach { (range, source) ->
			val converted = decideDoviPlayback(request(range = range, source = source))
			converted.decision.request?.target shouldBe DoviTarget.PROFILE_8_1
			converted.advertisedHevcRangeTypes.shouldContainExactly(range)

			val noProfile8 = decideDoviPlayback(
				request(range = range, source = source, device = DoviDeviceCapabilities()),
			)
			noProfile8.decision.route shouldBe DoviRoute.ServerFallback
			noProfile8.advertisedHevcRangeTypes shouldBe emptySet()

			val noTarget = decideDoviPlayback(
				request(
					range = range,
					source = source,
					bridgeEvidence = DoviBridgeEvidence(
						true,
						nativeCapabilities - DoviCapability.PROFILE_8_1,
					),
				),
			)
			noTarget.decision.route shouldBe DoviRoute.ServerFallback
			noTarget.advertisedHevcRangeTypes shouldBe emptySet()
		}
	}

	test("advertised transform retains the exact source facts used by negotiation") {
		val plan = decideDoviPlayback(
			request(
				range = VideoRangeType.DOVI_WITH_ELHDR10_PLUS,
				source = DoviSource(
					DoviPresentation.PROFILE_7_FEL,
					DoviPresentation.HDR10_PLUS,
				),
			),
		)

		plan.decision.transformEvidence shouldBe DoviTransformEvidence(
			inputPresentation = DoviPresentation.PROFILE_7_FEL,
			sourceBasePresentation = DoviPresentation.HDR10_PLUS,
			sourceProfile = DoviSourceProfile.PROFILE_7,
			sourceLayer = DoviSourceLayer.FEL,
		)
	}

	test("Profile 5 without explicit compatible base evidence remains server fallback") {
		val plan = decideDoviPlayback(
			request(range = VideoRangeType.DOVI, source = DoviSource(DoviPresentation.PROFILE_5)),
		)
		plan.decision.route shouldBe DoviRoute.ServerFallback
		plan.advertisedHevcRangeTypes shouldBe emptySet()
	}

	test("Always advertises only routes supported by the selected backend container and bridge") {
		val converted = decideDoviPlayback(
			request(
				mode = DoviCompatibilityMode.ALWAYS,
				backend = DoviPlaybackBackend.MPV,
				device = DoviDeviceCapabilities(supportsProfile7 = true, supportsProfile8 = true),
			),
		)
		converted.decision.request?.target shouldBe DoviTarget.PROFILE_8_1

		listOf(
			request(mode = DoviCompatibilityMode.ALWAYS, container = "avi"),
			request(mode = DoviCompatibilityMode.ALWAYS, backend = DoviPlaybackBackend.OTHER),
			request(mode = DoviCompatibilityMode.ALWAYS, builtIn = false),
			request(mode = DoviCompatibilityMode.ALWAYS, bridgeEvidence = DoviBridgeEvidence(false, emptySet())),
		).forEach { unsupported ->
			val plan = decideDoviPlayback(unsupported)
			plan.decision.route shouldBe DoviRoute.ServerFallback
			plan.advertisedHevcRangeTypes shouldBe emptySet()
		}
	}

	test("Always with unavailable bridge retains server fallback even for reported Profile 7") {
		decideDoviPlayback(request(
			mode = DoviCompatibilityMode.ALWAYS,
			device = DoviDeviceCapabilities(supportsProfile7 = true, supportsProfile8 = true),
			bridgeEvidence = DoviBridgeEvidence(false, emptySet()),
		)).decision.route shouldBe DoviRoute.ServerFallback
	}

	test("Compatibility requires inspection evidence for inspection-dependent repairs") {
		val plan = decideDoviPlayback(
			request(
				mode = DoviCompatibilityMode.COMPATIBILITY,
				workarounds = DoviWorkarounds(cmv40 = DoviCmv40Workaround.REMOVE),
			),
		)

		plan.decision.reason shouldBe DoviDecisionReason.INSPECTION_REQUIRED
		plan.decision.route shouldBe DoviRoute.ServerFallback
		plan.advertisedHevcRangeTypes shouldBe emptySet()
	}

	test("Compatibility advertises FEL to MEL only with positive MEL and negative FEL evidence") {
		val melPlan = decideDoviPlayback(
			request(
				mode = DoviCompatibilityMode.COMPATIBILITY,
				device = DoviDeviceCapabilities(
					supportsProfile7 = true,
					supportsMel = true,
					supportsFel = false,
				),
			),
		)
		melPlan.decision.request?.target shouldBe DoviTarget.MEL
		melPlan.advertisedHevcRangeTypes.shouldContainExactly(VideoRangeType.DOVI_WITH_EL)

		val noLayerEvidence = decideDoviPlayback(
			request(
				mode = DoviCompatibilityMode.COMPATIBILITY,
				device = DoviDeviceCapabilities(supportsProfile7 = true),
				bridgeEvidence = DoviBridgeEvidence(
					true,
					nativeCapabilities - DoviCapability.PROFILE_8_1 - DoviCapability.SOURCE_BASE_PRESENTATION,
				),
			),
		)
		noLayerEvidence.decision.route shouldBe DoviRoute.ServerFallback
		noLayerEvidence.advertisedHevcRangeTypes shouldBe emptySet()

		val unknownSourceLayer = decideDoviPlayback(
			request(
				mode = DoviCompatibilityMode.COMPATIBILITY,
				source = DoviSource(
					profile = DoviSourceProfile.PROFILE_7,
					layer = DoviSourceLayer.UNKNOWN,
					sourceBasePresentation = DoviPresentation.HDR10,
				),
				device = DoviDeviceCapabilities(supportsProfile7 = true, supportsMel = true),
				bridgeEvidence = DoviBridgeEvidence(
					true,
					nativeCapabilities - DoviCapability.PROFILE_8_1 - DoviCapability.SOURCE_BASE_PRESENTATION,
				),
			),
		)
		unknownSourceLayer.decision.route shouldBe DoviRoute.ServerFallback
	}

	test("AV1 Profile 10 reporting never advertises a video transformation") {
		val plan = decideDoviPlayback(
			request(
				codec = DoviVideoCodec.AV1,
				range = VideoRangeType.DOVI_WITH_HDR10,
				source = DoviSource(DoviPresentation.UNKNOWN),
			),
		)

		plan.decision.route shouldBe DoviRoute.Native
		plan.decision.request shouldBe null
		plan.advertisedHevcRangeTypes shouldBe emptySet()
	}

	test("device profile removes only the exact selected HEVC range and leaves AV1 reporting unchanged") {
		mockkConstructor(Size::class)
		every { anyConstructed<Size>().width } returns 3840
		every { anyConstructed<Size>().height } returns 2160
		val size = mockk<Size> {
			every { width } returns 3840
			every { height } returns 2160
		}
		val mediaTest = mockk<MediaCodecCapabilitiesTest>(relaxed = true) {
			every { getMaxResolution(any()) } returns size
		}
		val plan = decideDoviPlayback(request())

		fun profile(
			doviPlan: DoviPlaybackPlan?,
			forceDisabled: Set<VideoRangeType> = emptySet(),
			capabilities: MediaCodecCapabilitiesTest = mediaTest,
		) = createDeviceProfile(
			mediaTest = capabilities,
			maxBitrate = 100_000_000,
			maxResolution = PlaybackResolution.NATIVE,
			isAC3PrefEnabled = true,
			isEAC3PrefEnabled = true,
			isDTSPrefEnabled = true,
			isTrueHDPrefEnabled = true,
			downMixAudio = false,
			assDirectPlay = true,
			pgsDirectPlay = true,
			userAVCLevel = null,
			userHEVCLevel = null,
			forceEnabledHdr = emptySet(),
			forceDisabledHdr = forceDisabled,
			doviPlaybackPlan = doviPlan,
		)

		fun DeviceProfile.unsupportedRanges(codec: String): Set<String> = codecProfiles
			.asSequence()
			.filter { it.codec == codec }
			.flatMap { it.applyConditions.asSequence() }
			.filter { it.property == ProfileConditionValue.VIDEO_RANGE_TYPE }
			.flatMap { it.value.orEmpty().split('|').asSequence() }
			.filter(String::isNotEmpty)
			.toSet()

		val baseline = profile(null)
		val advertised = profile(plan)
		val selected = VideoRangeType.DOVI_WITH_EL.serialName
		val sibling = VideoRangeType.DOVI_WITH_ELHDR10_PLUS.serialName

		baseline.unsupportedRanges(Codec.Video.HEVC).contains(selected) shouldBe true
		advertised.unsupportedRanges(Codec.Video.HEVC).contains(selected) shouldBe false
		advertised.unsupportedRanges(Codec.Video.HEVC).contains(sibling) shouldBe true
		advertised.unsupportedRanges(Codec.Video.AV1) shouldBe baseline.unsupportedRanges(Codec.Video.AV1)

		val fallback = decideDoviPlayback(
			request(
				device = DoviDeviceCapabilities(),
				bridgeEvidence = DoviBridgeEvidence(false, emptySet()),
			),
		)
		val supportedMediaTest = mockk<MediaCodecCapabilitiesTest>(relaxed = true) {
			every { getMaxResolution(any()) } returns size
			every { supportsHevcDolbyVisionProfile7() } returns true
		}
		profile(fallback, capabilities = supportedMediaTest)
			.unsupportedRanges(Codec.Video.HEVC).contains(selected) shouldBe true
		profile(plan, setOf(VideoRangeType.DOVI_WITH_EL))
			.unsupportedRanges(Codec.Video.HEVC).contains(selected) shouldBe false
	}

	test("binding a new request clears a stale queue-entry decision") {
		val entry = QueueEntry()
		val firstPlan = decideDoviPlayback(request())
		entry.bindDoviPlaybackPlan(firstPlan)
		entry.doviDecision shouldBe firstPlan.decision

		entry.bindDoviPlaybackPlan(null)
		entry.doviDecision shouldBe null
	}

	test("selected source mismatch clears the exact queued decision") {
		val entry = QueueEntry()
		val plan = decideDoviPlayback(request()).copy(mediaSourceId = "source-a")
		entry.bindDoviPlaybackPlan(plan)
		val mismatchedSource = doviMediaSource("source-b", "mkv")
		entry.retainDoviPlaybackPlanFor(plan, mismatchedSource, plan.decision)
		entry.doviDecision shouldBe null
	}

	test("ambiguous media sources require an explicit source id") {
		val first = doviMediaSource("a", "mkv")
		val second = doviMediaSource("b", "mp4")
		val item = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE, mediaSources = listOf(first, second))

		item.findDoviVideo(null) shouldBe null
		item.findDoviVideo("b")?.first shouldBe second
		BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE, mediaSources = listOf(first))
			.findDoviVideo(null)?.first shouldBe first
	}

	test("production stream parsing keeps Profile 5 base unknown and Profile 7 layer unknown") {
		val profile5 = doviMediaSource("p5", "mp4", VideoRangeType.DOVI, dvProfile = 5)
			.mediaStreams.orEmpty().single().toDoviSource()
		profile5.profile shouldBe DoviSourceProfile.PROFILE_5
		profile5.sourceBasePresentation shouldBe DoviPresentation.UNKNOWN

		val profile7 = doviMediaSource("p7", "mkv", VideoRangeType.DOVI_WITH_EL, dvProfile = 7, dvLevel = 9)
			.mediaStreams.orEmpty().single().toDoviSource()
		profile7.profile shouldBe DoviSourceProfile.PROFILE_7
		profile7.layer shouldBe DoviSourceLayer.UNKNOWN
		profile7.dvLevel shouldBe 9
		decideDoviPlayback(request(
			source = profile7,
			device = DoviDeviceCapabilities(supportsProfile7 = true),
		)).decision.route shouldBe DoviRoute.Native
	}

	test("Profile 7 production metadata without a compatibility id converts to Profile 8.1") {
		val source = doviMediaSource("p7", "mkv", VideoRangeType.DOVI_WITH_EL, dvProfile = 7)
			.mediaStreams.orEmpty().single().toDoviSource()

		decideDoviPlayback(request(
			source = source,
			device = DoviDeviceCapabilities(supportsProfile8 = true),
		)).decision.request?.target shouldBe DoviTarget.PROFILE_8_1
	}

	test("Profile 5 production metadata converts only with explicit compatibility id base evidence") {
		fun plan(compatibilityId: Int?, range: VideoRangeType = VideoRangeType.DOVI): DoviPlaybackPlan {
			val source = doviMediaSource(
				id = "p5",
				container = "mp4",
				range = range,
				dvProfile = 5,
				compatibilityId = compatibilityId,
			).mediaStreams.orEmpty().single().toDoviSource()
			return decideDoviPlayback(request(
				range = range,
				source = source,
				device = DoviDeviceCapabilities(supportsProfile8 = true),
			))
		}

		plan(null).decision.route shouldBe DoviRoute.ServerFallback
		plan(0).decision.route shouldBe DoviRoute.ServerFallback
		plan(1).decision.request?.target shouldBe DoviTarget.PROFILE_8_1
		// HDR10+ range evidence is more specific than the HDR10 compatibility id.
		plan(1, VideoRangeType.DOVI_WITH_HDR10_PLUS).decision.request?.target shouldBe DoviTarget.PROFILE_8_1
	}

	test("create plan uses Profile 5 compatibility metadata and rejects absent base") {
		val preferences = mockk<UserPreferences>()
		val videoPlayer = UserPreferences.playbackPlayerPreferences(hdr = false)
		val hdrPlayer = UserPreferences.playbackPlayerPreferences(hdr = true)
		every { preferences[hdrPlayer.playbackBackend] } returns PlaybackBackend.SAME_VIDEO_PLAYER
		every { preferences[UserPreferences.doviCompatibilityMode] } returns AppDoviCompatibilityMode.AUTO
		every { preferences[videoPlayer.useExternalPlayer] } returns false
		every { preferences[videoPlayer.playbackRewriteVideoEnabled] } returns true
		every { preferences[videoPlayer.playbackBackend] } returns PlaybackBackend.EXOPLAYER
		HdrFormat.entries.forEach { format ->
			every { preferences[format.preference] } returns HdrOverrideMode.AUTO
		}
		val mediaTest = mockk<MediaCodecCapabilitiesTest>(relaxed = true) {
			every { supportsHevcDolbyVisionProfile8() } returns true
		}
		fun create(compatibilityId: Int?, range: VideoRangeType = VideoRangeType.DOVI) =
			createDoviPlaybackPlan(
				item = BaseItemDto(
					id = UUID.randomUUID(),
					type = BaseItemKind.MOVIE,
					mediaSources = listOf(doviMediaSource(
						id = "p5",
						container = "mp4",
						range = range,
						dvProfile = 5,
						compatibilityId = compatibilityId,
					)),
				),
				mediaSourceId = "p5",
				userPreferences = preferences,
				mediaTest = mediaTest,
				retrySuppressed = false,
				bridge = bridge,
				displayHdrTypes = setOf(DISPLAY_HDR_TYPE_HDR10, DISPLAY_HDR_TYPE_HDR10_PLUS),
			)

		create(null)?.decision?.route shouldBe DoviRoute.ServerFallback
		create(0)?.decision?.route shouldBe DoviRoute.ServerFallback
		create(1)?.decision?.request?.target shouldBe DoviTarget.PROFILE_8_1
		create(1, VideoRangeType.DOVI_WITH_HDR10_PLUS)?.decision?.request?.target shouldBe DoviTarget.PROFILE_8_1
	}

	test("disabling native Profile 7 still permits its HDR10 base route") {
		val preferences = mockk<UserPreferences>()
		val hdrPlayer = UserPreferences.playbackPlayerPreferences(hdr = true)
		every { preferences[UserPreferences.doviCompatibilityMode] } returns AppDoviCompatibilityMode.AUTO
		every { preferences[hdrPlayer.useExternalPlayer] } returns false
		every { preferences[hdrPlayer.playbackRewriteVideoEnabled] } returns true
		every { preferences[hdrPlayer.playbackBackend] } returns PlaybackBackend.MPV
		HdrFormat.entries.forEach { format ->
			every { preferences[format.preference] } returns if (format == HdrFormat.DOVI_PROFILE_7) {
				HdrOverrideMode.DISABLE
			} else {
				HdrOverrideMode.AUTO
			}
		}
		val plan = createDoviPlaybackPlan(
			item = BaseItemDto(
				id = UUID.randomUUID(),
				type = BaseItemKind.MOVIE,
				mediaSources = listOf(doviMediaSource(
					id = "p7",
					container = "mkv",
					range = VideoRangeType.DOVI_WITH_EL,
					dvProfile = 7,
					compatibilityId = 6,
				)),
			),
			mediaSourceId = "p7",
			userPreferences = preferences,
			mediaTest = mockk(relaxed = true),
			retrySuppressed = false,
			bridge = bridge,
			displayHdrTypes = setOf(DISPLAY_HDR_TYPE_HDR10),
		)

		plan?.decision?.route shouldBe DoviRoute.SourceBase(
			io.github.thor2002ro.libdovi.DoviTransformRequest(DoviTarget.SOURCE_BASE_PRESENTATION),
		)
	}

	test("expected decision mismatch clears a returned source match") {
		val entry = QueueEntry()
		val plan = decideDoviPlayback(request()).copy(mediaSourceId = "source-a")
		entry.bindDoviPlaybackPlan(plan)
		val otherDecision = decideDoviPlayback(
			request(device = DoviDeviceCapabilities(supportsProfile7 = true)),
		).decision
		entry.retainDoviPlaybackPlanFor(plan, doviMediaSource("source-a", "mkv"), otherDecision)
		entry.doviDecision shouldBe null
	}

	test("source-base fallback requires effective HDR output support") {
		val source = DoviSource(DoviPresentation.PROFILE_7_FEL, DoviPresentation.HDR10)
		val noDisplay = decideDoviPlayback(
			request(
				mode = DoviCompatibilityMode.ALWAYS,
				source = source,
				device = DoviDeviceCapabilities(supportsHdr10 = false),
			),
		)
		noDisplay.decision.route shouldBe DoviRoute.ServerFallback

		val supportedDisplay = decideDoviPlayback(
			request(
				mode = DoviCompatibilityMode.ALWAYS,
				source = source,
				device = DoviDeviceCapabilities(supportsHdr10 = true),
			),
		)
		supportedDisplay.decision.route shouldBe DoviRoute.SourceBase(
			io.github.thor2002ro.libdovi.DoviTransformRequest(DoviTarget.SOURCE_BASE_PRESENTATION),
		)
	}

	test("source-base playback remains attemptable when decoder HDR reporting is false") {
		val mediaTest = mockk<MediaCodecCapabilitiesTest>(relaxed = true)
		val capabilities = effectiveDoviDeviceCapabilities(
			mediaTest,
			enabled = emptySet(),
			disabled = emptySet(),
			displayHdrTypes = setOf(
				DISPLAY_HDR_TYPE_HDR10,
				DISPLAY_HDR_TYPE_HDR10_PLUS,
				DISPLAY_HDR_TYPE_HLG,
			),
		)

		capabilities.supportsHdr10 shouldBe true
		capabilities.supportsHdr10Plus shouldBe true
		capabilities.supportsHlg shouldBe true
		decideDoviPlayback(request(
			source = DoviSource(DoviPresentation.PROFILE_7_FEL, DoviPresentation.HDR10),
			device = capabilities,
		)).decision.route shouldBe DoviRoute.SourceBase(
			io.github.thor2002ro.libdovi.DoviTransformRequest(DoviTarget.SOURCE_BASE_PRESENTATION),
		)
	}

	test("selected optimistic Dolby Vision route advertises HEVC Main 10 to Jellyfin") {
		mockkConstructor(Size::class)
		every { anyConstructed<Size>().width } returns 0
		every { anyConstructed<Size>().height } returns 0
		val mediaTest = mockk<MediaCodecCapabilitiesTest>(relaxed = true) {
			every { getMaxResolution(any()) } returns Size(0, 0)
			every { supportsHevc() } returns false
			every { supportsHevcMain10() } returns false
		}
		val plan = decideDoviPlayback(request(
			mode = DoviCompatibilityMode.ALWAYS,
			device = DoviDeviceCapabilities(supportsHdr10 = true),
		))
		val profile = createDeviceProfile(
			mediaTest = mediaTest,
			maxBitrate = 100_000_000,
			maxResolution = PlaybackResolution.NATIVE,
			isAC3PrefEnabled = true,
			isEAC3PrefEnabled = true,
			isDTSPrefEnabled = true,
			isTrueHDPrefEnabled = true,
			downMixAudio = false,
			assDirectPlay = true,
			pgsDirectPlay = true,
			userAVCLevel = null,
			userHEVCLevel = null,
			forceEnabledHdr = emptySet(),
			forceDisabledHdr = emptySet(),
			doviPlaybackPlan = plan,
		)

		profile.codecProfiles
			.asSequence()
			.filter { it.codec == Codec.Video.HEVC }
			.flatMap { it.conditions.asSequence() }
			.filter { it.property == ProfileConditionValue.VIDEO_PROFILE }
			.flatMap { it.value.orEmpty().split('|').asSequence() }
			.any { it == "main 10" } shouldBe true
		profile.codecProfiles
			.asSequence()
			.filter { it.codec == Codec.Video.HEVC }
			.flatMap { it.conditions.asSequence() }
			.none { condition ->
				condition.property == ProfileConditionValue.WIDTH ||
					condition.property == ProfileConditionValue.HEIGHT
			} shouldBe true
	}

	test("selected optimistic Dolby Vision route preserves the explicit HEVC resolution limit") {
		mockkConstructor(Size::class)
		every { anyConstructed<Size>().width } returns 0
		every { anyConstructed<Size>().height } returns 0
		val mediaTest = mockk<MediaCodecCapabilitiesTest>(relaxed = true) {
			every { getMaxResolution(any()) } returns Size(0, 0)
			every { supportsHevc() } returns false
			every { supportsHevcMain10() } returns false
		}
		val plan = decideDoviPlayback(request(
			mode = DoviCompatibilityMode.ALWAYS,
			device = DoviDeviceCapabilities(supportsHdr10 = true),
		))
		val profile = createDeviceProfile(
			mediaTest = mediaTest,
			maxBitrate = 100_000_000,
			maxResolution = PlaybackResolution.FULL_HD_1080,
			isAC3PrefEnabled = true,
			isEAC3PrefEnabled = true,
			isDTSPrefEnabled = true,
			isTrueHDPrefEnabled = true,
			downMixAudio = false,
			assDirectPlay = true,
			pgsDirectPlay = true,
			userAVCLevel = null,
			userHEVCLevel = null,
			forceEnabledHdr = emptySet(),
			forceDisabledHdr = emptySet(),
			doviPlaybackPlan = plan,
		)

		profile.codecProfiles
			.asSequence()
			.filter { it.codec == Codec.Video.HEVC }
			.flatMap { it.conditions.asSequence() }
			.filter { condition ->
				condition.property == ProfileConditionValue.WIDTH ||
					condition.property == ProfileConditionValue.HEIGHT
			}
			.associate { it.property to it.value } shouldBe mapOf(
			ProfileConditionValue.WIDTH to "1920",
			ProfileConditionValue.HEIGHT to "1080",
		)
	}

	test("Main10 decoding keeps source-base playback attemptable on SDR displays for both backends") {
		val mediaTest = mockk<MediaCodecCapabilitiesTest>(relaxed = true) {
			every { supportsHevcMain10() } returns true
		}
		val capabilities = effectiveDoviDeviceCapabilities(
			mediaTest,
			enabled = emptySet(),
			disabled = emptySet(),
			displayHdrTypes = emptySet(),
		)

		listOf(
			DoviPresentation.HDR10 to DoviDeviceCapabilities::supportsHdr10,
			DoviPresentation.HDR10_PLUS to DoviDeviceCapabilities::supportsHdr10Plus,
			DoviPresentation.HLG to DoviDeviceCapabilities::supportsHlg,
		).forEach { (basePresentation, isSupported) ->
			isSupported(capabilities) shouldBe true
			DoviPlaybackBackend.entries
				.filter { it != DoviPlaybackBackend.OTHER }
				.forEach { backend ->
					decideDoviPlayback(request(
						mode = DoviCompatibilityMode.ALWAYS,
						backend = backend,
						source = DoviSource(DoviPresentation.PROFILE_7_FEL, basePresentation),
						device = capabilities,
					)).decision.route shouldBe DoviRoute.SourceBase(
						io.github.thor2002ro.libdovi.DoviTransformRequest(DoviTarget.SOURCE_BASE_PRESENTATION),
					)
				}
		}
	}

	test("HLG capability requires positive decoder format and display evidence") {
		val mediaTest = mockk<MediaCodecCapabilitiesTest>(relaxed = true) {
			every { supportsHevcDolbyVisionProfile8() } returns true
			every { supportsHevcHlg() } returns true
		}
		val positive = effectiveDoviDeviceCapabilities(
			mediaTest,
			enabled = emptySet(),
			disabled = emptySet(),
			displayHdrTypes = setOf(DISPLAY_HDR_TYPE_HLG),
		)
		positive.supportsHlg shouldBe true
		positive.supportsProfile84 shouldBe true
		val negative = effectiveDoviDeviceCapabilities(
			mediaTest,
			enabled = emptySet(),
			disabled = emptySet(),
			displayHdrTypes = emptySet(),
		)
		negative.supportsHlg shouldBe false
		negative.supportsProfile84 shouldBe false

		val hlgSource = DoviSource(DoviPresentation.PROFILE_8_4, DoviPresentation.HLG)
		decideDoviPlayback(request(
			mode = DoviCompatibilityMode.COMPATIBILITY,
			range = VideoRangeType.DOVI_WITH_HLG,
			source = hlgSource,
			device = positive,
		)).decision.route shouldBe DoviRoute.Native
		decideDoviPlayback(request(
			mode = DoviCompatibilityMode.COMPATIBILITY,
			range = VideoRangeType.DOVI_WITH_HLG,
			source = hlgSource,
			device = negative,
		)).decision.route shouldBe DoviRoute.ServerFallback
	}

	test("stale negotiation token cannot bind or clear a newer decision") {
		val store = DoviPlaybackNegotiationStore()
		val entry = QueueEntry()
		val first = decideDoviPlayback(request()).copy(mediaSourceId = "source-a")
		val second = decideDoviPlayback(
			request(device = DoviDeviceCapabilities(supportsProfile7 = true)),
		).copy(mediaSourceId = "source-a")
		val firstToken = store.begin(entry, first)
		val secondToken = store.begin(entry, second)

		store.complete(entry, firstToken, doviMediaSource("source-a", "mkv"), first.decision) shouldBe false
		entry.doviDecision shouldBe second.decision
		store.cancel(entry, firstToken) shouldBe false
		entry.doviDecision shouldBe second.decision
		store.complete(entry, secondToken, doviMediaSource("source-a", "mkv"), second.decision) shouldBe true
		entry.doviDecision shouldBe second.decision
	}

	test("begin atomically replaces token and visible decision") {
		val store = DoviPlaybackNegotiationStore()
		val entry = QueueEntry()
		val first = decideDoviPlayback(request())
		val second = decideDoviPlayback(request(device = DoviDeviceCapabilities(supportsProfile7 = true)))
		val firstToken = store.begin(entry, first)
		val secondToken = store.begin(entry, second)

		entry.doviDecision shouldBe second.decision
		store.cancel(entry, firstToken) shouldBe false
		entry.doviDecision shouldBe second.decision
		store.cancel(entry, secondToken) shouldBe true
		entry.doviDecision shouldBe null
	}

	test("throwing profile construction never creates pending negotiation state") {
		val store = DoviPlaybackNegotiationStore()
		val entry = QueueEntry()
		val result = runCatching {
			store.prepare(entry, decideDoviPlayback(request())) {
				error("profile failed")
			}
		}
		result.isFailure shouldBe true
		store.hasPending(entry) shouldBe false
		entry.doviDecision shouldBe null
	}

	test("negotiation tokens are independent across queue entries") {
		val store = DoviPlaybackNegotiationStore()
		val firstEntry = QueueEntry()
		val secondEntry = QueueEntry()
		val plan = decideDoviPlayback(request()).copy(mediaSourceId = "source-a")
		val firstToken = store.begin(firstEntry, plan)
		val secondToken = store.begin(secondEntry, plan)

		store.cancel(firstEntry, firstToken) shouldBe true
		store.complete(secondEntry, secondToken, doviMediaSource("source-a", "mkv"), plan.decision) shouldBe true
		secondEntry.doviDecision shouldBe plan.decision
	}

	test("only direct play and remux retain a negotiated decision") {
		MediaConversionMethod.None.retainsDoviDecision() shouldBe true
		MediaConversionMethod.Remux.retainsDoviDecision() shouldBe true
		MediaConversionMethod.Transcode.retainsDoviDecision() shouldBe false
		(null as MediaConversionMethod?).retainsDoviDecision() shouldBe false
	}

	test("stream construction exception cancels the owned token") {
		val store = DoviPlaybackNegotiationStore()
		val entry = QueueEntry()
		val plan = decideDoviPlayback(request()).copy(mediaSourceId = "source-a")
		val token = store.begin(entry, plan)
		val owner = DoviNegotiationOwner(entry, token, plan.decision) { queued, request, source, method, expected ->
			if (source == null || !method.retainsDoviDecision()) store.cancel(queued, request)
			else store.complete(queued, request, source, expected)
		}

		var failed = false
		try {
			owner.run<Unit> { error("URL construction failed") }
		} catch (_: IllegalStateException) {
			failed = true
		}
		failed shouldBe true
		store.hasPending(entry) shouldBe false
		entry.doviDecision shouldBe null
	}

	test("exception from stale owner cannot clear a newer request") {
		val store = DoviPlaybackNegotiationStore()
		val entry = QueueEntry()
		val first = decideDoviPlayback(request())
		val second = decideDoviPlayback(request(device = DoviDeviceCapabilities(supportsProfile7 = true)))
		val staleToken = store.begin(entry, first)
		val owner = DoviNegotiationOwner(entry, staleToken, first.decision) { queued, request, _, _, _ ->
			store.cancel(queued, request)
		}
		val newestToken = store.begin(entry, second)

		try {
			owner.run<Unit> { error("track construction failed") }
		} catch (_: IllegalStateException) {
			// Expected construction failure; the assertion below verifies token isolation.
		}
		store.hasPending(entry) shouldBe true
		entry.doviDecision shouldBe second.decision
		store.cancel(entry, newestToken) shouldBe true
	}
})

private fun doviMediaSource(
	id: String,
	container: String,
	range: VideoRangeType = VideoRangeType.DOVI_WITH_EL,
	dvProfile: Int? = 7,
	dvLevel: Int? = null,
	compatibilityId: Int? = null,
) = MediaSourceInfo(
	protocol = MediaProtocol.FILE,
	type = MediaSourceType.DEFAULT,
	id = id,
	container = container,
	isRemote = false,
	readAtNativeFramerate = false,
	ignoreDts = false,
	ignoreIndex = false,
	genPtsInput = false,
	supportsTranscoding = true,
	supportsDirectStream = true,
	supportsDirectPlay = true,
	isInfiniteStream = false,
	requiresOpening = false,
	requiresClosing = false,
	requiresLooping = false,
	supportsProbing = false,
	mediaStreams = listOf(MediaStream(
		codec = "hevc",
		isInterlaced = false,
		isDefault = true,
		isForced = false,
		isHearingImpaired = false,
		type = MediaStreamType.VIDEO,
		index = 0,
		isExternal = false,
		isTextSubtitleStream = false,
		supportsExternalStream = false,
		videoRangeType = range,
		dvProfile = dvProfile,
		dvLevel = dvLevel,
		dvBlSignalCompatibilityId = compatibilityId,
	)),
	transcodingSubProtocol = MediaStreamProtocol.HTTP,
	defaultSubtitleStreamIndex = -1,
	hasSegments = false,
)
