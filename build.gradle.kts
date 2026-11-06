import java.net.URI

plugins {
	alias(libs.plugins.android.application) apply false
	alias(libs.plugins.android.library) apply false
	alias(libs.plugins.detekt)
	java
}

val thorMedia3FfmpegDecoderAarUrl =
	"https://raw.githubusercontent.com/thor2002ro/jellyfin-androidx-media/master/OUTPUT/media3-ffmpeg-decoder-latest-SNAPSHOT.aar"
val thorMedia3FfmpegDecoderAarFile =
	layout.projectDirectory.file(".gradle/thor-media3/media3-ffmpeg-decoder-latest-SNAPSHOT.aar").asFile

if (!thorMedia3FfmpegDecoderAarFile.isFile || thorMedia3FfmpegDecoderAarFile.length() == 0L) {
	thorMedia3FfmpegDecoderAarFile.parentFile.mkdirs()
	val temporaryFile = thorMedia3FfmpegDecoderAarFile.resolveSibling("${thorMedia3FfmpegDecoderAarFile.name}.tmp")
	temporaryFile.delete()
	URI(thorMedia3FfmpegDecoderAarUrl).toURL().openStream().use { input ->
		temporaryFile.outputStream().use { output -> input.copyTo(output) }
	}
	if (!temporaryFile.renameTo(thorMedia3FfmpegDecoderAarFile)) {
		temporaryFile.copyTo(thorMedia3FfmpegDecoderAarFile, overwrite = true)
		temporaryFile.delete()
	}
}

if (!thorMedia3FfmpegDecoderAarFile.isFile || thorMedia3FfmpegDecoderAarFile.length() == 0L) {
	throw GradleException("Failed to download thor Media3 FFmpeg decoder from $thorMedia3FfmpegDecoderAarUrl")
}

extra["thorMedia3FfmpegDecoderAarFile"] = thorMedia3FfmpegDecoderAarFile

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
