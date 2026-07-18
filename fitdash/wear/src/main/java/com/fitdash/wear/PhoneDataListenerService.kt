package com.fitdash.wear

import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import android.content.ComponentName
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

class PhoneDataListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED &&
                event.dataItem.uri.path == "/fitdash/steps") {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val steps   = dataMap.getInt("steps", 0)
                val goal    = dataMap.getInt("goal", 10000)

                getSharedPreferences("fitdash_wear", MODE_PRIVATE).edit()
                    .putInt("steps", steps)
                    .putInt("goal", goal)
                    .apply()

                ComplicationDataSourceUpdateRequester.create(
                    this,
                    ComponentName(this, StepsComplicationService::class.java)
                ).requestUpdateAll()
            }
        }
    }
}
