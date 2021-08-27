package com.guykn.smartchessboard2

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

fun <T1, T2> Flow<T1>.combinePairs(other: Flow<T2>): Flow<Pair<T1, T2>> {
    return this.combine(other) { val1, val2 ->
        Pair(val1, val2)
    }
}

fun String.countOccurrences(c: Char): Int=count{it==c}

inline fun <T: Any> ifLet(vararg elements: T?, closure: (List<T>) -> Unit) {
    if (elements.all { it != null }) {
        closure(elements.filterNotNull())
    }
}
