package org.jellyfin.androidtv.ui.browsing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BrowseGridFragmentTests : FunSpec({
	test("safe selected position never returns invalid leanback positions") {
		BrowseGridFragment.getSafeSelectedPosition(-1, -1, -1, 125) shouldBe 0
		BrowseGridFragment.getSafeSelectedPosition(21, 6, 6, 80) shouldBe 21
		BrowseGridFragment.getSafeSelectedPosition(150, -1, -1, 125) shouldBe 124
		BrowseGridFragment.getSafeSelectedPosition(-1, 150, -1, 125) shouldBe 124
		BrowseGridFragment.getSafeSelectedPosition(-1, -1, 150, 125) shouldBe 124
		BrowseGridFragment.getSafeSelectedPosition(-1, -1, -1, 0) shouldBe -1
	}
})
