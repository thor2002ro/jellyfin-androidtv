plugins {
	alias(libs.plugins.android.library)
}

android {
	namespace = "org.jellyfin.playback.dovi"
	compileSdk = libs.versions.android.compileSdk.get().toInt()
	ndkVersion = libs.versions.android.ndk.get()

	defaultConfig {
		minSdk = libs.versions.android.minSdk.get().toInt()
	}

	compileOptions {
		isCoreLibraryDesugaringEnabled = true
	}

	lint {
		lintConfig = file("$rootDir/android-lint.xml")
		abortOnError = false
	}

	testOptions.unitTests.all {
		it.useJUnitPlatform()
	}
}

dependencies {
	api(libs.libdovi.android)
	implementation(projects.playback.core)
	coreLibraryDesugaring(libs.android.desugar)

	testImplementation(libs.kotest.runner.junit5)
	testImplementation(libs.kotest.assertions)
}
