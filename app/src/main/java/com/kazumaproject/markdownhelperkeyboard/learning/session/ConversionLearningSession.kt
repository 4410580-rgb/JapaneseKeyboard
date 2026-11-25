package com.kazumaproject.markdownhelperkeyboard.learning.session

import com.kazumaproject.markdownhelperkeyboard.learning.database.LearnEntity
import com.kazumaproject.markdownhelperkeyboard.learning.database.LearningScorePolicy

data class LearningFragment(
    val reading: String,
    val output: String,
    val candidateScore: Int,
    val candidateIndex: Int,
    val leftId: Short? = null,
    val rightId: Short? = null,
    val explicitlySelected: Boolean = false,
)

/** Collects every committed fragment until the original reading has been fully committed. */
class ConversionLearningSession {
    private var originalReading: String? = null
    private val fragments = mutableListOf<LearningFragment>()

    val isActive: Boolean
        get() = originalReading != null

    /** 透過キーボード: 直前に finish した入力全体の(読み, 表記)。続けて確定した2語をつないで覚えるのに使う */
    var lastComplete: Pair<String, String>? = null
        private set

    /**
     * 透過キーボード: 直前の finish が返した物のうち、先頭候補をそのまま確定しただけの物の (読み, 表記)。
     * これらは既に覚えていても点数を強めない（先頭に出る → 確定 → さらに強くなる、の輪を切る）
     */
    var lastPassive: Set<Pair<String, String>> = emptySet()
        private set

    fun beginIfNeeded(reading: String) {
        if (originalReading == null && reading.isNotEmpty()) {
            originalReading = reading
        }
    }

    fun record(fragment: LearningFragment) {
        if (originalReading == null || fragment.reading.isEmpty() || fragment.output.isEmpty()) return
        fragments += fragment
    }

    fun finish(
        learnFirstCandidate: Boolean,
        timestamp: Long = System.currentTimeMillis(),
    ): List<LearnEntity> {
        val reading = originalReading
        val recorded = fragments.toList()
        cancel()
        lastComplete = null
        lastPassive = emptySet()
        if (reading.isNullOrEmpty() || recorded.isEmpty()) return emptyList()
        lastComplete = reading to recorded.joinToString(separator = "") { it.output }

        fun chosen(parts: List<LearningFragment>) =
            parts.any { it.explicitlySelected || it.candidateIndex != 0 }
        // 選び直していない確定は短い読みだけ覚える（打った長文が丸ごと予測に出るのを防ぐ）
        fun learnable(parts: List<LearningFragment>, readingLength: Int) =
            chosen(parts) || (learnFirstCandidate && readingLength <= MAX_UNCHOSEN_READING)

        val passive = mutableSetOf<Pair<String, String>>()
        fun LearnEntity.tracked(parts: List<LearningFragment>): LearnEntity {
            if (!chosen(parts)) passive += input to out
            return this
        }

        val segmentEntries = recorded
            .filter { learnable(listOf(it), it.reading.length) }
            .map { fragment ->
                fragment.toEntity(timestamp = timestamp, isPhrase = false).tracked(listOf(fragment))
            }
        val cumulativePhraseEntries = recorded.indices.drop(1).mapNotNull { lastIndex ->
            val phraseFragments = recorded.take(lastIndex + 1)
            if (!learnable(phraseFragments, phraseFragments.sumOf { it.reading.length })) {
                return@mapNotNull null
            }
            LearnEntity(
                input = phraseFragments.joinToString(separator = "") { it.reading },
                out = phraseFragments.joinToString(separator = "") { it.output },
                score = phraseScore(phraseFragments),
                leftId = phraseFragments.first().leftId,
                rightId = phraseFragments.last().rightId,
                usageCount = 1,
                lastUsedAt = timestamp,
                isPhrase = true,
            ).tracked(phraseFragments)
        }
        val completeOutput = recorded.joinToString(separator = "") { it.output }
        val completeEntry = if (!learnable(recorded, reading.length)) null else LearnEntity(
            input = reading,
            out = completeOutput,
            score = phraseScore(recorded),
            leftId = recorded.firstOrNull()?.leftId,
            rightId = recorded.lastOrNull()?.rightId,
            usageCount = 1,
            lastUsedAt = timestamp,
            isPhrase = recorded.size > 1 || reading != recorded.first().reading,
        ).tracked(recorded)

        // 同じ (読み, 表記) が「選んだ物」と「そのままの物」の両方から出たら、選んだ方として扱う
        val entries = (segmentEntries + cumulativePhraseEntries + listOfNotNull(completeEntry))
        val chosenKeys = buildSet {
            recorded.filter { chosen(listOf(it)) }.forEach { add(it.reading to it.output) }
        }
        lastPassive = passive - chosenKeys
        return entries.distinctBy { it.input to it.out }
    }

    fun cancel() {
        originalReading = null
        fragments.clear()
    }

    private fun LearningFragment.toEntity(timestamp: Long, isPhrase: Boolean) = LearnEntity(
        input = reading,
        out = output,
        score = LearningScorePolicy.initial(candidateScore, candidateIndex),
        leftId = leftId,
        rightId = rightId,
        usageCount = 1,
        lastUsedAt = timestamp,
        isPhrase = isPhrase,
    )

    companion object {
        /** 選び直していない確定を覚える読みの長さの上限 */
        const val MAX_UNCHOSEN_READING = 8
    }

    private fun phraseScore(fragments: List<LearningFragment>): Int =
        LearningScorePolicy.phrase(
            fragments.map {
                LearningScorePolicy.initial(it.candidateScore, it.candidateIndex)
            }
        )
}
