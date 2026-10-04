package org.jellyfin.androidtv.util.profile

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

private const val STANDARD_IEC61937_CARRIER_RATE = 48_000
private const val HIGH_RATE_IEC61937_CARRIER_RATE = 192_000
private const val MINIMUM_BUFFER_DURATION_MS = 75
private const val IEC61937_FRAME_SIZE_BYTES = 4
private const val CARRIER_PROBE_CACHE_DURATION_MS = 2_000L

private data class CarrierProbeCache(
	val routeSupportedMimes: Set<String>,
	val supportedCarrierRates: Set<Int>,
	val expiresAt: Long,
)

private val carrierProbeLock = Any()
private var carrierProbeCache: CarrierProbeCache? = null

@OptIn(UnstableApi::class)
private val mpvIec61937CarrierRates = mapOf(
	MimeTypes.AUDIO_AC3 to STANDARD_IEC61937_CARRIER_RATE,
	MimeTypes.AUDIO_DTS to STANDARD_IEC61937_CARRIER_RATE,
	MimeTypes.AUDIO_DTS_EXPRESS to STANDARD_IEC61937_CARRIER_RATE,
	MimeTypes.AUDIO_E_AC3 to HIGH_RATE_IEC61937_CARRIER_RATE,
	MimeTypes.AUDIO_E_AC3_JOC to HIGH_RATE_IEC61937_CARRIER_RATE,
	MimeTypes.AUDIO_DTS_HD to HIGH_RATE_IEC61937_CARRIER_RATE,
	MimeTypes.AUDIO_DTS_UHD_P2 to HIGH_RATE_IEC61937_CARRIER_RATE,
	MimeTypes.AUDIO_TRUEHD to HIGH_RATE_IEC61937_CARRIER_RATE,
)

@OptIn(UnstableApi::class)
fun getSupportedMPVPassthroughAudioMimes(context: Context, mimeTypes: Collection<String>): Set<String> {
	val routeSupportedMimes = getSupportedPassthroughAudioMimes(context, mimeTypes)
	val supportedCarrierRates = getSupportedCarrierRates(routeSupportedMimes)
	return filterMPVPassthroughAudioMimes(routeSupportedMimes, supportedCarrierRates)
}

@OptIn(UnstableApi::class)
internal fun filterMPVPassthroughAudioMimes(
	mimeTypes: Collection<String>,
	supportedCarrierRates: Set<Int>,
): Set<String> = mimeTypes.filterTo(mutableSetOf()) { mime ->
	mpvIec61937CarrierRates[mime] in supportedCarrierRates
}

private fun getSupportedCarrierRates(routeSupportedMimes: Set<String>): Set<Int> = synchronized(carrierProbeLock) {
	val now = SystemClock.elapsedRealtime()
	carrierProbeCache?.takeIf { cache ->
		cache.routeSupportedMimes == routeSupportedMimes && now < cache.expiresAt
	}?.let { cache -> return@synchronized cache.supportedCarrierRates }

	val requiredCarrierRates = routeSupportedMimes.mapNotNullTo(mutableSetOf()) { mime ->
		mpvIec61937CarrierRates[mime]
	}
	val supportedCarrierRates = requiredCarrierRates.filterTo(mutableSetOf()) { sampleRate ->
		canOpenIec61937AudioTrack(sampleRate)
	}
	carrierProbeCache = CarrierProbeCache(
		routeSupportedMimes = routeSupportedMimes,
		supportedCarrierRates = supportedCarrierRates,
		expiresAt = now + CARRIER_PROBE_CACHE_DURATION_MS,
	)
	supportedCarrierRates
}

private fun canOpenIec61937AudioTrack(sampleRate: Int): Boolean {
	val minimumBufferSize = AudioTrack.getMinBufferSize(
		sampleRate,
		AudioFormat.CHANNEL_OUT_STEREO,
		AudioFormat.ENCODING_IEC61937,
	)
	if (minimumBufferSize <= 0) return false

	val attributes = AudioAttributes.Builder()
		.setUsage(AudioAttributes.USAGE_MEDIA)
		.setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
		.build()
	val format = AudioFormat.Builder()
		.setEncoding(AudioFormat.ENCODING_IEC61937)
		.setSampleRate(sampleRate)
		.setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
		.build()
	val minimumWindowSize = sampleRate * IEC61937_FRAME_SIZE_BYTES * MINIMUM_BUFFER_DURATION_MS / 1_000

	val audioTrack = try {
		AudioTrack(
			attributes,
			format,
			maxOf(minimumBufferSize * 2, minimumWindowSize),
			AudioTrack.MODE_STREAM,
			AudioManager.AUDIO_SESSION_ID_GENERATE,
		)
	} catch (_: RuntimeException) {
		return false
	}

	return try {
		audioTrack.state == AudioTrack.STATE_INITIALIZED
	} finally {
		audioTrack.release()
	}
}
