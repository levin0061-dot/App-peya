package com.repartidor.rentable

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SAMPLE_PAY = 2500.0
private const val SAMPLE_KM = 5.0
private const val SAMPLE_MIN = 25.0

data class Eval(val net: Double, val perKm: Double, val perHour: Double, val fails: List<String>) {
    val ok: Boolean get() = fails.isEmpty()
}

fun evaluate(pay: Double, kms: Double, mins: Double, p: SharedPreferences, zone: String?): Eval {
    val cost = p.getFloat("cost", 0f)
    val minNet = p.getFloat("minNet", 0f)
    val minKm = p.getFloat("minKm", 0f)
    val minHour = p.getFloat("minHour", 0f)
    val net = pay - kms * cost
    val perKm = if (kms > 0) net / kms else 0.0
    val perHour = if (mins > 0) net / mins * 60 else 0.0
    val f = ArrayList<String>()
    if (net < minNet) f.add("ganancia por pedido")
    if (kms > 0 && perKm < minKm) f.add("ganancia por km")
    if (mins > 0 && minHour > 0 && perHour < minHour) f.add("ganancia por hora")
    if (zone != null) f.add("zona a evitar ($zone)")
    return Eval(net, perKm, perHour, f)
}

fun money(x: Double): String = "$" + "%,.0f".format(x)

data class Rec(val ts: Long, val pay: Double, val km: Double, val min: Double, val net: Double)

fun loadHist(p: SharedPreferences): List<Rec> =
    (p.getString("hist", "") ?: "").lines().mapNotNull { line ->
        val s = line.split(",")
        if (s.size != 5) null else try {
            Rec(s[0].toLong(), s[1].toDouble(), s[2].toDouble(), s[3].toDouble(), s[4].toDouble())
        } catch (e: Exception) { null }
    }

fun saveHist(p: SharedPreferences, list: List<Rec>) {
    p.edit().putString("hist", list.joinToString("\n") { "${it.ts},${it.pay},${it.km},${it.min},${it.net}" }).apply()
}

class MainActivity : Activity() {

    private val night: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val BG by lazy { Color.parseColor(if (night) "#0B0F14" else "#F4F5F7") }
    private val CARD by lazy { Color.parseColor(if (night) "#151B23" else "#FFFFFF") }
    private val INK by lazy { Color.parseColor(if (night) "#F3F4F6" else "#111827") }
    private val MUT by lazy { Color.parseColor(if (night) "#9CA3AF" else "#6B7280") }
    private val LINE by lazy { Color.parseColor(if (night) "#263040" else "#E5E7EB") }
    private val FIELD by lazy { Color.parseColor(if (night) "#0F141B" else "#F9FAFB") }
    private val SOFT by lazy { Color.parseColor(if (night) "#222B38" else "#F3F4F6") }
    private val GREEN by lazy { Color.parseColor(if (night) "#22C55E" else "#15803D") }
    private val RED = Color.parseColor("#E11D48")

