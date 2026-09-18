@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.craigeley.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craigeley.chat.Attachment
import com.craigeley.chat.ChatMessage
import com.craigeley.chat.ChatViewModel
import com.craigeley.chat.Contacts
import com.craigeley.chat.Conversation
import com.craigeley.chat.Reaction
import com.craigeley.chat.ReactionType
import com.craigeley.chat.ui.theme.ChatColors
import com.craigeley.chat.ui.theme.ChatType

/** One open conversation: messages oldest→newest, user on the right, others left. */
@Composable
fun ThreadScreen(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    val convo = state.open ?: return
    val listState = rememberLazyListState()

    // Which message's tapback picker is open (its guid), if any. Long-press opens
    // it; picking a reaction or tapping elsewhere closes it. Only when the server's
    // Private API is live — otherwise reacting can't be sent, so we don't offer it.
    var reactingTo by remember { mutableStateOf<String?>(null) }

    // The message the next send replies to (chosen from the long-press menu),
    // shown as a banner above the compose bar until sent or cancelled.
    var replyingTo by remember(convo.guid) { mutableStateOf<ChatMessage?>(null) }

    // Chat details (tap the title): participants, rename, add/remove, leave.
    // Groups only — a 1:1 has nothing to manage.
    var showDetails by remember(convo.guid) { mutableStateOf(false) }
    if (showDetails) {
        BackHandler { showDetails = false }
        ChatDetailsScreen(viewModel, onBack = { showDetails = false })
        return
    }

    // Full-screen image viewer (tap an inline image). Drawn as an opaque overlay
    // on top of the thread — NOT the details screen's early-return pattern — so the
    // LazyColumn (and its scroll position) never leaves composition: dismissing
    // lands exactly where you were, and the thread is already rendered underneath,
    // which is also what hides the delayed grayscale restore (see ColorMode).
    var viewingImage by remember(convo.guid) { mutableStateOf<Attachment?>(null) }

    // System photo picker (no permission needed; falls back to the document picker
    // where the dedicated picker isn't present). A pick sends straight away.
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.sendImage(uri)
    }

    // Which messages begin a same-speaker run (so only they get a name label).
    val labeled = remember(state.messages) {
        buildSet {
            state.messages.forEachIndexed { i, m ->
                val prev = state.messages.getOrNull(i - 1)
                if (prev == null || prev.fromMe != m.fromMe || prev.sender != m.sender) add(m.guid)
            }
        }
    }

    // The one message that shows a delivery receipt ("Delivered" / "Read 3:14 PM"):
    // your newest sent message, like iMessage — and only in a 1:1, where receipts
    // actually mean something (a group has no single read state).
    val receiptGuid = if (convo.isGroup) null else state.messages.lastOrNull { it.fromMe }?.guid

    // Guid → message, so a reply row can quote the message it points back at.
    val byGuid = remember(state.messages) { state.messages.associateBy { it.guid } }

    // The list is reverse-laid-out (newest pinned to the bottom), so opening a
    // thread shows the latest immediately — no scroll to watch. Only nudge to the
    // bottom for a *new* newest message, and only if the user is already down there
    // (don't yank them away while they're scrolled up reading history).
    val newestGuid = state.messages.lastOrNull()?.guid
    LaunchedEffect(newestGuid) {
        if (newestGuid != null && listState.firstVisibleItemIndex <= 2) {
            listState.animateScrollToItem(0)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp)) {
            ScreenHeader(
                title = state.contacts.title(convo),
                onBack = viewModel::closeThread,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                // Group details (members, rename, leave) live behind the title.
                onTitleClick = if (convo.isGroup) {
                    { showDetails = true }
                } else {
                    null
                },
            )

            if (state.messages.isEmpty() && state.threadLoading) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(text = "Loading…", style = ChatType.body, color = ChatColors.onSurfaceDisabled)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(top = 8.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Newest first so reverseLayout pins it to the bottom.
                    items(state.messages.asReversed(), key = { it.guid }) { message ->
                        MessageRow(
                            message,
                            convo,
                            state.contacts,
                            showLabel = message.guid in labeled,
                            showReceipt = message.guid == receiptGuid,
                            // The quoted original when this message is an inline reply.
                            replyQuote = message.threadOriginatorGuid?.let { g ->
                                byGuid[g]?.shortDescription ?: "an earlier message"
                            },
                            loadImage = viewModel::loadImage,
                            onImageTap = { viewingImage = it },
                            onOpenAttachment = viewModel::openAttachment,
                            canReact = state.privateApi,
                            pickerOpen = reactingTo == message.guid,
                            onLongPress = { if (state.privateApi) reactingTo = message.guid },
                            onReact = { type ->
                                viewModel.sendReaction(message, type)
                                reactingTo = null
                            },
                            onReply = {
                                replyingTo = message
                                reactingTo = null
                            },
                            onDismissPicker = { reactingTo = null },
                        )
                    }
                }
            }

            state.message?.let { msg ->
                Text(
                    text = msg,
                    style = ChatType.hint,
                    color = ChatColors.onSurfaceDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }

            if (state.typingChatGuid == convo.guid) {
                TypingIndicator()
            }

            // Reply banner: what the next send will reply to, with a cancel ×.
            replyingTo?.let { target ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Replying to ${target.shortDescription}",
                        style = ChatType.hint,
                        color = ChatColors.onSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    HapticText(
                        text = "×",
                        style = ChatType.body,
                        color = ChatColors.onSurfaceDim,
                        onClick = { replyingTo = null },
                    )
                }
            }

            ComposeBar(
                onSend = { text ->
                    viewModel.sendMessage(text, replyingTo?.guid)
                    replyingTo = null
                },
                onPickImage = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onTextChange = viewModel::onComposeTextChanged,
            )
        }

        // The open image covers everything (opaque, gesture-consuming); the
        // thread stays composed — and visible again the instant this leaves.
        viewingImage?.let { image ->
            ImageViewerScreen(image, viewModel::loadImage, onClose = { viewingImage = null })
        }
    }
}

