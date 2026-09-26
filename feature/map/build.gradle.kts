plugins {
    id("location.android.library.compose")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.chan.location.feature.map"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    implementation(project(":map:api"))
    implementation(project(":service:mock"))
}
