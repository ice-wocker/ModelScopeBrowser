# ---- JNI 符号 ----
# native 方法由 JVM 按 Java_<包名>_<类名>_<方法名> 查找，名字不能被混淆
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.mscope.browser.llama.LlamaBridge { *; }

# native 层是用「字面名」回调的：
#   GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V")
# 因此接口方法名与所有实现类（lambda / 匿名类）的同名方法都必须保留，
# 否则 release 包运行时报：
#   no non-static method "Lxxx;.onToken(Ljava/lang/String;)V"
-keep interface com.mscope.browser.llama.LlamaBridge$TokenCallback { *; }
-keepclassmembers class * implements com.mscope.browser.llama.LlamaBridge$TokenCallback {
    public void onToken(java.lang.String);
}

# 保留行号，便于崩溃栈定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile