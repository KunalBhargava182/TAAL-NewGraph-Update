# R8 rules for building taal-stemz-ui-kit.aar itself (release = obfuscated).

# Obfuscated names must not differ only by case (collide on case-insensitive file systems, e.g. Windows).
-dontusemixedcaseclassnames
# Move every obfuscated class into this SDK's own package. Without this both SDKs emit
# top-level a.a, b.c, … classes and an app using both fails with "Duplicate class a.a".
-repackageclasses 'com.musediagnostics.o.uikit'

-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-dontwarn java.lang.invoke.StringConcatFactory

# ---- Public API ----
-keep public class com.musediagnostics.taal.stemz.uikit.TaalRecorderActivity { public *; }
-keep public class com.musediagnostics.taal.stemz.uikit.TaalPlayerActivity { public *; }
-keep public class com.musediagnostics.taal.stemz.uikit.TaalSavedRecordingsActivity { public *; }
-keep public class com.musediagnostics.taal.stemz.uikit.TaalRecorderActivity$Companion { public *; }
-keep public class com.musediagnostics.taal.stemz.uikit.TaalPlayerActivity$Companion { public *; }
-keep public class com.musediagnostics.taal.stemz.uikit.TaalSavedRecordingsActivity$Companion { public *; }
-keep public class com.musediagnostics.taal.stemz.uikit.StemzHostActivity { public protected *; }
-keep public class com.musediagnostics.taal.stemz.uikit.TaalStemzUiKit { public *; }
# Reusable views for integrators building their own screens
-keep public class com.musediagnostics.taal.stemz.uikit.graph.** { public protected *; }
-keep public class com.musediagnostics.taal.stemz.uikit.segmentation.PcgSegmentationView { public protected *; }

# ---- Screens are instantiated by name from the navigation graph: keep names, obfuscate bodies ----
-keepnames class com.musediagnostics.taal.stemz.uikit.** extends androidx.fragment.app.Fragment
-keep class com.musediagnostics.taal.stemz.uikit.** extends androidx.fragment.app.Fragment { <init>(); }
-keep class com.musediagnostics.taal.stemz.uikit.** extends androidx.lifecycle.ViewModel { <init>(); }
# View binding classes are looked up only statically — no rule needed.

# ---- Dependencies (resolved by the integrating app) ----
-dontwarn com.github.mikephil.charting.**
-dontwarn ai.onnxruntime.**

# ---- Strip verbose/debug/info logging from the shipped AAR ----
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
