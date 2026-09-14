# Add project specific ProGuard rules here.
# Native methods must be kept or JNI binding will fail silently at runtime.
-keepclasseswithmembernames class * {
    native <methods>;
}
