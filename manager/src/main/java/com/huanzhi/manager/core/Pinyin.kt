package com.huanzhi.manager.core

import android.icu.text.Transliterator
import java.util.Locale

/**
 * 应用名 → 拼音，供选择列表做「首字母索引 + 拼音搜索」。
 *
 * 用系统自带的 ICU「Han-Latin」转写，而不是内置一张 GB2312 码表：
 *   · 码表只能覆盖 6763 个常用字，生僻字会掉进「#」，还占几十 KB；
 *   · ICU 带多音字表，常用词的读音基本都对（「重庆」→ chongqing）。
 * 代价是每次转写 0.1–1 ms，所以结果按名字 memoize —— 一个应用只算一次。
 */
object Pinyin {

    /** 复合转写器：汉字转拼音，再把声调符号（ē→e）去掉 */
    private val transliterator: Transliterator? by lazy {
        runCatching { Transliterator.getInstance("Han-Latin; Latin-ASCII") }.getOrNull()
    }

    private val cache = HashMap<String, Key>()

    data class Key(
        /** 全拼小写，去掉空格与标点：「微信」→ weixin */
        val full: String,
        /** 每个字的首字母：「微信」→ wx */
        val initials: String,
        /** 索引分组：A–Z，非字母开头归 # */
        val letter: Char,
    )

    @Synchronized
    fun keyOf(text: String): Key = cache.getOrPut(text) { build(text) }

    private fun build(text: String): Key {
        val raw = runCatching { transliterator?.transliterate(text) }.getOrNull() ?: text
        val syllables = raw.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(text.lowercase(Locale.ROOT)) }

        val full = syllables.joinToString("")
        val initials = syllables.mapNotNull { it.firstOrNull() }.joinToString("")
        val letter = initials.firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'
        return Key(full, initials, letter)
    }

    /**
     * 按拼音排序，末尾放「#」组（数字、符号、生僻到没有拼音的名字）。
     * 同字母内部再按全拼、最后按原名，保证顺序稳定。
     */
    fun sortByLabel(list: List<AppEntry>): List<AppEntry> = list.sortedWith(
        compareBy(
            { if (keyOf(it.label).letter == '#') 1 else 0 },
            { keyOf(it.label).full },
            { it.label },
        )
    )
}
