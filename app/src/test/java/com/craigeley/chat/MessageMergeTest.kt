package com.craigeley.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageMergeTest {
    private fun message(id: String) = ChatMessage(id, id, 100L, false, null)

    @Test fun incomingMessageDuringFetchSurvives() {
        val old = message("old")
        val incoming = message("incoming")
        assertEquals(listOf(old, incoming), mergeFetchedThread(listOf(old), listOf(old), listOf(old, incoming)))
    }

    @Test fun newerSocketReceiptWinsOverStaleFetch() {
        val old = message("old")
        val read = old.copy(dateRead = 200L)
        assertEquals(listOf(read), mergeFetchedThread(listOf(old), listOf(old), listOf(read)))
    }

    @Test fun freshRestReceiptUpdatesUnchangedCachedRow() {
        val old = message("old")
        val read = old.copy(dateRead = 200L)
        assertEquals(listOf(read), mergeFetchedThread(listOf(read), listOf(old), listOf(old)))
    }

    @Test fun optimisticSendIsReconciledByRestEcho() {
        val pending = message("temp-1")
        val echo = message("real").copy(tempGuid = pending.guid)
        assertEquals(listOf(echo), mergeFetchedThread(listOf(echo), emptyList(), listOf(pending)))
    }

    @Test fun failedOptimisticSendIsNotResurrected() {
        val pending = message("temp-1")
        assertEquals(emptyList<ChatMessage>(), mergeFetchedThread(listOf(pending), listOf(pending), emptyList()))
    }
}
