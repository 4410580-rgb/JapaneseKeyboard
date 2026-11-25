package com.kazumaproject.markdownhelperkeyboard.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kazumaproject.markdownhelperkeyboard.learning.database.LearnEntity
import com.kazumaproject.markdownhelperkeyboard.user_dictionary.database.UserWord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PredictionDictionaryQueryTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun userDictionaryAppliesLimitAfterOrderingByConversionCost() = runBlocking {
        database.userWordDao().insertAll(
            listOf(
                UserWord(word = "low", reading = "かなa", posIndex = 0, posScore = 100),
                UserWord(word = "high", reading = "かなb", posIndex = 0, posScore = 900),
                UserWord(word = "middle", reading = "かなc", posIndex = 0, posScore = 300),
            )
        )

        val result = database.userWordDao().searchByReadingPrefixSuspend("かな", limit = 2)

        assertEquals(listOf("low", "middle"), result.map { it.word })
    }

    @Test
    fun learningDictionaryTreatsLowerScoresAsHigherPriorityBeforeLimit() = runBlocking {
        database.learnDao().insertAll(
            listOf(
                LearnEntity(input = "かなa", out = "low", score = 100),
                LearnEntity(input = "かなb", out = "high", score = 900),
                LearnEntity(input = "かなc", out = "middle", score = 300),
            )
        )

        val result = database.learnDao().predictiveSearchByInput(
            prefix = "かな",
            prefixUpperBound = "かな\uFFFF",
            limit = 2,
            minUsageCount = 1,
        )

        assertEquals(listOf("low", "middle"), result.map { it.out })
    }

    @Test
    fun learningDictionaryCanHideEntriesUsedOnlyOnce() = runBlocking {
        database.learnDao().insertAll(
            listOf(
                LearnEntity(input = "しん", out = "芯", score = 0, usageCount = 1),
                LearnEntity(input = "しんぶん", out = "新聞", score = 900, usageCount = 2),
            )
        )

        val result = database.learnDao().predictiveSearchByInput(
            prefix = "し",
            prefixUpperBound = "し\uFFFF",
            limit = 4,
            minUsageCount = 2,
        )

        assertEquals(listOf("新聞"), result.map { it.out })
    }

    @Test
    fun explicitChoiceOutranksRivalsAndPassiveAcceptDoesNotReinforce() = runBlocking {
        val dao = database.learnDao()
        dao.insertAll(listOf(LearnEntity(input = "しん", out = "芯", score = 100)))

        dao.upsertLearningEntries(listOf(LearnEntity(input = "しん", out = "新", score = 4000)), emptySet())
        dao.upsertLearningEntries(
            listOf(LearnEntity(input = "しん", out = "芯", score = 100)),
            setOf("しん" to "芯"),
        )

        val byOut = dao.findByInput("しん")!!.associate { it.out to it.score }
        assertEquals(100, byOut["芯"])
        assertEquals(99, byOut["新"])
    }

    @Test
    fun cleanupRemovesLongRarelyUsedEntriesAndRaisesScores() = runBlocking {
        val dao = database.learnDao()
        dao.insertAll(
            listOf(
                LearnEntity(input = "あとはちょうぶんのにゅうりょくが", out = "あとは長文の入力が", usageCount = 1),
                LearnEntity(input = "よろしくおねがいします", out = "よろしくお願いします", usageCount = 5),
                LearnEntity(input = "しん", out = "芯", score = 0, usageCount = 9),
            )
        )

        assertEquals(1, dao.deleteLongRarelyUsed(maxReading = 8, keepUsageCount = 3))
        dao.raiseScoresToFloor(2000)

        assertEquals(
            setOf("よろしくお願いします" to 3000, "芯" to 2000),
            dao.getAllSuspend().map { it.out to it.score }.toSet(),
        )
    }
}
