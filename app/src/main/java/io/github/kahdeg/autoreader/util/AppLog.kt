package io.github.kahdeg.autoreader.util

import android.util.Log

/**
 * Singleton logging service that prefixes all logs with [autoreader]
 */
object AppLog {
    private const val PREFIX = "[autoreader]"
    
    fun v(tag: String, message: String) {
        Log.v("$PREFIX $tag", message)
    }
    
    fun d(tag: String, message: String) {
        Log.d("$PREFIX $tag", message)
    }
    
    fun i(tag: String, message: String) {
        Log.i("$PREFIX $tag", message)
    }
    
    fun w(tag: String, message: String) {
        Log.w("$PREFIX $tag", message)
    }
    
    fun w(tag: String, message: String, throwable: Throwable) {
        Log.w("$PREFIX $tag", message, throwable)
    }
    
    fun e(tag: String, message: String) {
        Log.e("$PREFIX $tag", message)
    }
    
    fun e(tag: String, message: String, throwable: Throwable) {
        Log.e("$PREFIX $tag", message, throwable)
    }
}
