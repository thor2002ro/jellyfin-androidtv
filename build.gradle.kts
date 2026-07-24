import java.util.Properties

plugins {
	alias(libs.plugins.android.application) apply false
	alias(libs.plugins.android.library) apply false
	alias(libs.plugins.detekt)
	java
}

val customMediaOutputDir = layout.projectDirectory.dir("dependencies/jellyfin-androidx-media/OUTPUT").asFile

fun customMediaOutputProperty(fileName: String, propertyName: String): String {
	val file = customMediaOutputDir.resolve(fileName)
	if (!file.isFile) {
		throw GradleException("Missing custom Media3 metadata at $file; run dependencies/jellyfin-androidx-media/build.bat")
	}
	val properties = Properties()
	file.inputStream().use { properties.load(it) }
	return requireNotNull(properties.getProperty(propertyName)) {
		"Missing $propertyName in $file"
	}
}

val customMedia3FfmpegDecoderAarFile = run {
	val files = customMediaOutputDir.listFiles { file ->
		file.isFile && file.name.matches(Regex("""media3-ffmpeg-decoder-.+\.aar"""))
	}.orEmpty()

	require(files.size == 1) {
		"Expected exactly one custom Media3 FFmpeg decoder AAR in $customMediaOutputDir, found ${files.size}"
	}
	files.single()
}
val customMedia3Version = customMediaOutputProperty("version-media3.txt", "source_version")
val customMedia3MavenVersion = customMediaOutputProperty("version-media3.txt", "maven_version")
val customMedia3FfmpegDecoderVersion = customMedia3Version
val customFfmpegVersion = customMediaOutputProperty("version-ffmpeg.txt", "version")
val customLibyuvVersion = run {
	val version = customMediaOutputProperty("version-libyuv.txt", "version")
	val sourceRevision = customMediaOutputProperty("version-libyuv.txt", "source_revision")
	"$version+$sourceRevision"
}

if (!customMedia3FfmpegDecoderAarFile.isFile || customMedia3FfmpegDecoderAarFile.length() == 0L) {
	throw GradleException("Missing custom Media3 FFmpeg decoder at $customMedia3FfmpegDecoderAarFile")
}
val customMedia3MavenAarFile = customMediaOutputDir.resolve(
	"maven/androidx/media3/media3-extractor/$customMedia3MavenVersion/" +
		"media3-extractor-$customMedia3MavenVersion.aar"
)
if (!customMedia3MavenAarFile.isFile || customMedia3MavenAarFile.length() == 0L) {
	throw GradleException(
		"Missing custom Media3 Maven repository at $customMedia3MavenAarFile; " +
			"run dependencies/jellyfin-androidx-media/build.bat"
	)
}

extra["customMedia3FfmpegDecoderAarFile"] = customMedia3FfmpegDecoderAarFile
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
	val media3ConsumerProjects = setOf(
		":app",
		":playback:core",
		":playback:media3:exoplayer",
		":playback:media3:session",
	)

	if (path in media3ConsumerProjects) {
		tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
			compilerOptions {
				freeCompilerArgs.add("-opt-in=androidx.media3.common.util.UnstableApi")
			}
		}
	}
}
