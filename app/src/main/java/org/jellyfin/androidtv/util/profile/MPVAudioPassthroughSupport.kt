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

private const val MINIMUM_BUFFER_DURATION_MS = 75
private const val CARRIER_PROBE_CACHE_DURATION_MS = 2_000L

internal data class MPVIec61937Carrier(val sampleRate: Int, val channelMask: Int) {
	val frameSizeBytes: Int get() = Integer.bitCount(channelMask) * 2
}

private data class CarrierProbeCache(
	val routeSupportedMimes: Set<String>,
	val supportedCarriers: Set<MPVIec61937Carrier>,
	val expiresAt: Long,
)

private val carrierProbeLock = Any()
private var carrierProbeCache: CarrierProbeCache? = null

@OptIn(UnstableApi::class)
private val mpvIec61937Carriers: Map<String, Set<MPVIec61937Carrier>> = run {
	val stereo32 = MPVIec61937Carrier(32_000, AudioFormat.CHANNEL_OUT_STEREO)
	val stereo44 = MPVIec61937Carrier(44_100, AudioFormat.CHANNEL_OUT_STEREO)
	val stereo48 = MPVIec61937Carrier(48_000, AudioFormat.CHANNEL_OUT_STEREO)
	val stereo192 = MPVIec61937Carrier(192_000, AudioFormat.CHANNEL_OUT_STEREO)
	val surround192 = MPVIec61937Carrier(192_000, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND)
	mapOf(
		MimeTypes.AUDIO_AC3 to setOf(stereo48, stereo44, stereo32),
		MimeTypes.AUDIO_DTS to setOf(stereo48, stereo44),
		MimeTypes.AUDIO_DTS_EXPRESS to setOf(stereo48, stereo44),
		MimeTypes.AUDIO_E_AC3 to setOf(stereo192),
		MimeTypes.AUDIO_E_AC3_JOC to setOf(stereo192),
		// HRA uses stereo; MA uses eight carrier channels. The native AO validates
		// the actual stream's format and falls back if that particular carrier fails.
		MimeTypes.AUDIO_DTS_HD to setOf(stereo192, surround192),
		MimeTypes.AUDIO_DTS_UHD_P2 to setOf(stereo192, surround192),
		MimeTypes.AUDIO_TRUEHD to setOf(surround192),
	)
}

@OptIn(UnstableApi::class)
fun getSupportedMPVPassthroughAudioMimes(context: Context, mimeTypes: Collection<String>): Set<String> {
	val routeSupportedMimes = getSupportedPassthroughAudioMimes(context, mimeTypes)
	val supportedCarriers = getSupportedCarriers(routeSupportedMimes)
	return filterMPVPassthroughAudioMimes(routeSupportedMimes, supportedCarriers)
}

@OptIn(UnstableApi::class)
internal fun filterMPVPassthroughAudioMimes(
	mimeTypes: Collection<String>,
	supportedCarriers: Set<MPVIec61937Carrier>,
): Set<String> = mimeTypes.filterTo(mutableSetOf()) { mime ->
	mpvIec61937Carriers[mime]?.any { it in supportedCarriers } == true
}

internal fun probeMPVIec61937Carriers(
	mimeTypes: Set<String>,
	canOpen: (MPVIec61937Carrier) -> Boolean,
): Set<MPVIec61937Carrier> {
	val results = mutableMapOf<MPVIec61937Carrier, Boolean>()
	for (mime in mimeTypes) {
		// We need one viable carrier per codec family, not every possible rate.
		mpvIec61937Carriers[mime]?.any { carrier -> results.getOrPut(carrier) { canOpen(carrier) } }
	}
	return results.filterValues { it }.keys
}

private fun getSupportedCarriers(routeSupportedMimes: Set<String>): Set<MPVIec61937Carrier> = synchronized(carrierProbeLock) {
	val now = SystemClock.elapsedRealtime()
	carrierProbeCache?.takeIf { cache ->
		cache.routeSupportedMimes == routeSupportedMimes && now < cache.expiresAt
	}?.let { cache -> return@synchronized cache.supportedCarriers }

	val supportedCarriers = probeMPVIec61937Carriers(routeSupportedMimes, ::canOpenIec61937AudioTrack)
	carrierProbeCache = CarrierProbeCache(
		routeSupportedMimes = routeSupportedMimes,
		supportedCarriers = supportedCarriers,
		expiresAt = now + CARRIER_PROBE_CACHE_DURATION_MS,
	)
	supportedCarriers
}

private fun canOpenIec61937AudioTrack(carrier: MPVIec61937Carrier): Boolean {
	val minimumBufferSize = AudioTrack.getMinBufferSize(
		carrier.sampleRate,
		carrier.channelMask,
		AudioFormat.ENCODING_IEC61937,
	)
	if (minimumBufferSize <= 0) return false

	val attributes = AudioAttributes.Builder()
		.setUsage(AudioAttributes.USAGE_MEDIA)
		.setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
		.build()
	val format = AudioFormat.Builder()
		.setEncoding(AudioFormat.ENCODING_IEC61937)
		.setSampleRate(carrier.sampleRate)
		.setChannelMask(carrier.channelMask)
		.build()
	val minimumWindowSize = carrier.sampleRate * carrier.frameSizeBytes * MINIMUM_BUFFER_DURATION_MS / 1_000

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
