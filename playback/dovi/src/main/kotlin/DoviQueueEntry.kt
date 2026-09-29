package org.jellyfin.playback.dovi

import io.github.thor2002ro.libdovi.DoviStatus
import org.jellyfin.playback.core.element.ElementKey
import org.jellyfin.playback.core.element.element
import org.jellyfin.playback.core.queue.QueueEntry

private val doviDecisionKey = ElementKey<DoviDecision>("DoviConversionDecision")
// Retain the stored key names so an in-memory queue survives the local accessor rename.
private val doviTransformationRetryCountKey = ElementKey<Int>("DoviConversionRetryCount")
private val doviTransformationSuppressedKey = ElementKey<Boolean>("DoviConversionSuppressed")
private val doviRecoveryOwnershipKey = ElementKey<DoviRecoveryOwnership>("DoviRecoveryOwnership")
private val doviTransformFailureKey = ElementKey<DoviStatus>("DoviTransformFailure")
private val doviVideoDecoderFailureKey = ElementKey<Boolean>("DoviVideoDecoderFailure")

enum class DoviRecoveryOwnership { IN_FLIGHT, TERMINAL }

var QueueEntry.doviDecision: DoviDecision? by element(doviDecisionKey)
var QueueEntry.doviTransformationRetryCount: Int? by element(doviTransformationRetryCountKey)
var QueueEntry.doviTransformationSuppressed: Boolean? by element(doviTransformationSuppressedKey)
var QueueEntry.doviRecoveryOwnership: DoviRecoveryOwnership? by element(doviRecoveryOwnershipKey)
var QueueEntry.doviTransformFailure: DoviStatus? by element(doviTransformFailureKey)
var QueueEntry.doviVideoDecoderFailure: Boolean? by element(doviVideoDecoderFailureKey)