    private lateinit var prefs: SharedPreferences
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var statusBtn: Button
    private lateinit var statusHelp: LinearLayout
    private lateinit var pvBox: LinearLayout
    private lateinit var pvVerdict: TextView
    private lateinit var pvDetail: TextView
    private lateinit var histBox: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun rounded(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun tv(label: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = label
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun btn(label: String, bg: Int, fg: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        setAllCaps(false)
        textSize = 16f
        setTextColor(fg)
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(bg, 12)
        stateListAnimator = null
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { onClick() }
    }

    private fun card(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 16, LINE)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        parent.addView(c, lp(14))
        return c
    }

    private fun field(parent: LinearLayout, label: String, help: String, key: String, numeric: Boolean = true) {
        parent.addView(tv(label, 14f, INK, true), lp(16))
        parent.addView(tv(help, 12f, MUT), lp(2))
        val e = EditText(this).apply {
            if (numeric) {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                val v = prefs.getFloat(key, 0f)
                setText(if (v == 0f) "" else if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString())
            } else {
                inputType = InputType.TYPE_CLASS_TEXT
                setText(prefs.getString(key, "") ?: "")
            }
            textSize = 18f
            setTextColor(INK)
            setHintTextColor(MUT)
            background = rounded(FIELD, 10, LINE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setSingleLine()
        }
        e.hint = if (numeric) "0" else "Ej: barrio1, barrio2"
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (numeric) {
                    val v = s.toString().replace(',', '.').toFloatOrNull() ?: 0f
                    prefs.edit().putFloat(key, v).apply()
                } else {
                    prefs.edit().putString(key, s.toString()).apply()
                }
                refreshPreview()
            }
        })
        parent.addView(e, lp(8))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        if (!night) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(28))
        }
        page.addView(tv("Repartidor Rentable", 26f, INK, true))
        page.addView(tv("Sabé al instante si un pedido te conviene.", 15f, MUT), lp(4))

        // Estado del servicio
        val st = card(page)
        statusTitle = tv("", 18f, INK, true)
        statusSub = tv("", 14f, MUT)
        st.addView(statusTitle)
        st.addView(statusSub, lp(4))
        statusBtn = btn("Activar servicio", RED, Color.WHITE) {
            Toast.makeText(this, "Buscá «Repartidor Rentable» y activalo", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        st.addView(statusBtn, lp(14))
        statusHelp = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusHelp.addView(tv("¿Aparece en gris o no te deja activarlo?", 14f, INK, true), lp(16))
        statusHelp.addView(
            tv("Android bloquea por seguridad las apps instaladas desde un archivo. Tocá el botón de abajo, después los tres puntos ⋮ arriba a la derecha y elegí «Permitir configuración restringida». Volvé y activá el servicio.", 13f, MUT),
            lp(4)
        )
        statusHelp.addView(
            btn("Abrir ajustes de la app", SOFT, INK) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, lp(10)
        )
        st.addView(statusHelp, lp(0))

        // Números
        val cf = card(page)
        cf.addView(tv("Tus números", 18f, INK, true))
        cf.addView(tv("Se guardan solos. Con esto decide si un pedido es rentable.", 13f, MUT), lp(2))
        field(cf, "Costo por km", "Nafta + desgaste de la moto, por cada km.", "cost")
        field(cf, "Ganancia mínima por pedido", "Lo mínimo que querés ganar, ya descontado el costo.", "minNet")
        field(cf, "Ganancia mínima por km", "Lo mínimo que querés ganar por cada km.", "minKm")
        field(cf, "Ganancia mínima por hora", "Usa los minutos del pedido. Dejalo en 0 para ignorarlo.", "minHour")
        field(cf, "Zonas a evitar", "Barrios o palabras separados por coma. Si aparecen en el pedido, avisa.", "zones", false)

        // Vista previa
        val pv = card(page)
        pv.addView(tv("Así lo vas a ver", 18f, INK, true))
        pv.addView(tv("Ejemplo con un pedido de ${money(SAMPLE_PAY)}, ${SAMPLE_KM.toInt()} km y ${SAMPLE_MIN.toInt()} min.", 13f, MUT), lp(2))
        pvBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        pvVerdict = tv("", 20f, Color.WHITE, true)
        pvDetail = tv("", 14f, Color.WHITE)
        pvBox.addView(pvVerdict)
        pvBox.addView(pvDetail, lp(4))
        pv.addView(pvBox, lp(12))

        // Historial
        val hc = card(page)
        hc.addView(tv("Historial", 18f, INK, true))
        histBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hc.addView(histBox, lp(8))
        hc.addView(
            btn("Borrar historial", SOFT, INK) {
                AlertDialog.Builder(this)
                    .setTitle("¿Borrar el historial?")
                    .setMessage("Se eliminan todos los pedidos guardados.")
                    .setPositiveButton("Borrar") { _, _ ->
                        saveHist(prefs, emptyList())
                        refreshHistory()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }, lp(14)
        )

        // Ayuda
        val hp = card(page)
        hp.addView(tv("Cómo usarla", 18f, INK, true))
        hp.addView(
            tv("1. Cargá tus números.\n2. Activá el servicio.\n3. Abrí la app de pedidos: cuando te llegue un pedido vas a ver el cartel arriba, verde si conviene y rojo si no.\n4. Si aceptás el pedido, tocá «＋ Lo acepté» en el cartel para sumarlo al historial.", 14f, MUT),
            lp(6)
        )
        hp.addView(tv("Si el cartel deja de aparecer, poné esta app en «Sin restricciones» en los ajustes de batería.", 13f, MUT), lp(10))
        hp.addView(
            btn("Ajustes de batería", SOFT, INK) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }, lp(10)
        )

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(page)
        })
        refreshPreview()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshHistory()
    }

    private fun serviceOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("$packageName/") && s.contains("OverlayService")
    }

    private fun refreshStatus() {
        val on = serviceOn()
        statusTitle.text = if (on) "✅ Servicio activo" else "Servicio apagado"
        statusTitle.setTextColor(if (on) GREEN else RED)
        statusSub.text = if (on) "Ya vas a ver el cartel sobre la app de pedidos." else "Activalo para que pueda leer los pedidos."
        statusBtn.visibility = if (on) View.GONE else View.VISIBLE
        statusHelp.visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun refreshPreview() {
        val r = evaluate(SAMPLE_PAY, SAMPLE_KM, SAMPLE_MIN, prefs, null)
        pvBox.background = rounded(Color.parseColor(if (r.ok) "#15803D" else "#B91C1C"), 12)
        pvVerdict.text = if (r.ok) "✅ RENTABLE" else "❌ NO CONVIENE"
        var d = "Neto ${money(r.net)} · ${money(r.perKm)}/km · ${money(r.perHour)}/h"
        if (!r.ok) d += "\nFalla: " + r.fails.joinToString(", ")
        pvDetail.text = d
    }

    private fun histRow(left: String, right: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            addView(tv(left, 14f, MUT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(tv(right, 14f, INK, true))
        }

    private fun refreshHistory() {
        histBox.removeAllViews()
        val list = loadHist(prefs)
        if (list.isEmpty()) {
            histBox.addView(tv("Todavía no guardaste pedidos. Tocá «＋ Lo acepté» en el cartel cuando aceptes uno.", 13f, MUT))
            return
        }
        val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val label = SimpleDateFormat("EEE d MMM", Locale("es"))
        val byDay = list.groupBy { dayFmt.format(Date(it.ts)) }
        val today = byDay[dayFmt.format(Date())] ?: emptyList()
        val todayNet = today.sumOf { it.net }

        histBox.addView(tv("Hoy", 13f, MUT))
        histBox.addView(tv(money(todayNet), 30f, INK, true))
        val avg = if (today.isNotEmpty()) " · promedio ${money(todayNet / today.size)} por pedido" else ""
        histBox.addView(tv("${today.size} pedidos$avg", 13f, MUT), lp(2))

        histBox.addView(tv("Últimos días", 13f, MUT), lp(16))
        byDay.entries.sortedByDescending { it.key }.take(7).forEach { (_, recs) ->
            histBox.addView(
                histRow(
                    label.format(Date(recs[0].ts)) + " · ${recs.size} ped.",
                    money(recs.sumOf { it.net })
                )
            )
        }
    }
}

class Ui(
    val box: LinearLayout, val bg: GradientDrawable,
    val verdict: TextView, val pay: TextView, val sub: TextView,
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
        val texts = ArrayList<String>()
        collect(root, texts)
        Log.d("RR", texts.joinToString(" | "))
        analyze(texts)
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (n == null) return
        (n.text ?: n.contentDescription)?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        for (i in 0 until n.childCount) collect(n.getChild(i), out)
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
        var pay: Double? = null
        for (t in texts) if (payHint.containsMatchIn(t)) {
            moneyRx.find(t)?.let { pay = num(it.groupValues[1]) }
            if (pay != null) break
        }
        if (pay == null) for (t in texts) {
            moneyRx.find(t)?.let { pay = num(it.groupValues[1]) }
            if (pay != null) break
        }
        val p = pay ?: run { diag(texts); return }

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

        val r = evaluate(p, kms, mins, cfg, zoneHit)
        cur = Rec(System.currentTimeMillis(), p, kms, mins, r.net)
        curSig = "$p|$kms|$mins"
        show(p, kms, mins, r)
    }

    private fun txt(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun chip(): TextView = txt(13f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(8), dp(6), dp(8))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#1F2937"))
            cornerRadius = dp(12).toFloat()
        }
    }

    private fun lpm(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun buildUi(): Ui {
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#F2111827"))
            cornerRadius = dp(20).toFloat()
            setStroke(dp(2), Color.parseColor("#22C55E"))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            minimumWidth = dp(300)
            background = bg
        }
        val verdict = txt(18f, Color.WHITE, true)
        val pay = txt(32f, Color.WHITE, true)
        val sub = txt(13f, Color.parseColor("#9CA3AF"))
        val net = chip()
        val km = chip()
        val hour = chip()
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chips.addView(net, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        chips.addView(km, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        chips.addView(hour, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val fails = txt(12f, Color.parseColor("#FBBF24"), true)
        val save = txt(15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
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
        return Ui(box, bg, verdict, pay, sub, net, km, hour, fails, save)
    }

    private fun show(p: Double, kms: Double, mins: Double, r: Eval) {
        val u = ui ?: run {
            val n = buildUi()
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(48) }
            try { wm?.addView(n.box, lp) } catch (_: Exception) { return }
            ui = n
            n
        }
        val col = Color.parseColor(if (r.ok) "#22C55E" else "#EF4444")
        u.bg.setStroke(dp(2), col)
        u.verdict.text = if (r.ok) "✅ RENTABLE" else "❌ NO CONVIENE"
        u.verdict.setTextColor(col)
        u.pay.text = money(p)
        u.sub.text = (if (kms > 0) "%.1f km".format(kms) else "km ?") + " · " +
                (if (mins > 0) "${mins.toInt()} min" else "min ?")
        u.net.text = "Neto\n" + money(r.net)
        u.km.text = "Por km\n" + (if (kms > 0) money(r.perKm) else "–")
        u.hour.text = "Por hora\n" + (if (mins > 0) money(r.perHour) else "–")
        if (r.ok) {
            u.fails.visibility = View.GONE
        } else {
            u.fails.visibility = View.VISIBLE
            u.fails.text = "Falla: " + r.fails.joinToString(", ")
        }
        val saved = curSig == savedSig
        u.save.text = if (saved) "✓ Guardado en el historial" else "＋ Lo acepté (guardar)"
        u.save.alpha = if (saved) 0.6f else 1f
    }

      private fun diag(texts: List<String>) {
        cur = null
        val u = ui ?: run {
            val n = buildUi()
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(48) }
            try { wm?.addView(n.box, lp) } catch (_: Exception) { return }
            ui = n
            n
        }
        val amber = Color.parseColor("#FBBF24")
        u.bg.setStroke(dp(2), amber)
        u.verdict.text = "🔍 No encuentro el pago"
        u.verdict.setTextColor(amber)
        u.pay.text = "${texts.size} textos leídos"
        u.sub.text = texts.take(12).joinToString(" | ").take(350)
        u.net.text = "–"
        u.km.text = "–"
        u.hour.text = "–"
        u.fails.visibility = View.GONE
        u.save.text = "Sacá captura y mandásela a Claude"
        u.save.alpha = 0.6f
      }  private fun hide() {
        ui?.let { try { wm?.removeView(it.box) } catch (_: Exception) {} }
        ui = null
        cur = null
        savedSig = ""
    }

    override fun onInterrupt() { hide() }
    override fun onDestroy() { hide(); super.onDestroy() }
}
