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
		"Expected exactly one thor Media3 FFmpeg decoder AAR in $outputDir, found ${files.size}"
	}
	files.single()
}

if (!customMedia3FfmpegDecoderAarFile.isFile || customMedia3FfmpegDecoderAarFile.length() == 0L) {
	throw GradleException("Missing thor Media3 FFmpeg decoder at $customMedia3FfmpegDecoderAarFile")
}

extra["customMedia3FfmpegDecoderAarFile"] = customMedia3FfmpegDecoderAarFile

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
