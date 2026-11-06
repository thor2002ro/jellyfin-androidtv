package org.jellyfin.androidtv.data.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.auth.model.AuthenticationStoreServer
import org.jellyfin.androidtv.auth.model.AuthenticationStoreUser
import java.io.Reader
import java.util.UUID

data class AppBackupSnapshot(
	val preferences: Map<String, Map<String, Any>>,
	val servers: Map<UUID, AuthenticationStoreServer>,
)

enum class AppBackupRestoreResult {
	RESTORED,
	FAILED,
	ROLLBACK_FAILED,
}

fun restore(
	current: AppBackupSnapshot,
	restored: AppBackupSnapshot,
	write: (AppBackupSnapshot) -> Boolean,
): AppBackupRestoreResult {
	if (runCatching { write(restored) }.getOrDefault(false)) return AppBackupRestoreResult.RESTORED
	return if (runCatching { write(current) }.getOrDefault(false)) {
		AppBackupRestoreResult.FAILED
	} else AppBackupRestoreResult.ROLLBACK_FAILED
}

fun isExcludedStore(name: String) = name in EXCLUDED_STORES

fun isExcludedPreference(store: String, key: String) =
	store == TELEMETRY_STORE && key in EXCLUDED_TELEMETRY_KEYS

object AppBackupCodec {
	private const val FORMAT = "jellyfin-androidtv-backup"
	private const val VERSION = 1
	private const val MAX_DOCUMENT_SIZE = 10 * 1024 * 1024
	private val safeStoreName = Regex("[A-Za-z0-9._-]+")

	private val json = Json {
		classDiscriminator = "type"
		encodeDefaults = true
		prettyPrint = true
	}

	fun encode(snapshot: AppBackupSnapshot): String {
		val document = BackupDocument(
			format = FORMAT,
			version = VERSION,
			stores = snapshot.preferences
				.filterKeys { !isExcludedStore(it) }
				.map { (name, values) ->
					require(name.matches(safeStoreName)) { "Unsafe preference store name" }
					BackupStore(
						name = name,
						entries = values
							.filterKeys { !isExcludedPreference(name, it) }
							.map { (key, value) -> BackupEntry(key, value.toBackupValue()) }
							.sortedBy(BackupEntry::key),
					)
				}
				.sortedBy(BackupStore::name),
			servers = snapshot.servers.map { (id, server) -> server.toBackupServer(id) }
				.sortedBy(BackupServer::id),
		)

		return json.encodeToString(document)
	}

	fun decode(reader: Reader): AppBackupSnapshot {
		val document = json.decodeFromString<BackupDocument>(reader.readLimited())
		require(document.format == FORMAT) { "Not a Jellyfin Android TV backup" }
		require(document.version == VERSION) { "Unsupported backup version" }

		return AppBackupSnapshot(
			preferences = document.stores.associateUnique(BackupStore::name) { store ->
				require(store.name.matches(safeStoreName)) { "Unsafe preference store name" }
				store.entries.associateUnique(BackupEntry::key) { entry -> entry.value.toPreferenceValue() }
			}.filterKeys { !isExcludedStore(it) }.mapValues { (store, values) ->
				values.filterKeys { !isExcludedPreference(store, it) }
			},
			servers = document.servers.associateUnique({ server -> server.id.toBackupUuid() }) { server ->
				server.toAuthenticationStoreServer()
			},
		)
	}

	private fun Reader.readLimited(): String {
		val text = StringBuilder()
		val buffer = CharArray(8_192)
		while (true) {
			val count = read(buffer)
			if (count < 0) return text.toString()
			require(text.length + count <= MAX_DOCUMENT_SIZE) { "Backup is too large" }
			text.append(buffer, 0, count)
		}
	}
}

@Serializable
private data class BackupDocument(
	val format: String,
	val version: Int,
	val stores: List<BackupStore>,
	val servers: List<BackupServer>,
)

@Serializable
private data class BackupStore(
	val name: String,
	val entries: List<BackupEntry>,
)

@Serializable
private data class BackupEntry(
	val key: String,
	val value: BackupValue,
)

@Serializable
private sealed interface BackupValue {
	@Serializable
	@SerialName("string")
	data class StringValue(val value: String) : BackupValue

	@Serializable
	@SerialName("boolean")
	data class BooleanValue(val value: Boolean) : BackupValue

