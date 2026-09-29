# R8 rules for building taal-stemz-core.aar itself (release = obfuscated).
#
# Rule of thumb: everything the stemz team (or taal-stemz-ui-kit) calls is kept by name with
# its public/protected members; private members and pure internals are renamed/shrunk.

# ---- Kotlin metadata, so Kotlin callers still see default args, nullability, extension fns ----
# Obfuscated names must not differ only by case (collide on case-insensitive file systems, e.g. Windows).
-dontusemixedcaseclassnames
# Move every obfuscated class into this SDK's own package. Without this both SDKs emit
# top-level a.a, b.c, … classes and an app using both fails with "Duplicate class a.a".
-repackageclasses 'com.musediagnostics.o.core'

-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions
# Readable line numbers in stack traces, with the original file name hidden.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Public API: com.musediagnostics.taal.stemz.** ----
# Everything under the SDK package is public API EXCEPT these internals, which get obfuscated:
#   core.TaalAudioCapture  — USB capture internals (hardened source chain, diagnostics)
#   dsp.PcgSpectralGate    — internal stage of PcgDisplayFilter
-keep class !com.musediagnostics.taal.stemz.core.TaalAudioCapture,!com.musediagnostics.taal.stemz.core.TaalAudioCapture$*,!com.musediagnostics.taal.stemz.dsp.PcgSpectralGate,!com.musediagnostics.taal.stemz.dsp.PcgSpectralGate$*,com.musediagnostics.taal.stemz.** {
    public protected *;
}
-keep enum com.musediagnostics.taal.stemz.** { *; }

# ---- Segmentation types that appear in the public API / are used by taal-stemz-ui-kit ----
-keep class com.purnacardio.signal.pcg.SegmentationResult { *; }
-keep class com.purnacardio.signal.pcg.S1Result { *; }
-keep class com.purnacardio.signal.pcg.viz.** { public *; }
# Everything else under com.purnacardio.** (feature extractor, decoder, ONNX runner) is internal
# and is renamed/shrunk.

# ---- Java 17 string concatenation (invokedynamic); desugared by the consuming app's D8 ----
-dontwarn java.lang.invoke.StringConcatFactory

# ---- ONNX Runtime (called back from native code) ----
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ---- Strip verbose/debug/info logging from the shipped AAR (warnings/errors kept) ----
# The engine logs detailed capture diagnostics under the TAAL_AUDIO_DEBUG tag for internal
# device investigations; that output isn't meant for integrators' logcat.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
