/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalUnsignedTypes::class)

package io.opentelemetry.android.instrumentation.nativecrash

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NativeCrashStackUnwinderTest {
    @Test
    fun `walks a 64 bit frame pointer chain and renders build ids`() {
        val stack = stack(8, 0x7010UL, 0x1300UL, 0UL, 0x1500UL)

        val trace = NativeCrashStackUnwinder.unwind(snapshot(stack = stack))

        assertThat(trace.frames)
            .containsExactly(
                frame(0x120UL, NativeCrashFrameSource.PROGRAM_COUNTER),
                frame(0x300UL, NativeCrashFrameSource.FRAME_POINTER),
                frame(0x500UL, NativeCrashFrameSource.FRAME_POINTER),
            )
        assertThat(trace.toString())
            .isEqualTo(
                "#00 pc 0000000000000120  libapp.so (BuildId: 0123fe)\n" +
                    "#01 pc 0000000000000300  libapp.so (BuildId: 0123fe)\n" +
                    "#02 pc 0000000000000500  libapp.so (BuildId: 0123fe)",
            )
    }

    @Test
    fun `walks a zero extended 32 bit x86 chain`() {
        val trace =
            NativeCrashStackUnwinder.unwind(
                snapshot(
                    architecture = NativeCrashArchitecture.X86,
                    stack = stack(4, 0x7008UL, 0x1300UL, 0UL, 0x1500UL),
                ),
            )

        assertThat(trace.frames.map(NativeCrashStackFrame::moduleOffset))
            .containsExactly(0x120UL, 0x300UL, 0x500UL)
        assertThat(trace.toString()).startsWith("#00 pc 00000120")
    }

    @Test
    fun `uses the normalized arm link register when no frame pointer caller is available`() {
        val trace =
            NativeCrashStackUnwinder.unwind(
                snapshot(
                    architecture = NativeCrashArchitecture.ARM,
                    linkRegister = 0x1301UL,
                ),
            )

        assertThat(trace.frames)
            .containsExactly(
                frame(0x120UL, NativeCrashFrameSource.PROGRAM_COUNTER),
                frame(0x300UL, NativeCrashFrameSource.LINK_REGISTER),
            )
    }

    @Test
    fun `strips arm64 top byte and pointer authentication bits`() {
        val trace =
            NativeCrashStackUnwinder.unwind(
                snapshot(programCounter = 0xabcd_0000_0000_1120UL),
            )

        assertThat(trace.frames).containsExactly(frame(0x120UL, NativeCrashFrameSource.PROGRAM_COUNTER))
    }

    @Test
    fun `returns an empty trace when no module matches`() {
        val trace = NativeCrashStackUnwinder.unwind(snapshot(modules = emptyList()))

        assertThat(trace.frames).isEmpty()
        assertThat(trace.toString()).isEmpty()
    }

    @Test
    fun `stops a frame pointer loop`() {
        val trace =
            NativeCrashStackUnwinder.unwind(
                snapshot(stack = stack(8, 0x7000UL, 0x1300UL)),
            )

        assertThat(trace.frames).hasSize(2)
    }

    @Test
    fun `caps confirmed frames`() {
        val words = ArrayList<ULong>()
        repeat(64) { index ->
            words += if (index == 63) 0UL else 0x7000UL + ((index + 1) * 16).toULong()
            words += 0x1300UL
        }

        val trace = NativeCrashStackUnwinder.unwind(snapshot(stack = stack(8, *words.toULongArray())))

        assertThat(trace.frames).hasSize(64)
    }

    private fun snapshot(
        architecture: NativeCrashArchitecture = NativeCrashArchitecture.ARM64,
        programCounter: ULong = 0x1120UL,
        linkRegister: ULong = 0UL,
        modules: List<NativeCrashModule> = listOf(module),
        stack: ByteArray = ByteArray(0),
    ) = NativeCrashSnapshot(
        architecture = architecture,
        programCounter = programCounter,
        stackPointer = 0x7000UL,
        framePointer = 0x7000UL,
        linkRegister = linkRegister,
        modules = modules,
        stackStart = 0x7000UL,
        stack = stack,
    )

    private fun frame(
        offset: ULong,
        source: NativeCrashFrameSource,
    ) = NativeCrashStackFrame("libapp.so", offset, "0123fe", source)

    private fun stack(
        pointerSize: Int,
        vararg words: ULong,
    ): ByteArray {
        val bytes = ByteArray(words.size * pointerSize)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        words.forEach { word ->
            if (pointerSize == Long.SIZE_BYTES) buffer.putLong(word.toLong()) else buffer.putInt(word.toInt())
        }
        return bytes
    }

    private companion object {
        val module =
            NativeCrashModule(
                loadBias = 0x1000UL,
                executableStart = 0x1100UL,
                executableEnd = 0x2000UL,
                name = "libapp.so",
                buildId = "0123fe",
            )
    }
}
