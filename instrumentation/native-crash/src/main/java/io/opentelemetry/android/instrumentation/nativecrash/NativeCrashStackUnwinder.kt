/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.nativecrash

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class NativeCrashFrameSource {
    PROGRAM_COUNTER,
    FRAME_POINTER,
    LINK_REGISTER,
    STACK_SCAN,
}

internal data class NativeCrashStackFrame(
    val moduleName: String,
    val moduleOffset: ULong,
    val buildId: String?,
    val source: NativeCrashFrameSource,
)

internal class NativeCrashStackTrace(
    val frames: List<NativeCrashStackFrame>,
    val scanCandidates: List<NativeCrashStackFrame>,
    private val pointerSize: Int,
) {
    override fun toString(): String =
        frames
            .mapIndexed { index, frame ->
                val frameNumber = index.toString().padStart(2, '0')
                val offset = frame.moduleOffset.toString(16).padStart(pointerSize * 2, '0')
                val buildId = frame.buildId?.let { " (BuildId: $it)" }.orEmpty()
                "#$frameNumber pc $offset  ${frame.moduleName}$buildId"
            }.joinToString("\n")
}

internal object NativeCrashStackUnwinder {
    private const val MAX_FRAMES = 64

    fun unwind(snapshot: NativeCrashSnapshot): NativeCrashStackTrace {
        val collector = FrameCollector(snapshot.architecture, snapshot.modules)
        collector.addConfirmed(snapshot.programCounter, NativeCrashFrameSource.PROGRAM_COUNTER)

        val stack = CapturedStack(snapshot.stackStart, snapshot.stack, snapshot.architecture.pointerSize)
        val recoveredFrames =
            if (snapshot.architecture.supportsFramePointerWalk) {
                walkFramePointers(snapshot.framePointer, stack, collector)
            } else {
                0
            }
        if (recoveredFrames == 0) {
            if (snapshot.architecture.hasLinkRegister) {
                collector.addConfirmed(snapshot.linkRegister, NativeCrashFrameSource.LINK_REGISTER)
            }
            scanStack(stack, collector)
        }
        return NativeCrashStackTrace(
            frames = collector.frames,
            scanCandidates = collector.scanCandidates,
            pointerSize = snapshot.architecture.pointerSize,
        )
    }

    private fun walkFramePointers(
        initialFramePointer: ULong,
        stack: CapturedStack,
        collector: FrameCollector,
    ): Int {
        var framePointer = initialFramePointer
        var recoveredFrames = 0
        while (!collector.isFull) {
            val record = stack.readFrame(framePointer) ?: return recoveredFrames
            if (collector.addConfirmed(record.returnAddress, NativeCrashFrameSource.FRAME_POINTER)) {
                recoveredFrames++
            }
            if (record.previousFramePointer <= framePointer || !stack.contains(record.previousFramePointer)) {
                return recoveredFrames
            }
            framePointer = record.previousFramePointer
        }
        return recoveredFrames
    }

    private fun scanStack(
        stack: CapturedStack,
        collector: FrameCollector,
    ) {
        var offset = 0
        while (offset + stack.pointerSize <= stack.size) {
            collector.addScanCandidate(stack.readPointerAtOffset(offset))
            offset += stack.pointerSize
        }
    }

    private class FrameCollector(
        private val architecture: NativeCrashArchitecture,
        private val modules: List<NativeCrashModule>,
    ) {
        val frames = ArrayList<NativeCrashStackFrame>()
        val scanCandidates = ArrayList<NativeCrashStackFrame>()
        private val confirmedAddresses = HashSet<ULong>()
        private val candidateAddresses = HashSet<ULong>()
        val isFull: Boolean
            get() = frames.size >= MAX_FRAMES

        fun addConfirmed(
            address: ULong,
            source: NativeCrashFrameSource,
        ): Boolean {
            if (address == 0UL || isFull) return false
            val resolved = resolve(address) ?: return false
            frames += resolved.toFrame(source)
            confirmedAddresses += resolved.address
            return true
        }

        fun addScanCandidate(address: ULong) {
            if (address == 0UL || scanCandidates.size >= MAX_FRAMES) return
            val resolved = resolve(address) ?: return
            if (resolved.address in confirmedAddresses || !candidateAddresses.add(resolved.address)) return
            scanCandidates += resolved.toFrame(NativeCrashFrameSource.STACK_SCAN)
        }

        private fun resolve(address: ULong): ResolvedAddress? {
            for (candidate in architecture.normalizedCandidates(address)) {
                val module = modules.firstOrNull { candidate in it.executableStart..<it.executableEnd }
                if (module != null) return ResolvedAddress(candidate, module)
            }
            return null
        }
    }

    private data class ResolvedAddress(
        val address: ULong,
        val module: NativeCrashModule,
    ) {
        fun toFrame(source: NativeCrashFrameSource) =
            NativeCrashStackFrame(
                moduleName = module.name,
                moduleOffset = address - module.loadBias,
                buildId = module.buildId,
                source = source,
            )
    }

    private class CapturedStack(
        private val start: ULong,
        private val bytes: ByteArray,
        val pointerSize: Int,
    ) {
        val size: Int
            get() = bytes.size

        fun contains(address: ULong): Boolean =
            bytes.size >= pointerSize &&
                address >= start &&
                address - start <= (bytes.size - pointerSize).toULong()

        fun readPointer(address: ULong): ULong? {
            if (!contains(address)) return null
            val offset = (address - start).toInt()
            if (offset % pointerSize != 0) return null
            return readPointerAtOffset(offset)
        }

        fun readPointerAtOffset(offset: Int): ULong {
            val buffer = ByteBuffer.wrap(bytes, offset, pointerSize).order(ByteOrder.LITTLE_ENDIAN)
            return if (pointerSize == Long.SIZE_BYTES) buffer.long.toULong() else buffer.int.toUInt().toULong()
        }

        fun readFrame(framePointer: ULong): FrameRecord? {
            val previousFramePointer = readPointer(framePointer) ?: return null
            val returnAddress = readPointer(framePointer + pointerSize.toULong()) ?: return null
            return FrameRecord(previousFramePointer, returnAddress)
        }
    }

    private data class FrameRecord(
        val previousFramePointer: ULong,
        val returnAddress: ULong,
    )
}

private val NativeCrashArchitecture.hasLinkRegister: Boolean
    get() = this == NativeCrashArchitecture.ARM || this == NativeCrashArchitecture.ARM64

private val NativeCrashArchitecture.supportsFramePointerWalk: Boolean
    get() = this != NativeCrashArchitecture.ARM

private fun NativeCrashArchitecture.normalizedCandidates(address: ULong): Sequence<ULong> =
    when (this) {
        NativeCrashArchitecture.ARM -> {
            sequenceOf(address and 1UL.inv())
        }

        NativeCrashArchitecture.ARM64 -> {
            sequenceOf(
                address,
                address and 0x00ff_ffff_ffff_ffffUL,
                address and 0x000f_ffff_ffff_ffffUL,
                address and 0x0000_ffff_ffff_ffffUL,
            ).distinct()
        }

        NativeCrashArchitecture.X86, NativeCrashArchitecture.X86_64 -> {
            sequenceOf(address)
        }
    }