/** A left-aligned animated ellipsis shown while the other party is typing — the
 *  bubble-less equivalent of iMessage's "…" indicator, just above the compose bar. */
@Composable
private fun TypingIndicator() {
    var dots by remember { mutableStateOf(1) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(400)
            dots = (dots % 3) + 1
        }
    }
    Text(
        // "•" is centred mid-line (unlike a baseline ".") and is in Public Sans, so
        // it reads as typing dots with no font fallback.
        text = "•".repeat(dots),
        style = ChatType.body,
        color = ChatColors.onSurfaceDim,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
        textAlign = TextAlign.Start,
    )
}

/** Bottom compose row: a growing text field and a Send action. Shared with the
 *  new-message screen. When [onPickImage] is supplied (the thread, not a brand-new
 *  chat) a leading "+" opens the photo picker. */
@Composable
fun ComposeBar(
    onSend: (String) -> Unit,
    onPickImage: (() -> Unit)? = null,
    onTextChange: ((String) -> Unit)? = null,
    showTopDivider: Boolean = true,
) {
    var input by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth()) {
        // Suppressed when the caller already draws a divider right above us (the
        // new-message screen's "To" line) — otherwise it reads as a double line.
        if (showTopDivider) HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (onPickImage != null) {
                HapticText(
                    text = "+",
                    style = ChatType.body,
                    color = ChatColors.onSurfaceDisabled,
                    onClick = onPickImage,
                )
                Spacer(modifier = Modifier.width(16.dp))
            }
            Box(modifier = Modifier.weight(1f)) {
                if (input.isEmpty()) {
                    Text(text = "Message", style = ChatType.body, color = ChatColors.onSurfaceDisabled)
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it; onTextChange?.invoke(it) },
                    textStyle = ChatType.body.copy(color = ChatColors.onSurface),
                    cursorBrush = SolidColor(ChatColors.onSurface),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        autoCorrectEnabled = true,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            HapticText(
                text = "Send",
                style = ChatType.body,
                color = if (input.isBlank()) ChatColors.onSurfaceDisabled else ChatColors.onSurface,
                onClick = {
                    if (input.isNotBlank()) {
                        onSend(input)
                        input = ""
                    }
                },
            )
        }
    }
}

