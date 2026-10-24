pluginManagement {
 repositories { google(); mavenCentral(); gradlePluginPortal() }
 resolutionStrategy { eachPlugin { if (requested.id.id == "com.android.library") useModule("com.android.tools.build:gradle:${requested.version ?: "9.4.1"}") } }
}
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { maven { url = uri("vendor/sdk-0.7.0/maven"); content { includeGroup("dev.offlinescan") } }; google(); mavenCentral() } }
rootProject.name = "DapanAssistant"
include(":app")
// Scanner runs inside the app. Only its model data is downloaded on demand.
include(":scanner-processing-opencv")
project(":scanner-processing-opencv").projectDir = file("vendor/open-android-doc-scanner-0.7.0/scanner-processing-opencv")
project(":scanner-processing-opencv").buildFileName = "dapan.gradle.kts"
include(":scanner-ui-compose")
project(":scanner-ui-compose").projectDir = file("vendor/open-android-doc-scanner-0.7.0/scanner-ui-compose")
project(":scanner-ui-compose").buildFileName = "dapan.gradle.kts"
