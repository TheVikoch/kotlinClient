package com.messenger.client.ui.screens.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.client.media.AttachmentPicker
import com.messenger.client.media.PickedFile
import com.messenger.client.media.buildUserProfilePhotoCacheKey
import com.messenger.client.media.rememberMediaCache
import com.messenger.client.models.UserProfileDto
import com.messenger.client.services.ApiService
import com.messenger.client.services.AuthState
import com.messenger.client.ui.components.CachedUserProfilePhoto
import kotlinx.coroutines.launch

@Composable
fun UserProfileScreen(
    authState: AuthState,
    userId: String,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val apiService = remember { ApiService() }
    val cache = rememberMediaCache()
    val token by authState.jwtToken.collectAsState()
    val currentUserId by authState.currentUserId.collectAsState()
    val currentUserEmail by authState.currentUserEmail.collectAsState()
    val isCurrentUser = userId == currentUserId

    var profile by remember(userId) { mutableStateOf<UserProfileDto?>(null) }
    var isLoading by remember(userId) { mutableStateOf(false) }
    var isSaving by remember(userId) { mutableStateOf(false) }
    var isUploading by remember(userId) { mutableStateOf(false) }
    var errorMessage by remember(userId) { mutableStateOf<String?>(null) }
    var displayName by remember(userId) { mutableStateOf("") }
    var aboutMe by remember(userId) { mutableStateOf("") }
    var selectedPhotoId by remember(userId) { mutableStateOf<String?>(null) }
    var showAttachmentPicker by remember(userId) { mutableStateOf(false) }

    fun applyProfile(newProfile: UserProfileDto) {
        profile = newProfile
        displayName = newProfile.displayName
        aboutMe = newProfile.aboutMe.orEmpty()
        selectedPhotoId = when {
            selectedPhotoId == null -> newProfile.photos.firstOrNull()?.id
            newProfile.photos.any { it.id == selectedPhotoId } -> selectedPhotoId
            else -> newProfile.photos.firstOrNull()?.id
        }
        if (isCurrentUser) {
            authState.updateCurrentUserProfile(newProfile)
        }
    }

    fun loadProfile() {
        scope.launch {
            val currentToken = token
            if (currentToken.isNullOrBlank()) {
                errorMessage = "Нет авторизации"
                return@launch
            }

            isLoading = true
            errorMessage = null

            val result = if (isCurrentUser) {
                apiService.getMyProfile(currentToken)
            } else {
                apiService.getUserProfile(currentToken, userId)
            }

            result.fold(
                onSuccess = { loadedProfile ->
                    applyProfile(loadedProfile)
                    isLoading = false
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Не удалось загрузить профиль"
                    isLoading = false
                }
            )
        }
    }

    suspend fun uploadPhotos(files: List<PickedFile>) {
        val currentToken = token
        if (currentToken.isNullOrBlank()) {
            errorMessage = "Нет авторизации"
            return
        }

        val imageFiles = files.filter { it.contentType.startsWith("image/", ignoreCase = true) }
        if (imageFiles.isEmpty()) {
            errorMessage = "Выберите хотя бы одно изображение"
            return
        }

        isUploading = true
        errorMessage = null

        var latestProfile: UserProfileDto? = null
        for (file in imageFiles) {
            val initResponse = apiService.initUserProfilePhotoUpload(
                token = currentToken,
                fileName = file.name,
                contentType = file.contentType,
                size = file.bytes.size.toLong()
            ).getOrElse { error ->
                isUploading = false
                errorMessage = error.message ?: "Не удалось начать загрузку фото"
                return
            }

            val uploadResult = apiService.uploadToPresignedUrl(
                uploadUrl = initResponse.uploadUrl,
                bytes = file.bytes,
                contentType = file.contentType
            )
            if (uploadResult.isFailure) {
                isUploading = false
                errorMessage = uploadResult.exceptionOrNull()?.message ?: "Не удалось загрузить фото"
                return
            }

            latestProfile = apiService.completeUserProfilePhotoUpload(
                token = currentToken,
                photoId = initResponse.photoId
            ).getOrElse { error ->
                isUploading = false
                errorMessage = error.message ?: "Не удалось завершить загрузку фото"
                return
            }
        }

        latestProfile?.let(::applyProfile)
        isUploading = false
    }

    fun saveProfile() {
        scope.launch {
            val currentToken = token
            if (currentToken.isNullOrBlank()) {
                errorMessage = "Нет авторизации"
                return@launch
            }

            isSaving = true
            errorMessage = null
            apiService.updateMyProfile(
                token = currentToken,
                displayName = displayName,
                aboutMe = aboutMe.ifBlank { null }
            ).fold(
                onSuccess = { updatedProfile ->
                    applyProfile(updatedProfile)
                    isSaving = false
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Не удалось сохранить профиль"
                    isSaving = false
                }
            )
        }
    }

    fun deleteSelectedPhoto() {
        scope.launch {
            val currentToken = token
            val currentProfile = profile
            val currentSelectedPhotoId = selectedPhotoId
            if (
                currentToken.isNullOrBlank() ||
                currentProfile == null ||
                currentSelectedPhotoId.isNullOrBlank()
            ) {
                errorMessage = "Нет авторизации"
                return@launch
            }

            isSaving = true
            errorMessage = null
            apiService.deleteUserProfilePhoto(currentToken, currentSelectedPhotoId).fold(
                onSuccess = { updatedProfile ->
                    cache.delete(
                        buildUserProfilePhotoCacheKey(
                            currentProfile.userId,
                            currentSelectedPhotoId
                        )
                    )
                    applyProfile(updatedProfile)
                    isSaving = false
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Не удалось удалить фото"
                    isSaving = false
                }
            )
        }
    }

    LaunchedEffect(userId, token) {
        loadProfile()
    }

    val selectedPhoto = remember(profile, selectedPhotoId) {
        val currentProfile = profile ?: return@remember null
        currentProfile.photos.firstOrNull { it.id == selectedPhotoId }
            ?: currentProfile.photos.firstOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBackIos,
                        contentDescription = "Назад"
                    )
                }
                Column {
                    Text(
                        text = if (isCurrentUser) "Мой профиль" else "Профиль",
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (isCurrentUser && !currentUserEmail.isNullOrBlank()) {
                        Text(
                            text = currentUserEmail!!,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            if (isCurrentUser) {
                Button(
                    onClick = ::saveProfile,
                    enabled = !isSaving && !isUploading
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Сохранить")
                    }
                }
            }
        }

        if (isLoading && profile == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            errorMessage?.let { message ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedPhoto != null && profile != null) {
                            CachedUserProfilePhoto(
                                token = token,
                                userId = profile!!.userId,
                                photoId = selectedPhoto.id,
                                displayName = profile!!.displayName,
                                modifier = Modifier.fillMaxSize(),
                                shape = RoundedCornerShape(24.dp),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                text = (profile?.displayName ?: "Профиль")
                                    .trim()
                                    .firstOrNull()
                                    ?.uppercaseChar()
                                    ?.toString()
                                    ?: "?",
                                fontSize = 72.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    profile?.let { currentProfile ->
                        if (currentProfile.photos.isNotEmpty() || isCurrentUser) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                currentProfile.photos.forEach { photo ->
                                    val isSelected = photo.id == selectedPhoto?.id
                                    Card(
                                        modifier = Modifier
                                            .size(76.dp)
                                            .clickable { selectedPhotoId = photo.id },
                                        shape = RoundedCornerShape(18.dp),
                                        border = if (isSelected) {
                                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                        } else {
                                            null
                                        },
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        CachedUserProfilePhoto(
                                            token = token,
                                            userId = currentProfile.userId,
                                            photoId = photo.id,
                                            displayName = currentProfile.displayName,
                                            modifier = Modifier.fillMaxSize(),
                                            shape = RoundedCornerShape(18.dp),
                                            contentScale = ContentScale.Crop,
                                            showLoadingIndicator = false
                                        )
                                    }
                                }

                                if (isCurrentUser) {
                                    Card(
                                        modifier = Modifier
                                            .size(76.dp)
                                            .clickable(enabled = !isUploading) {
                                                showAttachmentPicker = true
                                            },
                                        shape = RoundedCornerShape(18.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isUploading) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    strokeWidth = 2.dp
                                                )
                                            } else {
                                                Icon(
                                                    Icons.Filled.AddPhotoAlternate,
                                                    contentDescription = "Добавить фото",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (isCurrentUser && selectedPhoto != null) {
                            Button(
                                onClick = ::deleteSelectedPhoto,
                                enabled = !isSaving && !isUploading,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("Удалить выбранное фото")
                            }
                        }

                        HorizontalDivider()

                        if (isCurrentUser) {
                            OutlinedTextField(
                                value = displayName,
                                onValueChange = { displayName = it },
                                label = { Text("Имя") },
                                singleLine = true,
                                enabled = !isSaving && !isUploading,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = aboutMe,
                                onValueChange = { aboutMe = it },
                                label = { Text("О себе") },
                                enabled = !isSaving && !isUploading,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                            )
                        } else {
                            Text(
                                text = currentProfile.displayName.ifBlank { "Без имени" },
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            currentProfile.aboutMe
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { aboutText ->
                                    Text(
                                        text = aboutText,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                    )
                                }
                        }
                    }
                }
            }
        }
    }

    if (isCurrentUser) {
        AttachmentPicker(
            show = showAttachmentPicker,
            onDismiss = { showAttachmentPicker = false },
            onPicked = { files ->
                showAttachmentPicker = false
                scope.launch {
                    uploadPhotos(files)
                }
            },
            onError = { error ->
                showAttachmentPicker = false
                errorMessage = error
            }
        )
    }
}
