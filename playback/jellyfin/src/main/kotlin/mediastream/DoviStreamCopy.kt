package org.jellyfin.playback.jellyfin.mediastream

import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType
import java.net.URI
import java.net.URLDecoder

private val doviCopyReasons = setOf(
	"audiocodecnotsupported", "audiobitratenotsupported", "audiochannelsnotsupported",
	"audioprofilenotsupported", "audiosampleratenotsupported", "audiobitdepthnotsupported",
	"secondaryaudionotsupported", "audioisexternal", "containernotsupported", "videocodectagnotsupported",
)

// Server 12's transcoding-profile branch reports Transcode even when only audio needs conversion.
// This is permission to try the original HLS variant, not permission to transform an SDR re-encode.
internal fun MediaSourceInfo.isDoviVideoCopyCandidate(): Boolean {
	val video = mediaStreams.orEmpty().firstOrNull { it.type == MediaStreamType.VIDEO } ?: return false
	if (!video.codec.equals("hevc", true) || video.dvProfile == null || video.rpuPresentFlag != 1) return false
	val uri = runCatching { URI(transcodingUrl ?: return false) }.getOrNull() ?: return false
	if (!uri.path.orEmpty().endsWith("/master.m3u8", true)) return false
	val pairs = runCatching {
		uri.rawQuery.orEmpty().split('&').map { part ->
			URLDecoder.decode(part.substringBefore('='), "UTF-8").lowercase() to
				URLDecoder.decode(part.substringAfter('=', ""), "UTF-8").lowercase()
		}
	}.getOrNull() ?: return false
	val options = pairs.toMap()
	if (options.size != pairs.size) return false
	if (options["allowvideostreamcopy"] == "false" || options["subtitlemethod"] == "encode") return false
	if (options.keys.any { it == "videorangetype" || it.endsWith("-rangetype") }) return false
	if (options["videocodec"]?.split(',')?.contains("hevc") != true) return false
	val reasons = options["transcodereasons"]?.split(',') ?: return false
	return reasons.isNotEmpty() && reasons.all(doviCopyReasons::contains)
}
