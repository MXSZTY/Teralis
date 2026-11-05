# 数据模型通过 Gson 反射序列化，保留模型类与泛型签名。
-keep class com.mxszty.teralis.data.** { *; }
-keepattributes Signature, *Annotation*

# WebView JS 调试与 JavaScriptInterface（当前未使用注解桥，保留以备扩展）。
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
