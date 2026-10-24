# WorkManager creates workers and the boot receiver by class name.
-keep class com.marketforecast.prox.MarketMonitorWorker { *; }
-keep class com.marketforecast.prox.ScanWorker { *; }
-keep class com.marketforecast.prox.BootReceiver { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn org.xmlpull.**
