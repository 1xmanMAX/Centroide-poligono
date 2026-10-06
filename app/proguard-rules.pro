# OpenCV usa JNI
-keep class org.opencv.** { *; }
# PdfBox-Android
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.scannerpromax.** { *** Companion; }
-keepclasseswithmembers class com.scannerpromax.** { kotlinx.serialization.KSerializer serializer(...); }
