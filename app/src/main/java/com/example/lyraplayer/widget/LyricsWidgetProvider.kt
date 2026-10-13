package com.example.lyraplayer.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.widget.RemoteViews
import com.example.lyraplayer.R

class LyricsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val prefs = context.getSharedPreferences("lyra", Context.MODE_PRIVATE)
        update(context, manager, ids, prefs.getString("current_title", "Lyra Player").orEmpty(), prefs.getString("current_lyric", "Pilih lagu untuk menampilkan lirik").orEmpty())
    }

    override fun onReceive(context: Context, intent: android.content.Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_UPDATE) {
            updateAll(context, intent.getStringExtra("title").orEmpty(), intent.getStringExtra("lyric").orEmpty())
        }
    }

    companion object {
        const val ACTION_UPDATE = "com.example.lyraplayer.UPDATE_LYRICS_WIDGET"
        fun updateAll(context: Context, title: String, lyric: String) {
            context.getSharedPreferences("lyra", Context.MODE_PRIVATE).edit()
                .putString("current_title", title).putString("current_lyric", lyric).apply()
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, LyricsWidgetProvider::class.java))
            update(context, manager, ids, title, lyric)
        }
        private fun update(context: Context, manager: AppWidgetManager, ids: IntArray, title: String, lyric: String) {
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.lyrics_widget)
                views.setTextViewText(R.id.widget_title, title.ifBlank { "Lyra Player" })
                views.setTextViewText(R.id.widget_lyric, lyric.ifBlank { "Lirik belum tersedia" })
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launchIntent != null) {
                    val pending = PendingIntent.getActivity(
                        context, id, launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(R.id.widget_root, pending)
                }
                manager.updateAppWidget(id, views)
            }
        }
    }
}
