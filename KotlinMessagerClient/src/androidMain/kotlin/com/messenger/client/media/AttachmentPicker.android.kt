package com.messenger.client.media

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size as AndroidSize
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.io.ByteArrayOutputStream

private enum class PickerTab { Media, Files }
private enum class MediaFilter { All, Photos, Videos }
private enum class FileFilter { All, Documents, Audio, Archives }

private data class MediaItem(
    val uri: android.net.Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
    val dateAdded: Long,
    val isVideo: Boolean
)

private data class FileItem(
    val uri: android.net.Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
    val dateAdded: Long
)

private data class MediaSection(
    val title: String,
    val items: List<MediaItem>
)

private data class FileSection(
    val title: String,
    val items: List<FileItem>
)

private data class EditedMedia(
    val bytes: ByteArray,
    val mimeType: String,
    val name: String
)

private sealed class EditTool {
    data object Pen : EditTool()
    data object Line : EditTool()
    data object Rect : EditTool()
    data object Circle : EditTool()
    data object Triangle : EditTool()
    data object Crop : EditTool()
}

private sealed class Shape {
    data class PathShape(val points: List<Offset>, val color: Color, val stroke: Float) : Shape()
    data class LineShape(val start: Offset, val end: Offset, val color: Color, val stroke: Float) : Shape()
    data class RectShape(val start: Offset, val end: Offset, val color: Color, val stroke: Float) : Shape()
    data class CircleShape(val start: Offset, val end: Offset, val color: Color, val stroke: Float) : Shape()
    data class TriangleShape(val start: Offset, val end: Offset, val color: Color, val stroke: Float) : Shape()
}

private data class EditorSnapshot(
    val bitmap: Bitmap,
    val cropRect: Rect,
    val shapes: List<Shape>
)

