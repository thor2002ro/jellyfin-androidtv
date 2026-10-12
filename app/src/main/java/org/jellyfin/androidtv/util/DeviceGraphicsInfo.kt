package org.jellyfin.androidtv.util

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

internal data class DeviceGraphicsInfo(
	val gpuName: String,
	val socName: String?,
	val openGlVersion: String?,
	val vulkanApiVersion: Int?,
) {
	val label = listOfNotNull(gpuName, socName)
		.distinctBy(String::lowercase)
		.joinToString(" / ")
	val vulkanVersion = vulkanApiVersion?.let(::formatVulkanVersion)

	fun apiVersion(api: String): String? = when (api) {
		"opengl" -> openGlVersion?.removePrefix("OpenGL ")
		"vulkan" -> vulkanVersion
		else -> null
	}
}

internal object DeviceGraphicsInfoProvider {
	private lateinit var detection: Deferred<DeviceGraphicsInfo>
	@Volatile
	private var detected: DeviceGraphicsInfo? = null

	fun initialize(context: Context) {
		if (::detection.isInitialized) return
		detection = ProcessLifecycleOwner.get().lifecycleScope.async(Dispatchers.IO) {
			detectDeviceGraphicsInfo(context.applicationContext)
				.also { detected = it }
		}
	}

	suspend fun get(): DeviceGraphicsInfo = detection.await()
	fun getNow(): DeviceGraphicsInfo? = detected
}

internal fun detectDeviceGraphicsInfo(context: Context): DeviceGraphicsInfo {
	val openGl = runCatching(::detectOpenGlInfo).getOrNull()
	val declaredOpenGlVersion = runCatching {
		(context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
			?.deviceConfigurationInfo
			?.glEsVersion
			?.let { "OpenGL ES $it" }
	}.getOrNull()
	val vulkanApiVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
		runCatching {
			context.packageManager.systemAvailableFeatures
				.firstOrNull { feature -> feature.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
				?.version
				?.takeIf { version -> version > 0 }
		}.getOrNull()
	} else {
		null
	}

	return DeviceGraphicsInfo(
		gpuName = openGl?.renderer?.sanitizeGpuLabel() ?: "GPU",
		socName = detectSocLabel(),
		openGlVersion = selectOpenGlVersion(openGl?.version, declaredOpenGlVersion),
		vulkanApiVersion = vulkanApiVersion,
	)
}

internal fun formatVulkanVersion(version: Int) =
	"${version ushr 22}.${version ushr 12 and 0x3ff}.${version and 0xfff}"

internal fun selectOpenGlVersion(actual: String?, declared: String?): String? =
	listOfNotNull(actual?.detectedOpenGlVersion(), declared)
		.maxByOrNull { version -> version.openGlVersionCode() }

private data class OpenGlInfo(
	val renderer: String?,
	val version: String?,
)

private fun detectOpenGlInfo(): OpenGlInfo? {
	val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
	if (display == EGL14.EGL_NO_DISPLAY) return null

	val previousDisplay = EGL14.eglGetCurrentDisplay()
	val previousContext = EGL14.eglGetCurrentContext()
	val previousDrawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
	val previousReadSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
	val hadPreviousContext = previousDisplay != EGL14.EGL_NO_DISPLAY && previousContext != EGL14.EGL_NO_CONTEXT
	var initialized = false
	var madeCurrent = false
	var releaseThread = false
	var surface = EGL14.EGL_NO_SURFACE
	var context = EGL14.EGL_NO_CONTEXT
	try {
		initialized = EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)
		if (!initialized) return null

		val configs = arrayOfNulls<EGLConfig>(1)
		val configCount = IntArray(1)
		val configAttributes = intArrayOf(
			EGL14.EGL_RENDERABLE_TYPE,
			EGL14.EGL_OPENGL_ES2_BIT,
			EGL14.EGL_SURFACE_TYPE,
			EGL14.EGL_PBUFFER_BIT,
			EGL14.EGL_RED_SIZE,
			8,
			EGL14.EGL_GREEN_SIZE,
			8,
			EGL14.EGL_BLUE_SIZE,
			8,
			EGL14.EGL_NONE,
		)
		if (!EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, configCount, 0)) return null
		val config = configs.firstOrNull() ?: return null
		if (configCount[0] <= 0) return null

		surface = EGL14.eglCreatePbufferSurface(
			display,
			config,
			intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
			0,
		)
		if (surface == EGL14.EGL_NO_SURFACE) return null

		context = EGL14.eglCreateContext(
			display,
			config,
			EGL14.EGL_NO_CONTEXT,
			intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
			0,
		)
		if (context == EGL14.EGL_NO_CONTEXT) return null
		if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null
		madeCurrent = true

		return OpenGlInfo(
			renderer = GLES20.glGetString(GLES20.GL_RENDERER),
			version = GLES20.glGetString(GLES20.GL_VERSION),
		)
	} finally {
		if (initialized) {
			if (madeCurrent) {
				if (hadPreviousContext) {
					if (!EGL14.eglMakeCurrent(previousDisplay, previousDrawSurface, previousReadSurface, previousContext)) {
						EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
					}
				} else {
					EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
					releaseThread = true
				}
			}
			if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
			if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
			if (releaseThread) EGL14.eglReleaseThread()
		}
	}
}

