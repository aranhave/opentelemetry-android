/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.nativecrash

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.time.Instant

internal object NativeCrashSnapshotLayout {
    const val MAGIC_OFFSET = 0
    const val MAGIC_SIZE = 8
    const val VERSION_OFFSET = MAGIC_OFFSET + MAGIC_SIZE
    const val ARCHITECTURE_OFFSET = VERSION_OFFSET + Int.SIZE_BYTES
    const val RECORD_SIZE_OFFSET = ARCHITECTURE_OFFSET + Int.SIZE_BYTES
    const val SIGNAL_NUMBER_OFFSET = RECORD_SIZE_OFFSET + Int.SIZE_BYTES
    const val TIMESTAMP_OFFSET = SIGNAL_NUMBER_OFFSET + Int.SIZE_BYTES
    const val PROGRAM_COUNTER_OFFSET = TIMESTAMP_OFFSET + Long.SIZE_BYTES
    const val STACK_POINTER_OFFSET = PROGRAM_COUNTER_OFFSET + Long.SIZE_BYTES
    const val FRAME_POINTER_OFFSET = STACK_POINTER_OFFSET + Long.SIZE_BYTES
    const val LINK_REGISTER_OFFSET = FRAME_POINTER_OFFSET + Long.SIZE_BYTES
    const val MODULE_COUNT_OFFSET = LINK_REGISTER_OFFSET + Long.SIZE_BYTES
    const val STACK_SIZE_OFFSET = MODULE_COUNT_OFFSET + Int.SIZE_BYTES
    const val STACK_START_OFFSET = STACK_SIZE_OFFSET + Int.SIZE_BYTES
    const val MODULES_OFFSET = STACK_START_OFFSET + Long.SIZE_BYTES

    const val MAX_MODULES = 128
    const val MODULE_ENTRY_SIZE = 128
    const val MODULE_LOAD_BIAS_OFFSET = 0
    const val MODULE_EXECUTABLE_START_OFFSET = 8
    const val MODULE_EXECUTABLE_END_OFFSET = 16
    const val MODULE_NAME_OFFSET = 24
    const val MODULE_NAME_SIZE = 64
    const val MODULE_BUILD_ID_SIZE_OFFSET = 88
    const val MODULE_BUILD_ID_OFFSET = 92
    const val MODULE_BUILD_ID_CAPACITY = 32
    const val MODULE_RESERVED_OFFSET = 124

    const val STACK_OFFSET = MODULES_OFFSET + MAX_MODULES * MODULE_ENTRY_SIZE
    const val STACK_CAPACITY = 4_096
    const val RESERVED_OFFSET = STACK_OFFSET + STACK_CAPACITY
    const val CHECKSUM_OFFSET = RESERVED_OFFSET + Int.SIZE_BYTES
    const val RECORD_SIZE = CHECKSUM_OFFSET + Int.SIZE_BYTES
}

internal enum class NativeCrashArchitecture(
    val id: Int,
    val pointerSize: Int,
) {
    ARM(1, Int.SIZE_BYTES),
    ARM64(2, Long.SIZE_BYTES),
    X86(3, Int.SIZE_BYTES),
    X86_64(4, Long.SIZE_BYTES),
    ;

    val addressMask: ULong =
        if (pointerSize == Int.SIZE_BYTES) {
            UInt.MAX_VALUE.toULong()
        } else {
            ULong.MAX_VALUE
        }

    companion object {
        fun fromId(id: Int): NativeCrashArchitecture? = entries.firstOrNull { it.id == id }
    }
}

internal class NativeCrashSnapshot(
    val architecture: NativeCrashArchitecture,
    val programCounter: ULong,
    val stackPointer: ULong,
    val framePointer: ULong,
    val linkRegister: ULong,
    val modules: List<NativeCrashModule>,
    val stackStart: ULong,
    val stack: ByteArray,
)

internal data class NativeCrashModule(
    val loadBias: ULong,
    val executableStart: ULong,
    val executableEnd: ULong,
    val name: String,
    val buildId: String?,
)

