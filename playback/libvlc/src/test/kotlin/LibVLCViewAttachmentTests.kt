package org.jellyfin.playback.libvlc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class LibVLCViewAttachmentTests : StringSpec({
	"video and subtitle updates are coalesced into one native attachment" {
		val pending = mutableListOf<Runnable>()
		val attachedViews = mutableListOf<Pair<String?, String?>>()
		var videoView: String? = null
		var subtitleView: String? = null
		val scheduler = CoalescingViewAttachScheduler(
			post = pending::add,
			remove = pending::remove,
			attach = { attachedViews += videoView to subtitleView },
		)

		videoView = "video"
		scheduler.schedule()
		subtitleView = "subtitles"
		scheduler.schedule()

		pending.size shouldBe 1
		pending.single().run()
		attachedViews.shouldContainExactly("video" to "subtitles")
	}

	"removing the video view cancels a pending native attachment" {
		val pending = mutableListOf<Runnable>()
		val scheduler = CoalescingViewAttachScheduler(
			post = pending::add,
			remove = pending::remove,
			attach = {},
		)

		scheduler.schedule()
		scheduler.cancel()

		pending shouldBe emptyList()
	}
})
