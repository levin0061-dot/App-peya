package com.repartidor.rentable

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        root.addView(TextView(this).apply { text = "🛵 Repartidor Rentable"; textSize = 22f })

        fun field(label: String, key: String): EditText {
            root.addView(TextView(this).apply { text = label; setPadding(0, 40, 0, 0) })
            val e = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(p.getFloat(key, 0f).toString())
            }
            root.addView(e)
            return e
        }

        val cost = field("Costo por km (nafta + desgaste)", "cost")
        val minNet = field("Ganancia neta mínima por pedido", "minNet")
        val minKm = field("Ganancia neta mínima por km", "minKm")

        root.addView(Button(this).apply {
            text = "Guardar"
            setOnClickListener {
                fun v(e: EditText) = e.text.toString().replace(',', '.').toFloatOrNull() ?: 0f
                p.edit()
                    .putFloat("cost", v(cost))
                    .putFloat("minNet", v(minNet))
                    .putFloat("minKm", v(minKm))
                    .apply()
                Toast.makeText(this@MainActivity, "Guardado", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = "Activar el servicio (Accesibilidad)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }
}

class OverlayService : AccessibilityService() {

    // Solo lee apps cuyo paquete contenga esto.
    private val targetPkg = "pedidosya"

    private var wm: WindowManager? = null
    private var view: TextView? = null
    private var last = 0L

    private val money = Regex("""\$\s?(\d[\d.,]*)""")
    private val km = Regex("""(\d+(?:[.,]\d+)?)\s?km""", RegexOption.IGNORE_CASE)
    private val payHint = Regex("ganancia|pago|cobr|recibir|total", RegexOption.IGNORE_CASE)

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
            money.find(t)?.let { pay = num(it.groupValues[1]) }
            if (pay != null) break
        }
        if (pay == null) for (t in texts) {
            money.find(t)?.let { pay = num(it.groupValues[1]) }
            if (pay != null) break
        }
        val p = pay ?: run { hide(); return }

        var kms = 0.0
        for (t in texts) km.findAll(t).forEach { kms += num(it.groupValues[1]) ?: 0.0 }

        val cfg = getSharedPreferences("cfg", MODE_PRIVATE)
        val cost = cfg.getFloat("cost", 0f)
        val minNet = cfg.getFloat("minNet", 0f)
        val minKm = cfg.getFloat("minKm", 0f)

        val net = p - kms * cost
        val perKm = if (kms > 0) net / kms else 0.0
        val ok = net >= minNet && (kms == 0.0 || perKm >= minKm)

        val line2 = if (kms > 0)
            "Neto ${fmt(net)} · ${fmt(perKm)}/km"
        else "Sin km detectados · Neto ${fmt(net)}"
        show("Pago ${fmt(p)} · ${"%.1f".format(kms)} km\n$line2\n" +
                if (ok) "✅ RENTABLE" else "❌ NO CONVIENE", ok)
    }

    private fun fmt(x: Double) = "$" + "%,.0f".format(x)

    private fun show(text: String, ok: Boolean) {
        val color = if (ok) Color.argb(230, 21, 128, 61) else Color.argb(230, 185, 28, 28)
        if (view == null) {
            view = TextView(this).apply {
                setTextColor(Color.WHITE); textSize = 16f; setPadding(32, 20, 32, 20)
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 140 }
            wm?.addView(view, lp)
        }
        view?.text = text
        view?.setBackgroundColor(color)
    }

    private fun hide() {
        view?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        view = null
    }

    override fun onInterrupt() { hide() }
    override fun onDestroy() { hide(); super.onDestroy() }
}
