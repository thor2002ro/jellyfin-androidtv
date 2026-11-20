package org.jellyfin.androidtv.integration.dream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.androidtv.R
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

internal enum class DreamWeatherCondition(val label: Int?) {
	CLEAR(R.string.screensaver_weather_clear),
	PARTLY_CLOUDY(R.string.screensaver_weather_partly_cloudy),
	CLOUDY(R.string.screensaver_weather_cloudy),
	FOG(R.string.screensaver_weather_fog),
	DRIZZLE(R.string.screensaver_weather_drizzle),
	RAIN(R.string.screensaver_weather_rain),
	SNOW(R.string.screensaver_weather_snow),
	THUNDERSTORM(R.string.screensaver_weather_thunderstorm),
	UNKNOWN(null),
}

internal data class DreamWeatherReading(
	val celsius: Double,
	val condition: DreamWeatherCondition,
	val city: String?,
	val isDay: Boolean,
)

@Serializable
internal data class DreamWeatherLocation(val name: String, val region: String, val latitude: Double, val longitude: Double) {
	fun encode(): String = Json.encodeToString(this)

	companion object {
		fun decode(value: String): DreamWeatherLocation? {
			if (value.isBlank()) return null
			return try {
				Json.decodeFromString<DreamWeatherLocation>(value).takeIf {
					it.name.isNotBlank() && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
				}
			} catch (_: SerializationException) {
				null
			}
		}
	}
}

internal object DreamWeather {
	private data class ForecastLocation(val latitude: Double, val longitude: Double, val city: String?)

	private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
	private val lock = Mutex()
	private var automaticLocation: ForecastLocation? = null
	private var locatedAt: TimeMark? = null
	private var reading: DreamWeatherReading? = null
	private var fetchedAt: TimeMark? = null
	private var attemptedAt: TimeMark? = null
	private var selectedLocation: DreamWeatherLocation? = null

	suspend fun current(location: DreamWeatherLocation?): DreamWeatherReading? = lock.withLock {
		val automaticLocationExpired = location == null && locatedAt?.elapsedNow()?.let { it >= 6.hours } == true
		if (automaticLocationExpired) {
			automaticLocation = null
			locatedAt = null
		}
		if (location != selectedLocation || automaticLocationExpired) {
			selectedLocation = location
			reading = null
			fetchedAt = null
			attemptedAt = null
		}
		if (fetchedAt?.elapsedNow()?.let { it < 30.minutes } == true) return reading
		if (attemptedAt?.elapsedNow()?.let { it < 5.minutes } != true) {
			if (location == null && automaticLocation == null) {
				locate()?.let { location ->
					automaticLocation = location
					locatedAt = TimeSource.Monotonic.markNow()
				}
			}
			val forecastLocation = location?.let { ForecastLocation(it.latitude, it.longitude, it.name) } ?: automaticLocation
			forecastLocation?.let { position ->
				forecast(position)?.let { weather ->
					reading = weather
					fetchedAt = TimeSource.Monotonic.markNow()
				}
			}
			attemptedAt = TimeSource.Monotonic.markNow()
		}
		reading?.takeIf { fetchedAt?.elapsedNow()?.let { age -> age < 2.hours } == true }
	}

	suspend fun searchLocations(query: String): List<DreamWeatherLocation>? {
		val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
			.addQueryParameter("name", query)
			.addQueryParameter("count", "8")
			.addQueryParameter("language", Locale.getDefault().language)
			.build()
		val response = fetchJson(url.toString()) ?: return null
		if (response.optBoolean("error")) return null
		val results = response.optJSONArray("results") ?: return emptyList()
		return (0 until results.length()).mapNotNull { index ->
			val result = results.optJSONObject(index) ?: return@mapNotNull null
			val name = result.optString("name")
			val latitude = result.optDouble("latitude")
			val longitude = result.optDouble("longitude")
			if (name.isBlank() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return@mapNotNull null
			val region = listOf(result.optString("admin1"), result.optString("country"))
				.filter(String::isNotBlank).distinct().joinToString(", ")
			DreamWeatherLocation(name, region, latitude, longitude)
		}.distinct()
	}

	private suspend fun locate(): ForecastLocation? {
		val location = fetchJson("https://ipwho.is/?fields=success,city,latitude,longitude") ?: return null
		if (!location.optBoolean("success")) return null
		val latitude = location.optDouble("latitude")
		val longitude = location.optDouble("longitude")
		if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
		// Current conditions only need approximate coordinates.
		val city = if (location.isNull("city")) null else location.optString("city").trim().takeIf(String::isNotEmpty)
		return ForecastLocation((latitude * 100).roundToInt() / 100.0, (longitude * 100).roundToInt() / 100.0, city)
	}

	private suspend fun forecast(location: ForecastLocation): DreamWeatherReading? {
		val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
			.addQueryParameter("latitude", location.latitude.toString())
			.addQueryParameter("longitude", location.longitude.toString())
			.addQueryParameter("current", "temperature_2m,weather_code,is_day")
			.build()
		val current = fetchJson(url.toString())?.optJSONObject("current") ?: return null
		val temperature = current.optDouble("temperature_2m")
		val code = current.optInt("weather_code", -1)
		if (temperature !in -100.0..100.0 || code !in 0..99) return null
		val condition = when (code) {
			0 -> DreamWeatherCondition.CLEAR
			1, 2 -> DreamWeatherCondition.PARTLY_CLOUDY
			3 -> DreamWeatherCondition.CLOUDY
			45, 48 -> DreamWeatherCondition.FOG
			51, 53, 55, 56, 57 -> DreamWeatherCondition.DRIZZLE
			61, 63, 65, 66, 67, 80, 81, 82 -> DreamWeatherCondition.RAIN
			71, 73, 75, 77, 85, 86 -> DreamWeatherCondition.SNOW
			95, 96, 99 -> DreamWeatherCondition.THUNDERSTORM
			else -> DreamWeatherCondition.UNKNOWN
		}
		return DreamWeatherReading(temperature, condition, location.city, current.optInt("is_day", 1) == 1)
	}

	private suspend fun fetchJson(url: String): JSONObject? = runInterruptible(Dispatchers.IO) {
		try {
			client.newCall(Request.Builder().url(url).build()).execute().use { response ->
				if (response.isSuccessful) response.body?.string()?.let(::JSONObject) else null
			}
		} catch (_: IOException) {
			null
		} catch (_: JSONException) {
			null
		}
	}
}
