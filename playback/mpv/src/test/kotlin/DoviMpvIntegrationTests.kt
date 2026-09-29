package org.jellyfin.playback.mpv

import io.github.thor2002ro.libdovi.DoviMpvSession
import io.github.thor2002ro.libdovi.DoviPresentation
import io.github.thor2002ro.libdovi.DoviTransformObservation
import io.github.thor2002ro.libdovi.DoviRepair
import io.github.thor2002ro.libdovi.DoviStatus
import io.github.thor2002ro.libdovi.DoviTarget
import io.github.thor2002ro.libdovi.DoviTransformRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DoviMpvIntegrationTests : FunSpec({
	test("publishes the exact immutable semantic request before resetting its token") {
		val originalRepairs = mutableSetOf(DoviRepair.REMOVE_MAPPING, DoviRepair.ZERO_ACTIVE_AREA)
		val request = DoviTransformRequest(DoviTarget.PROFILE_8_1_PRESERVE_MAPPING, originalRepairs)
		val native = FakeNativeMpvState()
		val session = native.session()

		session.prepare(request)
		originalRepairs.clear()
		originalRepairs += DoviRepair.REMOVE_CMV40

		native.calls shouldBe listOf("publish:1", "reset:1")
		native.requests.single()?.target shouldBe DoviTarget.PROFILE_8_1_PRESERVE_MAPPING
		native.requests.single()?.repairs shouldBe setOf(
			DoviRepair.REMOVE_MAPPING,
			DoviRepair.ZERO_ACTIVE_AREA,
		)
	}

	test("source-base and null routes receive distinct opaque sessions") {
		val native = FakeNativeMpvState()
		val session = native.session()

		session.prepare(DoviTransformRequest(DoviTarget.SOURCE_BASE_PRESENTATION))
		session.prepare(null)

		native.requests shouldBe listOf(
			DoviTransformRequest(DoviTarget.SOURCE_BASE_PRESENTATION),
			null,
		)
		native.calls shouldBe listOf("publish:1", "reset:1", "publish:2", "reset:2")
	}

	test("late old-stream failure cannot overwrite or be consumed by the new session") {
		val native = FakeNativeMpvState()
		val session = native.session()
		session.prepare(DoviTransformRequest(DoviTarget.MEL))
		val old = native.publishedSessions.last()
		session.prepare(DoviTransformRequest(DoviTarget.PROFILE_8_4))
		val current = native.publishedSessions.last()

		native.record(old, DoviStatus.RPU_CONVERT_FAILED)
		native.record(current, DoviStatus.RPU_WRITE_FAILED)

		session.consumeErrorCodeAndClear() shouldBe
			"DOVI_TRANSFORMATION_FAILED_RPU_WRITE_FAILED"
		native.consumedSessions shouldBe listOf(current)
		native.requests.last() shouldBe null
	}

	test("first error wins within one generation") {
		val native = FakeNativeMpvState()
		val session = native.session()
		session.prepare(DoviTransformRequest(DoviTarget.PROFILE_8_1))
		val active = native.publishedSessions.last()

		native.record(active, DoviStatus.REPAIR_FAILED)
		native.record(active, DoviStatus.INTERNAL_ERROR)

		session.consumeErrorCodeAndClear() shouldBe
			"DOVI_TRANSFORMATION_FAILED_REPAIR_FAILED"
	}

	test("transform observation is read only from the active generation") {
		val native = FakeNativeMpvState()
		val session = native.session()
		session.prepare(DoviTransformRequest(DoviTarget.PROFILE_8_1))
		val active = native.publishedSessions.last()
		val expected = DoviTransformObservation(
			DoviPresentation.PROFILE_7_FEL,
			DoviPresentation.PROFILE_8_1,
		)
		native.observe(active, expected)

		session.transformObservation() shouldBe expected

		session.prepare(DoviTransformRequest(DoviTarget.MEL))
		native.observe(active, expected)
		session.transformObservation() shouldBe null
	}

	test("redirect continuation preserves the active session until the real end") {
		val native = FakeNativeMpvState()
		val session = native.session()
		session.prepare(DoviTransformRequest(DoviTarget.LOSSLESS_REWRITE))
		val active = native.publishedSessions.last()

		doviMpvEndAction(7, 7, false, "redirect", true, false) shouldBe DoviMpvEndAction.REDIRECT
		native.requests shouldBe listOf(DoviTransformRequest(DoviTarget.LOSSLESS_REWRITE))
		native.record(active, DoviStatus.INCONSISTENT_RPU)
		doviMpvEndAction(7, 7, false, "error", true, false) shouldBe DoviMpvEndAction.FINISH
		session.consumeErrorCodeAndClear() shouldBe
			"DOVI_TRANSFORMATION_FAILED_INCONSISTENT_RPU"
	}

	test("old replacement end event is ignored without consuming the new session") {
		val native = FakeNativeMpvState()
		val session = native.session()
		session.prepare(DoviTransformRequest(DoviTarget.MEL))
		val old = native.publishedSessions.last()
		session.prepare(DoviTransformRequest(DoviTarget.PROFILE_8_1))
		val current = native.publishedSessions.last()

		doviMpvEndAction(10, 11, false, "error", true, false) shouldBe DoviMpvEndAction.IGNORE
		native.record(old, DoviStatus.RPU_CONVERT_FAILED)
		native.consumedSessions shouldBe emptyList()
		native.record(current, DoviStatus.OK)
		session.consumeErrorCodeAndClear() shouldBe null
		native.consumedSessions shouldBe listOf(current)
	}

	test("load replacement end event is ignored before playlist identity is known") {
		doviMpvEndAction(null, null, true, "error", true, false) shouldBe DoviMpvEndAction.IGNORE
	}

	test("activation reset exception invalidates the published generation") {
		val native = FakeNativeMpvState(failResetGeneration = 1)
		val session = native.session()

		shouldThrow<IllegalStateException> {
			session.prepare(DoviTransformRequest(DoviTarget.MEL))
		}

		native.requests shouldBe listOf(DoviTransformRequest(DoviTarget.MEL), null)
		native.publishedSessions.map(FakeMpvSession::ordinal) shouldBe listOf(1L, 2L)
	}

	test("explicit clear advances generation and makes a late error harmless") {
		val native = FakeNativeMpvState()
		val session = native.session()
		session.prepare(DoviTransformRequest(DoviTarget.PROFILE_8_4))
		val old = native.publishedSessions.last()

		session.clear()
		native.record(old, DoviStatus.INTERNAL_ERROR)

		native.requests shouldBe listOf(DoviTransformRequest(DoviTarget.PROFILE_8_4), null)
		session.consumeErrorCodeAndClear() shouldBe null
		native.consumedSessions shouldBe emptyList()
	}

	test("unavailable bridge rejects transformation without publishing a session") {
		val native = FakeNativeMpvState(available = false)
		shouldThrow<IllegalStateException> {
			native.session().prepare(DoviTransformRequest(DoviTarget.PROFILE_8_1))
		}
		native.requests shouldBe emptyList()
	}
})