private data class PointerMapping(
    val displayRect: Rect,
    val drawOffsetX: Float,
    val drawOffsetY: Float,
    val scale: Float,
    val imageW: Float,
    val imageH: Float
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun AttachmentPicker(
    show: Boolean,
    onDismiss: () -> Unit,
    onPicked: (List<PickedFile>) -> Unit,
    onError: (String) -> Unit
) {
    if (!show) return

    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableStateOf(PickerTab.Media) }
    var selected by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var mediaItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var fileItems by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var mediaFilter by remember { mutableStateOf(MediaFilter.All) }
    var fileFilter by remember { mutableStateOf(FileFilter.All) }
    var editedMedia by remember { mutableStateOf<Map<android.net.Uri, EditedMedia>>(emptyMap()) }
    var editorTarget by remember { mutableStateOf<MediaItem?>(null) }

    val requiredPermissions = remember { buildPermissionList() }
    var permissionsGranted by remember { mutableStateOf(hasPermissions(context, requiredPermissions)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result.values.any { it }
    }

    val systemPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri -> readPickedFile(resolver, uri) }
                }
                if (files.isNotEmpty()) onPicked(files) else onError("Не удалось прочитать файлы")
            } catch (e: Exception) {
                onError(e.message ?: "Не удалось прочитать файлы")
            }
        }
    }

    LaunchedEffect(show) {
        selected = emptyList()
        editedMedia = emptyMap()
        mediaFilter = MediaFilter.All
        fileFilter = FileFilter.All
        if (!permissionsGranted) {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    LaunchedEffect(permissionsGranted, tab) {
        if (!permissionsGranted) return@LaunchedEffect
        if (tab == PickerTab.Media && mediaItems.isEmpty()) {
            mediaItems = loadMediaItems(resolver)
        }
        if (tab == PickerTab.Files && fileItems.isEmpty()) {
            fileItems = loadFileItems(resolver)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Выбор вложений",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                )
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabButton(
                    active = tab == PickerTab.Media,
                    label = "Медиа"
                ) { tab = PickerTab.Media }
                TabButton(
                    active = tab == PickerTab.Files,
                    label = "Файлы"
                ) { tab = PickerTab.Files }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (!permissionsGranted) {
                Text(
                    text = "Нужно разрешение на доступ к файлам и медиа",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { permissionLauncher.launch(requiredPermissions) }) {
                    Text("Разрешить доступ")
                }
                Spacer(modifier = Modifier.height(16.dp))
            } else if (tab == PickerTab.Media) {
                CategoryFilters(
                    selected = mediaFilter,
                    onSelected = { mediaFilter = it }
                )
                Spacer(modifier = Modifier.height(10.dp))
                MediaGridSections(
                    sections = buildMediaSections(mediaItems, mediaFilter),
                    selected = selected,
                    onToggle = { uri ->
                        selected = if (selected.contains(uri)) {
                            selected - uri
                        } else {
                            selected + uri
                        }
                    },
                    onEdit = { item -> editorTarget = item },
                    resolver = resolver
                )
            } else {
                CategoryFiltersFiles(
                    selected = fileFilter,
                    onSelected = { fileFilter = it }
                )
                Spacer(modifier = Modifier.height(10.dp))
                FileListSections(
                    sections = buildFileSections(fileItems, fileFilter),
                    selected = selected,
                    onToggle = { uri ->
                        selected = if (selected.contains(uri)) {
                            selected - uri
                        } else {
                            selected + uri
                        }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { systemPicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Открыть системный проводник")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            BottomActionBar(
                selectedCount = selected.size,
                onClear = { selected = emptyList() },
                onSend = {
                    scope.launch {
                        try {
                            val files = withContext(Dispatchers.IO) {
                                selected.mapNotNull { uri ->
                                    val edited = editedMedia[uri]
                                    if (edited != null) {
                                        PickedFile(edited.name, edited.bytes, edited.mimeType)
                                    } else {
                                        readPickedFile(resolver, uri)
                                    }
                                }
                            }
                            if (files.isNotEmpty()) {
                                onPicked(files)
                            } else {
                                onError("РќРµ СѓРґР°Р»РѕСЃСЊ РїСЂРѕС‡РёС‚Р°С‚СЊ С„Р°Р№Р»С‹")
                            }
                        } catch (e: Exception) {
                            onError(e.message ?: "РќРµ СѓРґР°Р»РѕСЃСЊ РїСЂРѕС‡РёС‚Р°С‚СЊ С„Р°Р№Р»С‹")
                        }
                    }
                }
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (false) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selected.isEmpty()) "Ничего не выбрано" else "Выбрано: ${selected.size}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val files = withContext(Dispatchers.IO) {
                                    selected.mapNotNull { uri -> readPickedFile(resolver, uri) }
                                }
                                if (files.isNotEmpty()) {
                                    onPicked(files)
                                } else {
                                    onError("Не удалось прочитать файлы")
                                }
                            } catch (e: Exception) {
                                onError(e.message ?: "Не удалось прочитать файлы")
                            }
                        }
                    },
                    enabled = selected.isNotEmpty()
                ) {
                    Text("Отправить")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    editorTarget?.let { target ->
        ImageEditorDialog(
            item = target,
            resolver = resolver,
            onDismiss = { editorTarget = null },
            onSave = { bytes ->
                editedMedia = editedMedia + (target.uri to EditedMedia(bytes, target.mimeType, target.name))
                if (!selected.contains(target.uri)) {
                    selected = selected + target.uri
                }
                editorTarget = null
            }
        )
    }
}

@Composable
private fun TabButton(active: Boolean, label: String, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
    ) {
        Text(label, color = fg)
    }
}

@Composable
private fun MediaGrid(
    items: List<MediaItem>,
    selected: Set<android.net.Uri>,
    onToggle: (android.net.Uri) -> Unit,
    resolver: ContentResolver
) {
    if (items.isEmpty()) {
        Text(
            text = "Медиа не найдено",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.height(360.dp)
    ) {
        items(items) { item ->
            val isSelected = selected.contains(item.uri)
            val thumb = rememberThumbnail(resolver, item.uri)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onToggle(item.uri) }
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (item.isVideo) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                }
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileList(
    items: List<FileItem>,
    selected: Set<android.net.Uri>,
    onToggle: (android.net.Uri) -> Unit
) {
    if (items.isEmpty()) {
        Text(
            text = "Файлы не найдены",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        return
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.height(320.dp)
    ) {
        items(items) { item ->
            val isSelected = selected.contains(item.uri)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                    .clickable { onToggle(item.uri) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Column {
                        Text(
                            text = item.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = formatFileSize(item.size),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
                if (isSelected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaGridSections(
    sections: List<MediaSection>,
    selected: List<android.net.Uri>,
    onToggle: (android.net.Uri) -> Unit,
    onEdit: (MediaItem) -> Unit,
    resolver: ContentResolver
) {
    if (sections.isEmpty()) {
        Text(
            text = "Медиа не найдено",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.heightIn(min = 260.dp, max = 420.dp)
    ) {
        sections.forEach { section ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = section.title,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )
            }
            items(section.items) { item ->
                val index = selected.indexOf(item.uri)
                val isSelected = index >= 0
                val thumb = rememberThumbnail(resolver, item.uri)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            if (item.isVideo) {
                                onToggle(item.uri)
                            } else {
                                onEdit(item)
                            }
                        }
                ) {
                    if (thumb != null) {
                        Image(
                            bitmap = thumb,
                            contentDescription = item.name,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (item.isVideo) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(6.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.padding(4.dp)
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { onEdit(item) },
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(4.dp)
                                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                        ) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(6.dp)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), CircleShape)
                                .clickable { onToggle(item.uri) }
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(4.dp)
                            )
                        }
                    }
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        ) {
                            Text(
                                text = "${index + 1}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileListSections(
    sections: List<FileSection>,
    selected: List<android.net.Uri>,
    onToggle: (android.net.Uri) -> Unit
) {
    if (sections.isEmpty()) {
        Text(
            text = "Файлы не найдены",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        return
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.height(320.dp)
    ) {
        sections.forEach { section ->
            item {
                Text(
                    text = section.title,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )
            }
            items(section.items) { item ->
                val index = selected.indexOf(item.uri)
                val isSelected = index >= 0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                        .clickable { onToggle(item.uri) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Column {
                            Text(
                                text = item.name,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = formatFileSize(item.size),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${index + 1}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryFilters(
    selected: MediaFilter,
    onSelected: (MediaFilter) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        FilterChip(
            active = selected == MediaFilter.All,
            label = "Все",
            icon = Icons.Filled.Collections
        ) { onSelected(MediaFilter.All) }
        FilterChip(
            active = selected == MediaFilter.Photos,
            label = "Фото",
            icon = Icons.Filled.Image
        ) { onSelected(MediaFilter.Photos) }
        FilterChip(
            active = selected == MediaFilter.Videos,
            label = "Видео",
            icon = Icons.Filled.Videocam
        ) { onSelected(MediaFilter.Videos) }
    }
}

@Composable
private fun CategoryFiltersFiles(
    selected: FileFilter,
    onSelected: (FileFilter) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        FilterChip(
            active = selected == FileFilter.All,
            label = "Все",
            icon = Icons.Filled.InsertDriveFile
        ) { onSelected(FileFilter.All) }
        FilterChip(
            active = selected == FileFilter.Documents,
            label = "Документы",
            icon = Icons.Filled.Folder
        ) { onSelected(FileFilter.Documents) }
        FilterChip(
            active = selected == FileFilter.Audio,
            label = "Аудио",
            icon = Icons.Filled.MusicNote
        ) { onSelected(FileFilter.Audio) }
        FilterChip(
            active = selected == FileFilter.Archives,
            label = "Архивы",
            icon = Icons.Filled.Archive
        ) { onSelected(FileFilter.Archives) }
    }
}

@Composable
private fun FilterChip(
    active: Boolean,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(16.dp)
        )
        Text(label, color = fg, fontSize = 12.sp)
    }
}

@Composable
private fun BottomActionBar(
    selectedCount: Int,
    onClear: () -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = if (selectedCount == 0) "Ничего не выбрано" else "Выбрано: $selectedCount",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            if (selectedCount > 0) {
                Text(
                    text = "Очистить",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onClear() }
                )
            }
        }
        Button(
            onClick = onSend,
            enabled = selectedCount > 0
        ) {
            Text("Отправить")
        }
    }
}

@Composable
private fun ImageEditorDialog(
    item: MediaItem,
    resolver: ContentResolver,
    onDismiss: () -> Unit,
    onSave: (ByteArray) -> Unit
) {
    val density = LocalDensity.current
    val imageBytes = produceState<ByteArray?>(null, item.uri) {
        value = withContext(Dispatchers.IO) {
            resolver.openInputStream(item.uri)?.use { it.readBytes() }
        }
    }.value

    var baseBitmap by remember(imageBytes) {
        mutableStateOf(imageBytes?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) })
    }

    if (baseBitmap == null || imageBytes == null) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text("Загрузка...", color = Color.White)
            }
        }
        return
    }

    val safeBitmap = baseBitmap!!

    var tool by remember { mutableStateOf<EditTool>(EditTool.Pen) }
    var isDrawingMode by remember { mutableStateOf(false) }
    var color by remember { mutableStateOf(Color.White) }
    var strokeWidth by remember { mutableStateOf(6f) }
    var showStrokeSlider by remember { mutableStateOf(false) }
    var shapes by remember { mutableStateOf<List<Shape>>(emptyList()) }
    var currentPath by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var currentStart by remember { mutableStateOf<Offset?>(null) }
    var currentEnd by remember { mutableStateOf<Offset?>(null) }
    var cropRect by remember(safeBitmap) {
        mutableStateOf(Rect(0f, 0f, safeBitmap.width.toFloat(), safeBitmap.height.toFloat()))
    }
    var zoomAnchorRect by remember(safeBitmap) {
        mutableStateOf(Rect(0f, 0f, safeBitmap.width.toFloat(), safeBitmap.height.toFloat()))
    }
    var activeCorner by remember { mutableStateOf<CropCorner?>(null) }
    var isCropDragging by remember { mutableStateOf(false) }
    var cropStartRect by remember { mutableStateOf<Rect?>(null) }
    var history by remember { mutableStateOf<List<EditorSnapshot>>(emptyList()) }

    fun pushHistory() {
        val snapshot = baseBitmap?.let { bitmap -> EditorSnapshot(bitmap, cropRect, shapes) } ?: return
        history = history + snapshot
    }

    val palette = listOf(
        Color.White,
        Color(0xFF00C853),
        Color(0xFFFFC107),
        Color(0xFFFF5252),
        Color(0xFF40C4FF),
        Color(0xFFFF6D00)
    )

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("Назад", color = Color.White) }
                Text(
                    text = "Редактор",
                    fontSize = 16.sp,
                    color = Color.White
                )
                IconButton(
                    onClick = {
                        if (history.isNotEmpty()) {
                            val last = history.last()
                            history = history.dropLast(1)
                            baseBitmap = last.bitmap
                            cropRect = last.cropRect
                            zoomAnchorRect = last.cropRect
                            shapes = last.shapes
                            currentPath = emptyList()
                            currentStart = null
                            currentEnd = null
                        }
                    },
                    enabled = history.isNotEmpty()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Undo,
                        contentDescription = "РћС‚РјРµРЅРёС‚СЊ",
                        tint = if (history.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.4f)
                    )
                }
                IconButton(
                    onClick = {
                        pushHistory()
                        baseBitmap?.let { bitmap ->
                            val rotated = rotateBitmap90(bitmap)
                            baseBitmap = rotated
                            cropRect = Rect(0f, 0f, rotated.width.toFloat(), rotated.height.toFloat())
                            zoomAnchorRect = cropRect
                            shapes = emptyList()
                            currentPath = emptyList()
                            currentStart = null
                            currentEnd = null
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.RotateRight,
                        contentDescription = "Р РѕС‚Р°С†РёСЏ",
                        tint = Color.White
                    )
                }
                TextButton(
                    onClick = {
                        val bitmap = baseBitmap ?: return@TextButton
                        val edited = renderEditedBitmap(bitmap, shapes, cropRect)
                        val format = if (item.mimeType.lowercase().contains("png")) {
                            Bitmap.CompressFormat.PNG
                        } else {
                            Bitmap.CompressFormat.JPEG
                        }
                        val output = ByteArrayOutputStream()
                        edited.compress(format, 92, output)
                        onSave(output.toByteArray())
                    }
                ) { Text("Готово", color = Color.White) }
            }

            Spacer(modifier = Modifier.height(8.dp))

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.DarkGray)
            ) {
                val containerW = constraints.maxWidth.toFloat()
                val containerH = constraints.maxHeight.toFloat()
                val bitmap = baseBitmap ?: return@BoxWithConstraints
                val imageW = bitmap.width.toFloat()
                val imageH = bitmap.height.toFloat()
                val fullRect = Rect(0f, 0f, imageW, imageH)
                val displayRect = if (tool is EditTool.Crop) fullRect else cropRect
                val displayW = displayRect.width
                val displayH = displayRect.height
                val zoomRect = if (tool is EditTool.Crop) zoomAnchorRect else displayRect
                val hasCrop = zoomRect.left > 0f ||
                    zoomRect.top > 0f ||
                    zoomRect.right < imageW ||
                    zoomRect.bottom < imageH
                val zoomPadding = if (tool is EditTool.Crop) {
                    if (hasCrop) 1.3f else 1.18f
                } else {
                    1.2f
                }
                val targetW = zoomRect.width.coerceAtLeast(1f) * zoomPadding
                val targetH = zoomRect.height.coerceAtLeast(1f) * zoomPadding
                val targetScale = minOf(containerW / targetW, containerH / targetH)
                val focusRect = zoomRect
                val focusX = focusRect.left + focusRect.width / 2f
                val focusY = focusRect.top + focusRect.height / 2f
                val targetOffsetX = containerW / 2f - (focusX - displayRect.left) * targetScale
                val targetOffsetY = containerH / 2f - (focusY - displayRect.top) * targetScale
                val animSpec = tween<Float>(durationMillis = 420, easing = FastOutSlowInEasing)
                val animatedScale by animateFloatAsState(targetValue = targetScale, animationSpec = animSpec, label = "cropScale")
                val animatedOffsetX by animateFloatAsState(targetValue = targetOffsetX, animationSpec = animSpec, label = "cropOffsetX")
                val animatedOffsetY by animateFloatAsState(targetValue = targetOffsetY, animationSpec = animSpec, label = "cropOffsetY")
                val scaleAnim = remember { Animatable(targetScale) }
                val offsetXAnim = remember { Animatable(targetOffsetX) }
                val offsetYAnim = remember { Animatable(targetOffsetY) }

                LaunchedEffect(targetScale, targetOffsetX, targetOffsetY, tool, isCropDragging) {
                    if (tool is EditTool.Crop) {
                        if (isCropDragging) {
                            scaleAnim.stop()
                            offsetXAnim.stop()
                            offsetYAnim.stop()
                        } else {
                            coroutineScope {
                                launch { scaleAnim.animateTo(targetScale, animSpec) }
                                launch { offsetXAnim.animateTo(targetOffsetX, animSpec) }
                                launch { offsetYAnim.animateTo(targetOffsetY, animSpec) }
                            }
                        }
                    } else {
                        scaleAnim.snapTo(targetScale)
                        offsetXAnim.snapTo(targetOffsetX)
                        offsetYAnim.snapTo(targetOffsetY)
                    }
                }

                val scale = if (tool is EditTool.Crop) scaleAnim.value else animatedScale
                val offsetX = if (tool is EditTool.Crop) offsetXAnim.value else animatedOffsetX
                val offsetY = if (tool is EditTool.Crop) offsetYAnim.value else animatedOffsetY
                val drawW = displayW * scale
                val drawH = displayH * scale
                val drawOffsetX = offsetX.roundToInt().toFloat()
                val drawOffsetY = offsetY.roundToInt().toFloat()
                val cornerRadius = with(density) { 32.dp.toPx() }
                val minCrop = 80f
                val pointerMappingState = rememberUpdatedState(
                    PointerMapping(
                        displayRect = displayRect,
                        drawOffsetX = drawOffsetX,
                        drawOffsetY = drawOffsetY,
                        scale = scale,
                        imageW = imageW,
                        imageH = imageH
                    )
                )

                fun mapToScreen(p: Offset): Offset =
                    Offset(
                        drawOffsetX + (p.x - displayRect.left) * scale,
                        drawOffsetY + (p.y - displayRect.top) * scale
                    )

                fun mapToImage(p: Offset): Offset? {
                    val mapping = pointerMappingState.value
                    val x = (p.x - mapping.drawOffsetX) / mapping.scale + mapping.displayRect.left
                    val y = (p.y - mapping.drawOffsetY) / mapping.scale + mapping.displayRect.top
                    return if (x in mapping.displayRect.left..mapping.displayRect.right &&
                        y in mapping.displayRect.top..mapping.displayRect.bottom
                    ) {
                        Offset(x, y)
                    } else {
                        null
                    }
                }

                fun mapToImageClamped(p: Offset): Offset {
                    val mapping = pointerMappingState.value
                    val x = (p.x - mapping.drawOffsetX) / mapping.scale + mapping.displayRect.left
                    val y = (p.y - mapping.drawOffsetY) / mapping.scale + mapping.displayRect.top
                    return Offset(
                        x.coerceIn(mapping.displayRect.left, mapping.displayRect.right),
                        y.coerceIn(mapping.displayRect.top, mapping.displayRect.bottom)
                    )
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(tool, isDrawingMode) {
                            detectDragGestures(
                                onDragStart = { pos ->
                                    if (tool is EditTool.Crop) {
                                        cropStartRect = cropRect
                                        isCropDragging = true
                                        val rect = cropRect
                                        val imgPos = mapToImageClamped(pos)
                                        val tl = Offset(rect.left, rect.top)
                                        val tr = Offset(rect.right, rect.top)
                                        val bl = Offset(rect.left, rect.bottom)
                                        val br = Offset(rect.right, rect.bottom)
                                        val corners = listOf(
                                            CropCorner.TopLeft to tl,
                                            CropCorner.TopRight to tr,
                                            CropCorner.BottomLeft to bl,
                                            CropCorner.BottomRight to br
                                        )
                                        val nearest = corners.minByOrNull { (_, point) -> (imgPos - point).getDistance() }
                                        val nearestCorner = nearest?.first
                                        val nearestDist = nearest?.second?.let { (imgPos - it).getDistance() } ?: Float.MAX_VALUE
                                        val baseBand = (cornerRadius / scale).coerceAtLeast(10f)
                                        val edgeBand = minOf(baseBand, rect.width / 3f, rect.height / 3f)
                                        val inExpanded =
                                            imgPos.x >= rect.left - edgeBand &&
                                            imgPos.x <= rect.right + edgeBand &&
                                            imgPos.y >= rect.top - edgeBand &&
                                            imgPos.y <= rect.bottom + edgeBand
                                        activeCorner = if (inExpanded && nearestCorner != null && nearestDist <= edgeBand * 2f) {
                                            nearestCorner
                                        } else {
                                            null
                                        }
                                    } else {
                                        if (!isDrawingMode) return@detectDragGestures
                                        val imgPos = mapToImage(pos) ?: return@detectDragGestures
                                        when (tool) {
                                            EditTool.Pen -> currentPath = listOf(imgPos)
                                            else -> {
                                                currentStart = imgPos
                                                currentEnd = imgPos
                                            }
                                        }
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val pos = change.position
                                    if (tool is EditTool.Crop) {
                                        val corner = activeCorner ?: return@detectDragGestures
                                        val imgPos = mapToImageClamped(pos)
                                        val rect = cropRect
                                        val mapping = pointerMappingState.value
                                        cropRect = when (corner) {
                                            CropCorner.TopLeft -> Rect(
                                                left = imgPos.x.coerceIn(0f, rect.right - minCrop),
                                                top = imgPos.y.coerceIn(0f, rect.bottom - minCrop),
                                                right = rect.right,
                                                bottom = rect.bottom
                                            )
                                            CropCorner.TopRight -> Rect(
                                                left = rect.left,
                                                top = imgPos.y.coerceIn(0f, rect.bottom - minCrop),
                                                right = imgPos.x.coerceIn(rect.left + minCrop, mapping.imageW),
                                                bottom = rect.bottom
                                            )
                                            CropCorner.BottomLeft -> Rect(
                                                left = imgPos.x.coerceIn(0f, rect.right - minCrop),
                                                top = rect.top,
                                                right = rect.right,
                                                bottom = imgPos.y.coerceIn(rect.top + minCrop, mapping.imageH)
                                            )
                                            CropCorner.BottomRight -> Rect(
                                                left = rect.left,
                                                top = rect.top,
                                                right = imgPos.x.coerceIn(rect.left + minCrop, mapping.imageW),
                                                bottom = imgPos.y.coerceIn(rect.top + minCrop, mapping.imageH)
                                            )
                                        }
                                    } else {
                                        if (!isDrawingMode) return@detectDragGestures
                                        val imgPos = mapToImageClamped(pos)
                                        when (tool) {
                                            EditTool.Pen -> currentPath = currentPath + imgPos
                                            else -> currentEnd = imgPos
                                        }
                                    }
                                },
                                onDragEnd = {
                                    if (tool is EditTool.Crop) {
                                        val startRect = cropStartRect
                                        if (startRect != null && startRect != cropRect) {
                                            baseBitmap?.let { bitmap ->
                                                history = history + EditorSnapshot(bitmap, startRect, shapes)
                                            }
                                        }
                                        activeCorner = null
                                        isCropDragging = false
                                        cropStartRect = null
                                        zoomAnchorRect = cropRect
                                    } else {
                                        if (!isDrawingMode) {
                                            currentPath = emptyList()
                                            currentStart = null
                                            currentEnd = null
                                            return@detectDragGestures
                                        }
                                        when (tool) {
                                            EditTool.Pen -> {
                                                if (currentPath.size > 1) {
                                                    pushHistory()
                                                    shapes = shapes + Shape.PathShape(currentPath, color, strokeWidth)
                                                }
                                                currentPath = emptyList()
                                            }
                                            EditTool.Line -> {
                                                val start = currentStart
                                                val end = currentEnd
                                                if (start != null && end != null) {
                                                    pushHistory()
                                                    shapes = shapes + Shape.LineShape(start, end, color, strokeWidth)
                                                }
                                            }
                                            EditTool.Rect -> {
                                                val start = currentStart
                                                val end = currentEnd
                                                if (start != null && end != null) {
                                                    pushHistory()
                                                    shapes = shapes + Shape.RectShape(start, end, color, strokeWidth)
                                                }
                                            }
                                            EditTool.Circle -> {
                                                val start = currentStart
                                                val end = currentEnd
                                                if (start != null && end != null) {
                                                    pushHistory()
                                                    shapes = shapes + Shape.CircleShape(start, end, color, strokeWidth)
                                                }
                                            }
                                            EditTool.Triangle -> {
                                                val start = currentStart
                                                val end = currentEnd
                                                if (start != null && end != null) {
                                                    pushHistory()
                                                    shapes = shapes + Shape.TriangleShape(start, end, color, strokeWidth)
                                                }
                                            }
                                            else -> Unit
                                        }
                                        currentStart = null
                                        currentEnd = null
                                    }
                                },
                                onDragCancel = {
                                    currentPath = emptyList()
                                    currentStart = null
                                    currentEnd = null
                                    activeCorner = null
                                    isCropDragging = false
                                    cropStartRect = null
                                }
                            )
                        }
                ) {
                    val imageBitmap = bitmap.asImageBitmap()
                    val srcRect = displayRect
                    drawImage(
                        image = imageBitmap,
                        srcOffset = IntOffset(srcRect.left.toInt(), srcRect.top.toInt()),
                        srcSize = IntSize(srcRect.width.toInt(), srcRect.height.toInt()),
                        dstOffset = IntOffset(drawOffsetX.toInt(), drawOffsetY.toInt()),
                        dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt())
                    )

                    fun drawShape(shape: Shape) {
                        val stroke = Stroke(width = shapeStroke(shape) * scale)
                        when (shape) {
                            is Shape.PathShape -> {
                                val path = Path()
                                shape.points.firstOrNull()?.let { first ->
                                    path.moveTo(mapToScreen(first).x, mapToScreen(first).y)
                                    shape.points.drop(1).forEach { p ->
                                        val sp = mapToScreen(p)
                                        path.lineTo(sp.x, sp.y)
                                    }
                                    drawPath(path, shape.color, style = stroke)
                                }
                            }
                            is Shape.LineShape -> drawLine(
                                color = shape.color,
                                start = mapToScreen(shape.start),
                                end = mapToScreen(shape.end),
                                strokeWidth = shape.stroke * scale
                            )
                            is Shape.RectShape -> {
                                val rect = rectFrom(shape.start, shape.end)
                                drawRect(
                                    color = shape.color,
                                    topLeft = mapToScreen(Offset(rect.left, rect.top)),
                                    size = Size(rect.width * scale, rect.height * scale),
                                    style = stroke
                                )
                            }
                            is Shape.CircleShape -> {
                                val radius = (shape.end - shape.start).getDistance()
                                val center = shape.start
                                drawCircle(
                                    color = shape.color,
                                    radius = radius * scale,
                                    center = mapToScreen(center),
                                    style = stroke
                                )
                            }
                            is Shape.TriangleShape -> {
                                val halfWidth = kotlin.math.abs(shape.end.x - shape.start.x)
                                val baseY = shape.end.y
                                val path = Path()
                                val top = shape.start
                                val left = Offset(shape.start.x - halfWidth, baseY)
                                val right = Offset(shape.start.x + halfWidth, baseY)
                                path.moveTo(mapToScreen(top).x, mapToScreen(top).y)
                                path.lineTo(mapToScreen(left).x, mapToScreen(left).y)
                                path.lineTo(mapToScreen(right).x, mapToScreen(right).y)
                                path.close()
                                drawPath(path, shape.color, style = stroke)
                            }
                        }
                    }

                    shapes.forEach { drawShape(it) }

                    if (tool is EditTool.Crop) {
                        val rect = cropRect
                        val topLeft = mapToScreen(Offset(rect.left, rect.top))
                        val size = Size(rect.width * scale, rect.height * scale)
                        val cropRight = topLeft.x + size.width
                        val cropBottom = topLeft.y + size.height
                        val dimColor = Color.Black.copy(alpha = 0.45f)
                        drawRect(
                            color = dimColor,
                            topLeft = Offset(0f, 0f),
                            size = Size(this.size.width, topLeft.y.coerceAtLeast(0f))
                        )
                        drawRect(
                            color = dimColor,
                            topLeft = Offset(0f, cropBottom.coerceAtMost(this.size.height)),
                            size = Size(this.size.width, (this.size.height - cropBottom).coerceAtLeast(0f))
                        )
                        drawRect(
                            color = dimColor,
                            topLeft = Offset(0f, topLeft.y),
                            size = Size(topLeft.x.coerceAtLeast(0f), size.height.coerceAtLeast(0f))
                        )
                        drawRect(
                            color = dimColor,
                            topLeft = Offset(cropRight.coerceAtMost(this.size.width), topLeft.y),
                            size = Size((this.size.width - cropRight).coerceAtLeast(0f), size.height.coerceAtLeast(0f))
                        )
                        drawRect(
                            color = Color.White,
                            topLeft = topLeft,
                            size = size,
                            style = Stroke(width = 2.dp.toPx())
                        )
                        val handleRadius = with(density) { 6.dp.toPx() }
                        val corners = listOf(
                            mapToScreen(Offset(rect.left, rect.top)),
                            mapToScreen(Offset(rect.right, rect.top)),
                            mapToScreen(Offset(rect.left, rect.bottom)),
                            mapToScreen(Offset(rect.right, rect.bottom))
                        )
                        corners.forEach { corner ->
                            drawCircle(
                                color = Color.White,
                                radius = handleRadius,
                                center = corner
                            )
                        }
                    } else {
                        if (currentPath.isNotEmpty()) {
                            drawShape(Shape.PathShape(currentPath, color, strokeWidth))
                        }

                        if (currentStart != null && currentEnd != null && tool !is EditTool.Pen) {
                            val preview = when (tool) {
                                EditTool.Line -> Shape.LineShape(currentStart!!, currentEnd!!, color, strokeWidth)
                                EditTool.Rect -> Shape.RectShape(currentStart!!, currentEnd!!, color, strokeWidth)
                                EditTool.Circle -> Shape.CircleShape(currentStart!!, currentEnd!!, color, strokeWidth)
                                EditTool.Triangle -> Shape.TriangleShape(currentStart!!, currentEnd!!, color, strokeWidth)
                                else -> null
                            }
                            if (preview != null) drawShape(preview)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            ToolBar(
                activeTool = tool,
                isDrawingMode = isDrawingMode,
                isStrokeOpen = showStrokeSlider,
                onDrawingToggle = {
                    val next = !isDrawingMode
                    isDrawingMode = next
                    if (!next) {
                        currentPath = emptyList()
                        currentStart = null
                        currentEnd = null
                        showStrokeSlider = false
                    }
                    if (next && tool is EditTool.Crop) {
                        tool = EditTool.Pen
                    }
                },
                onStrokeToggle = { showStrokeSlider = !showStrokeSlider },
                strokeWidth = strokeWidth,
                onStrokeChange = { strokeWidth = it },
                onToolSelected = { tool = it }
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (isDrawingMode) {
                ColorPalette(
                    colors = palette,
                    selected = color,
                    onSelected = { color = it }
                )
            }
        }
    }
}

private enum class CropCorner { TopLeft, TopRight, BottomLeft, BottomRight }

private fun shapeStroke(shape: Shape): Float {
    return when (shape) {
        is Shape.PathShape -> shape.stroke
        is Shape.LineShape -> shape.stroke
        is Shape.RectShape -> shape.stroke
        is Shape.CircleShape -> shape.stroke
        is Shape.TriangleShape -> shape.stroke
    }
}

private fun rectFrom(start: Offset, end: Offset): Rect {
    val left = minOf(start.x, end.x)
    val right = maxOf(start.x, end.x)
    val top = minOf(start.y, end.y)
    val bottom = maxOf(start.y, end.y)
    return Rect(left, top, right, bottom)
}

@Composable
private fun LegacyToolBar(
    activeTool: EditTool,
    isDrawingMode: Boolean,
    onDrawingToggle: () -> Unit,
    onToolSelected: (EditTool) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        ToolButton(label = "Перо", active = activeTool is EditTool.Pen) { onToolSelected(EditTool.Pen) }
        ToolButton(label = "Линия", active = activeTool is EditTool.Line) { onToolSelected(EditTool.Line) }
        ToolButton(label = "Прямоуг.", active = activeTool is EditTool.Rect) { onToolSelected(EditTool.Rect) }
        ToolButton(label = "Круг", active = activeTool is EditTool.Circle) { onToolSelected(EditTool.Circle) }
        ToolButton(label = "Треуг.", active = activeTool is EditTool.Triangle) { onToolSelected(EditTool.Triangle) }
        ToolButton(label = "Обрезка", active = activeTool is EditTool.Crop) { onToolSelected(EditTool.Crop) }
    }
}

@Composable
private fun ToolButton(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) Color.White else Color.DarkGray
    val fg = if (active) Color.Black else Color.White
    Text(
        text = label,
        color = fg,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun ToolBar(
    activeTool: EditTool,
    isDrawingMode: Boolean,
    isStrokeOpen: Boolean,
    onDrawingToggle: () -> Unit,
    onStrokeToggle: () -> Unit,
    strokeWidth: Float,
    onStrokeChange: (Float) -> Unit,
    onToolSelected: (EditTool) -> Unit
) {
    val strokeOptions = listOf(3f, 6f, 10f, 14f)
    val strokePopoverHeight = (32 * strokeOptions.size + 6 * (strokeOptions.size - 1) + 12).dp
    val density = LocalDensity.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        ToolIconButton(
            icon = Icons.Filled.Edit,
            active = isDrawingMode,
            onClick = onDrawingToggle
        )
        if (isDrawingMode) {
            ToolIconButton(
                icon = Icons.Filled.Brush,
                active = activeTool is EditTool.Pen
            ) { onToolSelected(EditTool.Pen) }
            ToolIconButton(
                icon = Icons.Filled.ShowChart,
                active = activeTool is EditTool.Line
            ) { onToolSelected(EditTool.Line) }
            ToolIconButton(
                icon = Icons.Filled.CropSquare,
                active = activeTool is EditTool.Rect
            ) { onToolSelected(EditTool.Rect) }
            ToolIconButton(
                icon = Icons.Filled.RadioButtonUnchecked,
                active = activeTool is EditTool.Circle
            ) { onToolSelected(EditTool.Circle) }
            ToolIconButton(
                icon = Icons.Filled.ChangeHistory,
                active = activeTool is EditTool.Triangle
            ) { onToolSelected(EditTool.Triangle) }
            Box {
                ToolIconButton(
                    icon = Icons.Filled.LineWeight,
                    active = isStrokeOpen,
                    onClick = onStrokeToggle
                )
                if (isStrokeOpen) {
                    val offsetPx = with(density) { (strokePopoverHeight + 8.dp).roundToPx() }
                    Popup(
                        alignment = Alignment.TopCenter,
                        offset = IntOffset(0, -offsetPx),
                        onDismissRequest = onStrokeToggle,
                        properties = PopupProperties(focusable = true)
                    ) {
                        StrokeOptionsPopover(
                            options = strokeOptions,
                            selected = strokeWidth,
                            onSelect = {
                                onStrokeChange(it)
                                onStrokeToggle()
                            }
                        )
                    }
                }
            }
        }
        ToolIconButton(
            icon = Icons.Filled.Crop,
            active = activeTool is EditTool.Crop
        ) { onToolSelected(EditTool.Crop) }
    }
}

@Composable
private fun ToolIconButton(
    icon: ImageVector,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val bg = when {
        active -> Color.White
        enabled -> Color.DarkGray
        else -> Color.DarkGray.copy(alpha = 0.5f)
    }
    val fg = if (active) Color.Black else Color.White.copy(alpha = if (enabled) 0.9f else 0.35f)
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = fg)
    }
}

@Composable
private fun ColorPalette(
    colors: List<Color>,
    selected: Color,
    onSelected: (Color) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { color ->
            val isSelected = color == selected
            val borderColor = if (isSelected) {
                if (color == Color.White) Color.Black else Color.White
            } else {
                Color.White.copy(alpha = 0.25f)
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = borderColor,
                        shape = CircleShape
                    )
                    .clickable { onSelected(color) }
            )
        }
    }
}

