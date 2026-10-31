plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.compose) }
providers.gradleProperty("dapanBuildRoot").orNull?.let { layout.buildDirectory.set(file("$it/app")) }
val dapanAbi = providers.gradleProperty("dapanAbi").orElse("arm64-v8a").get()
require(dapanAbi in setOf("arm64-v8a", "x86_64")) { "dapanAbi must be arm64-v8a or x86_64" }
android {
 namespace = "cn.dapan.assistant"
 compileSdk { version = release(37) }
 defaultConfig { applicationId = "cn.dapan.assistant"; minSdk = 26; targetSdk = 37; versionCode = 103; versionName = "1.0.3"; ndk { abiFilters += dapanAbi } }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 buildFeatures { compose = true; buildConfig = true }
 packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
 implementation(project(":scanner-ui-compose"))
 implementation("io.noties.markwon:core:4.6.2")
 implementation("io.noties.markwon:ext-tables:4.6.2")
 implementation("io.noties.markwon:ext-strikethrough:4.6.2")
 implementation("io.noties.markwon:ext-tasklist:4.6.2")
 implementation(libs.androidx.core.ktx)
 implementation(libs.androidx.activity.compose)
 implementation(libs.androidx.lifecycle.runtime.ktx)
 implementation(platform(libs.androidx.compose.bom))
 implementation(libs.androidx.compose.ui)
 implementation(libs.androidx.compose.ui.graphics)
 implementation(libs.androidx.compose.ui.tooling.preview)
 implementation(libs.androidx.compose.material3)
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("androidx.exifinterface:exifinterface:1.4.2")
 debugImplementation(libs.androidx.compose.ui.tooling)
 debugImplementation(project(":scanner-processing-opencv"))
 testImplementation(libs.junit)
 testImplementation("org.json:json:20240303")
}




