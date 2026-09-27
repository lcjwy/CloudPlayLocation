plugins {
    id("location.android.library.compose")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.chan.location.core.ui"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }
}

// 以 api 传递 Compose 基建，feature 模块依赖 :core:ui 即可写 Compose
dependencies {
    api(project(":core:common"))
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.material3)
    api(libs.androidx.activity.compose)
    api(libs.androidx.lifecycle.runtime.compose)
}
