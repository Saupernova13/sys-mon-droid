package com.raavivi.sysmon.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.raavivi.sysmon.core.model.FsEntry
import com.raavivi.sysmon.ui.theme.SysMon

/** Palette colour for a file class. */
@Composable
fun kindColor(kind: FileKind): Color {
    val p = SysMon.palette
    return when (kind) {
        FileKind.Dir -> p.fsDir
        FileKind.Media -> p.fsMedia
        FileKind.Archive -> p.fsArchive
        FileKind.Exec -> p.fsExec
        FileKind.Code -> p.fsCode
        FileKind.Doc -> p.fsFile
    }
}

private fun kindIcon(name: String, kind: FileKind): ImageVector = when (kind) {
    FileKind.Dir -> Icons.Filled.Folder
    FileKind.Media -> when (mediaType(name)) {
        MediaType.Video -> Icons.Filled.Movie
        MediaType.Audio -> Icons.Filled.AudioFile
        else -> Icons.Filled.Image
    }
    FileKind.Archive -> Icons.Filled.FolderZip
    FileKind.Exec -> Icons.Filled.Terminal
    FileKind.Code -> Icons.Filled.Code
    FileKind.Doc -> Icons.AutoMirrored.Filled.InsertDriveFile
}

/** Width of the leading slot of a file row, so names line up across kinds. */
private val LEADING_WIDTH = 48.dp
private val THUMB_SIZE = 40.dp

/**
 * Kind glyph tinted with the palette. Instrument sets a folder on a small tinted
 * plate; archives carry their format as a label under the glyph.
 */
@Composable
fun KindIcon(name: String, isDir: Boolean, modifier: Modifier = Modifier) {
    val palette = SysMon.palette
    val kind = fileKind(name, isDir)
    val tint = kindColor(kind)
    Column(modifier.width(LEADING_WIDTH), horizontalAlignment = Alignment.CenterHorizontally) {
        if (kind == FileKind.Dir && palette.isInstrument) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(kindIcon(name, kind), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
        } else {
            Icon(kindIcon(name, kind), contentDescription = null, tint = tint, modifier = Modifier.size(32.dp))
        }
        if (kind == FileKind.Archive) {
            archiveBadge(name)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
            }
        }
    }
}

/**
 * Leading visual of an entry row: a shell thumbnail where the host can make one,
 * else the kind icon. Loading, a 404 (no thumbnail) and any error all fall back to
 * the icon silently. `modified` and `size` are in the cache key so an edit refreshes.
 */
@Composable
fun EntryLeading(entry: FsEntry, vm: FilesViewModel, modifier: Modifier = Modifier) {
    if (!mayHaveThumbnail(entry.name, entry.isDir)) {
        KindIcon(entry.name, entry.isDir, modifier)
        return
    }
    val key = "thumb:${entry.path}:${entry.modified}:${entry.size}"
    val request = ImageRequest.Builder(LocalContext.current)
        .data(vm.thumbnailUrl(entry.path))
        .memoryCacheKey(key)
        .build()
    val radius = if (SysMon.palette.isInstrument) 2.dp else 6.dp
    Box(modifier.width(LEADING_WIDTH), contentAlignment = Alignment.Center) {
        SubcomposeAsyncImage(
            model = request,
            imageLoader = vm.imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(THUMB_SIZE)
                .clip(RoundedCornerShape(radius)),
        ) {
            if (painter.state is AsyncImagePainter.State.Success) {
                SubcomposeAsyncImageContent()
            } else {
                KindIcon(entry.name, entry.isDir)
            }
        }
    }
}
