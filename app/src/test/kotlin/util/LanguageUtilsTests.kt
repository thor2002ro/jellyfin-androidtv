package org.jellyfin.androidtv.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LanguageUtilsTests : FunSpec({
	test("toIso2LanguageCodeOrNull converts ISO language codes") {
		"en-US".toIso2LanguageCodeOrNull() shouldBe "en"
		"eng".toIso2LanguageCodeOrNull() shouldBe "en"
		"ron".toIso2LanguageCodeOrNull() shouldBe "ro"
		"fra".toIso2LanguageCodeOrNull() shouldBe "fr"
		"deu".toIso2LanguageCodeOrNull() shouldBe "de"
		"heb".toIso2LanguageCodeOrNull() shouldBe "he"
		"zho".toIso2LanguageCodeOrNull() shouldBe "zh"
		"fre".toIso2LanguageCodeOrNull() shouldBe "fr"
		"ger".toIso2LanguageCodeOrNull() shouldBe "de"
		"rum".toIso2LanguageCodeOrNull() shouldBe "ro"
		"zz".toIso2LanguageCodeOrNull() shouldBe null
		"foo".toIso2LanguageCodeOrNull() shouldBe null
	}

	test("toIso2LanguageBadgeOrNull returns card language badges") {
		"ces".toIso2LanguageBadgeOrNull() shouldBe "CZ"
		"dan".toIso2LanguageBadgeOrNull() shouldBe "DK"
		"eng".toIso2LanguageBadgeOrNull() shouldBe "EN"
		"ell".toIso2LanguageBadgeOrNull() shouldBe "GR"
		"fre".toIso2LanguageBadgeOrNull() shouldBe "FR"
		"heb".toIso2LanguageBadgeOrNull() shouldBe "IL"
		"jpn".toIso2LanguageBadgeOrNull() shouldBe "JP"
		"kor".toIso2LanguageBadgeOrNull() shouldBe "KR"
		"swe".toIso2LanguageBadgeOrNull() shouldBe "SE"
		"ukr".toIso2LanguageBadgeOrNull() shouldBe "UA"
		"vie".toIso2LanguageBadgeOrNull() shouldBe "VN"
		"zho".toIso2LanguageBadgeOrNull() shouldBe "CN"
		"unknown".toIso2LanguageBadgeOrNull() shouldBe null
	}

	test("toIso2LanguageDisplayOrSelf converts known codes and preserves labels") {
		"eng".toIso2LanguageDisplayOrSelf() shouldBe "en"
		"fre".toIso2LanguageDisplayOrSelf() shouldBe "fr"
		"English".toIso2LanguageDisplayOrSelf() shouldBe "English"
		"und".toIso2LanguageDisplayOrSelf() shouldBe null
		"UND".toIso2LanguageDisplayOrSelf() shouldBe null
		" ".toIso2LanguageDisplayOrSelf() shouldBe null
	}

	test("withoutUndeterminedLanguagePrefix removes generated und labels") {
		"UND".withoutUndeterminedLanguagePrefix() shouldBe null
		"UND AAC 5.1".withoutUndeterminedLanguagePrefix() shouldBe "AAC 5.1"
		"Und - SRT - External".withoutUndeterminedLanguagePrefix() shouldBe "SRT - External"
		"AAC - UND - 5.1".withoutUndeterminedLanguagePrefix() shouldBe "AAC - 5.1"
		"undetermined / AAC".withoutUndeterminedLanguagePrefix() shouldBe "AAC"
		"Undefined, AAC".withoutUndeterminedLanguagePrefix() shouldBe "AAC"
		"English - AAC".withoutUndeterminedLanguagePrefix() shouldBe "English - AAC"
		"Surround - AAC".withoutUndeterminedLanguagePrefix() shouldBe "Surround - AAC"
	}

	test("languageCodesMatch compares through ISO-2") {
		languageCodesMatch("eng", "en") shouldBe true
		languageCodesMatch("fre", "fr") shouldBe true
		languageCodesMatch("ron", "ro") shouldBe true
		languageCodesMatch("eng", "fr") shouldBe false
	}

	test("subtitle language preferences normalize unique ISO codes up to the limit") {
		"eng,fre,ger,eng,und".toSubtitleLanguagePreferences() shouldBe listOf("en", "fr", "de")
		listOf("eng", "fre", "ger", "ron").toSubtitleLanguagePreferenceString() shouldBe "en,fr,de"
		"eng,fre,ger,ron".toSubtitleLanguagePreferences().size shouldBe MAX_SUBTITLE_LANGUAGE_PREFERENCES
	}
})
