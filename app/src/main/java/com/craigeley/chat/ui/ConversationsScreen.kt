@file:OptIn(ExperimentalFoundationApi::class)

package com.craigeley.chat.ui

import android.content.Context
import android.text.format.DateUtils
import java.text.DateFormat
import java.util.Date
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.craigeley.chat.ChatViewModel
import com.craigeley.chat.Conversation
import com.craigeley.chat.Status
import com.craigeley.chat.ui.theme.ChatColors
import com.craigeley.chat.ui.theme.ChatType
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** The conversation list — newest activity first, tap to open, tap title for settings. */
@Composable
fun ConversationsScreen(viewModel: ChatViewModel, onOpenSettings: () -> Unit, onNewMessage: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    // The list is keyed by guid, so LazyListState anchors to the first *visible*
    // row's key — a refresh that inserts a new chat above it (e.g. one just started
    // from New) slides the viewport down with the old top row, leaving the new chat
    // off-screen above. Snap back to the top when the top chat changes, but only if
    // the user is already up there (don't yank them out of a scrolled-down read).
    val topGuid = state.conversations.firstOrNull()?.guid
    LaunchedEffect(topGuid) {
        if (topGuid != null && listState.firstVisibleItemIndex <= 1) {
            listState.scrollToItem(0)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HapticText(
                text = "New",
                style = ChatType.hint,
                color = ChatColors.onSurfaceVariant,
                onClick = onNewMessage,
                modifier = Modifier.width(56.dp),
                textAlign = TextAlign.Start,
            )
            Spacer(modifier = Modifier.weight(1f))
            HapticText(
                text = "Messages",
                style = ChatType.body,
                color = ChatColors.onSurface,
                onClick = onOpenSettings,
            )
            Spacer(modifier = Modifier.weight(1f))
            HapticText(
                text = "Refresh",
                style = ChatType.hint,
                color = if (state.status == Status.Loading) ChatColors.onSurfaceDisabled else ChatColors.onSurfaceVariant,
                onClick = viewModel::refresh,
                modifier = Modifier.width(56.dp),
                textAlign = TextAlign.End,
            )
        }

        // Only after a sustained drop (see ChatViewModel.applyConnection) — a quick
        // handover or a normal launch never shows it.
        if (!state.connected) {
            Text(
                text = "Offline — reconnecting…",
                style = ChatType.hint,
                color = ChatColors.onSurfaceDisabled,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }

        when {
            state.conversations.isEmpty() -> {
                val label = when {
                    state.status == Status.Loading -> "Loading…"
                    else -> state.message ?: "No conversations"
                }
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = ChatType.body,
                        color = ChatColors.onSurfaceDisabled,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            else -> LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.conversations, key = { it.guid }) { convo ->
                    // A tapback as the newest activity shows as "Liz loved an image";
                    // otherwise the real message text, prefixed "You: " when it's ours.
                    val subtitle = convo.lastReaction?.summary(state.contacts)
                        ?: ((if (convo.lastFromMe) "You: " else "") + convo.lastText)
                    ConversationRow(
                        convo = convo,
                        title = state.contacts.title(convo),
                        subtitle = subtitle,
                        // Deleting a chat needs the Private API (server gate); only then
                        // do we let the row swipe to reveal Delete.
                        canDelete = state.privateApi,
                        onDelete = { viewModel.deleteConversation(convo) },
                        onClick = { viewModel.open(convo) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    convo: Conversation,
    title: String,
    subtitle: String,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    // How far the row slides left to reveal Delete. Keyed to the guid so a recycled
    // row for a different chat starts closed.
    val revealPx = with(LocalDensity.current) { 96.dp.toPx() }
    val offsetX = remember(convo.guid) { Animatable(0f) }

    Box(modifier = Modifier.fillMaxWidth()) {
        // Behind the row: the Delete action, uncovered as the row slides left. Tapping
        // it deletes (the row vanishes optimistically) — the swipe is its own confirm.
        if (canDelete) {
            Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                HapticText(
                    text = "Delete",
                    style = ChatType.body,
                    color = ChatColors.onSurface,
                    onClick = onDelete,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                // Opaque so the Delete action stays hidden under the row until swiped.
                .background(ChatColors.background)
                .then(
                    if (canDelete) {
                        Modifier.pointerInput(convo.guid) {
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { change, drag ->
                                    change.consume()
                                    scope.launch {
                                        offsetX.snapTo((offsetX.value + drag).coerceIn(-revealPx, 0f))
                                    }
                                },
                                // Settle open past the halfway point, else snap closed.
                                onDragEnd = {
                                    val target = if (offsetX.value < -revealPx / 2f) -revealPx else 0f
                                    scope.launch { offsetX.animateTo(target) }
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                )
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        // While open, a tap just closes the row rather than opening it.
                        if (offsetX.value < -1f) scope.launch { offsetX.animateTo(0f) } else onClick()
                    },
                )
                .padding(vertical = 14.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // Text-only unread marker, in keeping with the B&W style; the
                    // brighter subtitle below reinforces it.
                    text = (if (convo.unread) "• " else "") + title,
                    style = ChatType.body,
                    color = ChatColors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = listTime(LocalContext.current, convo.lastDate),
                    style = ChatType.hint,
                    color = ChatColors.onSurfaceDisabled,
                )
            }
            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = ChatType.meta,
                    color = if (convo.unread) ChatColors.onSurface else ChatColors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The list's timestamp, iMessage-style: a clock time for today ("3:14 PM"),
 * "Yesterday", the weekday within the last week ("Monday"), then a short date
 * ("7/1/26"). Absolute past a day — "18 hours ago" stops being parseable.
 */
private fun listTime(context: Context, ts: Long): String {
    if (ts <= 0L) return ""
    if (DateUtils.isToday(ts)) return DateUtils.formatDateTime(context, ts, DateUtils.FORMAT_SHOW_TIME)
    if (DateUtils.isToday(ts + DateUtils.DAY_IN_MILLIS)) return "Yesterday"
    if (System.currentTimeMillis() - ts < 7 * DateUtils.DAY_IN_MILLIS) {
        return DateUtils.formatDateTime(context, ts, DateUtils.FORMAT_SHOW_WEEKDAY)
    }
    return DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ts))
}
