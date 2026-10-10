plugins {
    id("location.android.library")
}

android {
    namespace = "com.chan.location.map.baidu"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }
    sourceSets {
        getByName("main") {
            jniLibs.directories.add("libs")
        }
    }
}

dependencies {
    implementation(project(":map:api"))
    implementation(project(":core:common"))
    implementation(files("libs/BaiduLBS_Android.jar"))
    implementation(libs.kotlinx.coroutines.android)
    // 百度定位 SDK（:remote 进程）运行期依赖 okhttp3，缺失会在子进程内
    // NoClassDefFoundError 崩溃循环（拉起即崩，蓝点不可用）
    implementation(libs.okhttp)
}
