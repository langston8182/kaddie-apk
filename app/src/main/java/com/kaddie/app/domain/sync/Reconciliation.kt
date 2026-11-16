package com.kaddie.app.domain.sync

data class LocalItemRef(val id: String, val listId: String)

data class Removals(val listIds: List<String>, val itemIds: List<String>)

/**
 * Decides what was deleted on another device. The server only returns what still exists, so absence alone
 * proves nothing: a list or product created here (or rejected by the API) is absent from the server too.
 * Only something that the server **used to have** ([knownRemoteIds], recorded after each full read) and no
 * longer has was deleted elsewhere. Anything with a pending local write is kept: gestures made offline are
 * never lost.
 */
object Reconciliation {
    fun removals(
        knownRemoteIds: Set<String>,
        /** Ids of lists/items with a not-yet-sent local write (including the list of a pending item write). */
        pendingIds: Set<String>,
        localLists: List<String>,
        localItems: List<LocalItemRef>,
        remoteListIds: Set<String>,
        remoteItemIdsByList: Map<String, Set<String>>,
    ): Removals {
        val lists = localLists.filter { it in knownRemoteIds && it !in remoteListIds && it !in pendingIds }
        val removedLists = lists.toSet()
        val items = localItems.filter { item ->
            item.id in knownRemoteIds &&
                item.id !in pendingIds &&
                item.listId !in removedLists &&
                // Only compare against lists that were read: an unread list says nothing about its items.
                remoteItemIdsByList[item.listId]?.let { item.id !in it } == true
        }
        return Removals(lists, items.map { it.id })
    }
}
