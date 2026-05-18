package com.callguard

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

class WebServerService : Service() {
    private var server: GuardServer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())

        val passHash = getSharedPreferences(CallMonitorService.PREFS, MODE_PRIVATE)
            .getString(KEY_HASH, null) ?: run { stopSelf(); return }

        server = GuardServer(this, passHash).also { it.start() }
        Handler(Looper.getMainLooper()).postDelayed({ stopSelf() }, 30 * 60 * 1000L)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_NOT_STICKY
    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() { server?.stop(); super.onDestroy() }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CH, "기록 조회 서버", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CH)
        .setContentTitle("기록 조회 서버 실행 중")
        .setContentText("브라우저 → localhost:$PORT")
        .setSmallIcon(android.R.drawable.ic_menu_info_details).build()

    companion object {
        const val CH       = "cg_web"
        const val NOTIF_ID = 1002
        const val PORT     = 8765
        const val KEY_HASH = "pass_hash"
    }
}

class GuardServer(private val ctx: Context, private val passHash: String)
    : NanoHTTPD("127.0.0.1", WebServerService.PORT) {

    private var token: String? = null
    private var sessionPw: String? = null

    override fun serve(s: IHTTPSession): Response {
        val uri    = s.uri
        val cookie = parseCookies(s.headers["cookie"] ?: "")
        val tkn    = cookie["token"] ?: ""

        if (s.method == Method.POST && uri == "/login") {
            val files = HashMap<String, String>()
            s.parseBody(files)
            val pw = parseField(files["postData"] ?: "", "password")
            return if (sha256(pw) == passHash) {
                token = genToken(); sessionPw = pw
                redirect("/logs", "token=$token; Path=/; HttpOnly")
            } else html(loginPage(true))
        }

        if (uri == "/logout") { token = null; sessionPw = null; return redirect("/", null) }

        if (uri == "/logs") {
            if (tkn != token || token == null) return redirect("/", null)
            return html(logsPage(LogStorage.getAll(ctx, sessionPw!!)))
        }

        if (uri == "/stop") {
            ctx.stopService(Intent(ctx, WebServerService::class.java))
            return html("<html><body style='background:#1a1a2e;color:#e2e8f0;text-align:center;padding:60px;font-family:sans-serif'><h2>서버가 종료되었습니다</h2></body></html>")
        }

        return html(loginPage(false))
    }

    private fun html(b: String) =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", b)

    private fun redirect(loc: String, setCookie: String?) =
        newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").also {
            it.addHeader("Location", loc)
            if (setCookie != null) it.addHeader("Set-Cookie", setCookie)
        }

    private fun parseCookies(h: String) = h.split(";").mapNotNull { p ->
        val i = p.indexOf('='); if (i < 0) null
        else p.substring(0, i).trim() to p.substring(i + 1).trim()
    }.toMap()

    private fun parseField(body: String, key: String) =
        body.split("&").firstOrNull { it.startsWith("$key=") }
            ?.removePrefix("$key=")?.let { URLDecoder.decode(it, "UTF-8") } ?: ""

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun genToken() = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private fun loginPage(error: Boolean) = """<!DOCTYPE html>
<html lang="ko"><head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>통화기록 조회</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,sans-serif;background:#1a1a2e;display:flex;
  justify-content:center;align-items:center;min-height:100vh}
.card{background:#16213e;border-radius:16px;padding:40px 32px;
  width:90%;max-width:340px;box-shadow:0 8px 32px rgba(0,0,0,.4)}
h2{color:#e2e8f0;text-align:center;margin-bottom:28px;font-size:1.3rem}
input{width:100%;padding:12px 16px;border:1px solid #2d3748;border-radius:8px;
  background:#0f3460;color:#e2e8f0;font-size:1rem;outline:none}
input:focus{border-color:#4a90d9}
button{width:100%;margin-top:16px;padding:12px;border:none;border-radius:8px;
  background:#4a90d9;color:#fff;font-size:1rem;cursor:pointer;font-weight:600}
.err{color:#fc8181;text-align:center;margin-top:12px;font-size:.9rem}
</style></head><body>
<div class="card">
<h2>🔒 통화기록 조회</h2>
<form method="POST" action="/login">
<input type="password" name="password" placeholder="비밀번호" autofocus>
<button type="submit">확인</button>
</form>
${if (error) "<p class=\"err\">비밀번호가 틀렸습니다</p>" else ""}
</div></body></html>"""

    private fun logsPage(logs: List<JSONObject>): String {
        val clr = mapOf("INCOMING" to "#68d391","OUTGOING" to "#63b3ed","MISSED" to "#fc8181")
        val lbl = mapOf("INCOMING" to "수신","OUTGOING" to "발신","MISSED" to "부재중")
        val rows = if (logs.isEmpty())
            "<tr><td colspan='5' style='text-align:center;padding:60px;color:#718096'>저장된 기록이 없습니다</td></tr>"
        else logs.joinToString("") { r ->
            val t   = r.optString("type")
            val dur = r.optLong("duration", 0).let {
                if (it <= 0) "-" else if (it < 60) "${it}초" else "${it/60}분 ${it%60}초" }
            val nm  = r.optString("name").ifBlank { "<span style='color:#718096'>(이름없음)</span>" }
            "<tr><td>${r.optString("date")}</td>" +
            "<td style='color:${clr[t]};font-weight:600'>${lbl[t] ?: t}</td>" +
            "<td>$nm</td><td>${r.optString("number")}</td><td>$dur</td></tr>"
        }
        return """<!DOCTYPE html>
<html lang="ko"><head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>통화기록</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,sans-serif;background:#1a1a2e;color:#e2e8f0;padding:16px}
.hd{display:flex;justify-content:space-between;align-items:center;margin-bottom:12px}
h2{font-size:1.1rem}
.meta{color:#a0aec0;font-size:.85rem}
a{color:#fc8181;text-decoration:none;font-size:.85rem;margin-left:12px}
.srch{width:100%;padding:10px 14px;border:1px solid #2d3748;border-radius:8px;
  background:#0f3460;color:#e2e8f0;font-size:.95rem;margin-bottom:12px;outline:none}
.wrap{overflow-x:auto;border-radius:10px}
table{width:100%;border-collapse:collapse;background:#16213e;font-size:.82rem;min-width:480px}
th{background:#0f3460;color:#a0aec0;font-weight:600;padding:10px 12px;text-align:left;white-space:nowrap}
td{padding:10px 12px;border-bottom:1px solid #2d3748}
tr:last-child td{border-bottom:none}
</style></head><body>
<div class="hd"><h2>📋 통화기록</h2>
<div><span class="meta">${logs.size}건</span>
<a href="/logout">로그아웃</a>
<a href="/stop">서버종료</a></div></div>
<input class="srch" type="text" placeholder="이름 또는 번호 검색..." oninput="f(this)">
<div class="wrap"><table id="t">
<thead><tr><th>일시</th><th>유형</th><th>이름</th><th>번호</th><th>통화시간</th></tr></thead>
<tbody>$rows</tbody></table></div>
<script>function f(e){const q=e.value.toLowerCase();
document.querySelectorAll('#t tbody tr').forEach(r=>{
r.style.display=r.innerText.toLowerCase().includes(q)?'':'none'})}</script>
</body></html>"""
    }
}
