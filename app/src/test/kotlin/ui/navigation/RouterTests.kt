package org.jellyfin.androidtv.ui.navigation

import androidx.compose.runtime.mutableStateListOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly

class RouterTests : FunSpec({
	test("back preserves the root route") {
		val root = RouteContext("/")
		val child = RouteContext("/child")
		val backStack = mutableStateListOf(root, child)
		val router = Router(emptyMap(), backStack)

		router.back()
		router.back()

		backStack shouldContainExactly listOf(root)
	}
})
