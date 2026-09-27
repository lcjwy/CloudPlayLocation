plugins {
    id("location.android.library.compose")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.chan.location.feature.home"
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
    implementation(project(":service:mock"))
    // MyLocation 十字准星图标在 extended 包，BOM 托管版本；release 由 R8 裁剪未用图标
    implementation(libs.androidx.compose.icons.extended)
}
