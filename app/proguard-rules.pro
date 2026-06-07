-keep class com.rstagit.androidspf.core.protocol.GoNativeBridge {
    native <methods>;
    public static <methods>;
}

-keepclassmembers class * {
    native <methods>;
}

-keep class com.rstagit.androidspf.core.model.** { *; }
-keep class com.rstagit.androidspf.service.TunnelForegroundService { *; }
-keep class com.rstagit.androidspf.ui.dashboard.LaunchActivity { *; }
-keep class com.rstagit.androidspf.ui.setup.ConfigActivity { *; }
-keep class com.rstagit.androidspf.ui.logview.LogActivity { *; }
