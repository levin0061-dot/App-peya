package com.repartidor.rentable

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

fun scaleOf(i: Int): Float = when (i) {
    0 -> 0.85f
    2 -> 1.25f
    else -> 1f
}

object Snd {
    private var cur: Ringtone? = null

    // "" = silencio, "default" = sonido de notificación del celular, otro = uri elegida
    fun get(p: SharedPreferences, key: String): String =
        p.getString(key, if (key == "sndOk") "default" else "") ?: ""

    fun uriOf(s: String): Uri? = when (s) {
        "" -> null
        "default" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        else -> Uri.parse(s)
    }

    fun name(ctx: Context, s: String): String {
        if (s.isEmpty()) return "Silencio"
        if (s == "default") return "Sonido predeterminado"
        return try {
            RingtoneManager.getRingtone(ctx, Uri.parse(s))?.getTitle(ctx) ?: "Personalizado"
        } catch (e: Exception) { "Personalizado" }
    }

    fun play(ctx: Context, key: String) {
        val s = get(ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE), key)
        val uri = uriOf(s) ?: return
        try {
            cur?.stop()
            val r = RingtoneManager.getRingtone(ctx, uri) ?: return
            r.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            r.play()
            cur = r
        } catch (e: Exception) {}
    }

    fun stop() {
        try { cur?.stop() } catch (e: Exception) {}
    }
}

class SettingsActivity : Activity() {

    private val night: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val BG by lazy { Color.parseColor(if (night) "#0B0F14" else "#F4F5F7") }
    private val CARD by lazy { Color.parseColor(if (night) "#151B23" else "#FFFFFF") }
    private val INK by lazy { Color.parseColor(if (night) "#F3F4F6" else "#111827") }
    private val MUT by lazy { Color.parseColor(if (night) "#9CA3AF" else "#6B7280") }
    private val LINE by lazy { Color.parseColor(if (night) "#263040" else "#E5E7EB") }
    private val SOFT by lazy { Color.parseColor(if (night) "#222B38" else "#F3F4F6") }
    private val ACC = Color.parseColor("#2563EB")

    private lateinit var p: SharedPreferences
    private lateinit var pvBox: LinearLayout
    private lateinit var pvV: TextView
    private lateinit var pvP: TextView
    private lateinit var pvSave: TextView
    private lateinit var pvRow: LinearLayout
    private val pvChips = ArrayList<TextView>()
    private val names = HashMap<String, TextView>()

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
        textSize = 15f
        setTextColor(fg)
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(bg, 12)
        stateListAnimator = null
        setPadding(dp(12), dp(12), dp(12), dp(12))
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

