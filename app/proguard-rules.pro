# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK location/sdk/tools/proguard/proguard-android.txt

# JNI: keep NativeMiner and all native methods so libminer.so can call them
-keep class com.btcminer.android.mining.NativeMiner { *; }

# Security crypto (EncryptedSharedPreferences, MasterKey) - keep for reflection
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**
