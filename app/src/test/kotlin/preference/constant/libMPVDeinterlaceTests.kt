package org.jellyfin.androidtv.preference.constant

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class LibMPVDeinterlaceTests : StringSpec({
	"deinterlacing exposes every native mode and preserves the disabled default" {
		LibMPVDeinterlace.entries.map(LibMPVDeinterlace::mpvValue) shouldContainExactly listOf("auto", "yes", "no")
		LibMPVChoiceSetting.DEINTERLACE.defaultOption() shouldBe LibMPVDeinterlace.DISABLED
	}
})
