package com.repartidor.rentable

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
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

private const val SAMPLE_PAY = 2500.0
private const val SAMPLE_KM = 5.0

data class Eval(val net: Double, val perKm: Double, val ok: Boolean)

fun evaluate(pay: Double, kms: Double, cost: Float, minNet: Float, minKm: Float): Eval {
    val net = pay - kms * cost
    val perKm = if (kms > 0) net / kms else 0.0
    val ok = net >= minNet && (kms == 0.0 || perKm >= minKm)
    return Eval(net, perKm, ok)
}

fun money(x: Double): String = "$" + "%,.0f".format(x)

class MainActivity : Activity() {

    private val BG = Color.parseColor("#F4F5F7")
    private val INK = Color.parseColor("#111827")
    private val MUT = Color.parseColor("#6B7280")
    private val LINE = Color.parseColor("#E5E7EB")
    private val RED = Color.parseColor("#E11D48")
    private val GREEN = Color.parseColor("#15803D")

    private lateinit var prefs: SharedPreferences
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var statusBtn: Button
    private lateinit var statusHelp: LinearLayout
    private lateinit var pvBox: LinearLayout
    private lateinit var pvVerdict: TextView
    private lateinit var pvDetail: TextView

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
            background = rounded(Color.WHITE, 16, LINE)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        parent.addView(c, lp(14))
        return c
    }

    private fun field(parent: LinearLayout, label: String, help: String, key: String) {
        parent.addView(tv(label, 14f, INK, true), lp(16))
        parent.addView(tv(help, 12f, MUT), lp(2))
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            val v = prefs.getFloat(key, 0f)
            setText(if (v == 0f) "" else if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString())
            textSize = 18f
            setTextColor(INK)
            setHintTextColor(MUT)
            background = rounded(Color.parseColor("#F9FAFB"), 10, LINE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setSingleLine()
        }
        e.hint = "0"
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s.toString().replace(',', '.').toFloatOrNull() ?: 0f
                prefs.edit().putFloat(key, v).apply()
                refreshPreview()
            }
        })
        parent.addView(e, lp(8))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        window.statusBarColor = BG
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

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
            btn("Abrir ajustes de la app", Color.parseColor("#F3F4F6"), INK) {
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

        // Vista previa
        val pv = card(page)
        pv.addView(tv("Así lo vas a ver", 18f, INK, true))
        pv.addView(tv("Ejemplo con un pedido de ${money(SAMPLE_PAY)} y ${SAMPLE_KM.toInt()} km.", 13f, MUT), lp(2))
        pvBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        pvVerdict = tv("", 20f, Color.WHITE, true)
        pvDetail = tv("", 14f, Color.WHITE)
        pvBox.addView(pvVerdict)
        pvBox.addView(pvDetail, lp(4))
        pv.addView(pvBox, lp(12))

        // Ayuda
        val hp = card(page)
        hp.addView(tv("Cómo usarla", 18f, INK, true))
        hp.addView(
            tv("1. Cargá tus números.\n2. Activá el servicio.\n3. Abrí la app de pedidos: cuando te llegue un pedido vas a ver el cartel arriba, verde si conviene y rojo si no.", 14f, MUT),
            lp(6)
        )
        hp.addView(tv("Si el cartel deja de aparecer, poné esta app en «Sin restricciones» en los ajustes de batería.", 13f, MUT), lp(10))
        hp.addView(
            btn("Ajustes de batería", Color.parseColor("#F3F4F6"), INK) {
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
        val r = evaluate(
            SAMPLE_PAY, SAMPLE_KM,
            prefs.getFloat("cost", 0f), prefs.getFloat("minNet", 0f), prefs.getFloat("minKm", 0f)
        )
        pvBox.background = rounded(if (r.ok) GREEN else RED, 12)
        pvVerdict.text = if (r.ok) "✅ RENTABLE" else "❌ NO CONVIENE"
        pvDetail.text = "Pago ${money(SAMPLE_PAY)} · ${SAMPLE_KM.toInt()} km\nNeto ${money(r.net)} · ${money(r.perKm)} por km"
    }
}

class OverlayService : AccessibilityService() {

    // Solo lee apps cuyo paquete contenga esto.
    private val targetPkg = "pedidosya"

    private var wm: WindowManager? = null
    private var box: LinearLayout? = null
    private var tVerdict: TextView? = null
    private var tPay: TextView? = null
    private var tNet: TextView? = null
    private var last = 0L

    private val moneyRx = Regex("""\$\s?(\d[\d.,]*)""")
    private val kmRx = Regex("""(\d+(?:[.,]\d+)?)\s?km""", RegexOption.IGNORE_CASE)
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
        val p = pay ?: run { hide(); return }

        var kms = 0.0
        for (t in texts) kmRx.findAll(t).forEach { kms += num(it.groupValues[1]) ?: 0.0 }

        val cfg = getSharedPreferences("cfg", MODE_PRIVATE)
        val r = evaluate(p, kms, cfg.getFloat("cost", 0f), cfg.getFloat("minNet", 0f), cfg.getFloat("minKm", 0f))

        val l1 = "Pago ${money(p)} · " + (if (kms > 0) "%.1f km".format(kms) else "km no detectados")
        val l2 = "Neto ${money(r.net)}" + (if (kms > 0) " · ${money(r.perKm)} por km" else "")
        show(if (r.ok) "✅ RENTABLE" else "❌ NO CONVIENE", l1, l2, r.ok)
    }

    private fun show(verdict: String, l1: String, l2: String, ok: Boolean) {
        if (box == null) {
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(12))
                background = GradientDrawable().apply { cornerRadius = dp(16).toFloat() }
            }
            val v = TextView(this).apply { setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD }
            val a = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f }
            val c = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f }
            b.addView(v); b.addView(a); b.addView(c)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(56) }
            try { wm?.addView(b, lp) } catch (_: Exception) { return }
            box = b; tVerdict = v; tPay = a; tNet = c
        }
        val color = if (ok) Color.argb(240, 21, 128, 61) else Color.argb(240, 185, 28, 28)
        (box?.background as? GradientDrawable)?.setColor(color)
        tVerdict?.text = verdict
        tPay?.text = l1
        tNet?.text = l2
    }

    private fun hide() {
        box?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        box = null; tVerdict = null; tPay = null; tNet = null
    }

    override fun onInterrupt() { hide() }
    override fun onDestroy() { hide(); super.onDestroy() }
}
