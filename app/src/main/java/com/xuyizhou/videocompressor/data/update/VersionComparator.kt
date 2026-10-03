/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.data.update

/**
 * 版本号比较。发布 tag 约定为 v&lt;versionName&gt;（如 v1.1），
 * 与 BuildConfig.VERSION_NAME 比较。
 */
object VersionComparator {

    /**
     * [latestTag] 是否比 [current] 新。
     * 任何解析失败都返回 false —— 宁可不提示更新，也不误弹窗。
     */
    fun isNewer(current: String, latestTag: String): Boolean {
        val a = current.trim()
        val b = latestTag.trim().removePrefix("v").removePrefix("V")
        if (a.isEmpty() || b.isEmpty()) return false
        return compare(a, b) < 0
    }

    private fun compare(a: String, b: String): Int {
        val sa = a.split('.')
        val sb = b.split('.')
        val n = maxOf(sa.size, sb.size)
        for (i in 0 until n) {
            // 缺失段按 0 处理，段多者新（1.0.1 > 1.0）
            val pa = sa.getOrNull(i) ?: "0"
            val pb = sb.getOrNull(i) ?: "0"
            val ia = pa.toIntOrNull()
            val ib = pb.toIntOrNull()
            if (ia != null && ib != null) {
                // 数值比较：1.10 > 1.9（字典序比较是经典错误）
                if (ia != ib) return ia.compareTo(ib)
            } else {
                // 任一段非纯数字（如 1.0-beta）时，该段起退回字符串比较
                val c = pa.compareTo(pb)
                if (c != 0) return c
            }
        }
        return 0
    }
}
