# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Room entities
-keep class com.musediagnostics.taal.app.data.db.entity.** { *; }

# Keep TaalSDK classes
-keep class com.musediagnostics.taal.** { *; }

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }
