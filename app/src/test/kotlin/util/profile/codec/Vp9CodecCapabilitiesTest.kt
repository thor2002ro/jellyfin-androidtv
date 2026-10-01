package org.jellyfin.androidtv.util.profile.codec

import android.media.MediaCodecInfo.CodecProfileLevel
import android.os.Build
import androidx.media3.common.MimeTypes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.jellyfin.androidtv.util.AndroidVersion

class Vp9CodecCapabilitiesTest : FunSpec({
	val mimeVp9 = MimeTypes.VIDEO_VP9

	beforeEach {
		mockkObject(AndroidVersion)
	}

	afterEach {
		unmockkObject(AndroidVersion)
	}

	test("Main10 accepts each Android Profile 2 variant") {
		every { AndroidVersion.sdkInt } returns Build.VERSION_CODES.Q

		listOf(
			CodecProfileLevel.VP9Profile2,
			CodecProfileLevel.VP9Profile2HDR,
			CodecProfileLevel.VP9Profile2HDR10Plus,
		).forEach { supportedProfile ->
			val query = mockk<MediaCodecQuery> {
				every { hasDecoder(mimeVp9, any(), any()) } answers {
					secondArg<Int>() == supportedProfile && thirdArg<Int>() == CodecProfileLevel.VP9Level1
				}
			}

			Vp9CodecCapabilities(query).supportsVp9Main10() shouldBe true
		}
	}

	test("Main10 rejects the 4:2:2 Profile 3 variants") {
		every { AndroidVersion.sdkInt } returns Build.VERSION_CODES.Q
		val profile3Variants = setOf(
			CodecProfileLevel.VP9Profile3,
			CodecProfileLevel.VP9Profile3HDR,
			CodecProfileLevel.VP9Profile3HDR10Plus,
		)
		val query = mockk<MediaCodecQuery> {
			every { hasCodecForMime(mimeVp9) } returns true
			every { hasDecoder(mimeVp9, any(), any()) } answers {
				secondArg<Int>() in profile3Variants
			}
		}

		Vp9CodecCapabilities(query).supportsVp9Main10() shouldBe false
		Vp9CodecCapabilities(query).supportsVp9() shouldBe false
	}

	test("HDR support requires the Profile 2 HDR variant") {
		every { AndroidVersion.sdkInt } returns Build.VERSION_CODES.Q
		val query = mockk<MediaCodecQuery> {
			every { hasDecoder(mimeVp9, any(), any()) } answers {
				secondArg<Int>() == CodecProfileLevel.VP9Profile2
			}
		}

		Vp9CodecCapabilities(query).supportsVp9Hdr() shouldBe false
	}

	test("HDR10 Plus is not queried before API 29") {
		every { AndroidVersion.sdkInt } returns Build.VERSION_CODES.Q - 1
		val query = mockk<MediaCodecQuery> {
			every { hasDecoder(mimeVp9, any(), any()) } returns true
		}

		Vp9CodecCapabilities(query).supportsVp9Hdr10Plus() shouldBe false
		verify(exactly = 0) {
			query.hasDecoder(mimeVp9, CodecProfileLevel.VP9Profile2HDR10Plus, any())
		}
	}
})
