# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Baidu Map SDK（jar+so，反射与 JNI 调用多，整体 keep）
-keep class com.baidu.** { *; }
-keep class vi.com.gdi.bgl.** { *; }
-dontwarn com.baidu.**

# osmdroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
