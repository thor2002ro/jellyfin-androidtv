plugins {
	alias(libs.plugins.android.library)
}

android {
	namespace = "org.jellyfin.updater"
	compileSdk = libs.versions.android.compileSdk.get().toInt()

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
	implementation(libs.androidx.core)
	implementation(libs.kotlinx.coroutines)
	implementation(libs.kotlinx.serialization.json)

	coreLibraryDesugaring(libs.android.desugar)

	testImplementation(libs.kotest.assertions)
	testImplementation(libs.kotest.runner.junit5)
}
