package com.cesicorp.hstreamer

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app log utility that mirrors messages to both Logcat and a UI TextView.
 */
object AppLog {
    private var textView: TextView? = null
    private var scrollView: ScrollView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val buffer = StringBuilder()

    fun bind(tv: TextView, sv: ScrollView) {
        textView = tv
        scrollView = sv
        if (buffer.isNotEmpty()) {
            tv.text = buffer.toString()
            scrollToBottom()
        }
    }

    fun unbind() {
        textView = null
        scrollView = null
    }

    fun clear() {
        buffer.clear()
        handler.post {
            textView?.text = ""
        }
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        append("I", tag, msg)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        append("E", tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        append("W", tag, msg)
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        append("D", tag, msg)
    }

    private fun append(level: String, tag: String, msg: String) {
        val time = timeFormat.format(Date())
        val line = "$time [$level/$tag] $msg\n"
        buffer.append(line)

        handler.post {
            textView?.append(line)
            scrollToBottom()
        }
    }

    private fun scrollToBottom() {
        scrollView?.post {
            scrollView?.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}
