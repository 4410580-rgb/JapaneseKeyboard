package com.kazumaproject.markdownhelperkeyboard.learning

import com.kazumaproject.markdownhelperkeyboard.ime_service.extensions.containsSymbolNumberOrEmoji

object LearningEligibilityPolicy {
    fun isEligible(
        input: String,
        output: String,
        allowJapaneseWithSymbolsAndNumbers: Boolean,
    ): Boolean {
        if (input.isBlank() || output.isBlank()) return false
        if (!output.containsSymbolNumberOrEmoji()) return true
        // 自分用: 「てん→、」「かっこ→「」」のような記号だけの候補も覚える
        return allowJapaneseWithSymbolsAndNumbers
    }
}
