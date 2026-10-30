package com.huanzhi.manager.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 改写 resources.arsc 里记录的包名。
 *
 * 结构（ResTable_package）：
 *   ResChunk_header header;  // 8
 *   uint32_t        id;      // 4
 *   uint16_t        name[128]; // 256 字节 UTF-16，定长
 *
 * 定长字段，所以可以原地改写，不影响后面的偏移。
 * 改这个是为了让 `getIdentifier(name, type, getPackageName())`
 * 这类按包名找资源的调用在分身里也能命中。
 */
object ArscPatcher {

    private const val TYPE_TABLE = 0x0002
    private const val TYPE_PACKAGE = 0x0200
    private const val NAME_CHARS = 128

    fun patchPackageName(bytes: ByteArray, expectPkg: String, newPkg: String): Boolean {
        if (bytes.size < 16) return false
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if ((buf.getShort(0).toInt() and 0xffff) != TYPE_TABLE) return false

        var p = buf.getShort(2).toInt() and 0xffff   // 跳过 table header
        while (p + 12 <= bytes.size) {
            val type = buf.getShort(p).toInt() and 0xffff
            val size = buf.getInt(p + 4)
            if (size <= 0 || p + size > bytes.size) break

            if (type == TYPE_PACKAGE) {
                val nameOff = p + 12
                val current = readUtf16(buf, nameOff)
                if (current == expectPkg) {
                    writeUtf16(buf, nameOff, newPkg)
                    return true
                }
            }
            p += size
        }
        return false
    }

    private fun readUtf16(buf: ByteBuffer, off: Int): String {
        val sb = StringBuilder()
        for (i in 0 until NAME_CHARS) {
            val c = buf.getShort(off + i * 2).toInt() and 0xffff
            if (c == 0) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun writeUtf16(buf: ByteBuffer, off: Int, value: String) {
        for (i in 0 until NAME_CHARS) {
            buf.putShort(off + i * 2, 0)
        }
        value.forEachIndexed { i, ch ->
            if (i < NAME_CHARS) buf.putShort(off + i * 2, ch.code.toShort())
        }
    }
}
