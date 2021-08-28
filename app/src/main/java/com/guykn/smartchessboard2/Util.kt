package com.guykn.smartchessboard2

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.Observer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

fun <T1, T2> Flow<T1>.combinePairs(other: Flow<T2>): Flow<Pair<T1, T2>> {
    return this.combine(other) { val1, val2 ->
        Pair(val1, val2)
    }
}

fun String.countOccurrences(c: Char): Int = count { it == c }

inline fun <T : Any> ifLet(vararg elements: T?, closure: (List<T>) -> Unit) {
    if (elements.all { it != null }) {
        closure(elements.filterNotNull())
    }
}


private class CombinedLiveData<T, K, S>(
    source1: LiveData<T>,
    source2: LiveData<K>,
    private val combine: (data1: T?, data2: K?) -> S
) : MediatorLiveData<S>() {

    private var data1: T? = null
    private var data2: K? = null

    init {
        super.addSource(source1) {
            data1 = it
            value = combine(data1, data2)
        }
        super.addSource(source2) {
            data2 = it
            value = combine(data1, data2)
        }
    }

    override fun <S : Any?> addSource(source: LiveData<S>, onChanged: Observer<in S>) {
        throw UnsupportedOperationException()
    }

    override fun <T : Any?> removeSource(toRemove: LiveData<T>) {
        throw UnsupportedOperationException()
    }
}

fun <T, K, S> combineLiveData(
    source1: LiveData<T>,
    source2: LiveData<K>,
    combine: (data1: T?, data2: K?) -> S
): LiveData<S> = CombinedLiveData(source1, source2, combine)

fun <T, K> combineLiveDataPairs(
    source1: LiveData<T>,
    source2: LiveData<K>
    ): LiveData<Pair<T?, K?>> = combineLiveData(source1, source2){v1, v2->Pair(v1, v2)}