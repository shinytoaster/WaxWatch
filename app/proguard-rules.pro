# Hammerhead Karoo SDK — keep all extension classes intact
-keep class io.hammerhead.** { *; }
-dontwarn io.hammerhead.**

# Kotlin reflection — required by the Karoo SDK for stream consumers
-keep class kotlin.reflect.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.reflect.**

# Preserve annotations, generics, and inner class info (needed for Compose + SDK)
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Kotlin Coroutines
-dontwarn kotlinx.coroutines.**

# Keep app data classes (used via SharedPreferences/JSON serialisation)
-keep class net.shinytoaster.waxwatch.data.** { *; }
