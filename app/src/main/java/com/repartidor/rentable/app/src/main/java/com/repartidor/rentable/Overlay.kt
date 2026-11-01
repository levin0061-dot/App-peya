package com.repartidor.rentable

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*

class Ui(
    val box: LinearLayout, val bg: GradientDrawable,
    val verdict: TextView, val pay: TextView, val sub: TextView,
    val chips: LinearLayout,
    val net: TextView, val km: TextView, val hour: TextView,
    val fails: TextView, val save: TextView
)

class OverlayService : AccessibilityService() {

    // Solo lee apps cuyo paquete contenga esto.
    private val targetPkg = "pedidosya"

    private var wm: WindowManager? = null
    private var ui: Ui? = null
    private var last = 0L
    private var cur: Rec? = null
    private var curSig = ""
    private var savedSig = ""
    private var lastAnn = ""

    private val moneyRx = Regex("""\$\s?(\d[\d.,]*)""")
    private val kmRx = Regex("""(\d+(?:[.,]\d+)?)\s?km""", RegexOption.IGNORE_CASE)
    private val minRx = Regex("""(\d+)\s?min""", RegexOption.IGNORE_CASE)
    private val payHint = Regex("ganancia|pago|cobr|recibir|total", RegexOption.IGNORE_CASE)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (!pkg.contains(targetPkg, true)) { hide(); return }

        val now = SystemClock.uptimeMillis()
        if (now - last < 600) return
        last = now

