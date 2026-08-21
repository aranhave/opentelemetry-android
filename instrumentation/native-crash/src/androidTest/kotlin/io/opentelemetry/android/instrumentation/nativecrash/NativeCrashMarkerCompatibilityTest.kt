/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.nativecrash

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.session.SessionProvider
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class NativeCrashMarkerCompatibilityTest {
    @Test
    fun nativeLibraryReportsTheDeviceArchitecture() {
        System.loadLibrary("otel_android_native_crash")
        val supportedArchitectureIds =
            Build.SUPPORTED_ABIS.mapNotNull {
                when (it) {
                    "armeabi-v7a" -> 1
                    "arm64-v8a" -> 2
                    "x86" -> 3
                    "x86_64" -> 4
                    else -> null
                }
            }

        assertTrue(
            "Native library ABI does not match the device ABIs",
            NativeCrashTestJni.compiledArchitecture() in supportedArchitectureIds,
        )
    }

    @Test
    fun nativeWriterAndKotlinReaderUseCompatibleMarkerFormat() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory =
            File(context.cacheDir, "native-crash-marker-compatibility").apply {
                deleteRecursively()
                assertTrue(mkdirs())
            }
        val store = FileNativeCrashStore(directory)
        val timestampNanos = 1_783_598_400_123_456_789L

        System.loadLibrary("otel_android_native_crash")
        assertTrue(
            NativeCrashTestJni.writeCrashMarker(
                markerPath = store.crashRecordPath.absolutePath,
                signalNumber = 11,
                timestampEpochNanos = timestampNanos,
            ),
        )
        assertTrue(
            NativeCrashTestJni.captureCrashSnapshot(
                snapshotPath = store.crashSnapshotPath.absolutePath,
                signalNumber = 11,
                timestampEpochNanos = timestampNanos,
            ),
        )

        val record = store.readCrashRecord()
        assertEquals(
            NativeCrashReadResult.Success(
                NativeCrashRecord(
                    signalNumber = 11,
                    timestamp = Instant.ofEpochSecond(1_783_598_400, 123_456_789),
                ),
            ),
            record,
        )
        val stackTrace = store.readCrashStackTrace(record.successValue())
        assertTrue(stackTrace is NativeCrashReadResult.Success)
    }

    @Test
    fun installedHandlerPreservesChainingAndStartupRecovery() {
        val store = newStore("handler-chaining")
        System.loadLibrary("otel_android_native_crash")

        assertTrue(
            NativeCrashTestJni.runHandlerChainingTest(
                store.crashRecordPath.absolutePath,
                store.crashSnapshotPath.absolutePath,
            ),
        )
        val record = store.readCrashRecord().successValue()
        assertEquals(6, record.signalNumber)
        assertTrue(
            store
                .readCrashStackTrace(record)
                .successValue()
                .frames
                .isNotEmpty(),
        )

        NativeCrashReporter(store, noopRum).replayPreviousCrash()

        assertFalse(store.crashRecordPath.exists())
        assertFalse(store.crashSnapshotPath.exists())
    }

    @Test
    fun simultaneousSignalsRecordOnlyTheFirstCrash() {
        val store = newStore("simultaneous-signals")
        System.loadLibrary("otel_android_native_crash")

        assertTrue(
            NativeCrashTestJni.runConcurrentSignalTest(
                store.crashRecordPath.absolutePath,
                store.crashSnapshotPath.absolutePath,
            ),
        )
        val record = store.readCrashRecord().successValue()
        assertEquals(6, record.signalNumber)
        assertTrue(
            store
                .readCrashStackTrace(record)
                .successValue()
                .frames
                .isNotEmpty(),
        )
    }

    private fun newStore(name: String): FileNativeCrashStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "native-crash-$name").apply { deleteRecursively() }
        assertTrue(directory.mkdirs())
        return FileNativeCrashStore(directory)
    }

    private fun <T> NativeCrashReadResult<T>.successValue(): T {
        assertTrue(this is NativeCrashReadResult.Success)
        return (this as NativeCrashReadResult.Success<T>).value
    }

    private val noopRum =
        object : OpenTelemetryRum {
            override val openTelemetry: OpenTelemetry = OpenTelemetry.noop()
            override val sessionProvider: SessionProvider = SessionProvider { "test-session" }
            override val clock: Clock = Clock.getDefault()

            override fun emitEvent(
                eventName: String,
                body: String,
                attributes: Attributes,
            ) {}

            override fun shutdown() {}
        }
}

internal object NativeCrashTestJni {
    @JvmStatic
    external fun compiledArchitecture(): Int

    @JvmStatic
    external fun runHandlerChainingTest(
        markerPath: String,
        snapshotPath: String,
    ): Boolean

    @JvmStatic
    external fun runConcurrentSignalTest(
        markerPath: String,
        snapshotPath: String,
    ): Boolean

    @JvmStatic
    external fun writeCrashMarker(
        markerPath: String,
        signalNumber: Int,
        timestampEpochNanos: Long,
    ): Boolean

    @JvmStatic
    external fun captureCrashSnapshot(
        snapshotPath: String,
        signalNumber: Int,
        timestampEpochNanos: Long,
    ): Boolean
}
