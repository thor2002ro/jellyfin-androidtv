package org.jellyfin.androidtv.util.profile.codec

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MediaCodecQueryTests : FunSpec({
	test("pre Android 10 software video decoders are recognized by codec name") {
		listOf(
			"OMX.google.h264.decoder",
			"OMX.ffmpeg.video.decoder",
			"OMX.SEC.avc.sw.dec",
			"OMX.qcom.video.decoder.hevcswvdec",
			"c2.android.hevc.decoder",
			"c2.google.av1.decoder",
			"software.custom.decoder",
		).forEach { codecName ->
			isSoftwareVideoCodecName(codecName) shouldBe true
		}
	}

	test("pre Android 10 hardware video decoders remain available") {
		listOf(
			"OMX.MTK.VIDEO.DECODER.HEVC",
			"OMX.qcom.video.decoder.hevc",
			"c2.amlogic.hevc.decoder",
			"arc.video.decoder",
		).forEach { codecName ->
			isSoftwareVideoCodecName(codecName) shouldBe false
		}
	}
})