/** Fraction of width a single turn may span. No bubbles signal who's talking, so
 *  capping the width leaves an empty gutter on the opposite side as the cue. */
private const val MESSAGE_MAX_WIDTH = 0.8f

/** A dim sender label (only when needed), then the text — no bubbles, just a
 *  width-capped column hugging its side. */
@Composable
private fun MessageRow(
    message: ChatMessage,
    convo: Conversation,
    contacts: Contacts,
    showLabel: Boolean,
    showReceipt: Boolean,
    replyQuote: String?,
    loadImage: suspend (Attachment) -> ImageBitmap?,
    onImageTap: (Attachment) -> Unit,
    onOpenAttachment: (Attachment) -> Unit,
    canReact: Boolean,
    pickerOpen: Boolean,
    onLongPress: () -> Unit,
    onReact: (ReactionType) -> Unit,
    onReply: () -> Unit,
    onDismissPicker: () -> Unit,
) {
    // A group-system row (rename, member change) is an event line, not a turn —
    // centered and dim, with no label, gutter, or tapback affordances.
    if (message.isGroupEvent) {
        GroupEventRow(message, contacts)
        return
    }
    // Name labels on both sides — "You" for your turns, the sender's name for
    // incoming (falling back to the 1:1 counterpart when a message has no handle) —
    // but only on the first message of a same-speaker run.
    val label = when {
        !showLabel -> null
        message.fromMe -> "You"
        message.sender != null -> contacts.sender(message.sender)
        convo.participants.size == 1 -> contacts.sender(convo.participants[0])
        else -> null
    }
    val context = LocalContext.current
    // The name label sits above the turn (not inside the content column) so the
    // gutter reaction lines up with the message's first line, not the label.
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.fromMe) Alignment.End else Alignment.Start,
    ) {
        if (label != null) {
            Text(text = label, style = ChatType.hint, color = ChatColors.onSurfaceDisabled)
            Spacer(modifier = Modifier.height(4.dp))
        }
        // An inline reply points back at what it answers: a dim quote line above
        // the turn, hugging the same side.
        if (replyQuote != null) {
            Text(
                text = "↳ $replyQuote",
                style = ChatType.hint,
                color = ChatColors.onSurfaceDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(MESSAGE_MAX_WIDTH),
                textAlign = if (message.fromMe) TextAlign.End else TextAlign.Start,
            )
            Spacer(modifier = Modifier.height(2.dp))
        }
        // The long-press menu — six tapbacks, Reply, Copy — sits above the turn at
        // the full thread width (not inside the 80% content column: on the LP3 that
        // column is ~237dp, too narrow for eight items, and the last label used to
        // wrap mid-word as "Repl / y").
        if (pickerOpen) {
            ReactionPicker(
                selected = message.reactions.firstOrNull { it.fromMe }?.type,
                fromMe = message.fromMe,
                onReact = onReact,
                onReply = onReply,
                onCopy = message.bodyText?.let { body ->
                    { copyToClipboard(context, body); onDismissPicker() }
                },
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        // Content is width-capped; the leftover gutter on the opposite side carries
        // any tapbacks (`<- ♥` / `♥ ->`), pointing back at the turn. 0.8/0.2 weights
        // keep the same cap whether or not there's a reaction.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (message.fromMe) ReactionGutter(message, Modifier.weight(1f - MESSAGE_MAX_WIDTH))
            MessageContent(message, loadImage, onImageTap, onOpenAttachment, canReact, pickerOpen, onLongPress, onDismissPicker, Modifier.weight(MESSAGE_MAX_WIDTH))
            if (!message.fromMe) ReactionGutter(message, Modifier.weight(1f - MESSAGE_MAX_WIDTH))
        }
        // "Not delivered" on any sent message the Mac later failed to deliver
        // (`error` set on the echo or a message-send-error event) — the failure the
        // app used to swallow. Full white: this line matters.
        if (message.fromMe && message.error != 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = "Not delivered", style = ChatType.hint, color = ChatColors.onSurface)
        } else if (showReceipt) {
            // The delivery receipt under your newest sent message — nothing until
            // the server reports it delivered, then "Read <when>" once read. Live
            // updates arrive as updated-message socket events through the merge.
            receiptText(message)?.let { line ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = line, style = ChatType.hint, color = ChatColors.onSurfaceDisabled)
            }
        }
    }
}

/** A group event ("Liz named the conversation “X”") as a centered dim line —
 *  the row form for messages that are system actions rather than speech. */
@Composable
private fun GroupEventRow(message: ChatMessage, contacts: Contacts) {
    val actor = when {
        message.fromMe -> "You"
        message.sender != null -> contacts.sender(message.sender)
        else -> "Someone"
    }
    val line = message.groupEventText(actor) ?: return
    Text(
        text = line,
        style = ChatType.hint,
        color = ChatColors.onSurfaceDim,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    )
}

/** The receipt line for a sent message: "Read 3:14 PM" (or "Read Yesterday" for
 *  older) once read, else "Delivered" once delivered, else nothing. */
@Composable
private fun receiptText(message: ChatMessage): String? {
    val context = LocalContext.current
    return when {
        message.dateRead > 0 -> "Read " + readTime(context, message.dateRead)
        message.dateDelivered > 0 -> "Delivered"
        else -> null
    }
}

private fun readTime(context: Context, ts: Long): String =
    if (DateUtils.isToday(ts)) {
        DateUtils.formatDateTime(context, ts, DateUtils.FORMAT_SHOW_TIME)
    } else {
        DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()
    }

/** The gutter cell beside a turn — its tapbacks (if any) hugging the message edge
 *  at the top, so they read as belonging to the first line. */