internal object NativeCrashSnapshotParser {
    private const val VERSION = 1

    // Keep these values in sync with native_crash_snapshot.h.
    private const val FNV_OFFSET_BASIS = 0x811c9dc5L
    private const val FNV_PRIME = 0x01000193L
    private const val UINT_MASK = 0xffff_ffffL

    // Keep this value in sync with OTEL_NCS_MAGIC in native_crash_snapshot.h.
    private val magic =
        byteArrayOf(
            'O'.code.toByte(),
            'T'.code.toByte(),
            'E'.code.toByte(),
            'L'.code.toByte(),
            'N'.code.toByte(),
            'C'.code.toByte(),
            'S'.code.toByte(),
            0,
        )

    fun parse(
        bytes: ByteArray,
        crashRecord: NativeCrashRecord,
    ): NativeCrashSnapshot? {
        if (!hasValidEnvelope(bytes)) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (!bytes.copyOfRange(0, NativeCrashSnapshotLayout.MAGIC_SIZE).contentEquals(magic)) return null
        if (buffer.getInt(NativeCrashSnapshotLayout.VERSION_OFFSET) != VERSION) return null
        val architecture =
            NativeCrashArchitecture.fromId(buffer.getInt(NativeCrashSnapshotLayout.ARCHITECTURE_OFFSET))
                ?: return null
        if (buffer.getInt(NativeCrashSnapshotLayout.RECORD_SIZE_OFFSET) != NativeCrashSnapshotLayout.RECORD_SIZE) return null
        if (!matchesCrashRecord(buffer, crashRecord)) return null

        val programCounter = buffer.addressAt(NativeCrashSnapshotLayout.PROGRAM_COUNTER_OFFSET, architecture)
        val stackPointer = buffer.addressAt(NativeCrashSnapshotLayout.STACK_POINTER_OFFSET, architecture)
        val framePointer = buffer.addressAt(NativeCrashSnapshotLayout.FRAME_POINTER_OFFSET, architecture)
        val linkRegister = buffer.addressAt(NativeCrashSnapshotLayout.LINK_REGISTER_OFFSET, architecture)
        val stackStart = buffer.addressAt(NativeCrashSnapshotLayout.STACK_START_OFFSET, architecture)
        val moduleCount = buffer.getInt(NativeCrashSnapshotLayout.MODULE_COUNT_OFFSET)
        val stackSize = buffer.getInt(NativeCrashSnapshotLayout.STACK_SIZE_OFFSET)

        if (programCounter == 0UL || stackPointer == 0UL || stackStart != stackPointer) return null
        if (stackStart % architecture.pointerSize.toULong() != 0UL) return null
        if (moduleCount !in 1..NativeCrashSnapshotLayout.MAX_MODULES) return null
        if (stackSize !in 0..NativeCrashSnapshotLayout.STACK_CAPACITY) return null
        if (buffer.getInt(NativeCrashSnapshotLayout.RESERVED_OFFSET) != 0) return null

        val modules =
            buildList(moduleCount) {
                repeat(moduleCount) { index ->
                    readModule(bytes, buffer, index, architecture)?.let(::add)
                }
            }
        val stack =
            bytes.copyOfRange(
                NativeCrashSnapshotLayout.STACK_OFFSET,
                NativeCrashSnapshotLayout.STACK_OFFSET + stackSize,
            )
        return NativeCrashSnapshot(
            architecture,
            programCounter,
            stackPointer,
            framePointer,
            linkRegister,
            modules,
            stackStart,
            stack,
        )
    }

    internal fun checksum(bytes: ByteArray): Long {
        var checksum = FNV_OFFSET_BASIS
        for (index in 0 until NativeCrashSnapshotLayout.CHECKSUM_OFFSET) {
            checksum = ((checksum xor (bytes[index].toLong() and 0xff)) * FNV_PRIME) and UINT_MASK
        }
        return checksum
    }

    private fun hasValidEnvelope(bytes: ByteArray): Boolean =
        bytes.size == NativeCrashSnapshotLayout.RECORD_SIZE &&
            checksum(bytes) == bytes.uintAt(NativeCrashSnapshotLayout.CHECKSUM_OFFSET)

