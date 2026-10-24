// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.library") version "9.4.1" apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Camera and export may refer to the published engine transitively. Always use
// this source variant so every caller reads the downloaded model from app data.
subprojects {
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("dev.offlinescan:scanner-processing-opencv"))
                .using(project(":scanner-processing-opencv"))
        }
    }
}
