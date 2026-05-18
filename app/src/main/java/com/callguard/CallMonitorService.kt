package com.callguard

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class CallMonitorService : Service() {

    private lateinit var tm: TelephonyManager
    private var prevState = TelephonyManager.CALL_STATE_IDLE

    @Suppress("DEPRECATION")
    private val listener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, number: String?) {
            if (prevState != TelephonyManager.CALL_STATE_IDLE
                && state == TelephonyManager.CALL_STATE_IDLE) {
                Thread {
                    Thread.sleep(1500) // wait for system to write call log
                    backup()
                }.start()
            }
            prevState = state
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        @Suppress("DEPRECATION")
        tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        @Suppress("DEPRECATION")
        tm.listen(listener, PhoneStateListener.LISTEN_NONE)
        super.onDestroy()
    }

    private fun backup() {
        val pw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PW, null) ?: return
        latestCall()?.also {
            LogStorage.save(this, it, pw)
            LogStorage.purgeOld(this)
        }
    }

    private fun latestCall(): JSONObject? {
        val cols = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                           CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
        return try {
            contentResolver.query(CallLog.Calls.CONTENT_URI, cols, null, null,
                "${CallLog.Calls.DATE} DESC")?.use { cur ->
                if (!cur.moveToFirst()) return null
                JSONObject().apply {
                    put("number", cur.getString(0) ?: "")
                    put("name",   cur.getString(1) ?: "")
                    put("type", when (cur.getInt(2)) {
                        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                        CallLog.Calls.MISSED_TYPE   -> "MISSED"
                        else -> "UNKNOWN"
                    })
                    put("date", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(Date(cur.getLong(3))))
                    put("duration", cur.getLong(4))
                }
            }
        } catch (e: Exception) { null }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CH, "통화 감지", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CH)
        .setContentTitle("통화기록 보호 실행 중")
        .setContentText("통화 종료 시 자동 백업됩니다")
        .setSmallIcon(android.R.drawable.ic_menu_call)
        .setContentIntent(PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true).build()

    companion object {
        const val CH       = "cg_monitor"
        const val NOTIF_ID = 1001
        const val PREFS    = "callguard"
        const val KEY_PW   = "password"
    }
}
