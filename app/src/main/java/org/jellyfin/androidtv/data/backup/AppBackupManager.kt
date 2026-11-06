package org.jellyfin.androidtv.data.backup

import android.content.Context
import android.content.SharedPreferences
import org.jellyfin.androidtv.auth.store.AuthenticationStore
import java.io.File
import java.io.Reader

class AppBackupManager(
	private val context: Context,
	private val authenticationStore: AuthenticationStore,
) {
	fun createBackup(): String = AppBackupCodec.encode(captureSnapshot())

	fun restoreBackup(reader: Reader): AppBackupRestoreResult {
		val current = captureSnapshot()
		val decoded = AppBackupCodec.decode(reader)
		val restored = decoded.copy(
			preferences = buildMap {
				current.preferences.filterKeys(::isExcludedStore).forEach { (name, values) ->
					put(name, if (name == APP_CACHE_VERSION_STORE) values else emptyMap())
				}
				putAll(decoded.preferences)
			}
		)

		return restore(current, restored, ::writeSnapshot)
	}

	private fun captureSnapshot() = AppBackupSnapshot(
		preferences = preferenceNames().associateWith { name ->
			context.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapValues { (_, value) ->
				val storedValue = requireNotNull(value) { "Null preference value" }
				if (storedValue is Set<*>) storedValue.toSet() else storedValue
			}
		},
		servers = authenticationStore.getServers().mapValues { (_, server) ->
			server.copy(users = server.users.mapValues { (_, user) -> user.copy() })
		},
	)

	private fun writeSnapshot(snapshot: AppBackupSnapshot): Boolean {
		val names = (preferenceNames() + snapshot.preferences.keys).toSortedSet()
		for (name in names) {
			val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
			for ((key, value) in snapshot.preferences[name].orEmpty()) {
				if (!editor.putValue(key, value)) return false
			}
			if (!editor.commit()) return false
		}

		return authenticationStore.replaceServers(snapshot.servers)
	}

	private fun preferenceNames(): Set<String> = File(context.applicationInfo.dataDir, SHARED_PREFERENCES_DIRECTORY)
		.listFiles { file -> file.isFile && file.extension == "xml" }
		.orEmpty()
		.mapTo(linkedSetOf()) { file -> file.nameWithoutExtension }
}

private fun SharedPreferences.Editor.putValue(key: String, value: Any): Boolean {
	when (value) {
		is String -> putString(key, value)
		is Boolean -> putBoolean(key, value)
		is Int -> putInt(key, value)
		is Long -> putLong(key, value)
		is Float -> putFloat(key, value)
		is Set<*> -> {
			if (value.any { it !is String }) return false
			@Suppress("UNCHECKED_CAST")
			putStringSet(key, value as Set<String>)
		}
		else -> return false
	}
	return true
}

private const val SHARED_PREFERENCES_DIRECTORY = "shared_prefs"
private const val APP_CACHE_VERSION_STORE = "app_cache_version"
