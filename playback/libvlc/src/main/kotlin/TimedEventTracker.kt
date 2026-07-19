package org.jellyfin.playback.libvlc

import org.jellyfin.playback.core.timedevent.BlockActivation
import org.jellyfin.playback.core.timedevent.TimedEvent
import kotlin.time.Duration

internal class TimedEventTracker {
	private var events = emptyList<TimedEvent>()
	private val activeBlocks = mutableSetOf<TimedEvent.Block>()

	fun setEvents(events: List<TimedEvent>) {
		activeBlocks.retainAll(events.filterIsInstance<TimedEvent.Block>().toSet())
		this.events = events
	}

	fun advance(from: Duration, to: Duration, duration: Duration, natural: Boolean) {
		for (event in events) when (event) {
			is TimedEvent.Instant -> if (natural && event.position.resolve(duration) in from..<to) event.onActivate()
			is TimedEvent.Block -> updateBlock(event, from, to, duration, natural)
		}
	}

	private fun updateBlock(event: TimedEvent.Block, from: Duration, to: Duration, duration: Duration, natural: Boolean) {
		val start = event.start.resolve(duration)
		val end = event.end.resolve(duration)
		val wasActive = from in start..end
		val isActive = to in start..end
		val metadata = if (natural) BlockActivation.Natural(to) else BlockActivation.Seek(from, to)

		if (!wasActive && isActive && activeBlocks.add(event)) event.onActivate?.invoke(metadata)
		if (wasActive && !isActive && activeBlocks.remove(event)) event.onDeactivate?.invoke(metadata)
	}

	private fun Duration.resolve(mediaDuration: Duration) = if (isNegative()) mediaDuration + this else this
}
