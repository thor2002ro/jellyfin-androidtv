package org.jellyfin.androidtv.ui.settings.compat

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.jellyfin.androidtv.R
import org.jellyfin.preference.Preference
import org.jellyfin.preference.store.PreferenceStore

internal fun showPreferenceSaveFailure(context: Context) {
	Toast.makeText(context, R.string.preference_save_failed, Toast.LENGTH_LONG).show()
}

/**
 * Utility to wrap the [PreferenceStore] getter/setter as [MutableState] to recompose when the value is changed. Uses a **local** state for
 * the preference, updates to the preference not done via this function won't trigger recomposition or update its value.
 */
@Composable
fun <ME, MV, T : Any> rememberPreference(
	store: PreferenceStore<ME, MV>,
	preference: Preference<T>,
	onCommitted: (T) -> Unit = {},
): MutableState<T> {
	val context = LocalContext.current
	val mutableState = remember { mutableStateOf(store[preference]) }
	LaunchedEffect(mutableState.value) {
		if (store[preference] != mutableState.value) {
			val result = commitPreferenceChange(store, preference, mutableState.value)
			mutableState.value = result.value
			if (result.succeeded) onCommitted(result.value)
			else showPreferenceSaveFailure(context)
		}
	}
	return mutableState
}

@Composable
@JvmName("rememberEnumPreference")
fun <ME, MV, T : Enum<T>> rememberPreference(
	store: PreferenceStore<ME, MV>,
	preference: Preference<T>,
	onCommitted: (T) -> Unit = {},
): MutableState<T> {
	val context = LocalContext.current
	val mutableState = remember { mutableStateOf(store[preference]) }
	LaunchedEffect(mutableState.value) {
		if (store[preference] != mutableState.value) {
			val result = commitPreferenceChange(store, preference, mutableState.value)
			mutableState.value = result.value
			if (result.succeeded) onCommitted(result.value)
			else showPreferenceSaveFailure(context)
		}
	}
	return mutableState
}
