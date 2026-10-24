// Dapan's UI customization. Core, camera, OpenCV and export use the bundled 0.7.0 SDK.
plugins { id("com.android.library"); alias(libs.plugins.kotlin.compose) }
providers.gradleProperty("dapanBuildRoot").orNull?.let { layout.buildDirectory.set(file("$it/scanner-ui-compose")) }
android {
    namespace = "dev.offlinescan.ui"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    api("dev.offlinescan:scanner-core:0.7.0")
    implementation("dev.offlinescan:scanner-processing-opencv:0.7.0")
    implementation("dev.offlinescan:scanner-camera:0.7.0")
    implementation("dev.offlinescan:scanner-export:0.7.0")
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
}
