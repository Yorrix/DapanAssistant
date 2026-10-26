plugins { alias(libs.plugins.android.application) }
providers.gradleProperty("dapanBuildRoot").orNull?.let { layout.buildDirectory.set(file("$it/timetable-component")) }
val dapanAbi = providers.gradleProperty("dapanAbi").orElse("arm64-v8a").get()
require(dapanAbi in setOf("arm64-v8a", "x86_64"))
android {
 namespace = "cn.dapan.assistant.timetable"
 compileSdk { version = release(37) { minorApiLevel = 2 } }
 defaultConfig {
  applicationId = "cn.dapan.assistant.timetable"
  minSdk = 26
  targetSdk = 37
  versionCode = 1
  versionName = "1.0"
  ndk { abiFilters += dapanAbi }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
 implementation("androidx.core:core-ktx:1.17.0")
 implementation("org.mozilla.geckoview:geckoview:158.0.20261021001945")
}