    private fun matchesCrashRecord(
        buffer: ByteBuffer,
        crashRecord: NativeCrashRecord,
    ): Boolean {
        if (buffer.getInt(NativeCrashSnapshotLayout.SIGNAL_NUMBER_OFFSET) != crashRecord.signalNumber) return false
        val timestamp =
            runCatching {
                Instant.ofEpochSecond(0, buffer.getLong(NativeCrashSnapshotLayout.TIMESTAMP_OFFSET))
            }.getOrNull()
        return timestamp == crashRecord.timestamp
    }

    private fun readModule(
        bytes: ByteArray,
        buffer: ByteBuffer,
        index: Int,
        architecture: NativeCrashArchitecture,
    ): NativeCrashModule? {
        val base = NativeCrashSnapshotLayout.MODULES_OFFSET + index * NativeCrashSnapshotLayout.MODULE_ENTRY_SIZE
        val loadBias = buffer.addressAt(base + NativeCrashSnapshotLayout.MODULE_LOAD_BIAS_OFFSET, architecture)
        val executableStart = buffer.addressAt(base + NativeCrashSnapshotLayout.MODULE_EXECUTABLE_START_OFFSET, architecture)
        val executableEnd = buffer.addressAt(base + NativeCrashSnapshotLayout.MODULE_EXECUTABLE_END_OFFSET, architecture)
        if (loadBias > executableStart || executableStart >= executableEnd) return null
        if (buffer.getInt(base + NativeCrashSnapshotLayout.MODULE_RESERVED_OFFSET) != 0) return null

        val name =
            decodeName(
                bytes,
                base + NativeCrashSnapshotLayout.MODULE_NAME_OFFSET,
                NativeCrashSnapshotLayout.MODULE_NAME_SIZE,
            ) ?: return null
        val buildIdSize = buffer.getInt(base + NativeCrashSnapshotLayout.MODULE_BUILD_ID_SIZE_OFFSET)
        if (buildIdSize !in 0..NativeCrashSnapshotLayout.MODULE_BUILD_ID_CAPACITY) return null
        val buildIdOffset = base + NativeCrashSnapshotLayout.MODULE_BUILD_ID_OFFSET
        if (bytes.hasNonZeroBytes(
                buildIdOffset + buildIdSize,
                buildIdOffset + NativeCrashSnapshotLayout.MODULE_BUILD_ID_CAPACITY,
            )
        ) {
            return null
        }
        val buildId =
            if (buildIdSize == 0) {
                null
            } else {
                bytes.toHex(buildIdOffset, buildIdSize)
            }
        return NativeCrashModule(loadBias, executableStart, executableEnd, name, buildId)
    }

    private fun decodeName(
        bytes: ByteArray,
        offset: Int,
        size: Int,
    ): String? {
        val terminator = (offset until offset + size).firstOrNull { bytes[it] == 0.toByte() } ?: return null
        if (terminator == offset || bytes.hasNonZeroBytes(terminator + 1, offset + size)) return null
        val name =
            runCatching {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, terminator - offset))
                    .toString()
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return name.takeUnless { value -> value.any(Char::isISOControl) }
    }

    private fun ByteBuffer.addressAt(
        offset: Int,
        architecture: NativeCrashArchitecture,
    ): ULong = getLong(offset).toULong() and architecture.addressMask

    private fun ByteArray.uintAt(offset: Int): Long =
        ByteBuffer
            .wrap(this, offset, Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
            .toLong() and UINT_MASK

    private fun ByteArray.hasNonZeroBytes(
        start: Int,
        end: Int,
    ): Boolean = (start until end).any { this[it] != 0.toByte() }

    private fun ByteArray.toHex(
        offset: Int,
        size: Int,
    ): String =
        buildString(size * 2) {
            repeat(size) { index ->
                val value = this@toHex[offset + index].toInt() and 0xff
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0xf])
            }
        }

    private const val HEX_DIGITS = "0123456789abcdef"
}
