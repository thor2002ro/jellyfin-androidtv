package org.jellyfin.androidtv.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class MemoryLogBufferTests : StringSpec({
	"keeps the newest bytes in order across wraparound" {
		val buffer = MemoryLogBuffer(8)
		buffer.dump() shouldBe ""
		buffer.append("abc")
		buffer.append("def")
		buffer.dump() shouldBe "abcdef"
		buffer.append("ghij")
		buffer.dump() shouldBe "cdefghij"
		buffer.dump() shouldBe "cdefghij"
	}

	"an oversized message replaces the old contents with its tail" {
		val buffer = MemoryLogBuffer(4)
		buffer.append("old")
		buffer.append("123456")
		buffer.dump() shouldBe "3456"
	}

	"drops an incomplete UTF-8 character at the retention boundary" {
		val buffer = MemoryLogBuffer(4)
		buffer.append("éabc")
		buffer.dump() shouldBe "abc"
	}

	"concurrent writers do not corrupt retained messages" {
		val buffer = MemoryLogBuffer(4000)
		val writers = List(4) {
			Thread { repeat(100) { buffer.append("abc\n") } }.apply { start() }
		}
		writers.forEach(Thread::join)
		buffer.dump() shouldBe "abc\n".repeat(400)
	}
})
