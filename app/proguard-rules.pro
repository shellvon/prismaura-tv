# Plugin scripts call these bridge methods by their JavaScript-visible names.
-keepclassmembers,allowoptimization class tv.prism.sources.PluginService$* {
    @android.webkit.JavascriptInterface <methods>;
}

# Room instantiates this generated WorkManager database by reflection.
-keep class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
}
