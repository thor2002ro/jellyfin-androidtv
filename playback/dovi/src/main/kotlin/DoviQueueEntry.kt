package org.jellyfin.playback.dovi

import org.jellyfin.playback.core.element.ElementKey
import org.jellyfin.playback.core.element.element
import org.jellyfin.playback.core.queue.QueueEntry

private val doviDecisionKey = ElementKey<DoviDecision>("DoviConversionDecision")
private val doviConversionRetryCountKey = ElementKey<Int>("DoviConversionRetryCount")
private val doviConversionSuppressedKey = ElementKey<Boolean>("DoviConversionSuppressed")

var QueueEntry.doviDecision: DoviDecision? by element(doviDecisionKey)
var QueueEntry.doviConversionRetryCount: Int? by element(doviConversionRetryCountKey)
var QueueEntry.doviConversionSuppressed: Boolean? by element(doviConversionSuppressedKey)
