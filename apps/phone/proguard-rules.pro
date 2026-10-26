# R8 rules for the phone release build. Library consumer rules (Room, WorkManager, OkHttp,
# Conscrypt, kotlinx, Compose) come in with their artifacts; this file adds only what the
# app itself depends on.

# Package names stay readable. The release dex purity check and the analytics scan identify
# third-party code by package-qualified class descriptor, and both have to keep seeing it.
-keeppackagenames

# Exception class names stay as written: diagnostics, gap records and start-failure reasons
# record or compare a throwable's simple name.
-keepnames class * extends java.lang.Throwable

# Glance records the widget class's name to map its receiver to the widget, and that record
# outlives an update, so the name stays. It also creates widget action callbacks by
# reflection from their names, through the no-argument constructor nothing else calls.
-keepnames class * extends androidx.glance.appwidget.GlanceAppWidget
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    <init>();
}