private fun detectSocLabel(): String? = buildList {
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Build.SOC_MODEL)
	addAll(readCpuInfoValues())
	add(Build.HARDWARE)
	add(Build.BOARD)
}.firstNotNullOfOrNull { value -> value.sanitizeSocLabel() }

private fun readCpuInfoValues(): List<String> = runCatching {
	val values = mutableMapOf<String, String>()
	File("/proc/cpuinfo").useLines { lines ->
		lines.forEach { line ->
			val separator = line.indexOf(':')
			if (separator <= 0) return@forEach
			val key = line.substring(0, separator).trim().lowercase()
			if (key in CpuInfoKeys && key !in values) {
				values[key] = line.substring(separator + 1).trim()
			}
		}
	}
	CpuInfoKeys.mapNotNull(values::get)
}.getOrDefault(emptyList())

private fun String?.sanitizeSocLabel(): String? = this
	?.trim()
	?.replace(Regex("\\s+"), " ")
	?.takeIf { label -> label.isNotBlank() && !label.equals("unknown", ignoreCase = true) }
	?.let(SocModelRegex::find)
	?.value

private fun String.detectedOpenGlVersion() =
	OpenGlVersionRegex.find(this)?.value ?: takeIf(String::isNotBlank)

private fun String.openGlVersionCode(): Int {
	val match = OpenGlNumericVersionRegex.find(this) ?: return 0
	val (major, minor) = match.destructured
	return major.toIntOrNull().orZero() * 1_000 + minor.toIntOrNull().orZero()
}

private fun Int?.orZero() = this ?: 0

private fun String.sanitizeGpuLabel(): String? {
	val clean = trim()
		.replace("(TM)", "")
		.replace(Regex("[(),;]+"), " ")
		.replace(Regex("\\s+"), " ")
		.trim()
		.takeIf { label -> label.isNotBlank() && !label.equals("unknown", ignoreCase = true) }
		?: return null

	val tokens = clean.split(' ')
		.map(String::trim)
		.filter(String::isNotBlank)
		.filterNot { token -> token.normalizedGpuRendererToken() in GpuRendererNoiseTokens }
		.filterNot { token -> GpuRendererVersionToken.matches(token.lowercase()) }
	if (tokens.isEmpty()) return clean

	val modelStart = tokens.indexOfFirst { token -> token.isGpuModelToken() }
	val modelTokens = when {
		modelStart > 0 && tokens[modelStart - 1].isGpuVendorToken() -> tokens.drop(modelStart - 1)
		modelStart >= 0 -> tokens.drop(modelStart)
		else -> tokens
	}
	return modelTokens
		.take(GPU_RENDERER_LABEL_MAX_WORDS)
		.joinToString(" ")
		.takeIf(String::isNotBlank)
		?: clean
}

private fun String.normalizedGpuRendererToken() = trim(',', '(', ')')
	.lowercase()
	.removeSuffix(":")

private fun String.isGpuModelToken(): Boolean {
	val normalized = normalizedGpuRendererToken()
	return isGpuVendorToken() || normalized.any(Char::isDigit) || normalized.startsWith("gc")
}

private fun String.isGpuVendorToken() = normalizedGpuRendererToken() in GpuRendererVendorTokens

private const val GPU_RENDERER_LABEL_MAX_WORDS = 4
private val OpenGlVersionRegex = Regex("OpenGL ES(?:-CM)?\\s+\\d+(?:\\.\\d+)+", RegexOption.IGNORE_CASE)
private val OpenGlNumericVersionRegex = Regex("(\\d+)\\.(\\d+)")
private val SocModelRegex = Regex("\\b[A-Za-z]{1,8}\\d[A-Za-z0-9._-]*\\b")
private val CpuInfoKeys = listOf("hardware", "model name", "processor")
private val GpuRendererVersionToken = Regex("(?:[vrp]\\d+(?:[._-]?[a-z]?\\d+)+.*|\\d+(?:[._-][a-z]?\\d+)+.*)")
private val GpuRendererVendorTokens = setOf("adreno", "immortalis", "mali", "nvidia", "powervr", "tegra", "vivante")
private val GpuRendererNoiseTokens = setOf(
	"android",
	"angle",
	"arm",
	"es",
	"google",
	"graphics",
	"inc",
	"llc",
	"opengl",
	"renderer",
	"technologies",
	"vulkan",
)
