package org.jellyfin.androidtv.util

const val MAX_SUBTITLE_LANGUAGE_PREFERENCES = 3

fun String?.toSubtitleLanguagePreferences(): List<String> =
	this.orEmpty()
		.split(',')
		.toSubtitleLanguagePreferences()

fun Iterable<String?>.toSubtitleLanguagePreferences(): List<String> =
	mapNotNull { language -> language.toIso2LanguageCodeOrNull() }
		.distinct()
		.take(MAX_SUBTITLE_LANGUAGE_PREFERENCES)

fun Iterable<String>.toSubtitleLanguagePreferenceString(): String =
	toSubtitleLanguagePreferences().joinToString(",")
