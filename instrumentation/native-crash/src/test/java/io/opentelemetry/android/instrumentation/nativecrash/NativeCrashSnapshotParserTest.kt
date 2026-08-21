/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.nativecrash

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

class NativeCrashSnapshotParserTest {
    @Test
    fun `uses the contract checksum constants`() {
        assertThat(
            NativeCrashSnapshotParser.checksum(ByteArray(NativeCrashSnapshotLayout.RECORD_SIZE)),
        ).isEqualTo(0x06260155L)
    }

    @Test
    fun `parses the complete contract`() {
        val snapshot = NativeCrashSnapshotParser.parse(SnapshotBuilder().build(), crashRecord)

        assertThat(snapshot).isNotNull()
        assertThat(snapshot!!.architecture).isEqualTo(NativeCrashArchitecture.ARM64)
        assertThat(snapshot.programCounter).isEqualTo(0x1120UL)
        assertThat(snapshot.stackPointer).isEqualTo(STACK_START.toULong())
        assertThat(snapshot.modules)
            .containsExactly(
                NativeCrashModule(
                    loadBias = 0x1000UL,
                    executableStart = 0x1100UL,
                    executableEnd = 0x2000UL,
                    name = "libapp.so",
                    buildId = "0123fe",
                ),
            )
        assertThat(snapshot.stack).containsExactly(1, 2, 3, 4)
    }

    @Test
    fun `zero extends every 32 bit address`() {
        val snapshot =
            NativeCrashSnapshotParser.parse(
                SnapshotBuilder(NativeCrashArchitecture.X86)
                    .addresses(
                        programCounter = 0xffff_ffff_f000_1120UL,
                        stackPointer = 0xffff_ffff_7000_0000UL,
                        framePointer = 0xffff_ffff_7000_0010UL,
                        moduleLoadBias = 0xffff_ffff_f000_0000UL,
                        moduleStart = 0xffff_ffff_f000_1000UL,
                        moduleEnd = 0xffff_ffff_f000_2000UL,
                    ).build(),
                crashRecord,
            )

        assertThat(snapshot).isNotNull()
        assertThat(snapshot!!.programCounter).isEqualTo(0xf000_1120UL)
        assertThat(snapshot.stackPointer).isEqualTo(0x7000_0000UL)
        assertThat(snapshot.framePointer).isEqualTo(0x7000_0010UL)
        assertThat(snapshot.modules.single().loadBias).isEqualTo(0xf000_0000UL)
        assertThat(snapshot.modules.single().executableStart).isEqualTo(0xf000_1000UL)
        assertThat(snapshot.modules.single().executableEnd).isEqualTo(0xf000_2000UL)
    }

    @TestFactory
    fun `rejects structurally invalid snapshots`() =
        listOf<Pair<String, (ByteBuffer) -> Unit>>(
            "magic" to { buffer: ByteBuffer -> buffer.put(0, 'X'.code.toByte()) },
            "version" to { buffer -> buffer.putInt(NativeCrashSnapshotLayout.VERSION_OFFSET, 2) },
            "architecture" to { buffer -> buffer.putInt(NativeCrashSnapshotLayout.ARCHITECTURE_OFFSET, 99) },
            "record size" to { buffer -> buffer.putInt(NativeCrashSnapshotLayout.RECORD_SIZE_OFFSET, 1) },
            "program counter" to { buffer -> buffer.putLong(NativeCrashSnapshotLayout.PROGRAM_COUNTER_OFFSET, 0) },
            "stack pointer" to { buffer -> buffer.putLong(NativeCrashSnapshotLayout.STACK_POINTER_OFFSET, 0) },
            "stack start" to { buffer -> buffer.putLong(NativeCrashSnapshotLayout.STACK_START_OFFSET, STACK_START + 8) },
            "stack alignment" to { buffer ->
                buffer.putLong(NativeCrashSnapshotLayout.STACK_POINTER_OFFSET, STACK_START + 1)
                buffer.putLong(NativeCrashSnapshotLayout.STACK_START_OFFSET, STACK_START + 1)
            },
            "zero modules" to { buffer -> buffer.putInt(NativeCrashSnapshotLayout.MODULE_COUNT_OFFSET, 0) },
            "too many modules" to { buffer -> buffer.putInt(NativeCrashSnapshotLayout.MODULE_COUNT_OFFSET, 129) },
            "oversized stack" to { buffer ->
                buffer.putInt(NativeCrashSnapshotLayout.STACK_SIZE_OFFSET, NativeCrashSnapshotLayout.STACK_CAPACITY + 1)
            },
            "reserved header" to { buffer -> buffer.putInt(NativeCrashSnapshotLayout.RESERVED_OFFSET, 1) },
        ).map { (name, mutation) ->
            dynamicTest(name) {
                assertThat(NativeCrashSnapshotParser.parse(SnapshotBuilder().build(mutation), crashRecord)).isNull()
            }
        }

