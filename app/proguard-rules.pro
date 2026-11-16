-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.kaddie.app.data.dictionary.** {
    *** Companion;
}
-keepclasseswithmembers class com.kaddie.app.data.dictionary.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class com.kaddie.app.data.remote.** {
    *** Companion;
}
-keepclasseswithmembers class com.kaddie.app.data.remote.** {
    kotlinx.serialization.KSerializer serializer(...);
}
