package org.jellyfin.androidtv.ui.settings.compat

import org.jellyfin.preference.Preference
import org.jellyfin.preference.store.AsyncPreferenceStore
import org.jellyfin.preference.store.PreferenceStore

internal data class PreferenceCommitResult<T>(
	val succeeded: Boolean,
	val value: T,
)

internal class PreferenceSaveGuard {
	private var saveInProgress = false

	fun tryStart(): Boolean {
		if (saveInProgress) return false
		saveInProgress = true
		return true
	}

	fun finish() {
		saveInProgress = false
	}
}

internal suspend fun <ME, MV, T : Any> commitPreferenceChange(
	store: PreferenceStore<ME, MV>,
	preference: Preference<T>,
	value: T,
): PreferenceCommitResult<T> {
	val previousValue = store[preference]
	var succeeded = false
	try {
		store[preference] = value
		succeeded = store !is AsyncPreferenceStore || store.commit()
		return PreferenceCommitResult(succeeded, if (succeeded) value else previousValue)
	} finally {
		if (!succeeded) store[preference] = previousValue
	}
}

@JvmName("commitEnumPreferenceChange")
internal suspend fun <ME, MV, T : Enum<T>> commitPreferenceChange(
	store: PreferenceStore<ME, MV>,
	preference: Preference<T>,
	value: T,
): PreferenceCommitResult<T> {
	val previousValue = store[preference]
	var succeeded = false
	try {
		store[preference] = value
		succeeded = store !is AsyncPreferenceStore || store.commit()
		return PreferenceCommitResult(succeeded, if (succeeded) value else previousValue)
	} finally {
		if (!succeeded) store[preference] = previousValue
	}
}

internal suspend fun <ME, MV, T : Enum<T>> commitEnumPreferenceChanges(
	store: PreferenceStore<ME, MV>,
	changes: List<Pair<Preference<T>, T>>,
): Boolean {
	val previousValues = changes.map { (preference) -> preference to store[preference] }
	var succeeded = false
	try {
		changes.forEach { (preference, value) -> store[preference] = value }
		succeeded = store !is AsyncPreferenceStore || store.commit()
		return succeeded
	} finally {
		if (!succeeded) previousValues.forEach { (preference, value) -> store[preference] = value }
	}
}
