package com.example.core.model

sealed class OnlineLoadState<out T> {
    data object Loading : OnlineLoadState<Nothing>()
    data class Success<T>(val data: T) : OnlineLoadState<T>()
    data class Error(val message: String) : OnlineLoadState<Nothing>()
    data class Offline(val message: String) : OnlineLoadState<Nothing>()
}
