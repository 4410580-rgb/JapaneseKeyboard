package com.kazumaproject.core.domain.state

import com.kazumaproject.core.domain.key.Key

data class PressedKey(
    var key: Key,
    var pointer: Int,
    var initialX: Float,
    var initialY: Float,
    /** 透過キーボード: フリックと分かった時点で、指を離す前に確定済みか(離した時にもう一度入力しない) */
    var committed: Boolean = false,
)
