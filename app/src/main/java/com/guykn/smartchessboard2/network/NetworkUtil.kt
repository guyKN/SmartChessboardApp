package com.guykn.smartchessboard2.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.yield
import okhttp3.ResponseBody
import okio.Buffer
import java.nio.charset.StandardCharsets

const val TAG = "MA_NetworkUtil"
val ResponseBody.lines: Flow<String>
    get() = flow {
        val buffer = Buffer()
        while (!source().exhausted()) {
            yield()
            // todo: figure out if there's a way that doesn't limit the amount of bytes
            source().read(buffer, 65536)
            val data = buffer.readString(StandardCharsets.UTF_8)
            val lines = data.split("\n")
            for (line in lines) {
                if (line != "") {
                    emit(line)
                }
            }
        }
    }.flowOn(Dispatchers.IO)