# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.loopa.**$$serializer { *; }
-keepclassmembers class com.loopa.** { *** Companion; }
-keepclasseswithmembers class com.loopa.** { kotlinx.serialization.KSerializer serializer(...); }
# Ktor / Supabase
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn org.jetbrains.annotations.**
