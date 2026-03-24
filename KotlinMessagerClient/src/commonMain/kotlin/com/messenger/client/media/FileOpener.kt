package com.messenger.client.media

import androidx.compose.runtime.Composable

@Composable
expect fun rememberFileOpener(): (String, String?) -> Boolean
