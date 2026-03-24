package com.messenger.client.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay

@Composable
fun TypingIndicatorText(
    prefix: String,
    textStyle: TextStyle
) {
    var text by remember(prefix) { mutableStateOf("") }

    LaunchedEffect(prefix) {
        val word = "печатает"
        val steps = listOf("п", "пе", "печ", "печа", "печат", "печата", "печатае", "печатает")
        while (true) {
            for (step in steps) {
                text = step
                delay(90)
            }
            text = "$word."
            delay(240)
            text = "$word.."
            delay(240)
            text = "$word..."
            delay(240)
        }
    }

    Text(
        text = prefix + text,
        style = textStyle
    )
}

