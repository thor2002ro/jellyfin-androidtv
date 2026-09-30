import java.io.File
import java.util.Properties

plugins {
	alias(libs.plugins.aboutlibraries)
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.kotlin.serialization)
}

val thorApplicationId = "org.jellyfin.androidtv.thor"
val appVersionName = project.getVersionName()
val apkAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
fun readVersionName(propertiesFile: File): String {
	val properties = Properties()
	propertiesFile.inputStream().use {
		properties.load(it)
	}
	return requireNotNull(properties.getProperty("VERSION_NAME")) {
		"VERSION_NAME not found in $propertiesFile"
	}
}
val libassAndroidVersion = readVersionName(rootProject.file("dependencies/libass-android/gradle.properties"))
val libdoviAndroidVersion = readVersionName(rootProject.file("dependencies/libdovi-android/gradle.properties"))
val libdoviVersions = apkAbis.map { abi ->
	rootProject.file("dependencies/libdovi-android/OUTPUT/native/$abi/UPSTREAM_SHA").readText().trim()
}.toSet()
require(libdoviVersions.size == 1) {
	"libdovi upstream revisions differ between ABI artifacts: $libdoviVersions"
}
val libdoviVersion = libdoviVersions.single().take(12)
val libassVersion = run {
	val header = rootProject.file("dependencies/libass-android/lib_ass/src/main/cpp/libass-cmake/src/ass/libass/ass.h").readText()
	val (major, minor, patch) = requireNotNull(Regex("""(?m)^#define\s+LIBASS_VERSION\s+0x([0-9])([0-9]{2})([0-9]{2})[0-9A-Fa-f]{3}\s*$""").find(header)) {
		"LIBASS_VERSION not found in vendored libass header"
	}.destructured

	"$major.${minor.toInt()}.${patch.toInt()}"
}

android {
	namespace = "org.jellyfin.androidtv"
	compileSdk = libs.versions.android.compileSdk.get().toInt()

	defaultConfig {
		minSdk = libs.versions.android.minSdk.get().toInt()
		targetSdk = libs.versions.android.targetSdk.get().toInt()

		// Release version
		applicationId = thorApplicationId
		versionName = appVersionName
		versionCode = getVersionCode(appVersionName)

		buildConfigField("String", "MEDIA3_VERSION", "\"${rootProject.extra["customMedia3Version"]}\"")
		buildConfigField("String", "MEDIA3_FFMPEG_DECODER_VERSION", "\"${rootProject.extra["customMedia3FfmpegDecoderVersion"]}\"")
		buildConfigField("String", "FFMPEG_VERSION", "\"${rootProject.extra["customFfmpegVersion"]}\"")
		buildConfigField("String", "LIBYUV_VERSION", "\"${rootProject.extra["customLibyuvVersion"]}\"")
		buildConfigField("String", "LIBASS_ANDROID_VERSION", "\"$libassAndroidVersion\"")
		buildConfigField("String", "LIBDOVI_ANDROID_VERSION", "\"$libdoviAndroidVersion\"")
		buildConfigField("String", "LIBDOVI_VERSION", "\"$libdoviVersion\"")
		buildConfigField("String", "LIBASS_VERSION", "\"$libassVersion\"")
		buildConfigField("String", "LIBVLC_VERSION", "\"${libs.versions.libvlc.get()}\"")
		buildConfigField("String", "UPDATE_ABIS", "\"${apkAbis.joinToString(",")}\"")
	}

	buildFeatures {
		buildConfig = true
		viewBinding = true
		compose = true
		resValues = true
	}

	compileOptions {
		isCoreLibraryDesugaringEnabled = true
	}

	packaging {
		jniLibs.pickFirsts += "**/libc++_shared.so"
	}

	splits {
		abi {
			isEnable = true
			reset()
			include(*apkAbis.toTypedArray())
			isUniversalApk = true
		}
	}

	signingConfigs {
		val keystoreFile = getProperty("keystore.file")
		val keystorePassword = getProperty("keystore.password")
		val signingKeyAlias = getProperty("signing.key.alias")
		val signingKeyPassword = getProperty("signing.key.password")

		if (keystoreFile != null && keystorePassword != null && signingKeyAlias != null && signingKeyPassword != null) {
			create("release") {
				storeFile = file(keystoreFile)
				storePassword = keystorePassword
				keyAlias = signingKeyAlias
				keyPassword = signingKeyPassword
			}
		}
	}

	dependenciesInfo {
		includeInBundle = false
		includeInApk = false
	}

	buildTypes {
		release {
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

			// Set package names used in various XML files
			resValue("string", "app_id", thorApplicationId)
			resValue("string", "app_search_suggest_authority", "$thorApplicationId.content")
			resValue("string", "app_search_suggest_intent_data", "content://$thorApplicationId.content/intent")

			// Set flavored application name
			resValue("string", "app_name", "@string/app_name_release")

			buildConfigField("boolean", "DEVELOPMENT", "false")

			signingConfig = signingConfigs.findByName("release")
		}

		debug {
			// Use different application id to run release and debug at the same time
			applicationIdSuffix = ".debug"
			// CI provides this config so published debug APKs keep a stable update signer
			signingConfigs.findByName("release")?.let { signingConfig = it }

			// Set package names used in various XML files
			resValue("string", "app_id", thorApplicationId + applicationIdSuffix)
			resValue("string", "app_search_suggest_authority", "${thorApplicationId + applicationIdSuffix}.content")
			resValue("string", "app_search_suggest_intent_data", "content://${thorApplicationId + applicationIdSuffix}.content/intent")

			// Set flavored application name
			resValue("string", "app_name", "@string/app_name_debug")

			buildConfigField("boolean", "DEVELOPMENT", (defaultConfig.versionCode!! < 100).toString())
		}
	}

	lint {
		lintConfig = file("$rootDir/android-lint.xml")
		abortOnError = false
		checkDependencies = true
	}

	testOptions.unitTests.all {
		it.useJUnitPlatform()
	}
}

