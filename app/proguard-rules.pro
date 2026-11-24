# JNI entry points are looked up by name.
-keep class io.github.guilhermefeitosa66.openpdfviewer.pdfium.PdfiumNative { *; }

# PdfBox-Android and BouncyCastle use reflection for fonts, filters and crypto providers.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn com.tom_roush.**
-dontwarn com.gemalto.jp2.**
