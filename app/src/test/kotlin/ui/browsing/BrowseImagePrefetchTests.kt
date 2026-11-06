package org.jellyfin.androidtv.ui.browsing

import coil3.request.Disposable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class BrowseImagePrefetchTests : FunSpec({
	test("changing the prefetch window cancels discarded requests and retains overlap") {
		var obsoleteCancelled = false
		var retainedCancelled = false
		val obsolete = mockk<Disposable> { every { dispose() } answers { obsoleteCancelled = true } }
		val retained = mockk<Disposable> { every { dispose() } answers { retainedCancelled = true } }
		val requests = mutableMapOf("obsolete" to obsolete, "retained" to retained)
		BrowseGridFragment.cancelObsoleteImagePrefetches(requests, setOf("retained", "next"))
		obsoleteCancelled shouldBe true
		retainedCancelled shouldBe false
		requests.keys shouldBe setOf("retained")
		BrowseGridFragment.cancelObsoleteImagePrefetches(requests, emptySet())
		retainedCancelled shouldBe true
		requests.isEmpty() shouldBe true
	}
})
