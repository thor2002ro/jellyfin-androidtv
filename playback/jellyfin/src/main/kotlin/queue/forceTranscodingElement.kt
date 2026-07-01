package org.jellyfin.playback.jellyfin.queue

import org.jellyfin.playback.core.element.ElementKey
import org.jellyfin.playback.core.element.element
import org.jellyfin.playback.core.queue.QueueEntry

private val forceTranscodingKey = ElementKey<Boolean>("ForceTranscoding")
private val forceTranscodingSourceBitrateKey = ElementKey<Int>("ForceTranscodingSourceBitrate")
private val forceTranscodingRecoveryAttemptsKey = ElementKey<Int>("ForceTranscodingRecoveryAttempts")

/**
 * Force the media stream resolver to request transcoding for this queue entry.
 */
var QueueEntry.forceTranscoding: Boolean? by element(forceTranscodingKey)

/**
 * The last known direct stream bitrate used to decide when forced transcoding can be released.
 */
var QueueEntry.forceTranscodingSourceBitrate: Int? by element(forceTranscodingSourceBitrateKey)

/**
 * Number of same-mode recovery attempts for a quality-forced transcode.
 */
var QueueEntry.forceTranscodingRecoveryAttempts: Int? by element(forceTranscodingRecoveryAttemptsKey)
