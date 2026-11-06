package org.jellyfin.playback.mpv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LibMPVPlayerLoaderTests : FunSpec({
	test("creation is deferred and a superseded player is disposed instead of published") {
		val work = ArrayDeque<Runnable>()
		val replies = ArrayDeque<() -> Unit>()
		val disposed = mutableListOf<String>()
		val published = mutableListOf<String>()
		val loader = LibMPVPlayerLoader<String>(Executor(work::addLast), replies::addLast, disposed::add)
		loader.load({ "first" }, published::add) { throw it }
		published shouldBe emptyList()
		work.removeFirst().run()
		loader.load({ "second" }, published::add) { throw it }
		replies.removeFirst().invoke()
		while (work.isNotEmpty()) work.removeFirst().run()
		replies.removeFirst().invoke()
		published shouldBe listOf("second")
		disposed shouldBe listOf("first")
	}

	test("release while initialization is pending disposes the result without publishing") {
		val work = ArrayDeque<Runnable>()
		val replies = ArrayDeque<() -> Unit>()
		val disposed = mutableListOf<String>()
		var published = false
		val loader = LibMPVPlayerLoader<String>(Executor(work::addLast), replies::addLast, disposed::add)
		loader.load({ "player" }, { published = true }) { throw it }
		work.removeFirst().run()
		loader.close()
		replies.removeFirst().invoke()
		while (work.isNotEmpty()) work.removeFirst().run()
		published shouldBe false
		disposed shouldBe listOf("player")
	}

	test("initialization errors are delivered on the reply queue") {
		val work = ArrayDeque<Runnable>()
		val replies = ArrayDeque<() -> Unit>()
		val failure = IllegalStateException("init failed")
		var reported: Throwable? = null
		val loader = LibMPVPlayerLoader<String>(Executor(work::addLast), replies::addLast, {})
		loader.load({ throw failure }, { error("unexpected player") }, { reported = it })
		work.removeFirst().run()
		reported shouldBe null
		replies.removeFirst().invoke()
		reported shouldBe failure
	}

	test("release does not wait for blocked initialization and cleanup runs on the worker") {
		val worker = Executors.newSingleThreadExecutor()
		val replies = LinkedBlockingQueue<() -> Unit>()
		val started = CountDownLatch(1)
		val finish = CountDownLatch(1)
		val disposed = CountDownLatch(1)
		val caller = Thread.currentThread()
		var cleanupThread: Thread? = null
		val loader = LibMPVPlayerLoader<String>(worker, { replies.add(it) }, {
			cleanupThread = Thread.currentThread()
			disposed.countDown()
		})
		try {
			loader.load({
				started.countDown()
				check(finish.await(5, TimeUnit.SECONDS))
				"player"
			}, { error("released player was published") }, { throw it })
			started.await(5, TimeUnit.SECONDS) shouldBe true
			loader.close()
			finish.count shouldBe 1
			finish.countDown()
			replies.poll(5, TimeUnit.SECONDS)!!.invoke()
			disposed.await(5, TimeUnit.SECONDS) shouldBe true
			(cleanupThread === caller) shouldBe false
		} finally {
			finish.countDown()
			worker.shutdownNow()
		}
	}

	test("surface attachment and detachment finish before retirement") {
		val work = ArrayDeque<Runnable>()
		val calls = mutableListOf<String>()
		val loader = LibMPVPlayerLoader<String>(Executor(work::addLast), {}, { calls.add("close") })
		loader.execute { calls.add("attach") }
		loader.execute { calls.add("detach") }
		loader.retire("player")
		calls shouldBe emptyList()
		while (work.isNotEmpty()) work.removeFirst().run()
		calls shouldBe listOf("attach", "detach", "close")
	}
})
