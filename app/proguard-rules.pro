# Don't obfuscate code
-dontobfuscate

# Our code
-keep class com.limelight.binding.input.evdev.* {*;}

# KeyMapper - keep all VK_* fields for reflection
-keep class com.limelight.utils.KeyMapper {*;}

# KeyConfigHelper - keep classes and fields for Gson
-keep class com.limelight.utils.KeyConfigHelper {*;}
-keep class com.limelight.utils.KeyConfigHelper$ShortcutFile {*;}
-keep class com.limelight.utils.KeyConfigHelper$Shortcut {*;}

# Keep TensorFlow Lite GPU delegate classes that R8 might incorrectly remove
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.opencv.** { *; }

# Profiles
-keep class com.limelight.profiles.ProfilesManager$ProfilesData {*;}
-keep class com.limelight.profiles.SettingsProfile {*;}

# Moonlight common — JNI bridge. Use .** to also keep nested types
# (MoonBridge$ServerStatsListener, MoonBridge$AudioConfiguration, etc.)
# since R8 may otherwise strip nested interfaces dispatched to from native.
-keep class com.limelight.nvstream.jni.** {*;}
-keepclassmembers class com.limelight.nvstream.jni.MoonBridge {
    public static <fields>;
    public static <methods>;
}
-keep interface com.limelight.nvstream.jni.MoonBridge$ServerStatsListener {*;}

# PerfOverlayListener — implementations are passed to native via setupBridge
# and dispatched from JNI callbacks; keep the interface so R8 cannot rename
# its abstract methods out from under reflection-style native dispatch.
-keep interface com.limelight.binding.video.PerfOverlayListener {*;}
-keep class * implements com.limelight.binding.video.PerfOverlayListener {
    public <methods>;
}

# Okio
-keep class sun.misc.Unsafe {*;}
-dontwarn java.nio.file.*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn okio.**

# BouncyCastle
-keep class org.bouncycastle.jcajce.provider.asymmetric.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.util.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.* {*;}
-keep class org.bouncycastle.jcajce.provider.digest.** {*;}
-keep class org.bouncycastle.jcajce.provider.symmetric.** {*;}
-keep class org.bouncycastle.jcajce.spec.* {*;}
-keep class org.bouncycastle.jce.** {*;}
-dontwarn javax.naming.**

# jMDNS
-dontwarn javax.jmdns.impl.DNSCache
-dontwarn org.slf4j.**

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**