base.archivesName.set("jellyfin-androidtv-thor-$appVersionName")

tasks.register("versionTxt") {
	val path = layout.buildDirectory.asFile.get().resolve("version.txt")

	doLast {
		val versionString = "${android.defaultConfig.versionName}=${android.defaultConfig.versionCode}"
		logger.info("Writing [$versionString] to $path")
		path.writeText("$versionString\n")
	}
}

dependencies {
	// Jellyfin
	implementation(projects.design)
	implementation(projects.playback.core)
	implementation(projects.playback.jellyfin)
	implementation(projects.playback.media3.exoplayer)
	implementation(projects.playback.media3.session)
	implementation(projects.playback.libvlc)
	implementation(projects.playback.mpv)
	implementation(projects.preference)
	implementation(projects.updater)
	implementation(libs.jellyfin.sdk) {
		// Change version if desired
		val sdkVersion = findProperty("sdk.version")?.toString()
		when (sdkVersion) {
			"local" -> version { strictly("latest-SNAPSHOT") }
			"snapshot" -> version { strictly("master-SNAPSHOT") }
			"unstable-snapshot" -> version { strictly("openapi-unstable-SNAPSHOT") }
		}
	}

	// Kotlin
	implementation(libs.kotlinx.coroutines)
	implementation(libs.kotlinx.serialization.json)

	// Android(x)
	implementation(libs.androidx.core)
	implementation(libs.androidx.activity)
	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.fragment)
	implementation(libs.androidx.fragment.compose)
	implementation(libs.androidx.leanback.core)
	implementation(libs.androidx.leanback.preference)
	implementation(libs.androidx.navigation3.ui)
	implementation(libs.androidx.preference)
	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.tvprovider)
	implementation(libs.androidx.constraintlayout)
	implementation(libs.androidx.recyclerview)
	implementation(libs.androidx.work.runtime)
	implementation(libs.bundles.androidx.lifecycle)
	implementation(libs.androidx.window)
	implementation(libs.androidx.cardview)
	implementation(libs.androidx.startup)
	implementation(libs.bundles.androidx.compose)
	implementation(libs.accompanist.permissions)

	// Dependency Injection
	implementation(libs.bundles.koin)

	// Media players
	implementation(libs.androidx.media3.exoplayer)
	implementation(libs.androidx.media3.datasource.okhttp)
	implementation(libs.androidx.media3.exoplayer.hls)
	implementation(libs.androidx.media3.ui)
	implementation(files(rootProject.extra["customMedia3FfmpegDecoderAarFile"] as File))
	implementation(libs.libass.media3)

	// Markdown
	implementation(libs.bundles.markwon)

	// Image utility
	implementation(libs.bundles.coil)

	// Crash Reporting
	implementation(libs.bundles.acra)

	// Licenses
	implementation(libs.aboutlibraries)

	// Logging
	implementation(libs.timber)
	implementation(libs.slf4j.timber)

	// Compatibility (desugaring)
	coreLibraryDesugaring(libs.android.desugar)

	// Testing
	testImplementation(libs.kotest.runner.junit5)
	testImplementation(libs.kotest.assertions)
	testImplementation(libs.mockk)
}
