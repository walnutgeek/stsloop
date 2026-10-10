package com.walnutgeek.stsloop.core

import kotlin.random.Random

/** Short random ids for Turns and Sessions: six lowercase hex digits. */
object Ids {
    fun next(random: Random = Random.Default): String =
        random.nextBits(24).toString(16).padStart(6, '0')
}
