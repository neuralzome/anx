package com.flomobility.hermes.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.flomobility.hermes.ui.login.LoginActivity
import javax.inject.Inject


class StartAppOnBootReceiver() : BroadcastReceiver() {

    @Inject
    lateinit var sharedPreferences: SharedPreferences

    override fun onReceive(context: Context?, intent: Intent?) {
        if (Intent.ACTION_BOOT_COMPLETED == intent?.action) {
            val i = Intent(context, LoginActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context?.startActivity(i)
//            runAsRoot(cmd = "am start com.flomobility.hermes/com.flomobility.hermes.MainActivity")
        }
    }
}