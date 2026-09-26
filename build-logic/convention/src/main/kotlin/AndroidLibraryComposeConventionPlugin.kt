import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** 在 library 基础上启用 Compose（编译器插件由模块脚本继续应用 alias(libs.plugins.kotlin.compose)） */
class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("location.android.library")
        target.pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        target.extensions.configure<LibraryExtension> {
            buildFeatures.compose = true
        }
    }
}
