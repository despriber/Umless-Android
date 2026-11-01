package org.umless.coach

/**
 * Python difflib.SequenceMatcher(None, a, b, autojunk=False) 的复刻（只需要 matching blocks）。
 * 没有 junk / popular 元素时 find_longest_match 末尾的扩展循环不会生效，见 spec/metrics.md 第 2 节。
 */
object Difflib {
    /** 匹配块 [i, j, size]，按 (i, j) 升序；不含末尾的哨兵块 */
    fun matchingBlocks(a: IntArray, b: IntArray): List<IntArray> {
        val la = a.size
        val lb = b.size
        // b2j：元素 → 在 b 中出现的位置（升序）
        val b2jLists = HashMap<Int, MutableList<Int>>()
        for (j in 0 until lb) b2jLists.getOrPut(b[j]) { ArrayList() }.add(j)
        val b2j = HashMap<Int, IntArray>(b2jLists.size * 2)
        for ((k, v) in b2jLists) b2j[k] = v.toIntArray()

        val blocks = ArrayList<IntArray>()
        val queue = ArrayDeque<IntArray>()
        queue.addLast(intArrayOf(0, la, 0, lb))
        // 与 difflib 一样用 LIFO 处理子区间，最后排序
        val prev = IntArray(lb + 1)
        val cur = IntArray(lb + 1)
        while (queue.isNotEmpty()) {
            val (alo, ahi, blo, bhi) = queue.removeLast()
            val m = findLongestMatch(a, b2j, alo, ahi, blo, bhi, prev, cur)
            val i = m[0]
            val j = m[1]
            val k = m[2]
            if (k > 0) {
                blocks.add(m)
                if (alo < i && blo < j) queue.addLast(intArrayOf(alo, i, blo, j))
                if (i + k < ahi && j + k < bhi) queue.addLast(intArrayOf(i + k, ahi, j + k, bhi))
            }
        }
        blocks.sortWith(compareBy<IntArray>({ it[0] }, { it[1] }, { it[2] }))
        return blocks
    }

    /**
     * 最长公共子串；并列时取 a 中起点最早、其次 b 中起点最早的（i、j 升序遍历，只在严格更长时更新）。
     * prev / cur 为调用方提供的工作区（长度 lb + 1，全 0），下标 j + 1 表示以 b[j] 结尾的匹配长度。
     */
    private fun findLongestMatch(
        a: IntArray, b2j: Map<Int, IntArray>,
        alo: Int, ahi: Int, blo: Int, bhi: Int,
        prevArr: IntArray, curArr: IntArray,
    ): IntArray {
        var besti = alo
        var bestj = blo
        var bestsize = 0
        var prev = prevArr
        var cur = curArr
        var prevTouched = IntArray(0)
        var prevCount = 0
        var curTouched = IntArray(16)
        for (i in alo until ahi) {
            var curCount = 0
            val positions = b2j[a[i]]
            if (positions != null) {
                for (j in positions) {
                    if (j < blo) continue
                    if (j >= bhi) break
                    val k = prev[j] + 1
                    cur[j + 1] = k
                    if (curCount == curTouched.size) curTouched = curTouched.copyOf(curTouched.size * 2)
                    curTouched[curCount++] = j + 1
                    if (k > bestsize) {
                        besti = i - k + 1
                        bestj = j - k + 1
                        bestsize = k
                    }
                }
            }
            for (t in 0 until prevCount) prev[prevTouched[t]] = 0
            val tmp = prev; prev = cur; cur = tmp
            val tt = prevTouched; prevTouched = curTouched; curTouched = if (tt.isEmpty()) IntArray(16) else tt
            prevCount = curCount
        }
        for (t in 0 until prevCount) prev[prevTouched[t]] = 0
        return intArrayOf(besti, bestj, bestsize)
    }
}
