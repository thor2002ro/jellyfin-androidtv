@file:JvmName("LanguageUtils")

package org.jellyfin.androidtv.util

import java.util.Locale

private val ISO2_LANGUAGE_CODES by lazy {
	Locale.getISOLanguages().map { language -> language.lowercase(Locale.ROOT) }.toSet()
}

private val ISO3_TO_ISO2 by lazy {
	ISO2_LANGUAGE_CODES.mapNotNull { iso2 ->
		runCatching { Locale.forLanguageTag(iso2).isO3Language.lowercase(Locale.ROOT) to iso2 }
			.getOrNull()
	}.toMap() + ISO3_BIBLIOGRAPHIC_TO_ISO2
}

private val ISO3_BIBLIOGRAPHIC_TO_ISO2 = mapOf(
	"alb" to "sq",
	"arm" to "hy",
	"baq" to "eu",
	"bur" to "my",
	"chi" to "zh",
	"cze" to "cs",
	"dut" to "nl",
	"fre" to "fr",
	"geo" to "ka",
	"ger" to "de",
	"gre" to "el",
	"ice" to "is",
	"mac" to "mk",
	"mao" to "mi",
	"may" to "ms",
	"per" to "fa",
	"rum" to "ro",
	"slo" to "sk",
	"tib" to "bo",
	"wel" to "cy",
)

private val ISO2_LEGACY_TO_MODERN = mapOf(
	"in" to "id",
	"iw" to "he",
	"ji" to "yi",
)

private val LANGUAGE_BADGE_ALIASES = mapOf(
	"cs" to "CZ",
	"da" to "DK",
	"el" to "GR",
	"he" to "IL",
	"ja" to "JP",
	"ko" to "KR",
	"sv" to "SE",
	"uk" to "UA",
	"vi" to "VN",
	"zh" to "CN",
)

private val UNDETERMINED_LANGUAGE_TOKEN = Regex(
	pattern = "\\b(?:und|undetermined|undefined)\\b",
	option = RegexOption.IGNORE_CASE,
)
private val REPEATED_LANGUAGE_SEPARATORS = Regex("\\s*[-:/|,;]+\\s*[-:/|,;]+\\s*")
private val EDGE_LANGUAGE_SEPARATORS = Regex("^\\s*[-:/|,;]+\\s*|\\s*[-:/|,;]+\\s*$")
private val REPEATED_WHITESPACE = Regex("\\s{2,}")

fun String?.toIso2LanguageCodeOrNull(): String? {
	val code = this
		?.trim()
		?.lowercase(Locale.ROOT)
		?.substringBefore(' ')
		?.substringBefore('-')
		?.substringBefore('_')
		?.takeIf { it.isNotBlank() }
		?: return null

	return when (code.length) {
		2 -> code.takeIf { it in ISO2_LANGUAGE_CODES || it in ISO2_LEGACY_TO_MODERN }
			?.let { ISO2_LEGACY_TO_MODERN[it] ?: it }
		3 -> ISO3_TO_ISO2[code]?.let { ISO2_LEGACY_TO_MODERN[it] ?: it }
		else -> null
	}
}

fun String?.toIso2LanguageBadgeOrNull(): String? =
	toIso2LanguageCodeOrNull()?.let { code -> LANGUAGE_BADGE_ALIASES[code] ?: code.uppercase(Locale.ROOT) }

fun String?.toIso2LanguageDisplayOrSelf(): String? =
	toIso2LanguageCodeOrNull() ?: withoutUndeterminedLanguagePrefix()

fun String?.withoutUndeterminedLanguagePrefix(): String? =
	this
		?.trim()
		?.replace(UNDETERMINED_LANGUAGE_TOKEN, "")
		?.replace(REPEATED_LANGUAGE_SEPARATORS, " - ")
		?.replace(EDGE_LANGUAGE_SEPARATORS, "")
		?.replace(REPEATED_WHITESPACE, " ")
		?.trim()
		?.takeIf { it.isNotBlank() }

fun languageCodesMatch(first: String?, second: String?): Boolean {
	val firstIso2 = first.toIso2LanguageCodeOrNull()
	return firstIso2 != null && firstIso2 == second.toIso2LanguageCodeOrNull()
}
