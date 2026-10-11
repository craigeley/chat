package com.craigeley.chat.api

import java.net.ServerSocket
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CatchUpTest {
    private fun withServer(count: Int, failOffset: Int? = null, check: (BlueBubblesApi, MutableList<Int>) -> Unit) {
        val offsets = mutableListOf<Int>()
        val server = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.net.SocketException) { break }
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    reader.readLine() // Request line.
                    var length = 0
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) {
                            length = header.substringAfter(':').trim().toInt()
                        }
                    }
                    val chars = CharArray(length)
                    var read = 0
                    while (read < length) {
                        val n = reader.read(chars, read, length - read)
                        check(n > 0) { "Incomplete request body" }
                        read += n
                    }
                    val body = JSONObject(String(chars))
            val offset = body.getInt("offset")
            offsets.add(offset)
            val data = JSONArray()
            for (i in offset until minOf(count, offset + body.getInt("limit"))) {
                data.put(JSONObject().put("guid", "message-$i").put("text", "test")
                    .put("dateCreated", 1000L + count - i).put("isFromMe", false)
                    .put("chats", JSONArray().put(JSONObject().put("guid", "room"))))
            }
            val code = if (offset == failOffset) 500 else 200
            val bytes = JSONObject().put("data", data).toString().toByteArray()
                    val output = it.getOutputStream()
                    output.write("HTTP/1.1 $code Test\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(bytes)
                    output.flush()
                }
            }
        }
        try { check(BlueBubblesApi("http://127.0.0.1:${server.localPort}", "test"), offsets) }
        finally { server.close(); worker.join(1000) }
    }

    @Test fun longGapFetchesEveryPageInChronologicalOrder() = withServer(250) { api, offsets ->
        val messages = api.messagesSince(1000)
        assertEquals(250, messages.size)
        assertEquals(listOf(0, 100, 200), offsets)
        assertEquals(messages.map { it.message.date }.sorted(), messages.map { it.message.date })
    }

    @Test fun exactPageMultipleChecksForAnotherPage() = withServer(200) { api, offsets ->
        assertEquals(200, api.messagesSince(1000).size)
        assertEquals(listOf(0, 100, 200), offsets)
    }

    @Test fun veryLongGapStopsAtPageCapKeepingNewest() = withServer(1000) { api, offsets ->
        val messages = api.messagesSince(1000)
        assertEquals(listOf(0, 100, 200), offsets)
        assertEquals(300, messages.size)
        assertEquals(2000L, messages.last().message.date) // newest kept
    }

    @Test fun laterPageFailureDoesNotReturnPartialGap() = withServer(250, 100) { api, _ ->
        assertThrows(ApiException::class.java) { api.messagesSince(1000) }
    }
}
