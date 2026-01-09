# Add project specific ProGuard rules here.
# Keep Koog classes
-keep class ai.koog.** { *; }

# Keep Room entities
-keep class io.github.kahdeg.autoreader.data.db.entity.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