    @TestFactory
    fun `skips malformed module entries`() =
        listOf<Pair<String, (ByteBuffer) -> Unit>>(
            "load bias" to { buffer: ByteBuffer -> buffer.putLong(MODULE_OFFSET, 0x1200) },
            "inverted range" to { buffer -> buffer.putLong(MODULE_OFFSET + 16, 0x1100) },
            "missing terminator" to { buffer -> buffer.fill(MODULE_OFFSET + 24, 64, 'a'.code.toByte()) },
            "nonzero name padding" to { buffer -> buffer.put(MODULE_OFFSET + 24 + 20, 1.toByte()) },
            "malformed utf8" to { buffer ->
                buffer.put(MODULE_OFFSET + 24, 0xc3.toByte()).put(MODULE_OFFSET + 25, 0x28.toByte())
            },
            "control character" to { buffer -> buffer.put(MODULE_OFFSET + 24 + 3, '\n'.code.toByte()) },
            "build id length" to { buffer -> buffer.putInt(MODULE_OFFSET + 88, 33) },
            "build id padding" to { buffer -> buffer.put(MODULE_OFFSET + 92 + 10, 1.toByte()) },
            "reserved module" to { buffer -> buffer.putInt(MODULE_OFFSET + 124, 1) },
        ).map { (name, mutation) ->
            dynamicTest(name) {
                val snapshot = NativeCrashSnapshotParser.parse(SnapshotBuilder().build(mutation), crashRecord)
                assertThat(snapshot).isNotNull()
                assertThat(snapshot!!.modules).isEmpty()
            }
        }

    @Test
    fun `rejects size checksum signal and timestamp mismatches`() {
        val valid = SnapshotBuilder().build()
        val corrupt = valid.copyOf().apply { this[NativeCrashSnapshotLayout.STACK_OFFSET] = 9 }

        assertThat(NativeCrashSnapshotParser.parse(valid.copyOf(valid.size - 1), crashRecord)).isNull()
        assertThat(NativeCrashSnapshotParser.parse(valid + byteArrayOf(0), crashRecord)).isNull()
        assertThat(NativeCrashSnapshotParser.parse(corrupt, crashRecord)).isNull()
        assertThat(NativeCrashSnapshotParser.parse(valid, crashRecord.copy(signalNumber = 6))).isNull()
        assertThat(NativeCrashSnapshotParser.parse(valid, crashRecord.copy(timestamp = crashRecord.timestamp.plusNanos(1)))).isNull()
    }

    private class SnapshotBuilder(
        private val architecture: NativeCrashArchitecture = NativeCrashArchitecture.ARM64,
    ) {
        private var programCounter = 0x1120UL
        private var stackPointer = STACK_START.toULong()
        private var framePointer = STACK_START.toULong()
        private var moduleLoadBias = 0x1000UL
        private var moduleStart = 0x1100UL
        private var moduleEnd = 0x2000UL

        fun addresses(
            programCounter: ULong,
            stackPointer: ULong,
            framePointer: ULong,
            moduleLoadBias: ULong,
            moduleStart: ULong,
            moduleEnd: ULong,
        ) = apply {
            this.programCounter = programCounter
            this.stackPointer = stackPointer
            this.framePointer = framePointer
            this.moduleLoadBias = moduleLoadBias
            this.moduleStart = moduleStart
            this.moduleEnd = moduleEnd
        }

        fun build(mutate: (ByteBuffer) -> Unit = {}): ByteArray {
            val bytes = ByteArray(NativeCrashSnapshotLayout.RECORD_SIZE)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("OTELNCS\u0000".toByteArray())
            buffer.putInt(1)
            buffer.putInt(architecture.id)
            buffer.putInt(bytes.size)
            buffer.putInt(crashRecord.signalNumber)
            buffer.putLong(TIMESTAMP_NANOS)
            buffer.putLong(programCounter.toLong())
            buffer.putLong(stackPointer.toLong())
            buffer.putLong(framePointer.toLong())
            buffer.putLong(0)
            buffer.putInt(1)
            buffer.putInt(4)
            buffer.putLong(stackPointer.toLong())
            buffer.position(MODULE_OFFSET)
            buffer.putLong(moduleLoadBias.toLong())
            buffer.putLong(moduleStart.toLong())
            buffer.putLong(moduleEnd.toLong())
            buffer.put("libapp.so".toByteArray())
            buffer.putInt(MODULE_OFFSET + 88, 3)
            buffer.position(MODULE_OFFSET + 92)
            buffer.put(byteArrayOf(0x01, 0x23, 0xfe.toByte()))
            buffer.position(NativeCrashSnapshotLayout.STACK_OFFSET)
            buffer.put(byteArrayOf(1, 2, 3, 4))
            mutate(buffer)
            buffer.putInt(
                NativeCrashSnapshotLayout.CHECKSUM_OFFSET,
                NativeCrashSnapshotParser.checksum(bytes).toInt(),
            )
            return bytes
        }
    }

    private fun ByteBuffer.fill(
        offset: Int,
        size: Int,
        value: Byte,
    ) {
        repeat(size) { put(offset + it, value) }
    }

    private companion object {
        const val STACK_START = 0x7000L
        const val MODULE_OFFSET = NativeCrashSnapshotLayout.MODULES_OFFSET
        const val TIMESTAMP_NANOS = 1_783_598_400_123_456_789L
        val crashRecord =
            NativeCrashRecord(
                signalNumber = 11,
                timestamp = Instant.ofEpochSecond(1_783_598_400, 123_456_789),
            )
    }
}
