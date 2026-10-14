# WaveStudio ProGuard rules

-keepattributes *Annotation*
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends androidx.lifecycle.ViewModel { *; }

# Keep native methods and JNI
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.wavestudio.app.engine.NativeAudioEngine { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers,includedescriptorclasses class com.wavestudio.app.domain.** {
    *** Companion;
}
-keepclasseswithmembers class com.wavestudio.app.domain.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
