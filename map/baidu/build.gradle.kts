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
}