@Composable
private fun StrokeOptionsPopover(
    options: List<Float>,
    selected: Float,
    onSelect: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1E1E1E))
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .padding(vertical = 6.dp, horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        options.forEach { option ->
            StrokeOptionButton(
                value = option,
                selected = option == selected,
                onClick = { onSelect(option) }
            )
        }
    }
}

@Composable
private fun StrokeOptionButton(
    value: Float,
    selected: Boolean,
    onClick: () -> Unit
) {
    val size = 32.dp
    val dotSize = (6f + (value - 2f) * 0.6f).coerceIn(6f, 16f)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (selected) Color.White else Color.DarkGray)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(dotSize.dp)
                .clip(CircleShape)
                .background(if (selected) Color.Black else Color.White)
        )
    }
}

private fun renderEditedBitmap(
    base: Bitmap,
    shapes: List<Shape>,
    cropRect: Rect
): Bitmap {
    val editable = base.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = AndroidCanvas(editable)
    shapes.forEach { shape ->
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = shapeStroke(shape)
            this.color = shapeColor(shape).toArgb()
        }
        when (shape) {
            is Shape.PathShape -> {
                val path = AndroidPath()
                val first = shape.points.firstOrNull()
                if (first != null) {
                    path.moveTo(first.x, first.y)
                    shape.points.drop(1).forEach { p -> path.lineTo(p.x, p.y) }
                    canvas.drawPath(path, paint)
                }
            }
            is Shape.LineShape -> canvas.drawLine(shape.start.x, shape.start.y, shape.end.x, shape.end.y, paint)
            is Shape.RectShape -> {
                val rect = rectFrom(shape.start, shape.end)
                canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, paint)
            }
            is Shape.CircleShape -> {
                val dx = shape.end.x - shape.start.x
                val dy = shape.end.y - shape.start.y
                val radius = kotlin.math.sqrt(dx * dx + dy * dy)
                canvas.drawCircle(shape.start.x, shape.start.y, radius, paint)
            }
            is Shape.TriangleShape -> {
                val halfWidth = kotlin.math.abs(shape.end.x - shape.start.x)
                val baseY = shape.end.y
                val path = AndroidPath()
                path.moveTo(shape.start.x, shape.start.y)
                path.lineTo(shape.start.x - halfWidth, baseY)
                path.lineTo(shape.start.x + halfWidth, baseY)
                path.close()
                canvas.drawPath(path, paint)
            }
        }
    }

    val left = cropRect.left.coerceIn(0f, editable.width.toFloat() - 1f)
    val top = cropRect.top.coerceIn(0f, editable.height.toFloat() - 1f)
    val right = cropRect.right.coerceIn(left + 1f, editable.width.toFloat())
    val bottom = cropRect.bottom.coerceIn(top + 1f, editable.height.toFloat())
    val width = (right - left).toInt()
    val height = (bottom - top).toInt()
    return Bitmap.createBitmap(editable, left.toInt(), top.toInt(), width, height)
}

private fun shapeColor(shape: Shape): Color {
    return when (shape) {
        is Shape.PathShape -> shape.color
        is Shape.LineShape -> shape.color
        is Shape.RectShape -> shape.color
        is Shape.CircleShape -> shape.color
        is Shape.TriangleShape -> shape.color
    }
}

private fun buildMediaSections(items: List<MediaItem>, filter: MediaFilter): List<MediaSection> {
    val filtered = when (filter) {
        MediaFilter.All -> items
        MediaFilter.Photos -> items.filter { !it.isVideo }
        MediaFilter.Videos -> items.filter { it.isVideo }
    }
    val grouped = filtered.groupBy { toLocalDate(it.dateAdded) }
    return grouped.toSortedMap(compareByDescending { it }).map { (date, list) ->
        MediaSection(
            title = formatSectionTitle(date),
            items = list.sortedByDescending { it.dateAdded }
        )
    }
}

private fun buildFileSections(items: List<FileItem>, filter: FileFilter): List<FileSection> {
    val filtered = when (filter) {
        FileFilter.All -> items
        FileFilter.Documents -> items.filter { isDocument(it.mimeType) }
        FileFilter.Audio -> items.filter { it.mimeType.startsWith("audio/") }
        FileFilter.Archives -> items.filter { isArchive(it.mimeType) || isArchiveName(it.name) }
    }
    val grouped = filtered.groupBy { toLocalDate(it.dateAdded) }
    return grouped.toSortedMap(compareByDescending { it }).map { (date, list) ->
        FileSection(
            title = formatSectionTitle(date),
            items = list.sortedByDescending { it.dateAdded }
        )
    }
}

private fun toLocalDate(epochSeconds: Long): LocalDate {
    return Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalDate()
}

private fun formatSectionTitle(date: LocalDate): String {
    val today = LocalDate.now()
    return when {
        date == today -> "Сегодня"
        date == today.minusDays(1) -> "Вчера"
        date.isAfter(today.minusDays(7)) -> "На этой неделе"
        else -> {
            val day = date.dayOfMonth
            val month = monthName(date.monthValue)
            "$day $month"
        }
    }
}

private fun monthName(month: Int): String {
    return when (month) {
        1 -> "января"
        2 -> "февраля"
        3 -> "марта"
        4 -> "апреля"
        5 -> "мая"
        6 -> "июня"
        7 -> "июля"
        8 -> "августа"
        9 -> "сентября"
        10 -> "октября"
        11 -> "ноября"
        12 -> "декабря"
        else -> ""
    }
}

private fun isDocument(mimeType: String): Boolean {
    val lower = mimeType.lowercase()
    return lower.startsWith("text/") ||
        lower == "application/pdf" ||
        lower.contains("word") ||
        lower.contains("excel") ||
        lower.contains("powerpoint") ||
        lower.contains("rtf") ||
        lower.contains("opendocument")
}

private fun isArchive(mimeType: String): Boolean {
    val lower = mimeType.lowercase()
    return lower == "application/zip" ||
        lower == "application/x-7z-compressed" ||
        lower == "application/x-rar-compressed" ||
        lower == "application/x-tar"
}