private data class FakeMpvSession(val ordinal: Long) : DoviMpvSession

private class FakeNativeMpvState(
	private val available: Boolean = true,
	private val failResetGeneration: Long? = null,
) {
	val calls = mutableListOf<String>()
	val requests = mutableListOf<DoviTransformRequest?>()
	val publishedSessions = mutableListOf<FakeMpvSession>()
	val consumedSessions = mutableListOf<FakeMpvSession>()
	private var current: FakeMpvSession? = null
	private var status = DoviStatus.OK

	fun session() = DoviMpvRequestSession(
		isAvailable = { available },
		publishRequest = ::publish,
		resetError = ::reset,
		consumeError = ::consume,
		getTransformObservation = ::getObservation,
	)

	private var observation: DoviTransformObservation? = null

	fun observe(session: FakeMpvSession, value: DoviTransformObservation) {
		if (session == current && observation == null) observation = value
	}

	fun record(session: FakeMpvSession, error: DoviStatus) {
		if (session == current && error != DoviStatus.OK && status == DoviStatus.OK) status = error
	}

	private fun publish(request: DoviTransformRequest?): DoviMpvSession {
		val session = FakeMpvSession((current?.ordinal ?: 0) + 1)
		current = session
		status = DoviStatus.OK
		observation = null
		requests += request
		publishedSessions += session
		calls += "publish:${session.ordinal}"
		return session
	}

	private fun reset(session: DoviMpvSession) {
		val token = session as FakeMpvSession
		calls += "reset:${token.ordinal}"
		if (token.ordinal == failResetGeneration) error("reset failed")
		if (token == current) status = DoviStatus.OK
	}

	private fun consume(session: DoviMpvSession): DoviStatus {
		val token = session as FakeMpvSession
		consumedSessions += token
		if (token != current) return DoviStatus.OK
		return status.also { status = DoviStatus.OK }
	}

	private fun getObservation(session: DoviMpvSession): DoviTransformObservation? =
		if (session == current) observation else null
}
