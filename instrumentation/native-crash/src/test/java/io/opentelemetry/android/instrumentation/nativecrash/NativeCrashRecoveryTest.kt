/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.nativecrash

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.session.SessionProvider
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.kotlin.semconv.ExceptionAttributes.EXCEPTION_STACKTRACE
import io.opentelemetry.sdk.common.Clock
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.Properties
import java.util.concurrent.Executor

class NativeCrashRecoveryTest {
    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @AfterEach
    fun cleanup() {
        otelTesting.clearLogRecords()
        unmockkStatic(Log::class)
    }

    @Test
    fun `retains recovery files when the marker read can be retried`() {
        val store = mockk<NativeCrashStore>(relaxed = true)
        every { store.recordRecoveryFailure() } returns true
        every { store.recoveryAttemptsExhausted() } returns false
        every { store.readContext() } returns null
        every { store.readCrashRecord() } returns NativeCrashReadResult.RetryableFailure

        NativeCrashReporter(store, fakeRum()).replayPreviousCrash()

        assertThat(otelTesting.logRecords).isEmpty()
        verify(exactly = 0) {
            store.deleteCrashRecord()
            store.deleteCrashSnapshot()
        }
    }

    @Test
    fun `retains recovery files when the snapshot read can be retried`() {
        val store = mockk<NativeCrashStore>(relaxed = true)
        val record = NativeCrashRecord(11, Instant.ofEpochSecond(1_783_598_400))
        every { store.recordRecoveryFailure() } returns true
        every { store.recoveryAttemptsExhausted() } returns false
        every { store.readContext() } returns null
        every { store.readCrashRecord() } returns NativeCrashReadResult.Success(record)
        every { store.readCrashStackTrace(record) } returns NativeCrashReadResult.RetryableFailure

        NativeCrashReporter(store, fakeRum()).replayPreviousCrash()

        assertThat(otelTesting.logRecords).isEmpty()
        verify(exactly = 0) {
            store.deleteCrashRecord()
            store.deleteCrashSnapshot()
        }
    }

    @Test
    fun `falls back to a marker only crash after snapshot retries are exhausted`() {
        val store = mockk<NativeCrashStore>(relaxed = true)
        val record = NativeCrashRecord(11, Instant.ofEpochSecond(1_783_598_400))
        every { store.recoveryDeliveryWasClaimed() } returns false
        every { store.recoveryAttemptsExhausted() } returnsMany
            listOf(false, false, false, false, false, true)
        every { store.recordRecoveryFailure() } returns true
        every { store.readContext() } returns null
        every { store.readCrashRecord() } returns NativeCrashReadResult.Success(record)
        every { store.readCrashStackTrace(record) } returns NativeCrashReadResult.RetryableFailure
        every { store.deleteCrashSnapshot() } returns true
        every { store.claimRecoveryDelivery() } returns true
        every { store.deleteCrashRecord() } returns true
        val reporter = NativeCrashReporter(store, fakeRum())

        assertThat(reporter.replayPreviousCrash()).isEqualTo(NativeCrashReplayResult.RETRY_PENDING)
        assertThat(reporter.replayPreviousCrash()).isEqualTo(NativeCrashReplayResult.RETRY_PENDING)
        assertThat(reporter.replayPreviousCrash()).isEqualTo(NativeCrashReplayResult.COMPLETE)

        assertThat(otelTesting.logRecords).hasSize(1)
        assertThat(
            otelTesting.logRecords
                .single()
                .attributes
                .get(stringKey(EXCEPTION_STACKTRACE)),
        ).isNull()
        verify(exactly = 1) { store.deleteCrashSnapshot() }
    }

    @Test
    fun `does not emit when the delivery claim cannot be persisted`() {
        val store = mockk<NativeCrashStore>(relaxed = true)
        val record = NativeCrashRecord(11, Instant.ofEpochSecond(1_783_598_400))
        every { store.recoveryDeliveryWasClaimed() } returns false
        every { store.recoveryAttemptsExhausted() } returns false
        every { store.readContext() } returns null
        every { store.readCrashRecord() } returns NativeCrashReadResult.Success(record)
        every { store.readCrashStackTrace(record) } returns NativeCrashReadResult.Missing
        every { store.claimRecoveryDelivery() } returns false
        every { store.deleteCrashRecord() } returns true

        val result = NativeCrashReporter(store, fakeRum()).replayPreviousCrash()

        assertThat(result).isEqualTo(NativeCrashReplayResult.COMPLETE)
        assertThat(otelTesting.logRecords).isEmpty()
        verify(exactly = 1) { store.claimRecoveryDelivery() }
    }

