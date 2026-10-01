package org.jellyfin.playback.mpv

import `is`.xyz.mpv.MPVNode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

class LibMPVSubtitlePollingTests : FunSpec({
	test("a subtitle poll waiting for player teardown does not enter the retired native handle") {
		val playerLock = Any()
		val active = AtomicBoolean(true)
		val reads = AtomicInteger()
		lateinit var poll: Thread
		synchronized(playerLock) {
			poll = thread {
				readLibMPVSubtitleOverlay(playerLock, active::get) {
					reads.incrementAndGet()
					MPVNode.None
				}
			}
			try {
				poll.awaitBlockedOrFinished()
			} finally {
				active.set(false)
			}
		}
		poll.join(5_000)

		poll.isAlive shouldBe false
		reads.get() shouldBe 0
	}

	test("player teardown waits until an in-flight subtitle native command finishes") {
		val playerLock = Any()
		val commandStarted = CountDownLatch(1)
		val finishCommand = CountDownLatch(1)
		val destroyed = AtomicBoolean(false)
		val overlapped = AtomicBoolean(false)
		val poll = thread {
			readLibMPVSubtitleOverlay(playerLock, { true }) {
				commandStarted.countDown()
				finishCommand.await(5, TimeUnit.SECONDS)
				overlapped.set(destroyed.get())
				MPVNode.None
			}
		}
		var destroy: Thread? = null
		try {
			commandStarted.await(5, TimeUnit.SECONDS) shouldBe true
			destroy = thread {
				synchronized(playerLock) { destroyed.set(true) }
			}
			destroy.awaitBlockedOrFinished()
		} finally {
			finishCommand.countDown()
			poll.join(5_000)
			destroy?.join(5_000)
		}

		poll.isAlive shouldBe false
		destroy.isAlive shouldBe false
		destroyed.get() shouldBe true
		overlapped.get() shouldBe false
	}
})

private fun Thread.awaitBlockedOrFinished() {
	val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
	while (isAlive && state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
		LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
	}
	check(!isAlive || state == Thread.State.BLOCKED) { "Worker did not reach the player lock" }
}
