		.filter { versionDirectory ->
			val version = versionDirectory.name
			versionDirectory.resolve("mpv-android-lib-$version.aar").isFile ||
				!moduleDirectory.endsWith("mpv-android-lib")
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

// Application
include(":app")

// Modules
include(":design")
include(":playback:core")
include(":playback:jellyfin")
include(":playback:media3:exoplayer")
include(":playback:media3:session")
include(":playback:libvlc")
include(":playback:mpv")
include(":preference")

dependencyResolutionManagement {
	repositories {
		exclusiveContent {
			forRepository {
				maven(rootDir.resolve("dependencies/mpv-android-lib/OUTPUT/maven"))
			}
			filter {
				includeModule("io.github.abdallahmehiz", "mpv-android-lib")
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
