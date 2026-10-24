// Dapan engine variant: keep scanner code in the app, download model data only.
plugins { id("com.android.library") }
providers.gradleProperty("dapanBuildRoot").orNull?.let {
    layout.buildDirectory.set(file("$it/scanner-processing-opencv"))
}

android {
    namespace = "dev.offlinescan.processing"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Preserve notices and metadata while keeping model data out of the APK.
    androidResources { ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~:*.ort" }
}

dependencies {
    api("dev.offlinescan:scanner-core:0.7.0")
    implementation(files("libs/opencv-java-4.12.0.jar"))
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.1")
}
