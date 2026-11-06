package org.jellyfin.androidtv.ui.browsing

import androidx.fragment.app.Fragment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.ui.home.HomeFragment

class MainActivityBackgroundTests : FunSpec({
	test("Home and the library own their backgrounds while other screens keep the activity background") {
		shouldDrawActivityBackground(HomeFragment::class) shouldBe false
		shouldDrawActivityBackground(BrowseGridFragment::class) shouldBe false
		shouldDrawActivityBackground(Fragment::class) shouldBe true
	}
})
