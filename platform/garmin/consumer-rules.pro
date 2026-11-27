# Keep Garmin Connect IQ Parcelables, Binder AIDL interfaces, and Monkey C serializer intact
# in release builds. Garmin Connect Mobile exchanges IQDevice/IQApp/IQMessage across IPC and
# deserializes watch messages through Serializer/MonkeyType classes.
-keep class com.garmin.android.connectiq.IQApp { *; }
-keep class com.garmin.android.connectiq.IQApp$* { *; }
-keep class com.garmin.android.connectiq.IQDevice { *; }
-keep class com.garmin.android.connectiq.IQDevice$* { *; }
-keep class com.garmin.android.connectiq.IQMessage { *; }
-keep class com.garmin.android.connectiq.IQMessage$* { *; }
-keep class com.garmin.android.apps.connectmobile.connectiq.** { *; }
-keep class com.garmin.monkeybrains.serialization.** { *; }
