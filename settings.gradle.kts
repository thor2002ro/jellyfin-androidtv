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
include(":preference")

pluginManagement {
	repositories {
		gradlePluginPortal()
		mavenCentral()
		google()
	}
}

dependencyResolutionManagement {
	repositories {
		mavenCentral()
		google()

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
