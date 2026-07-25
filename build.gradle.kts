plugins {
	alias(libs.plugins.android.application) apply false
	alias(libs.plugins.android.library) apply false
	alias(libs.plugins.detekt)
	java
}

val customMedia3FfmpegDecoderAarFile = run {
	val outputDir = layout.projectDirectory.dir("dependencies/jellyfin-androidx-media/OUTPUT").asFile
	val files = outputDir.listFiles { file ->
		file.isFile && file.name.matches(Regex("""media3-ffmpeg-decoder-.+\.aar"""))
	}.orEmpty()

	require(files.size == 1) {
		"Expected exactly one custom Media3 FFmpeg decoder AAR in $outputDir, found ${files.size}"
	}
	files.single()
}
val customMedia3AarFiles = run {
	val outputDir = layout.projectDirectory.dir("dependencies/jellyfin-androidx-media/OUTPUT").asFile
	listOf(
		"common",
		"container",
		"database",
		"datasource",
		"datasource-okhttp",
		"decoder",
		"extractor",
		"exoplayer",
		"exoplayer-hls",
		"session",
		"ui",
	).associateWith { artifact ->
		outputDir.resolve("media3-$artifact-patched-release.aar").also { file ->
			if (!file.isFile || file.length() == 0L) {
				throw GradleException("Missing patched Media3 AAR at $file; run dependencies/jellyfin-androidx-media/build.bat")
			}
		}
	}
}
val customMedia3Version = run {
	val versionFile = layout.projectDirectory.file("dependencies/jellyfin-androidx-media/OUTPUT/media3-version.txt").asFile
	if (versionFile.isFile) {
		versionFile.readText().trim()
	} else {
		val mediaVersionFile = layout.projectDirectory.file("dependencies/jellyfin-androidx-media/media/constants.gradle").asFile
		val releaseVersion = requireNotNull(Regex("""releaseVersion\s*=\s*['"]([^'"]+)['"]""").find(mediaVersionFile.readText())) {
			"Could not read Media3 releaseVersion from $mediaVersionFile"
		}.groupValues[1]
		val mediaDir = layout.projectDirectory.dir("dependencies/jellyfin-androidx-media/media").asFile.absolutePath
		val commit = providers.exec {
			commandLine("git", "-c", "safe.directory=$mediaDir", "-C", mediaDir, "rev-parse", "--short", "HEAD")
		}.standardOutput.asText.get().trim()

		"$releaseVersion+$commit"
	}
}
val customMedia3FfmpegDecoderVersion = customMedia3Version
val customFfmpegVersion = run {
	val versionFile = layout.projectDirectory.file("dependencies/jellyfin-androidx-media/OUTPUT/ffmpeg-version.txt").asFile
	if (versionFile.isFile) {
		versionFile.readText().trim()
	} else {
		val releaseVersion = layout.projectDirectory.file("dependencies/jellyfin-androidx-media/ffmpeg/RELEASE").asFile.readText().trim()
		val ffmpegDir = layout.projectDirectory.dir("dependencies/jellyfin-androidx-media/ffmpeg").asFile.absolutePath
		val commit = providers.exec {
			commandLine("git", "-c", "safe.directory=$ffmpegDir", "-C", ffmpegDir, "rev-parse", "--short", "HEAD")
		}.standardOutput.asText.get().trim()

		"$releaseVersion+$commit"
	}
}
val customLibyuvVersion = run {
	val versionFile = layout.projectDirectory.file("dependencies/jellyfin-androidx-media/OUTPUT/libyuv-version.txt").asFile
	if (versionFile.isFile) {
		versionFile.readText().trim()
	} else {
		val libyuvDir = layout.projectDirectory.dir("dependencies/jellyfin-androidx-media/build/libyuv").asFile.absolutePath
		val branch = providers.exec {
			commandLine("git", "-c", "safe.directory=$libyuvDir", "-C", libyuvDir, "branch", "--show-current")
		}.standardOutput.asText.get().trim().ifBlank { "unknown" }
		val commit = providers.exec {
			commandLine("git", "-c", "safe.directory=$libyuvDir", "-C", libyuvDir, "rev-parse", "--short", "HEAD")
		}.standardOutput.asText.get().trim()

		"$branch+$commit"
	}
}

if (!customMedia3FfmpegDecoderAarFile.isFile || customMedia3FfmpegDecoderAarFile.length() == 0L) {
	throw GradleException("Missing custom Media3 FFmpeg decoder at $customMedia3FfmpegDecoderAarFile")
}

extra["customMedia3FfmpegDecoderAarFile"] = customMedia3FfmpegDecoderAarFile
extra["customMedia3AarFiles"] = customMedia3AarFiles
extra["customMedia3Version"] = customMedia3Version
extra["customMedia3FfmpegDecoderVersion"] = customMedia3FfmpegDecoderVersion
extra["customFfmpegVersion"] = customFfmpegVersion
extra["customLibyuvVersion"] = customLibyuvVersion

buildscript {
	dependencies {
		classpath(libs.kotlin.gradle)
	}
}

java {
	toolchain {
		languageVersion.set(JavaLanguageVersion.of(libs.versions.java.jdk.get()))
	}
}

detekt {
	toolVersion = libs.versions.detekt.get()
	buildUponDefaultConfig = true
	ignoreFailures = true
	config.setFrom(files("$rootDir/detekt.yaml"))
	basePath.set(rootProject.layout.projectDirectory)
	parallel = true

	source.setFrom(fileTree(projectDir) {
		include("**/*.kt", "**/*.kts")
	})
}

tasks.withType<dev.detekt.gradle.Detekt> {
	reports {
		sarif.required.set(true)
	}
}

tasks.withType<Test> {
	// Ensure Junit emits the full stack trace when a unit test fails through gradle
	useJUnit()

	testLogging {
		events(
			org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
			org.gradle.api.tasks.testing.logging.TestLogEvent.STANDARD_ERROR,
			org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED
		)
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
		showExceptions = true
		showCauses = true
		showStackTraces = true
	}
}

subprojects {
	tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
		compilerOptions {
			freeCompilerArgs.add("-opt-in=androidx.media3.common.util.UnstableApi")
		}
	}
}
