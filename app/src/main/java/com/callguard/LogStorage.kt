package com.callguard

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object LogStorage {
    private fun dir(ctx: Context) = File(ctx.filesDir, "logs").also { it.mkdirs() }

    fun save(ctx: Context, entry: JSONObject, pw: String) {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        File(dir(ctx), "$ts.enc").writeBytes(CryptoManager.encrypt(entry.toString(), pw))
    }

    fun getAll(ctx: Context, pw: String): List<JSONObject> =
        dir(ctx).listFiles { f -> f.extension == "enc" }
            ?.sortedByDescending { it.name }
            ?.mapNotNull { f ->
                try { CryptoManager.decrypt(f.readBytes(), pw)?.let { JSONObject(it) } }
                catch (e: Exception) { null }
            } ?: emptyList()

    fun count(ctx: Context): Int =
        dir(ctx).listFiles { f -> f.extension == "enc" }?.size ?: 0

    fun purgeOld(ctx: Context) {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        dir(ctx).listFiles { f -> f.extension == "enc" && f.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }
}
