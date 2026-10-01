package org.jellyfin.playback.core.font

import android.content.Context
import android.graphics.Typeface
import android.system.ErrnoException
import android.system.Os
import android.util.AtomicFile
import androidx.core.graphics.TypefaceCompat
import timber.log.Timber
import java.io.File
import java.io.IOException

private const val FONT_CONFIG_ENVIRONMENT = "FONTCONFIG_FILE"
private const val BUNDLED_FONT_ENVIRONMENT = "LIBASS_BUNDLED_FONT_DIR"
private const val FONT_ASSET_DIRECTORY = "subtitle-fonts"
private const val BUNDLED_FONT_DIRECTORY = "noto-sans-v2.015"
private const val FONT_CONFIG_FILE = "jellyfin-subtitle-fonts.conf"
private const val REGULAR_FONT_FILE = "NotoSans-Regular.ttf"
private const val SEMIBOLD_FONT_FILE = "NotoSans-SemiBold.ttf"
private const val SEMIBOLD_WEIGHT = 600
private val BUNDLED_FONT_FILES = listOf(
	REGULAR_FONT_FILE,
	SEMIBOLD_FONT_FILE,
	"NotoSansSymbols-Regular.ttf",
	"NotoSansSymbols2-Regular.ttf",
)
private val PREFERRED_SANS_FAMILIES = listOf(
	"Noto Sans",
	"Noto Sans Symbols",
	"Noto Sans Symbols 2",
	"Noto Sans Arabic",
	"Noto Sans Hebrew",
	"Noto Sans Devanagari",
	"Noto Sans Thai",
	"Noto Sans CJK SC",
	"Noto Sans CJK TC",
	"Noto Sans CJK JP",
	"Noto Sans CJK KR",
)
private val DEVICE_FONT_DIRECTORIES = listOf(
	"/system/fonts",
	"/product/fonts",
	"/system_ext/fonts",
	"/vendor/fonts",
)

object SubtitleFontProvider {
	private val lock = Any()

	@Volatile
	private var fontConfigReady = false

	private val typefaces = mutableMapOf<String, Typeface>()

	@JvmStatic
	fun initialize(context: Context): Boolean = synchronized(lock) {
		if (fontConfigReady) return true

		val configDirectory = context.filesDir.resolve("subtitle-fonts")
		val fontDirectory = configDirectory.resolve(BUNDLED_FONT_DIRECTORY)
		val cacheDirectory = context.cacheDir.resolve("fontconfig")
		val configFile = configDirectory.resolve(FONT_CONFIG_FILE)

		try {
			fontDirectory.mkdirsOrThrow()
			cacheDirectory.mkdirsOrThrow()
			writeAtomically(
				configFile,
				buildSubtitleFontConfig(
					bundledFontsDirectory = fontDirectory.absolutePath,
					cacheDirectory = cacheDirectory.absolutePath,
				),
			)
			// Make device directories available even if extracting an asset fails.
			Os.setenv(FONT_CONFIG_ENVIRONMENT, configFile.absolutePath, true)
			BUNDLED_FONT_FILES.forEach { filename ->
				copyAssetIfNeeded(
					context = context,
					assetPath = "$FONT_ASSET_DIRECTORY/$filename",
					target = fontDirectory.resolve(filename),
				)
			}
			Os.setenv(BUNDLED_FONT_ENVIRONMENT, fontDirectory.absolutePath, true)
			fontConfigReady = true
			true
		} catch (error: IOException) {
			fontConfigFailure(error)
		} catch (error: ErrnoException) {
			fontConfigFailure(error)
		} catch (error: SecurityException) {
			fontConfigFailure(error)
		}
	}

	@JvmStatic
	fun typeface(context: Context, weight: Int): Typeface {
		initialize(context)
		val filename = if (weight >= SEMIBOLD_WEIGHT) SEMIBOLD_FONT_FILE else REGULAR_FONT_FILE
		val baseTypeface = synchronized(lock) {
			typefaces.getOrPut(filename) {
				loadFontOrFallback(Typeface.DEFAULT) {
					Typeface.createFromAsset(context.assets, "$FONT_ASSET_DIRECTORY/$filename")
				}
			}
		}
		return loadFontOrFallback(baseTypeface) {
			TypefaceCompat.create(context, baseTypeface, weight, false)
		}
	}
}

internal fun buildSubtitleFontConfig(
	bundledFontsDirectory: String,
	cacheDirectory: String,
): String = buildString {
	appendLine("<?xml version=\"1.0\"?>")
	appendLine("<fontconfig>")
	appendLine("  <dir>${bundledFontsDirectory.xmlEscape()}</dir>")
	DEVICE_FONT_DIRECTORIES.forEach { directory -> appendLine("  <dir>$directory</dir>") }
	appendLine("  <cachedir>${cacheDirectory.xmlEscape()}</cachedir>")
	appendLine("  <alias binding=\"strong\">")
	appendLine("    <family>sans-serif</family>")
	appendLine("    <prefer>")
	PREFERRED_SANS_FAMILIES.forEach { family -> appendLine("      <family>$family</family>") }
	appendLine("    </prefer>")
	appendLine("  </alias>")
	appendLine("</fontconfig>")
}

@Suppress("TooGenericExceptionCaught")
internal inline fun <T> loadFontOrFallback(fallback: T, load: () -> T): T = try {
	load()
} catch (error: RuntimeException) {
	Timber.w(error, "Unable to load bundled subtitle font; using device font")
	fallback
}

private fun fontConfigFailure(error: Throwable): Boolean {
	Timber.w(error, "Unable to prepare bundled subtitle fonts; using device fonts")
	return false
}

private fun File.mkdirsOrThrow() {
	if (!isDirectory && !mkdirs()) throw IOException("Unable to create directory: $absolutePath")
}

private fun copyAssetIfNeeded(context: Context, assetPath: String, target: File) {
	context.assets.open(assetPath).use { input ->
		if (target.isFile && target.length() == input.available().toLong()) return
		val temporary = File.createTempFile(target.name, ".tmp", target.parentFile)
		try {
			temporary.outputStream().use(input::copyTo)
			if (target.exists() && !target.delete()) throw IOException("Unable to replace: ${target.absolutePath}")
			if (!temporary.renameTo(target)) throw IOException("Unable to install: ${target.absolutePath}")
		} finally {
			temporary.delete()
		}
	}
}

private fun writeAtomically(target: File, contents: String) {
	if (target.isFile && target.readText() == contents) return
	val atomicFile = AtomicFile(target)
	val output = atomicFile.startWrite()
	try {
		output.write(contents.toByteArray(Charsets.UTF_8))
		atomicFile.finishWrite(output)
	} catch (error: IOException) {
		atomicFile.failWrite(output)
		throw error
	}
}

private fun String.xmlEscape() = buildString(length) {
	this@xmlEscape.forEach { character ->
		append(
			when (character) {
				'&' -> "&amp;"
				'<' -> "&lt;"
				'>' -> "&gt;"
				'\"' -> "&quot;"
				'\'' -> "&apos;"
				else -> character
			},
		)
	}
}
