plugins {
    id("location.android.library")
}

android {
    namespace = "com.chan.location.map.api"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }
}

dependencies {
    api(project(":core:common"))
}
