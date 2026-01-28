# Hotwire Native
-keep class dev.hotwire.** { *; }
-keepclassmembers class dev.hotwire.** { *; }

# Bridge Components
-keep class io.blaha.groovitation.components.** { *; }
-keepclassmembers class io.blaha.groovitation.components.** { *; }

# Firebase
-keep class com.google.firebase.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep data classes for JSON serialization
-keep class io.blaha.groovitation.services.** { *; }
