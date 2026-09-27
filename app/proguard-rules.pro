# ---- JNI ----
# LlamaBridge 的方法由 native 层按 Java_<包名>_<类名>_<方法名> 查找，名字不能被混淆
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.mscope.browser.llama.LlamaBridge { *; }

# 保留行号，便于崩溃栈定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile