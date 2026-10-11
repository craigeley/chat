package com.craigeley.chat

/** Merge a server echo by real guid or the optimistic guid it acknowledges. */
internal fun mergeMessage(list: List<ChatMessage>, message: ChatMessage): List<ChatMessage> {
    val index = list.indexOfFirst {
        it.guid == message.guid || (message.tempGuid != null && it.guid == message.tempGuid)
    }
    return if (index < 0) list + message else list.map {
        if (it.guid == message.guid || it.guid == message.tempGuid) message else it
    }.distinctBy { it.guid }
}

/** Preserve changes made during a fetch, without resurrecting rolled-back sends. */
internal fun mergeFetchedThread(
    fetched: List<ChatMessage>,
    baseline: List<ChatMessage>,
    current: List<ChatMessage>,
): List<ChatMessage> {
    val acknowledged = fetched.mapNotNull { it.tempGuid }.toSet()
    val changed = current.filter { it !in baseline && it.guid !in acknowledged }
    val removed = baseline.filter { old -> current.none { it.guid == old.guid } }.map { it.guid }.toSet()
    return changed.fold(fetched.filterNot { it.guid in removed }) { list, message -> mergeMessage(list, message) }
}
