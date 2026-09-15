package com.marketforecast.prox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val p = context.getSharedPreferences("mfprefs", Context.MODE_PRIVATE)
        val interval = p.getInt("notify_interval", 15).coerceAtLeast(15)
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "market_monitor", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<MarketMonitorWorker>(interval.toLong(), TimeUnit.MINUTES).setConstraints(constraints).build()
        )
    }
}
