package org.jellyfin.playback.exoplayer.dovi

import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import io.github.thor2002ro.libdovi.DoviStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class DoviHlsPlaylistTests : FunSpec({
	fun variant(copy: Boolean?): HlsMultivariantPlaylist.Variant {
		val uri = mockk<Uri> {
			every { queryParameterNames } returns if (copy == null) emptySet() else setOf("allowVideoStreamCopy")
			every { getQueryParameters("allowVideoStreamCopy") } returns listOf(copy.toString())
		}
		return HlsMultivariantPlaylist.Variant(uri, Format.Builder().build(), null, null, null, null, null, null)
	}
	fun playlist(variants: List<HlsMultivariantPlaylist.Variant>) = HlsMultivariantPlaylist(
		"https://server/Videos/item/master.m3u8", emptyList(), variants, emptyList(), emptyList(), emptyList(), emptyList(),
		null, emptyList(), true, emptyMap(), emptyList(), null,
	)
	test("local Dolby conversion cannot adapt to an SDR server re-encode") {
		val original = variant(null)
		val filtered = playlist(listOf(original, variant(false))).retainDoviVideoCopyVariants()
		filtered.variants shouldBe listOf(original)
	}
	test("unchanged stream-copy playlists retain their original object") {
		val original = playlist(listOf(variant(null), variant(true)))
		(original.retainDoviVideoCopyVariants() === original) shouldBe true
	}
	test("filtering video variants preserves audio subtitles captions and manifest metadata") {
		val audio = listOf(mockk<HlsMultivariantPlaylist.Rendition>(relaxed = true))
		val subtitles = listOf(mockk<HlsMultivariantPlaylist.Rendition>(relaxed = true))
		val captions = listOf(mockk<HlsMultivariantPlaylist.Rendition>(relaxed = true))
		val original = HlsMultivariantPlaylist(
			"https://server/master.m3u8", listOf("#EXT-X-VERSION:7"), listOf(variant(true), variant(false)), emptyList(),
			audio, subtitles, captions, Format.Builder().build(), emptyList(), true, mapOf("a" to "b"), emptyList(), null,
		)
		val filtered = original.retainDoviVideoCopyVariants()
		filtered.audios shouldBe audio
		filtered.subtitles shouldBe subtitles
		filtered.closedCaptions shouldBe captions
		filtered.tags shouldBe original.tags
		filtered.variableDefinitions shouldBe original.variableDefinitions
		filtered.muxedAudioFormat shouldBe original.muxedAudioFormat
	}
	test("an entirely re-encoded playlist requests existing Dolby recovery") {
		shouldThrow<DoviSampleTransformationException> {
			playlist(listOf(variant(false))).retainDoviVideoCopyVariants()
		}.status shouldBe DoviStatus.REENCODE_REQUIRED
	}
})