    private fun segmented(parent: LinearLayout, title: String, opts: List<String>, key: String, def: Int) {
        parent.addView(tv(title, 14f, INK, true), lp(16))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btns = ArrayList<TextView>()
        fun paint() {
            val sel = p.getInt(key, def)
            btns.forEachIndexed { i, b ->
                b.background = rounded(if (i == sel) ACC else SOFT, 10)
                b.setTextColor(if (i == sel) Color.WHITE else INK)
            }
        }
        opts.forEachIndexed { i, o ->
            val b = tv(o, 14f, INK, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setOnClickListener {
                    p.edit().putInt(key, i).apply()
                    paint()
                    refreshPreview()
                }
            }
            btns.add(b)
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(6)
            })
        }
        paint()
        parent.addView(row, lp(8))
    }

    private fun toggle(parent: LinearLayout, label: String, key: String, def: Boolean) {
        val s = Switch(this).apply {
            text = label
            textSize = 15f
            setTextColor(INK)
            isChecked = p.getBoolean(key, def)
            setOnCheckedChangeListener { _, c ->
                p.edit().putBoolean(key, c).apply()
                refreshPreview()
            }
        }
        parent.addView(s, lp(14))
    }

    private fun chip(label: String) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(8), dp(6), dp(8))
        background = rounded(Color.parseColor("#1F2937"), 12)
    }

    private fun soundRow(parent: LinearLayout, label: String, key: String, code: Int) {
        parent.addView(tv(label, 14f, INK, true), lp(18))
        val nm = tv(Snd.name(this, Snd.get(p, key)), 13f, MUT)
        names[key] = nm
        parent.addView(nm, lp(2))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            btn("Elegir sonido", SOFT, INK) { pick(key, code) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
        )
        row.addView(
            btn("▶ Probar", SOFT, INK) {
                if (Snd.get(p, key).isEmpty())
                    Toast.makeText(this, "Está en silencio: elegí un sonido", Toast.LENGTH_SHORT).show()
                else Snd.play(this, key)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        parent.addView(row, lp(8))
    }

    private fun pick(key: String, code: Int) {
        val curUri = Snd.uriOf(Snd.get(p, key))
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Elegí un sonido")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            )
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, curUri)
        }
        startActivityForResult(i, code)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data == null || (req != 1 && req != 2)) return
        val uri = data.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        val key = if (req == 1) "sndOk" else "sndNo"
        val def = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val s = when {
            uri == null -> ""
            uri == def -> "default"
            else -> uri.toString()
        }
        p.edit().putString(key, s).apply()
        names[key]?.text = Snd.name(this, s)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = getSharedPreferences("cfg", MODE_PRIVATE)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        if (!night) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(28))
        }
        page.addView(tv("Configuración", 26f, INK, true))
        page.addView(tv("Personalizá el cartel y el sonido. Se guarda solo.", 15f, MUT), lp(4))

        // Vista previa
        val pv = card(page)
        pv.addView(tv("Vista previa", 18f, INK, true))
        val wrap = LinearLayout(this).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Color.parseColor("#4B5563"), 12)
        }
        pvBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2111827"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(2), Color.parseColor("#22C55E"))
            }
        }
        pvV = tv("✅ RENTABLE", 18f, Color.parseColor("#22C55E"), true)
        pvP = tv("$2.500", 32f, Color.WHITE, true)
        pvRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Neto\n$2.100", "Por km\n$420", "Por hora\n$5.040").forEachIndexed { i, t ->
            val c = chip(t)
            pvChips.add(c)
            pvRow.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i < 2) marginEnd = dp(6)
            })
        }
        pvSave = TextView(this).apply {
            text = "＋ Lo acepté (guardar)"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(ACC, 12)
        }
        pvBox.addView(pvV)
        pvBox.addView(pvP, lp(2))
        pvBox.addView(pvRow, lp(10))
        pvBox.addView(pvSave, lp(12))
        wrap.addView(pvBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pv.addView(wrap, lp(12))

        // Opciones del cartel
        val oc = card(page)
        oc.addView(tv("Cartel del pedido", 18f, INK, true))
        segmented(oc, "Posición", listOf("Arriba", "Centro", "Abajo"), "ovPos", 0)
        oc.addView(tv("«Abajo» puede tapar el botón de aceptar pedido.", 12f, MUT), lp(6))
        segmented(oc, "Tamaño", listOf("Chico", "Mediano", "Grande"), "ovSize", 1)
        oc.addView(tv("Opacidad", 14f, INK, true), lp(16))
        oc.addView(SeekBar(this).apply {
            max = 60
            progress = p.getInt("ovAlpha", 95).coerceIn(40, 100) - 40
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                    p.edit().putInt("ovAlpha", v + 40).apply()
                    refreshPreview()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }, lp(8))
        toggle(oc, "Mostrar neto, por km y por hora", "ovChips", true)
        toggle(oc, "Mostrar botón «＋ Lo acepté»", "ovSave", true)
        oc.addView(
            btn("Restablecer el cartel", SOFT, INK) {
                p.edit().remove("ovPos").remove("ovSize").remove("ovAlpha")
                    .remove("ovChips").remove("ovSave").apply()
                recreate()
            }, lp(18)
        )

        // Sonido
        val sc = card(page)
        sc.addView(tv("Sonido", 18f, INK, true))
        sc.addView(tv("Suena una vez cuando aparece un pedido. Usa el volumen de notificaciones del celular.", 13f, MUT), lp(2))
        toggle(sc, "Sonar cuando aparece un pedido", "sndOn", true)
        soundRow(sc, "Pedido rentable ✅", "sndOk", 1)
        soundRow(sc, "Pedido que no conviene ❌", "sndNo", 2)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(page)
        })
        refreshPreview()
    }

    private fun refreshPreview() {
        val k = scaleOf(p.getInt("ovSize", 1))
        pvV.textSize = 18f * k
        pvP.textSize = 32f * k
        pvChips.forEach { it.textSize = 13f * k }
        pvSave.textSize = 15f * k
        pvRow.visibility = if (p.getBoolean("ovChips", true)) View.VISIBLE else View.GONE
        pvSave.visibility = if (p.getBoolean("ovSave", true)) View.VISIBLE else View.GONE
        pvBox.alpha = p.getInt("ovAlpha", 95).coerceIn(40, 100) / 100f
    }
}