    @Test
    fun `does not emit again when cleanup retry state cannot be updated`() {
        val store = mockk<NativeCrashStore>(relaxed = true)
        val record = NativeCrashRecord(11, Instant.ofEpochSecond(1_783_598_400))
        var deliveryClaimed = false
        every { store.recoveryDeliveryWasClaimed() } answers { deliveryClaimed }
        every { store.recoveryAttemptsExhausted() } returns false
        every { store.readContext() } returns null
        every { store.readCrashRecord() } returns NativeCrashReadResult.Success(record)
        every { store.readCrashStackTrace(record) } returns NativeCrashReadResult.Missing
        every { store.claimRecoveryDelivery() } answers {
            deliveryClaimed = true
            true
        }
        every { store.deleteCrashRecord() } returns false
        every { store.recordRecoveryFailure() } returns false
        val reporter = NativeCrashReporter(store, fakeRum())

        assertThat(reporter.replayPreviousCrash()).isEqualTo(NativeCrashReplayResult.COMPLETE)
        assertThat(reporter.replayPreviousCrash()).isEqualTo(NativeCrashReplayResult.COMPLETE)

        assertThat(otelTesting.logRecords).hasSize(1)
    }

    @Test
    fun `does not retry a crash after delivery has been claimed`() {
        val store = FileNativeCrashStore(tempDir)
        writeMarker()
        store.crashSnapshotPath.writeBytes(snapshotBytes())
        val rum = mockk<OpenTelemetryRum>()
        every { rum.openTelemetry } throws IllegalStateException("logger unavailable")

        val result = NativeCrashReporter(store, rum).replayPreviousCrash()

        assertThat(result).isEqualTo(NativeCrashReplayResult.COMPLETE)
        assertThat(store.crashRecordPath).doesNotExist()
        assertThat(store.crashSnapshotPath).doesNotExist()
        verify {
            Log.w(
                any<String>(),
                "Failed to replay native crash",
                any<IllegalStateException>(),
            )
        }
    }

    @Test
    fun `removes the snapshot when marker cleanup fails`() {
        val store = undeletableStore()
        writeMarker()
        store.crashSnapshotPath.writeBytes(snapshotBytes())

        val result = NativeCrashReporter(store, fakeRum()).replayPreviousCrash()

        assertThat(otelTesting.logRecords).hasSize(1)
        assertThat(result).isEqualTo(NativeCrashReplayResult.RETRY_PENDING)
        assertThat(store.crashRecordPath).exists()
        assertThat(store.crashSnapshotPath).doesNotExist()
    }

    @Test
    fun `retries cleanup without emitting the crash again`() {
        var failMarkerDeletion = true
        val store =
            FileNativeCrashStore(tempDir) { file ->
                if (file.name == "native-crash-record.properties" && failMarkerDeletion) {
                    failMarkerDeletion = false
                    false
                } else {
                    file.delete()
                }
            }
        writeMarker()
        store.crashSnapshotPath.writeBytes(snapshotBytes())
        val reporter = NativeCrashReporter(store, fakeRum())

        assertThat(reporter.replayPreviousCrash()).isEqualTo(NativeCrashReplayResult.RETRY_PENDING)
        assertThat(store.crashRecordPath).exists()
        assertThat(store.crashSnapshotPath).doesNotExist()
        otelTesting.clearLogRecords()

        assertThat(reporter.replayPreviousCrash()).isEqualTo(NativeCrashReplayResult.COMPLETE)
        assertThat(otelTesting.logRecords).isEmpty()
        assertThat(store.crashRecordPath).doesNotExist()
        assertThat(store.crashSnapshotPath).doesNotExist()
    }

    @Test
    fun `contains an emission failure after claiming delivery`() {
        val store = FileNativeCrashStore(tempDir)
        writeMarker()
        val rum = mockk<OpenTelemetryRum>()
        every { rum.openTelemetry } throws IllegalStateException("logger unavailable")
        val reporter = NativeCrashReporter(store, rum)

        assertThat(reporter.replayPreviousCrash()).isEqualTo(NativeCrashReplayResult.COMPLETE)

        assertThat(store.crashRecordPath).doesNotExist()
    }

    @Test
    fun `contains linkage errors during crash emission`() {
        val store = FileNativeCrashStore(tempDir)
        writeMarker()
        val failure = NoClassDefFoundError("logger unavailable")
        val rum = mockk<OpenTelemetryRum>()
        every { rum.openTelemetry } throws failure

        val result = NativeCrashReporter(store, rum).replayPreviousCrash()

        assertThat(result).isEqualTo(NativeCrashReplayResult.COMPLETE)
        assertThat(store.crashRecordPath).doesNotExist()
        verify {
            Log.w(
                any<String>(),
                "Failed to replay native crash",
                failure,
            )
        }
    }

    @Test
    fun `does not intercept virtual machine errors during crash emission`() {
        val store = FileNativeCrashStore(tempDir)
        writeMarker()
        val failure = OutOfMemoryError("unrecoverable")
        val rum = mockk<OpenTelemetryRum>()
        every { rum.openTelemetry } throws failure

        assertThatThrownBy { NativeCrashReporter(store, rum).replayPreviousCrash() }
            .isSameAs(failure)
    }

