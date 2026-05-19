package com.wsy.pixelproxygateway

object ProcessUtil {
    fun pidOf(process: Process?): Long {
        if (process == null) return -1
        var type: Class<*>? = process.javaClass
        while (type != null) {
            val pid = runCatching {
                val field = type.getDeclaredField("pid")
                field.isAccessible = true
                when (val value = field.get(process)) {
                    is Int -> value.toLong()
                    is Long -> value
                    else -> -1L
                }
            }.getOrDefault(-1L)
            if (pid > 0) return pid
            type = type.superclass
        }
        return -1
    }
}
