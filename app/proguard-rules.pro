# Proguard / R8 rules for Ambiorix Pay Release APK
-keep class com.ambiorix.pay.MainActivity { *; }
-keep class com.ambiorix.pay.SettingsActivity { *; }
-keep class com.ambiorix.pay.notification.AmbiorixNotificationListener { *; }
-keep class com.ambiorix.pay.sms.AmbiorixSmsReceiver { *; }

# Keep all enums and data models used in JSON/SharedPreferences serialization
-keep enum com.ambiorix.pay.** { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep class com.ambiorix.pay.data.** { *; }
-keep class com.ambiorix.pay.pos.** { *; }
-keep class com.ambiorix.pay.notification.** { *; }
-keep class com.ambiorix.pay.sms.** { *; }

