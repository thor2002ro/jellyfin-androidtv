import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionUtilsTest {
	@Test
	fun dateBuildVersionCodesIncludeTime() {
		assertTrue(getVersionCode("v2026.07.01.2134") > getVersionCode("v2026.07.01.1920"))
	}

	@Test
	fun semanticVersionCodesStayCompatible() {
		assertEquals(2_000_099, getVersionCode("v2.0.0"))
	}
}
