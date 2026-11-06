# Pleos Connect SDKs talk to the vehicle/navigation services over Binder (AIDL) and
# create callback/data objects from parcels: keep them exactly as shipped.
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-dontwarn ai.pleos.**

# Android Automotive car API is provided by the system at runtime.& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" forward tcp:8088 tcp:8088
-dontwarn android.car.**

# Enums are read back from saved JSON by name (Type.valueOf), which R8 keeps; nothing else
# in the app uses reflection.

# lifecycle-runtime-compose 2.9 looks up Compose UI 1.6's LocalLifecycleOwner by reflection.
# (Also provided directly in MainActivity; this keeps the fallback working.)
-keep class androidx.compose.ui.platform.AndroidCompositionLocals_androidKt {
    public static *** getLocalLifecycleOwner();
}
