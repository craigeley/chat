@file:OptIn(ExperimentalFoundationApi::class)

package com.craigeley.chat.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craigeley.chat.ui.theme.ChatColors
import com.craigeley.chat.ui.theme.ChatType

/** Tappable text with a haptic tick on press — the vandamd "button". */
@Composable
fun HapticText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    underline: Boolean = false,
    textAlign: TextAlign = TextAlign.Center,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = text,
        style = style,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        softWrap = softWrap,
        overflow = TextOverflow.Ellipsis,
        textDecoration = if (underline) TextDecoration.Underline else TextDecoration.None,
        modifier = modifier.combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
            onLongClick = onLongClick?.let {
                {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    it()
                }
            },
        ),
    )
}

/** Top row shared by the sub-screens: a back chevron on the left and a centred
 *  title (the trailing spacer balances the chevron so the title sits centred).
 *  A non-null [onTitleClick] makes the title itself tappable (the thread uses
 *  this to open the chat's details). */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onTitleClick: (() -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HapticText(
            text = "‹",
            style = ChatType.title,
            color = ChatColors.onSurface,
            onClick = onBack,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (onTitleClick != null) {
            HapticText(
                text = title,
                style = ChatType.body,
                color = ChatColors.onSurfaceVariant,
                maxLines = 1,
                onClick = onTitleClick,
            )
        } else {
            Text(
                text = title,
                style = ChatType.body,
                color = ChatColors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(24.dp))
    }
}
