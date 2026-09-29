# Consumer R8 rules shipped inside taal-stemz-ui-kit.aar — applied to the integrating app's
# release build automatically.

# Entry activities and reusable views (public API).
-keep public class com.musediagnostics.taal.stemz.uikit.** { public protected *; }

# Screens are created by class name from the navigation graph.
-keepnames class com.musediagnostics.taal.stemz.uikit.** extends androidx.fragment.app.Fragment
-keep class com.musediagnostics.taal.stemz.uikit.** extends androidx.fragment.app.Fragment { <init>(); }
-keep class com.musediagnostics.taal.stemz.uikit.** extends androidx.lifecycle.ViewModel { <init>(); }

# MPAndroidChart (waveform trace).
-dontwarn com.github.mikephil.charting.**
