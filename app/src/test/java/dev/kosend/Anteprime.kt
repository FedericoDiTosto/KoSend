package dev.kosend

import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test

class Anteprime {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, theme = "AppTheme")

    @Test
    fun italiano_in_attesa() = disegna(lingua = "it", stato = CalibreServer.State.Waiting(9090), libri = libriDiProva())

    @Test
    fun italiano_collegato_scuro() = disegna(
        lingua = "it", scuro = true,
        stato = CalibreServer.State.Connected("KOReader (Kindle)"), libri = libriDiProva(), infoAperto = false,
    )

    @Test
    fun italiano_spento_vuoto() = disegna(lingua = "it", stato = CalibreServer.State.Stopped, libri = emptyList())

    @Test
    fun inglese_in_attesa() = disegna(lingua = "en", stato = CalibreServer.State.Waiting(9090), libri = libriDiProva())

    @Test
    fun impostazioni() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_5.copy(locale = "it"))
        val view = paparazzi.inflate<android.view.View>(R.layout.activity_settings)
        view.findViewById<android.widget.TextView>(R.id.language_value).setText(R.string.language_system)
        view.findViewById<android.widget.TextView>(R.id.version_value).text = "1.0"
        paparazzi.snapshot(view)
    }

    @Test
    fun icona() {
        val ctx = paparazzi.context
        val riga = LinearLayout(ctx).apply {
            setBackgroundColor(0xFFE9E5DD.toInt())
            setPadding(48, 48, 48, 48)
        }
        val colori = android.graphics.drawable.LayerDrawable(arrayOf(
            ctx.getDrawable(R.drawable.ic_launcher_background)!!,
            ctx.getDrawable(R.drawable.ic_launcher_foreground)!!,
        ))
        val mono = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.ColorDrawable(0xFF2B3A35.toInt()),
            ctx.getDrawable(R.drawable.ic_launcher_monochrome)!!,
        ))
        listOf(colori, mono).forEach { icona ->
            val cornice = FrameLayout(ctx).apply {
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: android.view.View, o: android.graphics.Outline) = o.setOval(0, 0, v.width, v.height)
                }
            }
            val img = android.widget.ImageView(ctx).apply {
                setImageDrawable(icona)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                scaleX = 1.5f
                scaleY = 1.5f
            }
            cornice.addView(img, FrameLayout.LayoutParams(288, 288))
            riga.addView(cornice, LinearLayout.LayoutParams(288, 288).apply { setMargins(24, 0, 24, 0) })
        }
        paparazzi.snapshot(riga)
    }

    private fun libriDiProva() = listOf(
        Book("1", "Il nome della rosa.epub", "", 1_240_000, "Il nome della rosa", listOf("Umberto Eco"), BookStatus.SENDING, progress = 62),
        Book("2", "Manuale.pdf", "", 8_400_000, "Manuale di fotografia digitale", emptyList(), BookStatus.PENDING),
        Book("3", "Dune.mobi", "", 2_100_000, "Dune", listOf("Frank Herbert"), BookStatus.SENT),
        Book("4", "kindle.azw3", "", 900_000, "Libro in AZW3", emptyList(), BookStatus.ERROR, Messages.unsupported("azw3")),
    )

    private fun disegna(
        lingua: String,
        stato: CalibreServer.State,
        libri: List<Book>,
        scuro: Boolean = false,
        infoAperto: Boolean = true,
    ) {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = DeviceConfig.PIXEL_5.copy(
                locale = lingua,
                nightMode = if (scuro) NightMode.NIGHT else NightMode.NOTNIGHT,
            )
        )
        val ctx = paparazzi.context
        val adapter = QueueAdapter(ctx, infoAperto, {}, {}, {}, {}, {}, {})
        adapter.update(libri, stato, "192.168.1.42")

        val colonna = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ctx.getColor(R.color.background))
        }
        val parent = FrameLayout(ctx)
        for (i in 0 until adapter.itemCount) {
            val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(i))
            adapter.onBindViewHolder(holder, i)
            val lp = holder.itemView.layoutParams as? ViewGroup.MarginLayoutParams
            colonna.addView(holder.itemView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                lp?.let { setMargins(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin) }
            })
        }
        paparazzi.snapshot(colonna)
    }
}