private fun isArchiveName(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".zip") ||
        lower.endsWith(".7z") ||
        lower.endsWith(".rar") ||
        lower.endsWith(".tar")
}

private fun rotateBitmap90(bitmap: Bitmap): Bitmap {
    val matrix = Matrix().apply { postRotate(90f) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

@Composable
private fun rememberThumbnail(resolver: ContentResolver, uri: android.net.Uri): ImageBitmap? {
    val state = produceState<ImageBitmap?>(null, uri) {
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    resolver.loadThumbnail(uri, AndroidSize(300, 300), null)
                } else {
                    resolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                }
            }.getOrNull()
        }
        value = bitmap?.asImageBitmap()
    }
    return state.value
}

private fun buildPermissionList(): Array<String> {
    return if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private fun hasPermissions(context: Context, permissions: Array<String>): Boolean {
    return permissions.all { perm ->
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }
}

private suspend fun loadMediaItems(resolver: ContentResolver): List<MediaItem> {
    return withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()
        items += queryMedia(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
        items += queryMedia(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
        items.sortedByDescending { it.dateAdded }
    }
}

private fun queryMedia(
    resolver: ContentResolver,
    contentUri: android.net.Uri,
    isVideo: Boolean
): List<MediaItem> {
    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_ADDED
    )
    val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
    val results = mutableListOf<MediaItem>()
    resolver.query(contentUri, projection, null, null, sortOrder)?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val typeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idIndex)
            val uri = ContentUris.withAppendedId(contentUri, id)
            val name = cursor.getString(nameIndex) ?: "media"
            val mime = cursor.getString(typeIndex) ?: "application/octet-stream"
            val size = cursor.getLong(sizeIndex)
            val date = cursor.getLong(dateIndex)
            results += MediaItem(uri, name, mime, size, date, isVideo)
        }
    }
    return results
}

