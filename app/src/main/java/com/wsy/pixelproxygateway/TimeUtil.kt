package com.wsy.pixelproxygateway

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TimeUtil {
    private val iso = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
    }

    fun now(): String = format(System.currentTimeMillis())

    fun format(epochMillis: Long): String = iso.get()!!.format(Date(epochMillis))
}
