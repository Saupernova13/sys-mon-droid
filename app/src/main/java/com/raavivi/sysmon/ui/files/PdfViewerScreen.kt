package com.raavivi.sysmon.ui.files

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.ui.common.ErrorBox
import com.raavivi.sysmon.ui.common.LoadingBox
import com.raavivi.sysmon.ui.common.ScreenHeader
import com.raavivi.sysmon.ui.common.rememberContainerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

private const val RENDER_WIDTH_PX = 1240
private const val MAX_PAGES = 60

class PdfViewerViewModel(
    private val container: AppContainer,
    private val path: String,
) : ViewModel() {

    var pages by mutableStateOf<List<ImageBitmap>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var truncated by mutableStateOf(false)
        private set

    init { load() }

    fun load() {
        loading = true
        error = null
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { render() } }
            result.onSuccess { pages = it }
                .onFailure { error = it.message ?: "Failed to open PDF" }
            loading = false
        }
    }

    private fun render(): List<ImageBitmap> {
        val cacheFile = File(container.appContext.cacheDir, "pdf_preview_${path.hashCode()}.pdf")
        val request = Request.Builder().url(container.api.fileUrl(path)).build()
        container.api.okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} fetching file")
            val body = resp.body ?: throw IllegalStateException("empty response")
            cacheFile.outputStream().use { out -> body.byteStream().copyTo(out) }
        }

        val out = ArrayList<ImageBitmap>()
        ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val count = renderer.pageCount
                truncated = count > MAX_PAGES
                for (i in 0 until minOf(count, MAX_PAGES)) {
                    renderer.openPage(i).use { page ->
                        val scale = RENDER_WIDTH_PX.toFloat() / page.width
                        val w = RENDER_WIDTH_PX
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        out.add(bmp.asImageBitmap())
                    }
                }
            }
        }
        cacheFile.delete()
        return out
    }
}

@Composable
fun PdfViewerScreen(path: String, onBack: () -> Unit) {
    val vm = rememberContainerViewModel { PdfViewerViewModel(it, path) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = path.substringAfterLast('\\').substringAfterLast('/'),
            actions = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        Box(Modifier.fillMaxSize()) {
            when {
                vm.loading -> LoadingBox()
                vm.error != null -> ErrorBox(vm.error!!, onRetry = vm::load)
                vm.pages.isEmpty() -> Text(
                    "No pages",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                else -> LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    itemsIndexed(vm.pages) { _, bmp ->
                        Image(
                            bitmap = bmp,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            contentScale = ContentScale.FillWidth,
                        )
                    }
                    if (vm.truncated) {
                        item {
                            Text(
                                "Preview limited to first $MAX_PAGES pages",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
