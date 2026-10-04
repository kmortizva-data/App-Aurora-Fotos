package com.aurorafotos.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {
    /** 3900000000 ns -> "3.9 s"; 500000000 -> "1/2 s"; 33333333 -> "1/30 s". */
    fun exposure(ns: Long): String {
        if (ns <= 0) return "max"
        val s = ns / 1e9
        return when {
            s >= 1.0 -> String.format(Locale.US, "%.1f s", s)
            else -> "1/" + Math.round(1.0 / s) + " s"
        }
    }

    fun seconds(ms: Long): String {
        val s = ms / 1000.0
        return if (s >= 60) String.format(Locale.US, "%d min %02d s", (s / 60).toInt(), (s % 60).toInt())
        else String.format(Locale.US, "%.1f s", s)
    }

    fun sessionStamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun frameIndex(i: Int): String = String.format(Locale.US, "%05d", i)
}
