plugins {
    id("location.android.library")
}

android {
    namespace = "com.chan.location.core.common"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }
}
