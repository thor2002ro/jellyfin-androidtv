package org.jellyfin.playback.jellyfin.mediastream

import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import java.net.URI
import java.net.URLDecoder

private val videoCopyReasons = setOf(
	"audiocodecnotsupported", "audiobitratenotsupported", "audiochannelsnotsupported",
	"audioprofilenotsupported", "audiosampleratenotsupported", "audiobitdepthnotsupported",
	"secondaryaudionotsupported", "audioisexternal", "containernotsupported", "videocodectagnotsupported",
)

// Server 12's transcoding-profile branch reports Transcode even when only audio needs conversion.
// These checks allow the client to keep the original video only when the request still permits it.
internal fun MediaSourceInfo.isHlsVideoCopyCandidate(clientTransformsVideo: Boolean = false): Boolean {
	if (transcodingSubProtocol != MediaStreamProtocol.HLS) return false
	val video = mediaStreams.orEmpty().firstOrNull { it.type == MediaStreamType.VIDEO } ?: return false
	val videoCodec = video.codec?.lowercase() ?: return false
	val uri = runCatching { URI(transcodingUrl ?: return false) }.getOrNull() ?: return false
	val pairs = runCatching {
		uri.rawQuery.orEmpty().split('&').map { part ->
			URLDecoder.decode(part.substringBefore('='), "UTF-8").lowercase() to
				URLDecoder.decode(part.substringAfter('=', ""), "UTF-8").lowercase()
		}
	}.getOrNull() ?: return false
	val options = pairs.toMap()
	if (options.size != pairs.size) return false
	if (options["allowvideostreamcopy"] == "false") return false
	if (options["subtitlemethod"] == "encode" && "subtitlestreamindex" in options) return false
	if (!clientTransformsVideo) {
		val requestedRanges = options.filterKeys { it == "videorangetype" || it.endsWith("-rangetype") }.values
		if (requestedRanges.any { video.videoRangeType.serialName.lowercase() !in it.split(',') }) return false
	}
	if (options["videocodec"]?.split(',')?.contains(videoCodec) != true) return false
	val reasons = options["transcodereasons"]?.split(',') ?: return false
	return reasons.isNotEmpty() && reasons.all(videoCopyReasons::contains)
}