	@Serializable
	@SerialName("integer")
	data class IntegerValue(val value: Int) : BackupValue

	@Serializable
	@SerialName("long")
	data class LongValue(val value: Long) : BackupValue

	@Serializable
	@SerialName("float")
	data class FloatValue(val value: Float) : BackupValue

	@Serializable
	@SerialName("string_set")
	data class StringSetValue(val value: List<String>) : BackupValue
}

@Serializable
private data class BackupServer(
	val id: String,
	val name: String,
	val address: String,
	val version: String?,
	@SerialName("login_disclaimer") val loginDisclaimer: String?,
	@SerialName("splashscreen_enabled") val splashscreenEnabled: Boolean,
	@SerialName("setup_completed") val setupCompleted: Boolean,
	@SerialName("last_used") val lastUsed: Long,
	@SerialName("last_refreshed") val lastRefreshed: Long,
	val users: List<BackupUser>,
)

@Serializable
private data class BackupUser(
	val id: String,
	val name: String,
	@SerialName("last_used") val lastUsed: Long,
	@SerialName("image_tag") val imageTag: String?,
)

private fun Any.toBackupValue(): BackupValue = when (this) {
	is String -> BackupValue.StringValue(this)
	is Boolean -> BackupValue.BooleanValue(this)
	is Int -> BackupValue.IntegerValue(this)
	is Long -> BackupValue.LongValue(this)
	is Float -> {
		require(isFinite()) { "Non-finite preference value" }
		BackupValue.FloatValue(this)
	}
	is Set<*> -> BackupValue.StringSetValue(map {
		require(it is String) { "Unsupported preference set value" }
		it
	}.sorted())
	else -> throw IllegalArgumentException("Unsupported preference value")
}

private fun BackupValue.toPreferenceValue(): Any = when (this) {
	is BackupValue.StringValue -> value
	is BackupValue.BooleanValue -> value
	is BackupValue.IntegerValue -> value
	is BackupValue.LongValue -> value
	is BackupValue.FloatValue -> value.also { require(it.isFinite()) { "Non-finite preference value" } }
	is BackupValue.StringSetValue -> value.toSet().also {
		require(it.size == value.size) { "Duplicate string-set value" }
	}
}

private fun AuthenticationStoreServer.toBackupServer(id: UUID) = BackupServer(
	id = id.toString(),
	name = name,
	address = address,
	version = version,
	loginDisclaimer = loginDisclaimer,
	splashscreenEnabled = splashscreenEnabled,
	setupCompleted = setupCompleted,
	lastUsed = lastUsed,
	lastRefreshed = lastRefreshed,
	users = users.map { (userId, user) ->
		BackupUser(
			id = userId.toString(),
			name = user.name,
			lastUsed = user.lastUsed,
			imageTag = user.imageTag,
		)
	}.sortedBy(BackupUser::id),
)

private fun BackupServer.toAuthenticationStoreServer() = AuthenticationStoreServer(
	name = name,
	address = address,
	version = version,
	loginDisclaimer = loginDisclaimer,
	splashscreenEnabled = splashscreenEnabled,
	setupCompleted = setupCompleted,
	lastUsed = lastUsed,
	lastRefreshed = lastRefreshed,
	users = users.associateUnique({ user -> user.id.toBackupUuid() }) { user ->
		AuthenticationStoreUser(
			name = user.name,
			lastUsed = user.lastUsed,
			imageTag = user.imageTag,
			accessToken = null,
		)
	},
)

private fun String.toBackupUuid() = UUID.fromString(this).also { uuid ->
	require(uuid.toString() == this) { "Non-canonical backup UUID" }
}

private inline fun <T, K, V> Iterable<T>.associateUnique(
	key: (T) -> K,
	value: (T) -> V,
): Map<K, V> = buildMap {
	for (item in this@associateUnique) {
		val itemKey = key(item)
		require(itemKey !in this) { "Duplicate backup identifier" }
		put(itemKey, value(item))
	}
}

private val EXCLUDED_STORES = setOf(
	"app_cache_version",
	"leanback_channels",
	"live_tv_tracks",
	"series_stream_badges_v1",
)
private const val TELEMETRY_STORE = "telemetry"
private val EXCLUDED_TELEMETRY_KEYS = setOf("server_token", "server_url")
