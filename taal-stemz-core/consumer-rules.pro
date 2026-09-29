# Consumer R8 rules shipped inside taal-stemz-core.aar — applied automatically to the
# integrating app's release build, so the stemz team needs no SDK-specific R8 config.

# ONNX Runtime resolves parts of its Java surface from native code; R8 can't see those
# references and would strip them (UnsatisfiedLinkError / NoSuchMethodError in release only).
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Public API of this SDK — must survive the integrating app's own minification.
# (Excludes ...stemz.uikit.**, which ships its own rules in taal-stemz-ui-kit.aar.)
-keep public class !com.musediagnostics.taal.stemz.uikit.**,com.musediagnostics.taal.stemz.** { public protected *; }
-keep class com.purnacardio.signal.pcg.SegmentationResult { *; }
-keep class com.purnacardio.signal.pcg.S1Result { *; }
-keep class com.purnacardio.signal.pcg.viz.** { public *; }
-keep class kotlin.Metadata { *; }
