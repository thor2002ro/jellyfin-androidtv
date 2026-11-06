package org.jellyfin.androidtv.test

import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class FaultingPlaybackHttpServer(private val media: File) : Closeable {
	private val server = ServerSocket(0)
	private val running = AtomicBoolean(true)
	private val getRequests = AtomicInteger()
	private val failNext = AtomicBoolean(false)
	private val injected = AtomicBoolean(false)
	private val worker = Thread({ serve() }, "playback-fault-server").apply { start() }

	val url: String get() = "http://127.0.0.1:${server.localPort}/silent-black-25s.mp4"
	val requestCount: Int get() = getRequests.get()
	val failureInjected: Boolean get() = injected.get()

	fun armFailure() {
		failNext.set(true)
	}

	private fun serve() {
		while (running.get()) runCatching { server.accept().use(::respond) }
	}

	private fun respond(socket: Socket) {
		socket.soTimeout = 5_000
		val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
		val request = reader.readLine() ?: return
		val headers = generateSequence(reader::readLine).takeWhile(String::isNotEmpty).toList()
		val method = request.substringBefore(' ')
		val output = socket.getOutputStream()
		if (method == "GET") getRequests.incrementAndGet()
		if (method == "GET" && failNext.compareAndSet(true, false)) {
			injected.set(true)
			output.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
			return
		}
		val rangeHeader = headers.firstOrNull { it.startsWith("Range:", ignoreCase = true) }
		val requestedStart = rangeHeader?.substringAfter("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
		val start = requestedStart.coerceIn(0, media.length() - 1)
		val end = media.length() - 1
		val length = if (method == "HEAD") media.length() else end - start + 1
		val status = if (rangeHeader == null) "200 OK" else "206 Partial Content"
		val responseHeaders = buildString {
			append("HTTP/1.1 $status\r\n")
			append("Content-Type: video/mp4\r\n")
			append("Accept-Ranges: bytes\r\n")
			append("Content-Length: $length\r\n")
			if (status.startsWith("206")) append("Content-Range: bytes $start-$end/${media.length()}\r\n")
			append("Connection: close\r\n\r\n")
		}
		output.write(responseHeaders.toByteArray())
		if (method != "HEAD") media.inputStream().use { input ->
			input.skip(start)
			input.copyTo(output, bufferSize = 8_192, limit = length)
		}
	}

	private fun java.io.InputStream.copyTo(output: java.io.OutputStream, bufferSize: Int, limit: Long) {
		val buffer = ByteArray(bufferSize)
		var remaining = limit
		while (remaining > 0) {
			val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
			if (read < 0) break
			output.write(buffer, 0, read)
			remaining -= read
		}
	}

	override fun close() {
		running.set(false)
		server.close()
		worker.join(2_000)
	}
}
