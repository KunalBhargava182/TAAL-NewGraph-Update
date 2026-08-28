# Consumer R8/ProGuard rules for pcg-segmentation-android.
#
# ONNX Runtime resolves parts of its Java surface from native code, so R8 cannot see those
# references and will strip them. The symptom is an UnsatisfiedLinkError or a
# NoSuchMethodError at OrtEnvironment.getEnvironment() in a release build only — debug builds
# are unaffected, which is what makes it an easy one to ship.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# The public API of this library, kept so reflection-based consumers and stack traces survive.
-keep class com.purnacardio.signal.pcg.SegmentationResult { *; }
-keep class com.purnacardio.signal.pcg.S1Result { *; }
