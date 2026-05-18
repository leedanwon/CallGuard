package com.callguard

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvCount: TextView
    private lateinit var etPw: EditText
    private lateinit var etPwConfirm: EditText
    private lateinit var btnSetPw: Button
    private lateinit var btnMonitor: Button
    private lateinit var btnView: Button

    private val prefs get() = getSharedPreferences(CallMonitorService.PREFS, MODE_PRIVATE)
    private var monitoring = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus    = findViewById(R.id.tvStatus)
        tvCount     = findViewById(R.id.tvCount)
        etPw        = findViewById(R.id.etPw)
        etPwConfirm = findViewById(R.id.etPwConfirm)
        btnSetPw    = findViewById(R.id.btnSetPw)
        btnMonitor  = findViewById(R.id.btnMonitor)
        btnView     = findViewById(R.id.btnView)

        requestPerms()
        btnSetPw.setOnClickListener  { savePassword() }
        btnMonitor.setOnClickListener { toggleMonitor() }
        btnView.setOnClickListener   { viewLogs() }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val hasPw = prefs.getString(WebServerService.KEY_HASH, null) != null
        tvStatus.text = if (monitoring) "● 감지 중" else "● 대기 중"
        tvStatus.setTextColor(if (monitoring) 0xFF48bb78.toInt() else 0xFFfc8181.toInt())
        tvCount.text  = "저장된 기록: ${LogStorage.count(this)}건"
        btnMonitor.text = if (monitoring) "감지 중지" else "감지 시작"
        btnMonitor.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (monitoring) 0xFFfc8181.toInt() else 0xFF48bb78.toInt())
        btnMonitor.isEnabled = hasPw
        btnView.isEnabled    = hasPw
        if (!hasPw) tvStatus.text = "비밀번호를 먼저 설정하세요"
    }

    private fun savePassword() {
        val pw1 = etPw.text.toString()
        val pw2 = etPwConfirm.text.toString()
        if (pw1.length < 4) { toast("4자 이상 입력하세요"); return }
        if (pw1 != pw2)      { toast("비밀번호가 일치하지 않습니다"); return }
        prefs.edit()
            .putString(WebServerService.KEY_HASH, CryptoManager.hashPassword(pw1))
            .putString(CallMonitorService.KEY_PW, pw1)
            .apply()
        etPw.text?.clear(); etPwConfirm.text?.clear()
        toast("비밀번호 설정 완료 ✓"); refresh()
    }

    private fun toggleMonitor() {
        if (monitoring) stopService(Intent(this, CallMonitorService::class.java))
        else ContextCompat.startForegroundService(this, Intent(this, CallMonitorService::class.java))
        monitoring = !monitoring; refresh()
    }

    private fun viewLogs() {
        ContextCompat.startForegroundService(this, Intent(this, WebServerService::class.java))
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://localhost:${WebServerService.PORT}")))
        }, 800)
    }

    private fun requestPerms() {
        val needed = mutableListOf(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE
        ).also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                it.add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty())
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
