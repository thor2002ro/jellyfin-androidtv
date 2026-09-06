package org.jellyfin.androidtv.preference.constant

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.libVLCDecoder

class LibVLCDefaultsTests : StringSpec({
	"libVLC uses automatic video decoding when no preference is stored" {
		UserPreferences.libVLCDecoder.defaultValue shouldBe LibVLCDecoder.AUTOMATIC
	}
})
