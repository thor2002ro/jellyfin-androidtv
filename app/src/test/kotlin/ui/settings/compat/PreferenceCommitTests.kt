package org.jellyfin.androidtv.ui.settings.compat

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.jellyfin.preference.Preference
import org.jellyfin.preference.booleanPreference
import org.jellyfin.preference.enumPreference
import org.jellyfin.preference.migration.MigrationContext
import org.jellyfin.preference.store.AsyncPreferenceStore

class PreferenceCommitTests : FunSpec({
	test("a failed asynchronous preference save restores the previous value") {
		runBlocking {
			val store = TestAsyncPreferenceStore(commitSucceeds = false)
			val preference = booleanPreference("enabled", false)

			val result = commitPreferenceChange(store, preference, true)

			result.succeeded shouldBe false
			result.value shouldBe false
			store[preference] shouldBe false
		}
	}

	test("a successful asynchronous preference save retains the new value") {
		runBlocking {
			val store = TestAsyncPreferenceStore(commitSucceeds = true)
			val preference = booleanPreference("enabled", false)

			val result = commitPreferenceChange(store, preference, true)

			result.succeeded shouldBe true
			result.value shouldBe true
			store[preference] shouldBe true
		}
	}

	test("a failed Home section save restores every previous section") {
		runBlocking {
			val store = TestAsyncPreferenceStore(commitSucceeds = false)
			val first = enumPreference("first", TestSection.FIRST)
			val second = enumPreference("second", TestSection.SECOND)

			commitEnumPreferenceChanges(
				store = store,
				changes = listOf(first to TestSection.SECOND, second to TestSection.FIRST),
			) shouldBe false

			store[first] shouldBe TestSection.FIRST
			store[second] shouldBe TestSection.SECOND
		}
	}

	test("a cancelled preference save restores the previous value") {
		runBlocking {
			val store = TestAsyncPreferenceStore(commitFailure = CancellationException("cancelled"))
			val preference = booleanPreference("enabled", false)

			shouldThrow<CancellationException> {
				commitPreferenceChange(store, preference, true)
			}

			store[preference] shouldBe false
		}
	}

	test("a cancelled Home section save restores every previous section") {
		runBlocking {
			val store = TestAsyncPreferenceStore(commitFailure = CancellationException("cancelled"))
			val first = enumPreference("first", TestSection.FIRST)
			val second = enumPreference("second", TestSection.SECOND)

			shouldThrow<CancellationException> {
				commitEnumPreferenceChanges(
					store = store,
					changes = listOf(first to TestSection.SECOND, second to TestSection.FIRST),
				)
			}

			store[first] shouldBe TestSection.FIRST
			store[second] shouldBe TestSection.SECOND
		}
	}
})

private enum class TestSection { FIRST, SECOND }

private class TestAsyncPreferenceStore(
	private val commitSucceeds: Boolean = true,
	private val commitFailure: Throwable? = null,
) : AsyncPreferenceStore<Unit, Unit>() {
	private val values = mutableMapOf<String, Any>()
	override val shouldUpdate = false

	override suspend fun commit(): Boolean {
		commitFailure?.let { throw it }
		return commitSucceeds
	}
	override suspend fun update() = true
	override fun <T : Any> delete(preference: Preference<T>) {
		values.remove(preference.key)
	}

	override fun getInt(key: String, defaultValue: Int) = values[key] as? Int ?: defaultValue
	override fun getLong(key: String, defaultValue: Long) = values[key] as? Long ?: defaultValue
	override fun getFloat(key: String, defaultValue: Float) = values[key] as? Float ?: defaultValue
	override fun getBool(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
	override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
	override fun setInt(key: String, value: Int) = set(key, value)
	override fun setLong(key: String, value: Long) = set(key, value)
	override fun setFloat(key: String, value: Float) = set(key, value)
	override fun setBool(key: String, value: Boolean) = set(key, value)
	override fun setString(key: String, value: String) = set(key, value)
	@Suppress("UNCHECKED_CAST")
	override fun <T : Enum<T>> getEnum(preference: Preference<T>) = values[preference.key] as? T ?: preference.defaultValue
	override fun <V : Enum<V>> setEnum(preference: Preference<*>, value: Enum<V>) = set(preference.key, value)
	override fun runMigrations(body: MigrationContext<Unit, Unit>.() -> Unit) = Unit

	private fun set(key: String, value: Any) {
		values[key] = value
	}
}
