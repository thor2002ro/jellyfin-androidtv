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

class HlsVideoCopyPlaylistTests : FunSpec({
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
	test("video copy drops explicitly re-encoded variants") {
		val original = variant(null)
		val filtered = playlist(listOf(original, variant(false))).retainVideoCopyVariants()
		filtered.variants shouldBe listOf(original)
	}
	test("an entirely re-encoded playlist requests existing Dolby recovery") {
		shouldThrow<DoviSampleTransformationException> {
			playlist(listOf(variant(false))).retainVideoCopyVariants(required = true)
		}.status shouldBe DoviStatus.REENCODE_REQUIRED
	}
	test("a generic remux remains playable when the manifest has no copy variant") {
		val original = playlist(listOf(variant(false)))
		(original.retainVideoCopyVariants() === original) shouldBe true
	}
})
