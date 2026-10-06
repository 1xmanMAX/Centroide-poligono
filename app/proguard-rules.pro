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

# --- Revisión de rendimiento ---
# OpenCV: los métodos nativos se resuelven por nombre JNI (Java_org_opencv_...) y el código nativo lanza
# org.opencv.core.CvException por nombre. Se mantiene el keep completo de arriba (seguro); estas reglas
# documentan lo imprescindible por si en el futuro se quiere reducir.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keep class org.opencv.core.CvException { <init>(...); }
# ML Kit / Play Services traen sus propias reglas (consumer rules) dentro del AAR.
# kotlinx.serialization: mantener los serializers generados de las clases @Serializable de la app.
-keepclassmembers @kotlinx.serialization.Serializable class com.scannerpromax.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.scannerpromax.**$$serializer { *; }
-dontnote kotlinx.serialization.**
# Release: quitar los logs verbose/debug (menos trabajo y menos cadenas en el hilo principal).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
