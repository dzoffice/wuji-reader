# 无极阅读 - 混淆规则
# v1 不开启压缩，规则先留空

-keepattributes Signature
-keepattributes *Annotation*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Jsoup
-dontwarn org.jsoup.**

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