private suspend fun loadFileItems(resolver: ContentResolver): List<FileItem> {
    return withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext emptyList()
        val contentUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val results = mutableListOf<FileItem>()
        resolver.query(contentUri, projection, null, null, sortOrder)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val typeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val uri = ContentUris.withAppendedId(contentUri, id)
                val name = cursor.getString(nameIndex) ?: "file"
                val mime = cursor.getString(typeIndex) ?: "application/octet-stream"
                val size = cursor.getLong(sizeIndex)
                val date = cursor.getLong(dateIndex)
                results += FileItem(uri, name, mime, size, date)
            }
        }
        results
    }
}

private fun readPickedFile(resolver: ContentResolver, uri: android.net.Uri): PickedFile? {
    val name = queryDisplayName(resolver, uri)
    val type = resolver.getType(uri) ?: "application/octet-stream"
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    return PickedFile(name, bytes, type)
}

private fun queryDisplayName(contentResolver: ContentResolver, uri: android.net.Uri): String {
    val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                val value = it.getString(index)
                if (!value.isNullOrBlank()) return value
            }
        }
    }
    return "file"
}

private fun formatFileSize(size: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    return when {
        size >= mb -> String.format("%.1f МБ", size / mb)
        size >= kb -> String.format("%.1f КБ", size / kb)
        else -> "$size Б"
    }
}
