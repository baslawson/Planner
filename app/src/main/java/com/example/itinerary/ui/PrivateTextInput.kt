package com.example.itinerary.ui

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Text typed in [content]'s fields is private: the keyboard is asked not to learn it, keep it for suggestions or send
 * it anywhere (Android's "incognito" flag for keyboards). For server addresses, user names, passwords, private links
 * and account numbers. Keyboards that follow Android's rules (Gboard, SwiftKey, Samsung) honour it; it can't be forced.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PrivateTextInput(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            nextHandler.startInputMethod(PlatformTextInputMethodRequest { attributes ->
                request.createInputConnection(attributes).also {
                    attributes.imeOptions = attributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                }
            })
        },
        content = content,
    )
}

/** Keyboard options for a private field: no autocorrect or suggestions to learn from. */
fun androidx.compose.foundation.text.KeyboardOptions.private() = copy(autoCorrectEnabled = false)
