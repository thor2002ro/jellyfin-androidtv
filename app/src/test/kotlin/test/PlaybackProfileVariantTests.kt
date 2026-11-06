package test

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.test.PlaybackProfileVariant
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile

class PlaybackProfileVariantTests : FunSpec({
	val production = DeviceProfile(
		name = "production",
		directPlayProfiles = listOf(
			DirectPlayProfile("mp4", "aac", "h264", DlnaProfileType.VIDEO),
		),
		transcodingProfiles = emptyList(),
		containerProfiles = emptyList(),
		codecProfiles = emptyList(),
		subtitleProfiles = listOf(SubtitleProfile("ass", SubtitleDeliveryMethod.EXTERNAL)),
	)

	test("production and direct variants preserve the profile") {
		PlaybackProfileVariant.PRODUCTION.configure(production).profile shouldBe production
		PlaybackProfileVariant.DIRECT.configure(production).profile shouldBe production
	}

	test("remux rejects only the source container while preserving codec declarations") {
		val configured = PlaybackProfileVariant.REMUX.configure(production, "mp4")

		configured.profile.directPlayProfiles.single().container shouldBe ""
		configured.profile.directPlayProfiles.single().videoCodec shouldBe "h264"
		configured.profile.subtitleProfiles shouldBe production.subtitleProfiles
		configured.forceTranscoding shouldBe false
	}

	test("video transcode disables stream copy through the resolver") {
		val configured = PlaybackProfileVariant.VIDEO_TRANSCODE.configure(production)

		configured.forceTranscoding shouldBe true
	}

	test("video profile transcode rejects only the selected profile while preserving codec support") {
		val configured = PlaybackProfileVariant.VIDEO_PROFILE_TRANSCODE.configure(
			production = production,
			sourceContainer = "mp4",
			sourceVideoCodec = "h264",
			sourceVideoProfile = "high 10",
		)

		configured.forceTranscoding shouldBe false
		configured.profile.directPlayProfiles shouldBe production.directPlayProfiles
		configured.profile.codecProfiles.single().run {
			codec shouldBe "h264"
			conditions.single().run {
				condition shouldBe ProfileConditionType.NOT_EQUALS
				property shouldBe ProfileConditionValue.VIDEO_PROFILE
				value shouldBe "high 10"
				isRequired shouldBe true
			}
		}
	}

	test("video profile transcode advertises the selected codec when production omits it") {
		val configured = PlaybackProfileVariant.VIDEO_PROFILE_TRANSCODE.configure(
			production = production,
			sourceContainer = "mkv",
			sourceVideoCodec = "mpeg4",
			sourceVideoProfile = "advanced simple profile",
		)

		configured.profile.directPlayProfiles.any { directPlay ->
			directPlay.type == DlnaProfileType.VIDEO &&
				directPlay.container == "mkv" &&
				directPlay.videoCodec == "mpeg4"
		} shouldBe true
	}

	test("audio transcode keeps video direct compatibility but rejects source audio") {
		val configured = PlaybackProfileVariant.AUDIO_TRANSCODE.configure(production)

		configured.profile.directPlayProfiles.mapNotNull { it.audioCodec } shouldContainOnly listOf("__playback_test_unsupported__")
		configured.forceTranscoding shouldBe false
	}

	test("subtitle transcode changes only subtitle delivery to encode") {
		val configured = PlaybackProfileVariant.SUBTITLE_TRANSCODE.configure(production)

		configured.profile.subtitleProfiles.map(SubtitleProfile::method) shouldContainOnly listOf(SubtitleDeliveryMethod.ENCODE)
		configured.burnSubtitles shouldBe true
	}
})
