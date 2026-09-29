package org.jellyfin.androidtv.ui.navigation

import android.os.Bundle
import androidx.fragment.app.Fragment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk

class NavigationRepositoryTests : FunSpec({
	test("current destination follows navigation and restoration") {
		val root = destination<RootFragment>()
		val first = destination<FirstFragment>()
		val second = destination<SecondFragment>()
		val repository = NavigationRepositoryImpl(root)

		repository.navigate(first)
		repository.navigate(second)
		repository.currentDestination.value shouldBe second

		repository.goBack() shouldBe true
		repository.currentDestination.value shouldBe first

		repository.synchronizeCurrentDestination(second)
		repository.currentDestination.value shouldBe second
	}

	test("replacing current destination preserves navigation history") {
		val root = destination<RootFragment>()
		val first = destination<FirstFragment>()
		val second = destination<SecondFragment>()
		val repository = NavigationRepositoryImpl(root)

		repository.navigate(first)
		repository.navigate(second)
		repository.navigate(second, replace = true)

		repository.goBack() shouldBe true
		repository.currentDestination.value shouldBe first
	}
})

private inline fun <reified T : Fragment> destination() = Destination.Fragment(T::class, mockk<Bundle>())

private class RootFragment : Fragment()
private class FirstFragment : Fragment()
private class SecondFragment : Fragment()
