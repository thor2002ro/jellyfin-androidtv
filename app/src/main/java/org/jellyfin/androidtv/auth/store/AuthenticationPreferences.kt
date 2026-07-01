package org.jellyfin.androidtv.auth.store

import android.content.Context
import org.jellyfin.androidtv.auth.model.AuthenticationSortBy
import org.jellyfin.androidtv.preference.constant.UserSelectBehavior
import org.jellyfin.preference.booleanPreference
import org.jellyfin.preference.enumPreference
import org.jellyfin.preference.store.SharedPreferenceStore
import org.jellyfin.preference.stringPreference
import java.util.UUID

class AuthenticationPreferences(context: Context) : SharedPreferenceStore(
	sharedPreferences = context.getSharedPreferences("authentication", Context.MODE_PRIVATE)
) {
	companion object {
		// Preferences
		val autoLoginUserBehavior = enumPreference("auto_login_user_behavior", UserSelectBehavior.LAST_USER)
		val autoLoginServerId = stringPreference("auto_login_server_id", "")
		val autoLoginUserId = stringPreference("auto_login_user_id", "")

		val sortBy = enumPreference("sort_by", AuthenticationSortBy.LAST_USE)
		val alwaysAuthenticate = booleanPreference("always_authenticate", false)

		// Persistent state
		val lastServerId = stringPreference("last_server_id", "")
		val lastUserId = stringPreference("last_user_id", "")
	}

	init {
		runMigrations {
			// v0.15.4 to v0.15.5
			migration(toVersion = 2) {
				// Unfortunately we cannot migrate the "specific user" login option
				// so we'll reset the preference to disabled if it was used
				if (it.getString("auto_login_user_behavior", null) === UserSelectBehavior.SPECIFIC_USER.name) {
					putString("auto_login_user_id", "")
					putString("auto_login_user_behavior", UserSelectBehavior.DISABLED.name)
				}

				putString("last_user_id", "")
			}
		}
	}

	fun clearServer(serverId: UUID) {
		val serverIdString = serverId.toString()

		if (this[lastServerId] == serverIdString) {
			this[lastServerId] = ""
			this[lastUserId] = ""
		}

		if (this[autoLoginServerId] == serverIdString) {
			clearSpecificAutoLogin()
		}
	}

	fun clearUser(serverId: UUID, userId: UUID) {
		val serverIdString = serverId.toString()
		val userIdString = userId.toString()

		if (this[lastServerId] == serverIdString && this[lastUserId] == userIdString) {
			this[lastUserId] = ""
		}

		if (this[autoLoginServerId] == serverIdString && this[autoLoginUserId] == userIdString) {
			clearSpecificAutoLogin()
		}
	}

	private fun clearSpecificAutoLogin() {
		this[autoLoginServerId] = ""
		this[autoLoginUserId] = ""

		if (this[autoLoginUserBehavior] == UserSelectBehavior.SPECIFIC_USER) {
			this[autoLoginUserBehavior] = UserSelectBehavior.DISABLED
		}
	}
}
