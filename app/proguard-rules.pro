# 保留入口与反射可见的类
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod,SourceFile,LineNumberTable

# org.json 被 Java/Kotlin 代码直接引用
-keep class org.json.** { *; }

# 打包进 APK 的本地 jar
-keep class org.brotli.dec.** { *; }
-keep class com.caverock.androidsvg.** { *; }

# miuix 主题与组件
-keep class top.yukonga.miuix.kmp.** { *; }

# 未使用的可选依赖引用
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.**
-dontwarn androidx.**
