import com.android.build.api.artifact.SingleArtifact
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 百度 Key 从 local.properties(BAIDU_MAP_KEY) 注入，源码零硬编码；未配置时使用占位值
val localProps =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

// 输出 APK 命名（下划线连接，不带空格）：虚拟定位_版本_构建类型.apk
val appVersionName = "1.1.0"
val appName = "虚拟定位"

android {
    namespace = "com.chan.location"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }

    defaultConfig {
        applicationId = "com.chan.location"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = appVersionName
        manifestPlaceholders["baiduMapKey"] =
            localProps.getProperty("BAIDU_MAP_KEY") ?: "PLEASE_APPLY_BAIDU_AK"
    }

    signingConfigs {
        // chan110 统一签名；凭据存 local.properties（gitignore），缺省时回退系统默认签名
        create("chan") {
            localProps.getProperty("STORE_FILE")?.let { storeFile = rootProject.file(it) }
            localProps.getProperty("STORE_PASSWORD")?.let { storePassword = it }
            localProps.getProperty("KEY_ALIAS")?.let { keyAlias = it }
            localProps.getProperty("KEY_PASSWORD")?.let { keyPassword = it }
        }
    }
    val chanKeystore = localProps.getProperty("STORE_FILE")?.let { rootProject.file(it) }

    buildTypes {
        debug {
            if (chanKeystore?.exists() == true) signingConfig = signingConfigs.getByName("chan")
        }
        release {
            isMinifyEnabled = true
            if (chanKeystore?.exists() == true) signingConfig = signingConfigs.getByName("chan")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

androidComponents {
    // AGP9 已移除 outputFileName 原地改名；改为构建后复制下划线命名副本。
    // 原名 app-debug.apk 保留：IDE 部署依赖 output-metadata.json 指向原名。
    onVariants { variant ->
        val variantName = variant.name
        val cap = variantName.replaceFirstChar { it.uppercase() }
        val taskName = "copyApk$cap"
        val apkDir = variant.artifacts.get(SingleArtifact.APK)
        tasks.register<Copy>(taskName) {
            group = "build"
            description = "输出下划线命名的 APK 副本（交付用）"
            from(apkDir)
            include("*.apk")
            into(layout.buildDirectory.dir("outputs/named"))
            rename { "${appName}_${appVersionName}_$variantName.apk" }
        }
        tasks.matching { it.name == "assemble$cap" }.configureEach { finalizedBy(taskName) }
    }
}

// 一键出正式包：./gradlew release（等价 assembleRelease，结束后自动产出下划线命名副本）
tasks.register("release") {
    group = "build"
    description = "编译签名正式版 APK（等价 assembleRelease，含下划线命名副本输出）"
    dependsOn("assembleRelease")
    doLast {
        logger.lifecycle("正式包输出：app/build/outputs/named/${appName}_${appVersionName}_release.apk")
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    implementation(project(":map:api"))
    implementation(project(":map:baidu"))
    implementation(project(":map:osm"))
    implementation(project(":service:mock"))
    implementation(project(":feature:home"))
    implementation(project(":feature:map"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
}
