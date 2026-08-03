package com.ryanpudd.photobooth

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class BootAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // Device admin enabled
    }
}