    @Test
    fun `contains linkage errors during instrumentation setup`() {
        val applicationContext = mockk<Context>()
        val context = mockk<Context>()
        every { context.applicationContext } returns applicationContext
        val failure = NoClassDefFoundError("store unavailable")
        val instrumentation =
            NativeCrashInstrumentation(
                storeFactory = { throw failure },
                executor = directExecutor,
            )

        instrumentation.install(context, fakeRum())

        verify {
            Log.w(
                any<String>(),
                "Failed to initialize native crash instrumentation",
                failure,
            )
        }
    }

    @Test
    fun `does not emit an undeletable crash more than once`() {
        val store = undeletableStore()
        writeMarker()
        val reporter = NativeCrashReporter(store, fakeRum())

        assertThat(reporter.replayPreviousCrash()).isEqualTo(NativeCrashReplayResult.RETRY_PENDING)
        assertThat(reporter.replayPreviousCrash()).isEqualTo(NativeCrashReplayResult.RETRY_PENDING)
        assertThat(reporter.replayPreviousCrash()).isEqualTo(NativeCrashReplayResult.COMPLETE)
        assertThat(otelTesting.logRecords).hasSize(1)

        assertThat(reporter.replayPreviousCrash()).isEqualTo(NativeCrashReplayResult.COMPLETE)
        assertThat(otelTesting.logRecords).hasSize(1)
        assertThat(store.crashRecordPath).exists()
    }

    @Test
    fun `installs the signal handler after recovery attempts are exhausted`() {
        val store = undeletableStore()
        writeMarker()
        repeat(3) { NativeCrashReporter(store, fakeRum()).replayPreviousCrash() }
        val packageManager = mockk<PackageManager>()
        val applicationContext = mockk<Context>()
        val context = mockk<Context>()
        every { context.applicationContext } returns applicationContext
        every { applicationContext.packageManager } returns packageManager
        every { applicationContext.packageName } returns "test.app"
        every { packageManager.getPackageInfo("test.app", 0) } throws
            PackageManager.NameNotFoundException()
        var signalHandlerInstalled = false
        val instrumentation =
            NativeCrashInstrumentation(
                storeFactory = { store },
                executor = directExecutor,
                signalHandlerInstaller = { _ ->
                    signalHandlerInstalled = true
                    true
                },
            )

        instrumentation.install(context, fakeRum())

        assertThat(signalHandlerInstalled).isTrue()
        assertThat(store.readContext()?.sessionId).isEqualTo("current-session")
    }

    private fun undeletableStore(): FileNativeCrashStore =
        FileNativeCrashStore(tempDir) { file ->
            if (file.name == "native-crash-record.properties") false else file.delete()
        }

    private fun writeMarker() {
        val properties =
            Properties().apply {
                setProperty("signal.number", "11")
                setProperty("timestamp.epoch_nanos", "1783598400123456789")
            }
        FileOutputStream(File(tempDir, "native-crash-record.properties")).use { properties.store(it, null) }
    }

    private fun snapshotBytes(): ByteArray {
        val bytes = ByteArray(NativeCrashSnapshotLayout.RECORD_SIZE)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("OTELNCS\u0000".toByteArray())
        buffer.putInt(1)
        buffer.putInt(NativeCrashArchitecture.ARM64.id)
        buffer.putInt(bytes.size)
        buffer.putInt(11)
        buffer.putLong(1_783_598_400_123_456_789L)
        buffer.putLong(0x1120)
        buffer.putLong(0x7000)
        buffer.putLong(0x7000)
        buffer.putLong(0)
        buffer.putInt(1)
        buffer.putInt(0)
        buffer.putLong(0x7000)
        buffer.position(NativeCrashSnapshotLayout.MODULES_OFFSET)
        buffer.putLong(0x1000)
        buffer.putLong(0x1100)
        buffer.putLong(0x2000)
        buffer.put("libapp.so".toByteArray())
        buffer.putInt(NativeCrashSnapshotLayout.MODULES_OFFSET + 88, 3)
        buffer.position(NativeCrashSnapshotLayout.MODULES_OFFSET + 92)
        buffer.put(byteArrayOf(0x01, 0x23, 0xfe.toByte()))
        buffer.putInt(
            NativeCrashSnapshotLayout.CHECKSUM_OFFSET,
            NativeCrashSnapshotParser.checksum(bytes).toInt(),
        )
        return bytes
    }

    private fun fakeRum(): OpenTelemetryRum =
        object : OpenTelemetryRum {
            override val openTelemetry: OpenTelemetry = otelTesting.openTelemetry
            override val sessionProvider: SessionProvider = SessionProvider { "current-session" }
            override val clock: Clock = Clock.getDefault()

            override fun emitEvent(
                eventName: String,
                body: String,
                attributes: Attributes,
            ) {}

            override fun shutdown() {}
        }

    companion object {
        private val directExecutor = Executor { command -> command.run() }

        @JvmField
        @RegisterExtension
        val otelTesting: OpenTelemetryExtension = OpenTelemetryExtension.create()
    }
}
