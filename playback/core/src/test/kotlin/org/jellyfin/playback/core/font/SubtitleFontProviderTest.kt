package org.jellyfin.playback.core.font

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class SubtitleFontProviderTest : StringSpec({
	"bundled subtitle fonts are preferred before device fallbacks" {
		val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
			ByteArrayInputStream(
				buildSubtitleFontConfig(
					bundledFontsDirectory = "/data/user/0/jellyfin/files/Noto & Friends",
					cacheDirectory = "/data/user/0/jellyfin/cache/fontconfig",
				).toByteArray(),
			),
		)

		val directories = document.getElementsByTagName("dir")
			.let { nodes -> (0 until nodes.length).map { nodes.item(it).textContent } }
		directories shouldContainExactly listOf(
			"/data/user/0/jellyfin/files/Noto & Friends",
			"/system/fonts",
			"/product/fonts",
			"/system_ext/fonts",
			"/vendor/fonts",
		)

		val preferredFamilies = document.getElementsByTagName("alias")
			.let { aliases -> (0 until aliases.length).map { aliases.item(it) as Element } }
			.single { alias -> alias.getElementsByTagName("family").item(0).textContent == "sans-serif" }
			.getElementsByTagName("prefer")
			.item(0)
			.childNodes
			.let { nodes ->
				(0 until nodes.length)
					.mapNotNull { nodes.item(it) as? Element }
					.map(Element::getTextContent)
			}
		preferredFamilies shouldContainExactly listOf(
			"Noto Sans",
			"Noto Sans Symbols",
			"Noto Sans Symbols 2",
			"Noto Sans Arabic",
			"Noto Sans Hebrew",
			"Noto Sans Devanagari",
			"Noto Sans Thai",
			"Noto Sans CJK SC",
			"Noto Sans CJK TC",
			"Noto Sans CJK JP",
			"Noto Sans CJK KR",
		)

		document.getElementsByTagName("cachedir").item(0).textContent shouldBe
			"/data/user/0/jellyfin/cache/fontconfig"
	}

	"device fallback is returned when a bundled font cannot load" {
		loadFontOrFallback(fallback = "device") {
			throw IllegalStateException("font is unavailable")
		} shouldBe "device"
	}
})