@Composable
private fun ReactionGutter(message: ChatMessage, modifier: Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = if (message.fromMe) Alignment.TopEnd else Alignment.TopStart,
    ) {
        if (message.reactions.isNotEmpty()) {
            GutterReactions(message.reactions, message.fromMe, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

/** The message itself: inline images, tappable file rows, then the text — no
 *  bubbles, just a column hugging its side. Long-press opens the tapback picker
 *  (drawn by [MessageRow] above the turn); a tap while it's open dismisses it. */
@Composable
private fun MessageContent(
    message: ChatMessage,
    loadImage: suspend (Attachment) -> ImageBitmap?,
    onImageTap: (Attachment) -> Unit,
    onOpenAttachment: (Attachment) -> Unit,
    canReact: Boolean,
    pickerOpen: Boolean,
    onLongPress: () -> Unit,
    onDismissPicker: () -> Unit,
    modifier: Modifier,
) {
    val align = if (message.fromMe) Alignment.End else Alignment.Start
    val textAlign = if (message.fromMe) TextAlign.End else TextAlign.Start
    val body = message.bodyText
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier.combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = canReact,
            onClick = { if (pickerOpen) onDismissPicker() },
            onLongClick = onLongPress,
        ),
        horizontalAlignment = align,
    ) {
        message.images.forEach { image ->
            AttachmentImage(
                attachment = image,
                load = loadImage,
                // A tap while the tapback picker is open dismisses it (matching a
                // tap anywhere else on the turn); otherwise it opens the viewer.
                onTap = { if (pickerOpen) onDismissPicker() else onImageTap(image) },
                // The image sits on top of the column's combinedClickable, so
                // re-offer the long-press here or images couldn't be reacted to.
                onLongPress = if (canReact) onLongPress else null,
            )
            Spacer(modifier = Modifier.height(if (body != null) 6.dp else 4.dp))
        }
        message.files.forEach { file ->
            AttachmentFile(file, textAlign) { onOpenAttachment(file) }
            Spacer(modifier = Modifier.height(if (body != null) 6.dp else 4.dp))
        }
        if (body != null) {
            Text(
                text = linkify(body),
                style = ChatType.body,
                color = ChatColors.onSurface,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Matches bare http/https URLs in message text so they can be made tappable. */
private val URL_REGEX = Regex("""https?://[^\s]+""")

/**
 * Turns any http/https URLs in [text] into tappable links (underlined, opened by
 * the platform's default handler — a browser), leaving the rest as plain text.
 * A message with no URL just renders verbatim.
 */
private fun linkify(text: String): AnnotatedString {
    val matches = URL_REGEX.findAll(text).toList()
    if (matches.isEmpty()) return AnnotatedString(text)
    val linkStyle = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        var last = 0
        for (m in matches) {
            if (m.range.first > last) append(text.substring(last, m.range.first))
            withLink(LinkAnnotation.Url(m.value, linkStyle)) { append(m.value) }
            last = m.range.last + 1
        }
        if (last < text.length) append(text.substring(last))
    }
}

/** The long-press menu: the six tapbacks as drawn glyphs, then Reply and Copy.
 *  The user's current tapback (if any) shows bright so re-tapping it reads as
 *  "remove". Glyphs and the two labels are one line at the LP3's thread width;
 *  the labels never break mid-word — if the row ever can't fit, they wrap to a
 *  second line as a unit. [onCopy] is null for a message with no text body. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReactionPicker(
    selected: ReactionType?,
    fromMe: Boolean,
    onReact: (ReactionType) -> Unit,
    onReply: () -> Unit,
    onCopy: (() -> Unit)?,
) {
    val haptics = LocalHapticFeedback.current
    val gap = Arrangement.spacedBy(PICKER_GAP, if (fromMe) Alignment.End else Alignment.Start)
    FlowRow(
        horizontalArrangement = gap,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        ReactionType.entries.forEach { type ->
            Box(
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onReact(type)
                },
            ) {
                TapbackGlyph(
                    type = type,
                    color = if (type == selected) ChatColors.onSurface else ChatColors.onSurfaceDim,
                    size = PICKER_GLYPH,
                )
            }
        }
        // Reply (a Private-API send) and Copy ride the same menu, as one unit.
        Row(horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) {
            HapticText(
                text = "Reply",
                style = ChatType.hint,
                color = ChatColors.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                onClick = onReply,
            )
            if (onCopy != null) {
                HapticText(
                    text = "Copy",
                    style = ChatType.hint,
                    color = ChatColors.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    onClick = onCopy,
                )
            }
        }
    }
}

private val PICKER_GLYPH = 18.dp
private val PICKER_GAP = 12.dp

/** Puts [text] on the system clipboard as plain text (Android 13+ shows its own
 *  "Copied" confirmation). */
private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("message", text))
}

/** One inline image: loads (download + cache + decode) off-thread via [load],
 *  showing a dim placeholder until the bitmap is ready. Height-capped so a tall
 *  photo can't swallow the thread; width fills the message column. Tapping it
 *  opens the full-screen viewer ([onTap]); long-press still reaches the tapback
 *  picker via [onLongPress] (the image's own gesture handler would otherwise
 *  swallow it). */
@Composable
private fun AttachmentImage(
    attachment: Attachment,
    load: suspend (Attachment) -> ImageBitmap?,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, attachment.guid) {
        value = load(attachment)
    }
    val image = bitmap
    if (image != null) {
        val haptics = LocalHapticFeedback.current
        Image(
            bitmap = image,
            contentDescription = attachment.transferName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onTap()
                    },
                    onLongClick = onLongPress,
                ),
        )
    } else {
        Text(text = "[Image]", style = ChatType.hint, color = ChatColors.onSurfaceDisabled)
    }
}

/** A non-image attachment as a tappable, underlined "Type · filename" row —
 *  tapping downloads it and hands off to an external app. */
@Composable
private fun AttachmentFile(attachment: Attachment, textAlign: TextAlign, onOpen: () -> Unit) {
    HapticText(
        text = attachment.fileLabel,
        style = ChatType.body,
        color = ChatColors.onSurface,
        underline = true,
        textAlign = textAlign,
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
    )
}
