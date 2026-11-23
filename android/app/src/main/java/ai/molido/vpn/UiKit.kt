package ai.molido.vpn

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/** Single dark palette for the whole app. */
object Palette {
    const val BG = 0xFF0B1020.toInt()
    const val SURFACE = 0xFF141A2E.toInt()
    const val SURFACE_2 = 0xFF1C2440.toInt()
    const val STROKE = 0x1FFFFFFF
    const val TEXT = 0xFFF1F5F9.toInt()
    const val MUTED = 0xFF94A3B8.toInt()
    const val ACCENT = 0xFF6366F1.toInt()
    const val ACCENT_2 = 0xFF22D3EE.toInt()
    const val SUCCESS = 0xFF10B981.toInt()
    const val WARN = 0xFFF59E0B.toInt()
    const val DANGER = 0xFFEF4444.toInt()
}

/** Small view factory so every screen uses the same spacing, radii and colours. */
class UiKit(private val context: Context) {
    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun rounded(fill: Int, radius: Int, stroke: Int = Palette.STROKE) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), stroke)
    }

    fun text(value: String, size: Float, color: Int = Palette.TEXT, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun row() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun card() = column().apply {
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(Palette.SURFACE, 20)
    }

    fun sectionLabel(value: String) = text(value, 13f, Palette.MUTED, bold = true).apply {
        setPadding(dp(4), dp(22), dp(4), dp(8))
    }

    fun pill(value: String, color: Int) = text(value, 12f, color, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = rounded(Palette.SURFACE_2, 30, color)
    }

    private fun button(value: String, fill: GradientDrawable, color: Int) = Button(context).apply {
        text = value
        textSize = 14f
        isAllCaps = false
        setTextColor(color)
        background = fill
        stateListAnimator = null
        elevation = 0f
        minHeight = 0
        minimumHeight = 0
    }

    fun primaryButton(value: String) = button(
        value,
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Palette.ACCENT, Palette.ACCENT_2))
            .apply { cornerRadius = dp(18).toFloat() },
        Palette.TEXT
    ).apply { textSize = 16f; setTypeface(typeface, Typeface.BOLD) }

    fun dangerButton(value: String) = button(value, rounded(0x33EF4444, 18, Palette.DANGER), Palette.TEXT)
        .apply { textSize = 16f; setTypeface(typeface, Typeface.BOLD) }

    fun secondaryButton(value: String, color: Int = Palette.TEXT) = button(value, rounded(Palette.SURFACE_2, 14), color)

    /** A tappable settings line: title on the start side, chevron on the end side. */
    fun actionRow(title: String, color: Int = Palette.TEXT, onClick: () -> Unit) = row().apply {
        setPadding(dp(4), dp(14), dp(4), dp(14))
        addView(text(title, 14f, color), LinearLayout.LayoutParams(0, -2, 1f))
        addView(text("‹", 18f, Palette.MUTED))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun divider() = View(context).apply { setBackgroundColor(Palette.STROKE) }

    fun dropdown(items: List<String>, selected: Int, onSelect: (Int) -> Unit): Spinner {
        val adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, items.toMutableList()) {
            private fun style(v: View, dropDown: Boolean): View = v.apply {
                (this as? TextView)?.apply {
                    setTextColor(Palette.TEXT)
                    textSize = 14f
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    textDirection = View.TEXT_DIRECTION_RTL
                }
                if (dropDown) setBackgroundColor(Palette.SURFACE_2)
            }
            override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
                style(super.getView(position, convertView, parent), false)
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup) =
                style(super.getDropDownView(position, convertView, parent), true)
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        return Spinner(context, Spinner.MODE_DROPDOWN).apply {
            this.adapter = adapter
            background = rounded(Palette.SURFACE_2, 14)
            setPopupBackgroundDrawable(rounded(Palette.SURFACE_2, 14))
            val start = selected.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            setSelection(start, false)
            var current = start
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    if (position != current) { current = position; onSelect(position) }
                }
                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
        }
    }
}
