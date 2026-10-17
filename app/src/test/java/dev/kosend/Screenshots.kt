package dev.kosend

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test

class Screenshots {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "AppTheme",
        useDeviceResolution = true,
    )

    @Test
    fun step1_empty() = screen(CalibreServer.State.Stopped, emptyList(), infoExpanded = false)

    @Test
    fun step2_queue() = screen(
        CalibreServer.State.Stopped,
        listOf(
            book("1", "Project Hail Mary", "Andy Weir", "epub", 1_830_000),
            book("2", "The Design of Everyday Things", "Don Norman", "pdf", 7_400_000),
            book("3", "Dune", "Frank Herbert", "mobi", 2_100_000),
        ),
        infoExpanded = false,
    )

    @Test
    fun step3_waiting() = screen(
        CalibreServer.State.Waiting(9090),
        listOf(
            book("1", "Project Hail Mary", "Andy Weir", "epub", 1_830_000),
            book("2", "The Design of Everyday Things", "Don Norman", "pdf", 7_400_000),
        ),
    )

    @Test
    fun step4_sending() = screen(
        CalibreServer.State.Connected("KOReader (Kindle)"),
        listOf(
            book("1", "Project Hail Mary", "Andy Weir", "epub", 1_830_000, BookStatus.SENT),
            book("2", "The Design of Everyday Things", "Don Norman", "pdf", 7_400_000, BookStatus.SENDING, progress = 64),
            book("3", "Dune", "Frank Herbert", "mobi", 2_100_000, BookStatus.PENDING),
            book("4", "The Left Hand of Darkness", "Ursula K. Le Guin", "epub", 950_000, BookStatus.PENDING),
        ),
        infoExpanded = false,
    )

    @Test
    fun step5_dark() = screen(
        CalibreServer.State.Connected("KOReader (Kindle)"),
        listOf(
            book("1", "Project Hail Mary", "Andy Weir", "epub", 1_830_000, BookStatus.SENT),
            book("2", "The Design of Everyday Things", "Don Norman", "pdf", 7_400_000, BookStatus.SENT),
            book("3", "Dune", "Frank Herbert", "mobi", 2_100_000, BookStatus.SENDING, progress = 38),
            book("4", "Piranesi", "Susanna Clarke", "epub", 1_240_000, BookStatus.PENDING),
        ),
        infoExpanded = false,
        dark = true,
    )

    @Test
    fun step6_settings() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_6.copy(locale = "en"))
        val view = paparazzi.inflate<View>(R.layout.activity_settings)
        view.findViewById<android.widget.TextView>(R.id.language_value).setText(R.string.language_system)
        view.findViewById<android.widget.TextView>(R.id.version_value).text = "1.0"
        paparazzi.snapshot(view)
    }

    private fun book(
        id: String,
        title: String,
        author: String,
        ext: String,
        size: Long,
        status: BookStatus = BookStatus.PENDING,
        progress: Int = 0,
        message: String = "",
    ) = Book(id, "$title.$ext", "", size, title, listOfNotNull(author.ifEmpty { null }), status, message, progress)

    private fun screen(
        state: CalibreServer.State,
        books: List<Book>,
        infoExpanded: Boolean = true,
        dark: Boolean = false,
    ) {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = DeviceConfig.PIXEL_6.copy(
                locale = "en",
                nightMode = if (dark) NightMode.NIGHT else NightMode.NOTNIGHT,
            )
        )
        val root = paparazzi.inflate<View>(R.layout.activity_main)
        val list = root.findViewById<RecyclerView>(R.id.list)
        val adapter = QueueAdapter(paparazzi.context, infoExpanded, {}, {}, {}, {}, {}, {})
        adapter.update(books, state, "192.168.1.42")
        list.layoutManager = LinearLayoutManager(paparazzi.context)
        list.adapter = adapter
        paparazzi.snapshot(root)
    }
}
