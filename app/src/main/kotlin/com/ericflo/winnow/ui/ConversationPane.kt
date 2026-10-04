package com.ericflo.winnow.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.ericflo.winnow.ui.thread.ThreadViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The conversation open beside the list on a wide screen. Scoped to the activity, so it and its
 * ViewModels (attachments, a recording, an Undo still counting down) outlast rotation, a keyboard
 * being plugged in, the window narrowing, and trips to Settings or Details. Closes by itself when
 * the conversation is deleted.
 */
class ConversationPane(private val saved: SavedStateHandle, deletedThreads: Flow<Set<Long>>) : ViewModel() {
    data class Open(val threadId: Long, val recipients: String)

    private val _open = MutableStateFlow(saved.get<Long>(THREAD_ID)?.let { Open(it, saved.get<String>(RECIPIENTS).orEmpty()) })
    val open: StateFlow<Open?> = _open.asStateFlow()

    private var store: ViewModelStore? = null
    private var storeFor: Open? = null

    init {
        viewModelScope.launch {
            deletedThreads.collect { ids -> if (_open.value?.threadId in ids) close() }
        }
    }

    fun open(threadId: Long, recipients: String) = set(Open(threadId, recipients))

    private val _searchRequest = MutableStateFlow<ThreadViewModel.SearchRequest?>(null)
    /** For the conversation being opened: a message to show, handed to its ViewModel once it exists. */
    val searchRequest: StateFlow<ThreadViewModel.SearchRequest?> = _searchRequest.asStateFlow()

    /** Opens the conversation (keeping it as it is if it's already open) onto a particular message. */
    fun open(threadId: Long, recipients: String, request: ThreadViewModel.SearchRequest) {
        open(threadId, recipients)
        _searchRequest.value = request
    }

    fun searchRequestHandled() {
        _searchRequest.value = null
    }

    fun close() = set(null)

    private fun set(value: Open?) {
        _open.value = value
        saved[THREAD_ID] = value?.threadId
        saved[RECIPIENTS] = value?.recipients
        if (value == null) clearStore()
    }

    /** The ViewModels of [open]: the same ones for as long as it stays open, fresh ones for another conversation. */
    fun storeFor(open: Open): ViewModelStore {
        if (open != storeFor) clearStore()
        storeFor = open
        return store ?: ViewModelStore().also { store = it }
    }

    private fun clearStore() {
        store?.clear()
        store = null
        storeFor = null
    }

    override fun onCleared() = clearStore()

    private companion object {
        const val THREAD_ID = "pane.threadId"
        const val RECIPIENTS = "pane.recipients"
    }
}
