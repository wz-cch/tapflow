-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# kotlinx.serialization
-keepclassmembers class com.tapflow.android.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.tapflow.android.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
