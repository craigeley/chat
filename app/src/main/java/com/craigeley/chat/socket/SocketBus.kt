package com.craigeley.chat.socket

import com.craigeley.chat.IncomingMessage
import com.craigeley.chat.ReadStatusEvent
import com.craigeley.chat.TypingEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-wide bridge from [SocketService] (which holds the live connection and
 * runs even when the activity is dead) to the ViewModel (which collects this when
 * it's alive to update the list and open thread in real time).
 */
object SocketBus {
    val incoming = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    val typing = MutableSharedFlow<TypingEvent>(extraBufferCapacity = 32)
    val readStatus = MutableSharedFlow<ReadStatusEvent>(extraBufferCapacity = 32)

    /** Whether the live socket is currently connected. The ViewModel refreshes on
     *  a false → true edge (something may have happened in the gap) and shows an
     *  offline hint while it stays false. */
    val connected = MutableStateFlow(false)
}
