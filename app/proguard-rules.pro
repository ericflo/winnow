# kotlinx.serialization, OkHttp and Room ship their own consumer rules.

# WorkManager (pulled in by Glance, which runs the home-screen widget's sessions as work) makes
# its input mergers and workers by reflection. R8's full mode drops their constructors otherwise:
# "OverwritingInputMerger has no zero argument constructor", and the widget never updates.
-keep class * extends androidx.work.InputMerger { public <init>(); }
-keep class * extends androidx.work.ListenableWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
