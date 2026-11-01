# sherpa-onnx：原生库通过 JNI 按名字读取配置类的字段、创建结果对象，不能被混淆或删除
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { native <methods>; }
