package com.kazumaproject.markdownhelperkeyboard.converter

import com.kazumaproject.Louds.LOUDS
import com.kazumaproject.Louds.with_term_id.ConverterWithTermId
import com.kazumaproject.Louds.with_term_id.LOUDSWithTermId
import com.kazumaproject.dictionary.TokenArray
import com.kazumaproject.markdownhelperkeyboard.converter.bitset.SuccinctBitVector
import com.kazumaproject.prefix.PrefixNode
import com.kazumaproject.prefix.PrefixTree
import com.kazumaproject.prefix.with_term_id.PrefixTreeWithTermId
import com.kazumaproject.toBitSet
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.ArrayDeque

/**
 * 透過キーボード（自分用）: Mozc UT 辞書（tools/clear-ut-dictionary/build_ut.py が作る TSV）から、
 * アプリの「システムユーザー辞書」と同じ形式の4ファイルを作る。CI でだけ動かす。
 *
 * - 環境変数 CLEAR_UT_TSV（入力）と CLEAR_UT_OUT（出力先）が無ければ何もしない
 * - 並び・形式は SystemUserDictionaryBuilder と同じ。ただし表記の LOUDS のノード位置は
 *   変換時に記録して引く（元の getNodeIndex は数百万語だと遅すぎるため）
 * - かなが混ざった表記（ひらがな＋カタカナ）も表記の木に入れる（元の作りだと見つからずカタカナになってしまう）
 */
class ClearUtDictionaryGenerator {

    private data class Entry(val yomi: String, val tango: String, val cost: Short, val posId: Short)

