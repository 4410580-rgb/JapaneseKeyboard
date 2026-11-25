package com.kazumaproject.markdownhelperkeyboard.learning.database

object LearningScorePolicy {
    private const val REINFORCEMENT = 1500

    fun initial(candidateScore: Int, candidateIndex: Int): Int =
        (candidateScore - 500L * candidateIndex.coerceAtLeast(0))
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()

    fun phrase(fragmentScores: List<Int>): Int =
        fragmentScores.takeIf { it.isNotEmpty() }
            ?.map(Int::toLong)
            ?.average()
            ?.toLong()
            ?.coerceIn(0L, Int.MAX_VALUE.toLong())
            ?.toInt()
            ?: 3000

    /** 自分で選び直した語は、同じ読みの他の学習語より必ず前に出す（点数は小さいほど優先） */
    fun outrank(score: Int, bestRivalScore: Int?): Int =
        if (bestRivalScore == null || score < bestRivalScore) score
        else (bestRivalScore - 1).coerceAtLeast(0)

    fun reinforce(existingScore: Int, incomingScore: Int): Int =
        (minOf(existingScore, incomingScore).toLong() - REINFORCEMENT)
            .coerceAtLeast(0L)
            .toInt()
}
