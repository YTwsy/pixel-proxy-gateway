package com.wsy.pixelproxygateway

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class ProcessUtilTest {
    @Test
    fun pidOfReturnsMinusOneForNull() {
        assertEquals(-1, ProcessUtil.pidOf(null))
    }

    @Test
    fun pidOfReturnsMinusOneWhenPidFieldIsUnavailable() {
        assertEquals(-1, ProcessUtil.pidOf(NoPidProcess()))
    }

    private class NoPidProcess : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
    }
}
