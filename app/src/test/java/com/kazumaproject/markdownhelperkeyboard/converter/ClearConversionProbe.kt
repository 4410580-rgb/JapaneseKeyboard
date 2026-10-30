package com.kazumaproject.markdownhelperkeyboard.converter

import android.content.Context
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files

/**
 * 透過キーボード（自分用）: 変換の様子を調べる道具（CI で環境変数 CLEAR_PROBE があるときだけ動く）。
 *
 * 読みごとに、上位の候補（コストと区切り）と、その読みの一部に当たる辞書の語（どの辞書から来たか・語のコスト）を
 * build/clear-probe.txt に書く。CLEAR_UT_OUT（UT 辞書の4ファイルの置き場所）があれば、UT 辞書あり・なしの両方を出す。
 */
class ClearConversionProbe {

    private val readings = listOf(
        "こうそくせんとう", "せんとう", "こうそく", "せんとうりょく", "たいけん", "へんかん", "よそくへんかん",
    )

    @Test
    fun probe() {
        assumeTrue("CLEAR_PROBE is not set", !System.getenv("CLEAR_PROBE").isNullOrBlank())
        val out = StringBuilder()
        val engine = TestEngineFactory.create()
        out.appendLine("===== UT 辞書なし =====")
        dump(engine, out)

        val utDir = System.getenv("CLEAR_UT_OUT")
        if (!utDir.isNullOrBlank() && File(utDir).isDirectory) {
            val base = Files.createTempDirectory("clear-probe").toFile()
            val dir = File(base, "system_user_dictionary").apply { mkdirs() }
            File(utDir).listFiles()?.filter { it.name.endsWith(".dat") }?.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
            val ctx = mock<Context>()
            whenever(ctx.filesDir).thenReturn(base)
            engine.loadSystemUserDictionaryFromFiles(ctx)
            out.appendLine()
            out.appendLine("===== UT 辞書あり =====")
            dump(engine, out)
        }
        val f = File("build/clear-probe.txt")
        f.parentFile?.mkdirs()
        f.writeText(out.toString())
        println(out)
    }

    private fun dump(engine: com.kazumaproject.markdownhelperkeyboard.converter.engine.KanaKanjiEngine, out: StringBuilder) {
        for (r in readings) {
            val t = engine.convertWithTrace(r)
            out.appendLine("--- $r")
            t.finalCandidates.take(12).forEach { c ->
                out.appendLine("  候補 ${c.candidate}  cost=${c.totalCost}  ${c.path.joinToString("|")}")
            }
            // 「せんとう」など、長めの部分の語がどの辞書から来ているか
            t.graphNodes
                .filter { it.yomiUsed.length >= 3 }
                .distinctBy { Triple(it.yomiUsed, it.tango, it.source) }
                .sortedWith(compareBy({ it.startPos }, { it.wordCost }))
                .take(40)
                .forEach { n ->
                    out.appendLine("  語 [${n.startPos},${n.endPos}) ${n.yomiUsed}→${n.tango}  word=${n.wordCost}  lid=${n.leftId} rid=${n.rightId}  ${n.source}/${n.event}")
                }
        }
    }
}
