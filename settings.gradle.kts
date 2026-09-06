import java.lang.module.ModuleDescriptor
import java.util.Properties

fun latestLocalMavenVersion(moduleDirectory: String): String {
	val directory = file(moduleDirectory)
	return directory.listFiles()
		.orEmpty()
		.filter(File::isDirectory)
		.filter { versionDirectory ->
			val version = versionDirectory.name
			versionDirectory.resolve("mpv-android-lib-$version.aar").isFile ||
				!moduleDirectory.endsWith("mpv-android-lib")
		}
		.map(File::getName)
		.maxWithOrNull(compareBy(ModuleDescriptor.Version::parse))
		?: error(
			if (moduleDirectory.endsWith("mpv-android-lib")) {
				"No compatible local MPV AAR found in $directory. " +
					"Publish the standalone libdovi Android v3 SDK, then run dependencies/mpv-android-lib/build.sh."
			} else {
				"No locally built artifact found in $directory"
			}
		)
}

pluginManagement {
	repositories {
		gradlePluginPortal()
		mavenCentral()
		google()
	}
	plugins {
		id("com.vanniktech.maven.publish") version "0.32.0"
	}
}

plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "jellyfin-androidtv"

file("local.properties").takeIf { it.isFile }?.inputStream()?.use {
	java.util.Properties().apply { load(it) }.getProperty("sdk.dir")?.let { sdkDir ->
		System.setProperty("android.home", sdkDir)
	}
}

includeBuild("dependencies/libass-android") {
	dependencySubstitution {
		substitute(module("io.github.peerless2012:ass-media")).using(project(":lib_ass_media"))
	}
}

includeBuild("dependencies/libdovi-android") {
	dependencySubstitution {
		substitute(module("io.github.thor2002ro:libdovi-android")).using(project(":lib"))
	}
}

// Application
include(":app")

// Modules
include(":design")
include(":playback:core")
include(":playback:dovi")
include(":playback:jellyfin")
include(":playback:media3:exoplayer")
include(":playback:media3:session")
include(":playback:libvlc")
include(":playback:mpv")
include(":preference")
include(":updater")

dependencyResolutionManagement {
	defaultLibrariesExtensionName = "baseLibs"
	versionCatalogs {
		create("libs") {
			from(files("gradle/libs.versions.toml"))
			library("libdovi-android", "io.github.thor2002ro", "libdovi-android").version("source")

			val media3Version = latestLocalMavenVersion(
				"dependencies/jellyfin-androidx-media/OUTPUT/maven/androidx/media3/media3-exoplayer"
			)
			version("androidx-media3-local", media3Version)
			library("androidx-media3-datasource-okhttp", "androidx.media3", "media3-datasource-okhttp")
				.versionRef("androidx-media3-local")
			library("androidx-media3-decoder-ffmpeg", "androidx.media3", "media3-decoder-ffmpeg")
				.versionRef("androidx-media3-local")
			library("androidx-media3-exoplayer", "androidx.media3", "media3-exoplayer")
				.versionRef("androidx-media3-local")
			library("androidx-media3-exoplayer-hls", "androidx.media3", "media3-exoplayer-hls")
				.versionRef("androidx-media3-local")
			library("androidx-media3-session", "androidx.media3", "media3-session")
				.versionRef("androidx-media3-local")
			library("androidx-media3-ui", "androidx.media3", "media3-ui")
				.versionRef("androidx-media3-local")

			val libassProperties = Properties().apply {
				file("dependencies/libass-android/gradle.properties").inputStream().use(::load)
			}
			version("libass-android-local", libassProperties.getProperty("VERSION_NAME"))
			library("libass-media3", "io.github.peerless2012", "ass-media")
				.versionRef("libass-android-local")
			val libassProviderProperties = Properties().apply {
				file("dependencies/libass-android/OUTPUT/libass-provider.properties").inputStream().use(::load)
			}
			library(
				"libass-provider",
				libassProviderProperties.getProperty("group"),
				libassProviderProperties.getProperty("artifact")
			).version(libassProviderProperties.getProperty("version"))

			val mpvVersion = latestLocalMavenVersion(
				"dependencies/mpv-android-lib/OUTPUT/maven/io/github/abdallahmehiz/mpv-android-lib"
			)
			version("mpv-android-lib-local", mpvVersion)
			library("mpv-android-lib", "io.github.abdallahmehiz", "mpv-android-lib")
				.versionRef("mpv-android-lib-local")
		}
	}

	repositories {
		exclusiveContent {
			forRepository {
				maven(rootDir.resolve("dependencies/jellyfin-androidx-media/OUTPUT/maven"))
			}
			filter {
				includeGroup("androidx.media3")
			}
		}
		exclusiveContent {
			forRepository {
				maven(rootDir.resolve("dependencies/libass-android/OUTPUT/maven"))
			}
			filter {
				includeModule("io.github.peerless2012", "libass-android-provider")
			}
		}
		exclusiveContent {
			forRepository {
				maven(rootDir.resolve("dependencies/mpv-android-lib/OUTPUT/maven"))
			}
			filter {
				includeModule("io.github.abdallahmehiz", "mpv-android-lib")
				includeModule("io.github.abdallahmehiz", "mpv-ffmpeg-android")
			}
		}
		mavenCentral()
		google()
		maven("https://androidx.dev/snapshots/builds/15645525/artifacts/repository") {
			content {
				includeGroup("androidx.media3")
			}
		}

		// Jellyfin SDK
		mavenLocal {
			content {
				includeVersionByRegex("org.jellyfin.sdk", ".*", "latest-SNAPSHOT")
			}
		}
		maven("https://s01.oss.sonatype.org/content/repositories/snapshots/") {
			content {
				includeVersionByRegex("org.jellyfin.sdk", ".*", "master-SNAPSHOT")
				includeVersionByRegex("org.jellyfin.sdk", ".*", "openapi-unstable-SNAPSHOT")
			}
		}
	}
}
