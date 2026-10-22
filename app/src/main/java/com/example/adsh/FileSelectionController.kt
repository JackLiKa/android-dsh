package com.example.adsh

class FileSelectionController<T> {
    private var pending: ((T?) -> Unit)? = null

    fun offer(callback: (T?) -> Unit) {
        val previous = pending
        pending = callback
        previous?.invoke(null)
    }

    fun complete(value: T?) {
        val callback = pending
        pending = null
        callback?.invoke(value)
    }

    fun cancel() = complete(null)
}