        val root = rootInActiveWindow ?: return
        val zones = (getSharedPreferences("cfg", MODE_PRIVATE).getString("zones", "") ?: "")
            .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val texts = ArrayList<String>()
        collect(root, texts, zones)
        analyze(texts)
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<String>, zones: List<String>) {
        if (n == null) return
        (n.text ?: n.contentDescription)?.toString()?.let { if (keep(it, zones)) out.add(it) }
        for (i in 0 until n.childCount) collect(n.getChild(i), out, zones)
    }

    // Solo se conserva lo esencial: km, minutos, montos, el botón de aceptar y tus zonas.
    private fun keep(t: String, zones: List<String>): Boolean {
        val l = t.lowercase()
        return kmRx.containsMatchIn(t) || minRx.containsMatchIn(t) ||
                moneyRx.containsMatchIn(t) || l.contains("aceptar") ||
                zones.any { l.contains(it) }
    }

    private fun num(s: String): Double? {
        var t = s.trim().trimEnd('.', ',')
        t = when {
            t.contains('.') && t.contains(',') -> t.replace(".", "").replace(',', '.')
            t.contains(',') -> if (t.substringAfter(',').length == 3) t.replace(",", "") else t.replace(',', '.')
            t.contains('.') -> if (t.substringAfter('.').length == 3) t.replace(".", "") else t
            else -> t
        }
        return t.toDoubleOrNull()
    }

    private fun analyze(texts: List<String>) {
        var kms = 0.0
        var mins = 0.0
        for (t in texts) {
            kmRx.findAll(t).forEach { kms += num(it.groupValues[1]) ?: 0.0 }
            minRx.findAll(t).forEach { mins += it.groupValues[1].toDoubleOrNull() ?: 0.0 }
        }

        val cfg = getSharedPreferences("cfg", MODE_PRIVATE)
        val zones = (cfg.getString("zones", "") ?: "").split(",")
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val blob = texts.joinToString(" ").lowercase()
        val zoneHit = zones.firstOrNull { blob.contains(it) }

        var pay: Double? = null
        for (t in texts) if (payHint.containsMatchIn(t)) {
            moneyRx.find(t)?.let { pay = num(it.groupValues[1]) }
            if (pay != null) break
        }
        if (pay == null) for (t in texts) {
            moneyRx.find(t)?.let { pay = num(it.groupValues[1]) }
            if (pay != null) break
        }
        val p = pay ?: run { noPay(blob, kms, cfg, zoneHit); return }

        val r = evaluate(p, kms, mins, cfg, zoneHit)
        cur = Rec(System.currentTimeMillis(), p, kms, mins, r.net)
        curSig = "$p|$kms|$mins"
        show(p, kms, mins, r, false)
    }

    // La pantalla del pedido no muestra el pago: usamos los km.
    private fun noPay(blob: String, kms: Double, cfg: SharedPreferences, zone: String?) {
        if (kms <= 0 || !blob.contains("aceptar")) { hide(); return }
        val base = cfg.getFloat("rateBase", 0f).toDouble()
        val rk = cfg.getFloat("rateKm", 0f).toDouble()
        if (base > 0 || rk > 0) {
            val est = base + rk * kms
            val r = evaluate(est, kms, 0.0, cfg, zone)
            cur = Rec(System.currentTimeMillis(), est, kms, 0.0, r.net)
            curSig = "est|$est|$kms"
            show(est, kms, 0.0, r, true)
        } else {
            showNeeded(kms, cfg, zone)
        }
    }

    private fun txt(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun chip(k: Float): TextView = txt(13f * k, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp((6 * k).toInt()), dp((8 * k).toInt()), dp((6 * k).toInt()), dp((8 * k).toInt()))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#1F2937"))
            cornerRadius = dp(12).toFloat()
        }
    }

    private fun lpm(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun buildUi(k: Float): Ui {
        fun s(v: Int) = dp((v * k).toInt())
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#F2111827"))
            cornerRadius = s(20).toFloat()
            setStroke(dp(2), Color.parseColor("#22C55E"))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(s(16), s(14), s(16), s(14))
            minimumWidth = dp(minOf(300f * k, 340f).toInt())
            background = bg
        }
        val verdict = txt(18f * k, Color.WHITE, true)
        val pay = txt(32f * k, Color.WHITE, true)
        val sub = txt(13f * k, Color.parseColor("#9CA3AF"))
        val net = chip(k)
        val km = chip(k)
        val hour = chip(k)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chips.addView(net, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        chips.addView(km, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        chips.addView(hour, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val fails = txt(12f * k, Color.parseColor("#FBBF24"), true)
        val save = txt(15f * k, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(s(12), s(10), s(12), s(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2563EB"))
                cornerRadius = dp(12).toFloat()
            }
        }
        save.setOnClickListener {
            val c = cur ?: return@setOnClickListener
            if (curSig == savedSig) return@setOnClickListener
            val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
            val l = loadHist(prefs).toMutableList()
            l.add(c)
            saveHist(prefs, l.takeLast(500))
            savedSig = curSig
            save.text = "✓ Guardado en el historial"
            save.alpha = 0.6f
        }
        box.addView(verdict)
        box.addView(pay, lpm(2))
        box.addView(sub, lpm(0))
        box.addView(chips, lpm(10))
        box.addView(fails, lpm(8))
        box.addView(save, lpm(12))
        return Ui(box, bg, verdict, pay, sub, chips, net, km, hour, fails, save)
    }

    private fun ensureUi(): Ui? {
        ui?.let { return it }
        val cfg = getSharedPreferences("cfg", MODE_PRIVATE)
        val n = buildUi(scaleOf(cfg.getInt("ovSize", 1)))
        n.box.alpha = cfg.getInt("ovAlpha", 95).coerceIn(40, 100) / 100f
        val pos = cfg.getInt("ovPos", 0)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            when (pos) {
                1 -> gravity = Gravity.CENTER
                2 -> { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(24) }
                else -> { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(48) }
            }
        }
        try { wm?.addView(n.box, lp) } catch (_: Exception) { return null }
        ui = n
        return n
    }

    private fun announce(sig: String, key: String) {
        if (sig == lastAnn) return
        lastAnn = sig
        val cfg = getSharedPreferences("cfg", MODE_PRIVATE)
        if (cfg.getBoolean("sndOn", true)) Snd.play(this, key)
    }

    private fun show(p: Double, kms: Double, mins: Double, r: Eval, est: Boolean) {
        val u = ensureUi() ?: return
        val cfg = getSharedPreferences("cfg", MODE_PRIVATE)
        val col = Color.parseColor(if (r.ok) "#22C55E" else "#EF4444")
        val tag = if (est) " (est.)" else ""
        u.bg.setStroke(dp(2), col)
        u.verdict.text = (if (r.ok) "✅ RENTABLE" else "❌ NO CONVIENE") + tag
        u.verdict.setTextColor(col)
        u.pay.text = (if (est) "≈ " else "") + money(p)
        u.sub.text = (if (kms > 0) "%.1f km".format(kms) else "km ?") + " · " +
                (if (mins > 0) "${mins.toInt()} min" else "min ?")
        u.net.text = "Neto\n" + money(r.net)
        u.km.text = "Por km\n" + (if (kms > 0) money(r.perKm) else "–")
        u.hour.text = "Por hora\n" + (if (mins > 0) money(r.perHour) else "–")
        u.chips.visibility = if (cfg.getBoolean("ovChips", true)) View.VISIBLE else View.GONE
        if (r.ok) {
            u.fails.visibility = View.GONE
        } else {
            u.fails.visibility = View.VISIBLE
            u.fails.text = "Falla: " + r.fails.joinToString(", ")
        }
        u.save.visibility = if (cfg.getBoolean("ovSave", true)) View.VISIBLE else View.GONE
        val saved = curSig == savedSig
        u.save.text = if (saved) "✓ Guardado en el historial" else "＋ Lo acepté (guardar)"
        u.save.alpha = if (saved) 0.6f else 1f
        announce(curSig, if (r.ok) "sndOk" else "sndNo")
    }

    private fun showNeeded(kms: Double, cfg: SharedPreferences, zone: String?) {
        cur = null
        val u = ensureUi() ?: return
        val blue = Color.parseColor("#60A5FA")
        val need = neededPay(kms, cfg)
        u.bg.setStroke(dp(2), blue)
        u.verdict.text = "📍 Aceptá si pagan más de"
        u.verdict.setTextColor(blue)
        u.pay.text = money(need)
        u.sub.text = "%.2f km en total · según tus números".format(kms)
        u.net.text = "Costo\n" + money(kms * cfg.getFloat("cost", 0f))
        u.km.text = "Mín. por km\n" + money(cfg.getFloat("minKm", 0f).toDouble())
        u.hour.text = "Mín. neto\n" + money(cfg.getFloat("minNet", 0f).toDouble())
        u.chips.visibility = if (cfg.getBoolean("ovChips", true)) View.VISIBLE else View.GONE
        if (zone != null) {
            u.fails.visibility = View.VISIBLE
            u.fails.text = "⚠️ Zona a evitar ($zone)"
        } else {
            u.fails.visibility = View.GONE
        }
        u.save.visibility = View.GONE
        announce("need|$kms", "sndOk")
    }

    private fun hide() {
        ui?.let { try { wm?.removeView(it.box) } catch (_: Exception) {} }
        ui = null
        cur = null
        savedSig = ""
        lastAnn = ""
        Snd.stop()
    }

    override fun onInterrupt() { hide() }
    override fun onDestroy() { hide(); super.onDestroy() }
}
