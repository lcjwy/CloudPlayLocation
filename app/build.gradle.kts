import com.android.build.api.artifact.SingleArtifact
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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

// 输出 APK 命名（下划线连接，不带空格）：云游_版本_年月日_时分秒_构建类型.apk
val appVersionName = "1.2.7"
val appName = "云游"

/** APK 文件名时间戳：年月日_时分秒（本机时区） */
private val apkStampFormat = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

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
        versionCode = 10
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
            isShrinkResources = true
            if (chanKeystore?.exists() == true) signingConfig = signingConfigs.getByName("chan")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    // 仅保留中文资源，剔除 androidx/地图库自带的多语言
    androidResources {
        localeFilters += "zh"
    }
    // APK 内压缩 so（默认不压缩以加速安装加载）：交付体积优先；安装后 so 会解压，磁盘占用略增
    packaging {
        jniLibs {
            useLegacyPackaging = true
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
    // AGP9 已移除 outputFileName 原地改名；改为构建后复制下划线命名副本（仅 release）。
    // debug 保持 app-debug.apk 原名：日常调试产物，无需交付命名；
    // 原名 app-release.apk 保留：IDE 部署依赖 output-metadata.json 指向原名。
    // 副本输出到项目级 build 目录（rootProject.layout），交付物不混在 module 产物里。
    onVariants { variant ->
        if (variant.name != "release") return@onVariants
        val variantName = variant.name
        val cap = variantName.replaceFirstChar { it.uppercase() }
        val taskName = "copyApk$cap"
        val apkDir = variant.artifacts.get(SingleArtifact.APK)
        val namedDir = rootProject.layout.buildDirectory.dir("outputs/named")
        tasks.register<Copy>(taskName) {
            group = "build"
            description = "输出带时间戳的下划线命名 APK 副本（交付用，位于项目级 build 目录）"
            from(apkDir)
            include("*.apk")
            into(namedDir)
            // rename 在任务执行期逐文件求值，时间取构建时刻而非配置时刻（daemon 下配置期值会过期）
            rename {
                "${appName}_${appVersionName}_${apkStampFormat.format(
                    LocalDateTime.now(),
                )}_$variantName.apk"
            }
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
        logger.lifecycle(
            "正式包输出：build/outputs/named/${appName}_${appVersionName}_<年月日_时分秒>_release.apk",
        )
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
