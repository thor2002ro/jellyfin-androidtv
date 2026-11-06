package org.jellyfin.androidtv.util

import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.MediaStream

/** Track identity survives different stream ordering and missing tracks in other episodes. */
@Serializable
data class TrackChoice(
	val index: Int,
	val language: String? = null,
	val codec: String? = null,
	val title: String? = null,
	val forced: Boolean = false,
	val hearingImpaired: Boolean = false,
	val original: Boolean? = null,
	val channels: Int? = null,
) {
	fun match(streams: List<MediaStream>, sameItem: Boolean): MediaStream? {
		val candidates = streams.filter { stream ->
			matchesLanguage(stream) && stream.isForced == forced
		}
		// Preserve an episode's index only when it still identifies the selected track.
		if (sameItem) candidates.firstOrNull { stream ->
			stream.index == index && matchesIdentity(stream)
		}?.let { return it }

		// Flags define the kind of track; title, codec and channel count refine that choice.
		return candidates.maxWithOrNull(
			compareBy<MediaStream> { it.isHearingImpaired == hearingImpaired }
				.thenBy { original != null && original == it.isOriginal }
				.thenBy { title != null && (title == it.title || title == it.displayTitle) }
				.thenBy { codec != null && codec.equals(it.codec, ignoreCase = true) }
				.thenBy { channels != null && channels == it.channels }
				.thenBy { it.isDefault }
		)
	}

	private fun matchesIdentity(stream: MediaStream): Boolean =
		stream.isHearingImpaired == hearingImpaired &&
			(original == null || original == stream.isOriginal) &&
			(codec == null || codec.equals(stream.codec, ignoreCase = true)) &&
			(title == null || title == stream.title || title == stream.displayTitle) &&
			(channels == null || channels == stream.channels)

	internal fun matchesLanguage(stream: MediaStream): Boolean = when {
		language == null -> stream.language.toIso2LanguageCodeOrNull() == null
		else -> languageCodesMatch(stream.language, language) || language.equals(stream.language?.trim(), ignoreCase = true)
	}

	companion object {
		fun from(stream: MediaStream) = TrackChoice(
			index = stream.index,
			language = stream.language.toIso2LanguageCodeOrNull() ?: stream.language?.trim()?.takeIf(String::isNotBlank),
			codec = stream.codec,
			title = stream.title?.takeIf(String::isNotBlank) ?: stream.displayTitle?.takeIf(String::isNotBlank),
			forced = stream.isForced,
			hearingImpaired = stream.isHearingImpaired,
			original = stream.isOriginal,
			channels = stream.channels,
		)
	}
}
