package org.jellyfin.playback.media3.exoplayer

import io.github.thor2002ro.libdovi.DoviTarget
import io.github.thor2002ro.libdovi.DoviTransformRequest
import io.github.thor2002ro.libdovi.DoviTransformStrategy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.playback.dovi.DoviDecision
import org.jellyfin.playback.dovi.DoviDecisionReason
import org.jellyfin.playback.dovi.DoviRoute

class DoviDirectSourceBaseRemovalTests : FunSpec({
	test("Media3 consumes the fast strategy recorded by the source-base route") {
		val request = DoviTransformRequest(DoviTarget.SOURCE_BASE_PRESENTATION)
		val decision = DoviDecision(
			route = DoviRoute.SourceBase(request, DoviTransformStrategy.FAST_SOURCE_BASE_FALLBACK),
			reason = DoviDecisionReason.SOURCE_BASE,
		)

		media3TransformStrategy(decision) shouldBe DoviTransformStrategy.FAST_SOURCE_BASE_FALLBACK
	}

	test("Media3 defaults non-source-base decisions to libdovi") {
		val decision = DoviDecision(
			route = DoviRoute.Transform(DoviTransformRequest(DoviTarget.PROFILE_8_1)),
			reason = DoviDecisionReason.PROFILE_8_1,
		)

		media3TransformStrategy(decision) shouldBe DoviTransformStrategy.LIBDOVI
	}
})