    @Test
    fun generate() {
        val input = System.getenv("CLEAR_UT_TSV")
        val outDir = System.getenv("CLEAR_UT_OUT")
        assumeTrue("CLEAR_UT_TSV is not set", !input.isNullOrBlank() && !outDir.isNullOrBlank())
        val out = File(outDir!!).apply { mkdirs() }

        // 読み → 表記の一覧（SystemUserDictionaryBuilder と同じく、読みの長さ → 文字列の順）
        val byYomi = java.util.TreeMap<String, MutableList<Entry>>(compareBy<String> { it.length }.thenBy { it })
        File(input!!).forEachLine { line ->
            val cols = line.split('\t')
            if (cols.size < 4) return@forEachLine
            val tango = Normalizer.normalize(cols[1], Normalizer.Form.NFC)
            val entry = Entry(cols[0], tango, cols[2].toInt().toShort(), cols[3].toInt().toShort())
            byYomi.getOrPut(entry.yomi) { ArrayList(2) }.add(entry)
        }
        val entryCount = byYomi.values.sumOf { it.size }
        println("entries=$entryCount yomi=${byYomi.size}")

        // 読みの木（term id 付き）
        val yomiTree = PrefixTreeWithTermId()
        byYomi.keys.forEach { yomiTree.insert(it) }
        val yomiLouds = ConverterWithTermId().convert(yomiTree.root).apply { convertListToBitSet() }

        // 表記の木（ひらがなだけ・カタカナだけの表記は入れない。読みから作れるため）
        val tangoTree = PrefixTree()
        byYomi.values.forEach { list ->
            list.forEach { e -> if (needsTangoNode(e)) tangoTree.insert(e.tango) }
        }
        val positions = IntArray(tangoTree.getNodeSize() + 2) { -1 }
        val tangoLouds = convertRecordingPositions(tangoTree.root, positions)
        println("yomiNodes=${yomiTree.getNodeSize()} tangoNodes=${tangoTree.getNodeSize()}")

        // 品詞の表（左右 ID の組 → 番号）
        val posIndex = LinkedHashMap<Pair<Short, Short>, Int>()
        byYomi.values.forEach { list -> list.forEach { e -> posIndex.getOrPut(e.posId to e.posId) { posIndex.size } } }

        // トークン列
        val posTableIndices = ShortArray(entryCount)
        val wordCosts = ShortArray(entryCount)
        val nodeIds = IntArray(entryCount)
        val bits = ArrayList<Boolean>(entryCount + byYomi.size)
        var i = 0
        byYomi.forEach { (yomi, list) ->
            bits.add(false)
            list.forEach { e ->
                bits.add(true)
                posTableIndices[i] = posIndex.getValue(e.posId to e.posId).toShort()
                wordCosts[i] = e.cost
                nodeIds[i] = when {
                    yomi == e.tango -> -2
                    e.tango.isHiraganaOnly() -> -2
                    e.tango.isKatakanaOnly() -> -1
                    else -> {
                        val node = tangoTree.find(e.tango)
                        val pos = node?.let { positions[it.id] } ?: -1
                        check(pos > 0) { "tango node not found: ${e.tango}" }
                        pos
                    }
                }
                i++
            }
        }

        write(File(out, "yomi_system_user_dictionary.dat")) { yomiLouds.writeExternalNotCompress(it) }
        write(File(out, "tango_system_user_dictionary.dat")) { tangoLouds.writeExternalNotCompress(it) }
        write(File(out, "token_system_user_dictionary.dat")) {
            it.writeObject(posTableIndices)
            it.writeObject(wordCosts)
            it.writeObject(nodeIds)
            it.writeObject(bits.toBitSet())
            it.flush()
        }
        write(File(out, "pos_table_system_user_dictionary.dat")) {
            val ordered = posIndex.entries.sortedBy { e -> e.value }.map { e -> e.key }
            it.writeObject(ordered.map { p -> p.first }.toShortArray())
            it.writeObject(ordered.map { p -> p.second }.toShortArray())
            it.flush()
        }
        write(File(out, "meta_system_user_dictionary.dat")) {
            it.writeInt(entryCount)
            it.writeLong(System.currentTimeMillis())
            it.flush()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        File(input).inputStream().buffered().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val hash = digest.digest().joinToString("") { b -> "%02x".format(b) }.take(16)
        File(out, "version.txt").writeText("$entryCount-$hash\n")

        verify(out, "たいけん", "大剣")
        verify(out, "たいけん", "体験")
    }

    /** 書いたファイルをアプリと同じ手順で読み戻し、読みから表記が引けるか確かめる */
    private fun verify(out: File, yomi: String, expected: String) {
        val tango = read(File(out, "tango_system_user_dictionary.dat")) { LOUDS().readExternalNotCompress(it) }
        val yomiLouds = read(File(out, "yomi_system_user_dictionary.dat")) { LOUDSWithTermId().readExternalNotCompress(it) }
        val tokens = TokenArray()
        read(File(out, "token_system_user_dictionary.dat")) { tokens.readExternal(it) }
        read(File(out, "pos_table_system_user_dictionary.dat")) { tokens.readPOSTable(it) }
        val lbsYomi = SuccinctBitVector(yomiLouds.LBS)
        val isLeaf = SuccinctBitVector(yomiLouds.isLeaf)
        val tokenBits = SuccinctBitVector(tokens.bitvector)
        val lbsTango = SuccinctBitVector(tango.LBS)
        val nodeIndex = yomiLouds.getNodeIndex(yomi, lbsYomi)
        val termId = yomiLouds.getTermId(nodeIndex, isLeaf)
        val found = mutableListOf<String>()
        tokens.forEachDictionaryByYomiTermId(termId, tokenBits) { _, _, nodeId ->
            found += when (nodeId) {
                -2 -> yomi
                -1 -> yomi
                else -> tango.getLetter(nodeId, lbsTango)
            }
        }
        println("$yomi -> $found")
        assertTrue("$expected not found for $yomi: $found", expected in found)
    }

    private fun needsTangoNode(e: Entry): Boolean =
        e.yomi != e.tango && !e.tango.isHiraganaOnly() && !e.tango.isKatakanaOnly()

    /** Converter と同じ幅優先で LOUDS を作り、各ノードの LBS 上の位置を記録する */
    private fun convertRecordingPositions(root: PrefixNode, positions: IntArray): LOUDS {
        val louds = LOUDS()
        val queue = ArrayDeque<PrefixNode>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.poll()!!
            if (node.hasChild()) {
                node.children.forEach { entry ->
                    val child = entry.value.second
                    queue.add(child)
                    positions[child.id] = louds.LBSTemp.size
                    louds.LBSTemp.add(true)
                    louds.labelsTemp.add(entry.key)
                    louds.isLeafTemp.add(child.isWord)
                }
            }
            louds.LBSTemp.add(false)
            louds.isLeafTemp.add(false)
        }
        louds.convertListToBitSet()
        return louds
    }

    private fun String.isHiraganaOnly(): Boolean = isNotEmpty() && all { it in 'ぁ'..'ゖ' || it == 'ー' }

    private fun String.isKatakanaOnly(): Boolean = isNotEmpty() && all { it in 'ァ'..'ヶ' || it == 'ー' }

    private fun write(file: File, block: (ObjectOutputStream) -> Unit) {
        ObjectOutputStream(BufferedOutputStream(FileOutputStream(file), 1 shl 20)).use(block)
    }

    private fun <T> read(file: File, block: (ObjectInputStream) -> T): T =
        ObjectInputStream(BufferedInputStream(FileInputStream(file), 1 shl 20)).use(block)
}
