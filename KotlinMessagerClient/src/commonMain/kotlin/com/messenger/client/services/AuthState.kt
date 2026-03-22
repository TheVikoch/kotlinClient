package com.messenger.client.services

import com.messenger.client.models.AuthResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AuthState {
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _jwtToken = MutableStateFlow<String?>(null)
    val jwtToken: StateFlow<String?> = _jwtToken.asStateFlow()

    private val _refreshToken = MutableStateFlow<String?>(null)
    val refreshToken: StateFlow<String?> = _refreshToken.asStateFlow()

    private val _currentUserEmail = MutableStateFlow<String?>(null)
    val currentUserEmail: StateFlow<String?> = _currentUserEmail.asStateFlow()

    private val _currentUserDisplayName = MutableStateFlow<String?>(null)
    val currentUserDisplayName: StateFlow<String?> = _currentUserDisplayName.asStateFlow()

    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val _tokenExpires = MutableStateFlow<String?>(null)
    val tokenExpires: StateFlow<String?> = _tokenExpires.asStateFlow()

    fun setAuthData(authResponse: AuthResponse) {
        _jwtToken.value = authResponse.token
        _refreshToken.value = authResponse.refreshToken
        _currentUserEmail.value = authResponse.email
        _currentUserDisplayName.value = authResponse.displayName
        _currentUserId.value = authResponse.userId
        _currentSessionId.value = authResponse.sessionId
        _tokenExpires.value = authResponse.expires
        _isLoggedIn.value = true
    }

    fun clearAuth() {
        _jwtToken.value = null
        _refreshToken.value = null
        _currentUserEmail.value = null
        _currentUserDisplayName.value = null
        _currentUserId.value = null
        _currentSessionId.value = null
        _tokenExpires.value = null
        _isLoggedIn.value = false
    }

    fun getToken(): String? = _jwtToken.value
    fun getUserId(): String? = _currentUserId.value
    fun getSessionId(): String? = _currentSessionId.value
}
