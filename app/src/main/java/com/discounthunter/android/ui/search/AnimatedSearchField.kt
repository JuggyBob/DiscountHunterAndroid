package com.discounthunter.android.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.discounthunter.android.R
import com.discounthunter.android.ui.theme.Motion

/**
 * The search field.
 *
 * Three things move here, and none of them is decoration:
 *
 * * **Focus** thickens the outline and fills the leading icon. A field that only draws a cursor still
 *   looks unfocused on a phone, where the caret is a two-pixel line.
 * * **The clear button** scales and fades in rather than popping, because it replaces the search
 *   spinner and swapping an icon mid-frame reads as a flicker.
 * * **Typing** nudges the field very slightly, so the first keystroke is acknowledged even when the
 *   results take a moment to arrive.
 */
@Composable
fun AnimatedSearchField(
    query: String,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }

    // A one-shot nudge on typing, rather than a per-keystroke wobble that never settles.
    val nudge = remember { Animatable(0f) }
    val previousLength = remember { mutableStateOf(query.length) }
    LaunchedEffect(query.length) {
        if (query.length > previousLength.value) {
            nudge.snapTo(1f)
            nudge.animateTo(0f, tween(Motion.PRESSED_MILLIS, easing = Motion.Emphasised))
        }
        previousLength.value = query.length
    }

    val outlineWidth by animateFloatAsState(
        targetValue = if (focused) 2.dp.value else 1.dp.value,
        animationSpec = tween(Motion.PRESSED_MILLIS, easing = Motion.Emphasised),
        label = "searchOutline",
    )

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                // 0.6% of a pixel of travel: below the threshold of being seen as movement on its
                // own, but enough that the field reacts to the first character typed.
                translationY = -nudge.value * 1.5f
                scaleX = 1f + nudge.value * 0.004f
                scaleY = 1f + nudge.value * 0.004f
            },
        placeholder = { Text(stringResource(R.string.search_hint)) },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        textStyle = MaterialTheme.typography.bodyLarge,
        leadingIcon = {
            val scale by animateFloatAsState(
                targetValue = if (focused) 1.1f else 1f,
                animationSpec = Motion.pop(),
                label = "searchIcon",
            )
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
            )
        },
        trailingIcon = {
            // The spinner and the clear button occupy the same slot, so exactly one is composed at a
            // time and the crossfade happens through `AnimatedVisibility`.
            AnimatedVisibility(
                visible = isSearching,
                enter = scaleIn(Motion.pop()) + fadeIn(Motion.fade()),
                exit = scaleOut(Motion.pop()) + fadeOut(Motion.fadeOut()),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
            AnimatedVisibility(
                visible = !isSearching && query.isNotEmpty(),
                enter = scaleIn(Motion.pop()) + fadeIn(Motion.fade()),
                exit = scaleOut(Motion.pop()) + fadeOut(Motion.fadeOut()),
            ) {
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.search_clear),
                    )
                }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                onSearch()
                keyboard?.hide()
            },
        ),
    )
}
