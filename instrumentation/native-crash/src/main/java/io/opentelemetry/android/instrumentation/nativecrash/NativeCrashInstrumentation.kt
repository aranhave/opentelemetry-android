/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(IncubatingApi::class)

package io.opentelemetry.android.instrumentation.nativecrash

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.auto.service.AutoService
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.android.semconv.internal.SemconvCompat.Companion.map
import io.opentelemetry.android.session.Session
import io.opentelemetry.android.session.SessionObserver
import io.opentelemetry.android.session.SessionPublisher
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.kotlin.semconv.ExceptionAttributes.EXCEPTION_MESSAGE
import io.opentelemetry.kotlin.semconv.ExceptionAttributes.EXCEPTION_STACKTRACE
import io.opentelemetry.kotlin.semconv.ExceptionAttributes.EXCEPTION_TYPE
import io.opentelemetry.kotlin.semconv.IncubatingApi
import io.opentelemetry.kotlin.semconv.OsAttributes.OS_NAME
import io.opentelemetry.kotlin.semconv.OsAttributes.OS_VERSION
import io.opentelemetry.kotlin.semconv.ServiceAttributes.SERVICE_VERSION
import io.opentelemetry.kotlin.semconv.SessionAttributes.SESSION_ID
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.util.Properties
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Entry point for replaying native crashes captured by a previous app process. */
@AutoService(AndroidInstrumentation::class)
class NativeCrashInstrumentation internal constructor(
    private val storeFactory: (Context) -> NativeCrashStore = { context ->
        FileNativeCrashStore(File(context.filesDir, "opentelemetry/native-crash"))
    },
    private val executor: Executor = Executors.newSingleThreadExecutor(),
    private val signalHandlerInstaller: NativeSignalHandlerInstaller = JniNativeSignalHandlerInstaller(),
) : AndroidInstrumentation {
    override val name: String = "native-crash"

    override fun install(
        context: Context,
        openTelemetryRum: OpenTelemetryRum,
    ) {
        val applicationContext = context.applicationContext
        executor.execute {
            try {
                installAfterRecovery(applicationContext, openTelemetryRum)
            } catch (error: Exception) {
                logInitializationFailure(error)
            } catch (error: LinkageError) {
                logInitializationFailure(error)
            }
        }
    }

    private fun logInitializationFailure(error: Throwable) {
        Log.w(
            RumConstants.OTEL_RUM_LOG_TAG,
            "Failed to initialize native crash instrumentation",
            error,
        )
    }

    private fun installAfterRecovery(
        applicationContext: Context,
        openTelemetryRum: OpenTelemetryRum,
    ) {
        val store = storeFactory(applicationContext)
        val crashContext = applicationContext.currentCrashContext(openTelemetryRum)
        val replayResult =
            NativeCrashReporter(
                store = store,
                openTelemetryRum = openTelemetryRum,
            ).replayPreviousCrash()
        if (replayResult == NativeCrashReplayResult.RETRY_PENDING) {
            Log.w(
                RumConstants.OTEL_RUM_LOG_TAG,
                "Native crash signal handler disabled while previous crash recovery is pending",
            )
            return
        }
        if (!store.writeContext(crashContext)) {
            Log.w(
                RumConstants.OTEL_RUM_LOG_TAG,
                "Native crash signal handler disabled because crash context could not be persisted",
            )
            return
        }

        val sessionProvider = openTelemetryRum.sessionProvider
        if (sessionProvider is SessionPublisher) {
            sessionProvider.addObserver(NativeCrashSessionObserver(store, crashContext, executor))
        }

        if (!signalHandlerInstaller.install(store.crashRecordPath, store.crashSnapshotPath)) {
            Log.w(RumConstants.OTEL_RUM_LOG_TAG, "Failed to install native crash signal handler")
        }
    }
}

internal fun interface NativeSignalHandlerInstaller {
    fun install(
        crashRecordPath: File,
        crashSnapshotPath: File,
    ): Boolean
}

internal class JniNativeSignalHandlerInstaller(
    private val loadLibrary: (String) -> Unit = System::loadLibrary,
    private val nativeInstall: (String, String) -> Boolean = NativeCrashJni::install,
) : NativeSignalHandlerInstaller {
    override fun install(
        crashRecordPath: File,
        crashSnapshotPath: File,
    ): Boolean {
        if (!prepareCrashRecordDirectory(crashRecordPath)) {
            return false
        }
        return try {
            loadLibrary(NATIVE_LIBRARY_NAME)
            nativeInstall(crashRecordPath.absolutePath, crashSnapshotPath.absolutePath)
        } catch (error: Exception) {
            Log.w(RumConstants.OTEL_RUM_LOG_TAG, "Failed to load native crash signal handler", error)
            false
        } catch (error: LinkageError) {
            Log.w(RumConstants.OTEL_RUM_LOG_TAG, "Failed to load native crash signal handler", error)
            false
        }
    }

    private companion object {
        const val NATIVE_LIBRARY_NAME = "otel_android_native_crash"
    }
}

internal fun prepareCrashRecordDirectory(crashRecordPath: File): Boolean {
    val directory = crashRecordPath.parentFile ?: return false
    return try {
        directory.isDirectory || directory.mkdirs() || directory.isDirectory
    } catch (error: Exception) {
        Log.w(RumConstants.OTEL_RUM_LOG_TAG, "Failed to prepare native crash marker directory", error)
        false
    }
}

internal class NativeCrashSessionObserver(
    private val store: NativeCrashStore,
    private val crashContext: NativeCrashContext,
    private val executor: Executor,
) : SessionObserver {
    override fun onSessionStarted(
        newSession: Session,
        previousSession: Session,
    ) {
        executor.execute {
            store.writeContext(crashContext.copy(sessionId = newSession.id))
        }
    }

    override fun onSessionEnded(session: Session) {}
}

internal class NativeCrashReporter(
    private val store: NativeCrashStore,
    private val openTelemetryRum: OpenTelemetryRum,
) {
    fun replayPreviousCrash(): NativeCrashReplayResult {
        if (store.recoveryDeliveryWasClaimed()) {
            return cleanupDeliveredCrash()
        }
        if (store.recoveryAttemptsExhausted()) {
            return abandonCrash()
        }
        return try {
            replayPreviousCrashOrThrow()
        } catch (error: Exception) {
            handleReplayFailure(error)
        } catch (error: LinkageError) {
            handleReplayFailure(error)
        }
    }

    private fun handleReplayFailure(error: Throwable): NativeCrashReplayResult {
        Log.w(RumConstants.OTEL_RUM_LOG_TAG, "Failed to replay native crash", error)
        return if (store.recoveryDeliveryWasClaimed()) {
            cleanupDeliveredCrash()
        } else {
            retryOrAbandon()
        }
    }

    private fun replayPreviousCrashOrThrow(): NativeCrashReplayResult {
        val crashContext = store.readContext()
        val record =
            when (val result = store.readCrashRecord()) {
                NativeCrashReadResult.Missing -> {
                    store.deleteCrashRecord()
                    return NativeCrashReplayResult.COMPLETE
                }

                NativeCrashReadResult.Invalid -> {
                    return if (store.deleteCrashRecord()) {
                        NativeCrashReplayResult.COMPLETE
                    } else {
                        retryOrAbandon()
                    }
                }

                NativeCrashReadResult.RetryableFailure -> {
                    return retryOrAbandon()
                }

                is NativeCrashReadResult.Success -> {
                    result.value
                }
            }
        val stackTrace =
            when (val result = store.readCrashStackTrace(record)) {
                NativeCrashReadResult.Missing -> {
                    null
                }

                NativeCrashReadResult.Invalid -> {
                    store.deleteCrashSnapshot()
                    null
                }

                NativeCrashReadResult.RetryableFailure -> {
                    return retrySnapshotOrReplayMarker(record, crashContext)
                }

                is NativeCrashReadResult.Success -> {
                    result.value
                }
            }
        return replay(record, crashContext, stackTrace)
    }

    private fun retrySnapshotOrReplayMarker(
        record: NativeCrashRecord,
        crashContext: NativeCrashContext?,
    ): NativeCrashReplayResult {
        val retryRecorded = store.recordRecoveryFailure()
        if (retryRecorded && !store.recoveryAttemptsExhausted()) {
            return NativeCrashReplayResult.RETRY_PENDING
        }

        store.deleteCrashSnapshot()
        return replay(record, crashContext, stackTrace = null)
    }

    private fun cleanupDeliveredCrash(): NativeCrashReplayResult =
        if (store.deleteCrashRecord()) {
            NativeCrashReplayResult.COMPLETE
        } else {
            retryOrAbandon()
        }

    private fun retryOrAbandon(): NativeCrashReplayResult {
        val retryRecorded = store.recordRecoveryFailure()
        if (retryRecorded && !store.recoveryAttemptsExhausted()) {
            return NativeCrashReplayResult.RETRY_PENDING
        }
        return abandonCrash()
    }

    private fun abandonCrash(): NativeCrashReplayResult {
        store.deleteCrashRecord()
        return NativeCrashReplayResult.COMPLETE
    }

    private fun replay(
        record: NativeCrashRecord,
        crashContext: NativeCrashContext?,
        stackTrace: NativeCrashStackTrace?,
    ): NativeCrashReplayResult {
        val attributes = Attributes.builder()
        attributes.put(stringKey(EXCEPTION_TYPE), record.signalName)
        attributes.put(
            stringKey(EXCEPTION_MESSAGE),
            "Native crash signal ${record.signalName} (${record.signalNumber})",
        )
        stackTrace?.takeIf { it.frames.isNotEmpty() }?.let {
            attributes.put(stringKey(EXCEPTION_STACKTRACE), it.toString())
        }
        crashContext?.addTo(attributes)

        if (!store.claimRecoveryDelivery()) {
            return abandonCrash()
        }

        openTelemetryRum.openTelemetry.logsBridge
            .loggerBuilder("io.opentelemetry.native-crash")
            .build()
            .logRecordBuilder()
            .setEventName(map("app.crash"))
            .setTimestamp(record.timestamp)
            .setAllAttributes(attributes.build())
            .emit()
        return if (store.deleteCrashRecord()) {
            NativeCrashReplayResult.COMPLETE
        } else {
            retryOrAbandon()
        }
    }
}

internal enum class NativeCrashReplayResult {
    COMPLETE,
    RETRY_PENDING,
}

internal interface NativeCrashStore {
    val crashRecordPath: File
    val crashSnapshotPath: File

    fun readCrashRecord(): NativeCrashReadResult<NativeCrashRecord>

    fun readCrashStackTrace(record: NativeCrashRecord): NativeCrashReadResult<NativeCrashStackTrace>

    fun deleteCrashRecord(): Boolean

    fun deleteCrashSnapshot(): Boolean

    fun recordRecoveryFailure(): Boolean

    fun claimRecoveryDelivery(): Boolean

    fun recoveryAttemptsExhausted(): Boolean

    fun recoveryDeliveryWasClaimed(): Boolean

    fun clearRecoveryAttempts(): Boolean

    fun readContext(): NativeCrashContext?

    fun writeContext(context: NativeCrashContext): Boolean
}

internal sealed interface NativeCrashReadResult<out T> {
    data object Missing : NativeCrashReadResult<Nothing>

    data object Invalid : NativeCrashReadResult<Nothing>

    data object RetryableFailure : NativeCrashReadResult<Nothing>

    data class Success<T>(
        val value: T,
    ) : NativeCrashReadResult<T>
}

internal class FileNativeCrashStore(
    private val directory: File,
    private val fileDeleter: (File) -> Boolean = File::delete,
) : NativeCrashStore {
    private val contextPath = File(directory, "native-crash-context.properties")
    private val recoveryAttemptsPath = File(directory, "native-crash-recovery-attempts.properties")
    override val crashRecordPath = File(directory, "native-crash-record.properties")
    override val crashSnapshotPath = File(directory, "native-crash-snapshot.bin")

    override fun readCrashRecord(): NativeCrashReadResult<NativeCrashRecord> {
        if (!crashRecordPath.isFile) {
            return NativeCrashReadResult.Missing
        }
        val properties =
            try {
                crashRecordPath.readProperties()
            } catch (error: IllegalArgumentException) {
                return NativeCrashReadResult.Invalid
            } catch (error: IOException) {
                Log.w(RumConstants.OTEL_RUM_LOG_TAG, "Failed to read native crash marker", error)
                return NativeCrashReadResult.RetryableFailure
            } catch (error: SecurityException) {
                Log.w(RumConstants.OTEL_RUM_LOG_TAG, "Failed to read native crash marker", error)
                return NativeCrashReadResult.RetryableFailure
            }
        val record = properties.toCrashRecordOrNull() ?: return NativeCrashReadResult.Invalid
        return NativeCrashReadResult.Success(record)
    }

    override fun deleteCrashRecord(): Boolean {
        val markerDeleted = deleteFile(crashRecordPath, "marker")
        val snapshotDeleted = deleteCrashSnapshot()
        val recoveryStateCleared = markerDeleted && clearRecoveryAttempts()
        return markerDeleted && snapshotDeleted && recoveryStateCleared
    }

    override fun readCrashStackTrace(record: NativeCrashRecord): NativeCrashReadResult<NativeCrashStackTrace> {
        if (!crashSnapshotPath.isFile) {
            return NativeCrashReadResult.Missing
        }
        val bytes =
            try {
                crashSnapshotPath.readExactBytes(NativeCrashSnapshotLayout.RECORD_SIZE)
            } catch (error: IOException) {
                Log.w(RumConstants.OTEL_RUM_LOG_TAG, "Failed to read native crash snapshot", error)
                return NativeCrashReadResult.RetryableFailure
            } catch (error: SecurityException) {
                Log.w(RumConstants.OTEL_RUM_LOG_TAG, "Failed to read native crash snapshot", error)
                return NativeCrashReadResult.RetryableFailure
            }
        if (bytes == null) {
            return NativeCrashReadResult.Invalid
        }
        val snapshot = NativeCrashSnapshotParser.parse(bytes, record) ?: return NativeCrashReadResult.Invalid
        val stackTrace = NativeCrashStackUnwinder.unwind(snapshot)
        return NativeCrashReadResult.Success(stackTrace)
    }

    override fun deleteCrashSnapshot(): Boolean = deleteFile(crashSnapshotPath, "snapshot")

    @Synchronized
    override fun recordRecoveryFailure(): Boolean {
        val markerIdentity = crashRecordPath.identity() ?: return false
        val previousState = readRecoveryAttemptState()?.takeIf { it.markerIdentity == markerIdentity }
        val nextState =
            RecoveryAttemptState(
                markerIdentity = markerIdentity,
                attempts = (previousState?.attempts ?: 0) + 1,
                deliveryClaimed = previousState?.deliveryClaimed == true,
            )
        return writePropertiesAtomically(recoveryAttemptsPath, nextState.toProperties()).also { persisted ->
            if (!persisted) {
                Log.w(RumConstants.OTEL_RUM_LOG_TAG, "Failed to persist native crash recovery attempt")
            }
        }
    }

    @Synchronized
    override fun claimRecoveryDelivery(): Boolean {
        val markerIdentity = crashRecordPath.identity() ?: return false
        val previousState = readRecoveryAttemptState()?.takeIf { it.markerIdentity == markerIdentity }
        val nextState =
            RecoveryAttemptState(
                markerIdentity = markerIdentity,
                attempts = previousState?.attempts ?: 0,
                deliveryClaimed = true,
            )
        return writePropertiesAtomically(recoveryAttemptsPath, nextState.toProperties()).also { persisted ->
            if (!persisted) {
                Log.w(RumConstants.OTEL_RUM_LOG_TAG, "Failed to claim native crash delivery")
            }
        }
    }

    @Synchronized
    override fun recoveryAttemptsExhausted(): Boolean {
        val markerIdentity = crashRecordPath.identity() ?: return false
        val state = readRecoveryAttemptState() ?: return false
        return state.markerIdentity == markerIdentity && state.attempts >= MAX_RECOVERY_ATTEMPTS
    }

    @Synchronized
    override fun recoveryDeliveryWasClaimed(): Boolean {
        val markerIdentity = crashRecordPath.identity() ?: return false
        val state = readRecoveryAttemptState() ?: return false
        return state.markerIdentity == markerIdentity && state.deliveryClaimed
    }

    @Synchronized
    override fun clearRecoveryAttempts(): Boolean = deleteFile(recoveryAttemptsPath, "recovery state")

    override fun readContext(): NativeCrashContext? {
        val properties =
            try {
                contextPath.readProperties()
            } catch (_: Exception) {
                return null
            }
        return properties.toCrashContextOrNull()
    }

    @Synchronized
    override fun writeContext(context: NativeCrashContext): Boolean =
        Properties()
            .apply {
                setIfNotNull(SESSION_ID, context.sessionId)
                setIfNotNull(SERVICE_VERSION, context.serviceVersion)
                setIfNotNull(OS_NAME, context.osName)
                setIfNotNull(OS_VERSION, context.osVersion)
            }.let { writePropertiesAtomically(contextPath, it) }

    private fun readRecoveryAttemptState(): RecoveryAttemptState? =
        try {
            recoveryAttemptsPath.readProperties().toRecoveryAttemptStateOrNull()
        } catch (error: Exception) {
            Log.w(RumConstants.OTEL_RUM_LOG_TAG, "Failed to read native crash recovery attempts", error)
            null
        }

    private fun writePropertiesAtomically(
        path: File,
        properties: Properties,
    ): Boolean =
        try {
            directory.mkdirs()
            val temporaryPath = File(directory, "${path.name}.tmp")
            try {
                FileOutputStream(temporaryPath).use { output ->
                    properties.store(output, null)
                    output.fd.sync()
                }
                val replaced =
                    temporaryPath.renameTo(path) ||
                        (path.isFile && path.delete() && temporaryPath.renameTo(path))
                if (!replaced) {
                    throw IOException("Failed to replace ${path.name}")
                }
            } finally {
                temporaryPath.delete()
            }
            true
        } catch (error: Exception) {
            Log.w(RumConstants.OTEL_RUM_LOG_TAG, "Failed to persist ${path.name}", error)
            false
        }

    private fun Properties.toCrashRecordOrNull(): NativeCrashRecord? {
        return try {
            val signalNumber =
                getProperty(SIGNAL_NUMBER_KEY)
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?: return null
            val timestamp =
                getProperty(TIMESTAMP_EPOCH_NANOS_KEY)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?.toInstant()
                    ?: return null
            NativeCrashRecord(
                signalNumber = signalNumber,
                timestamp = timestamp,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun Properties.toCrashContextOrNull(): NativeCrashContext? {
        val context =
            NativeCrashContext(
                sessionId = nonBlankProperty(SESSION_ID),
                serviceVersion = nonBlankProperty(SERVICE_VERSION),
                osName = nonBlankProperty(OS_NAME),
                osVersion = nonBlankProperty(OS_VERSION),
            )
        return context.takeUnless { it.isEmpty() }
    }

    private fun Properties.toRecoveryAttemptStateOrNull(): RecoveryAttemptState? {
        val markerIdentity = getProperty(RECOVERY_MARKER_IDENTITY_KEY)?.takeIf { it.isNotBlank() } ?: return null
        val attempts = getProperty(RECOVERY_ATTEMPTS_KEY)?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val deliveryClaimed = getProperty(RECOVERY_DELIVERY_CLAIMED_KEY)?.toBooleanStrictOrNull() ?: false
        return RecoveryAttemptState(markerIdentity, attempts, deliveryClaimed)
    }

    private fun RecoveryAttemptState.toProperties(): Properties =
        Properties().apply {
            setProperty(RECOVERY_MARKER_IDENTITY_KEY, markerIdentity)
            setProperty(RECOVERY_ATTEMPTS_KEY, attempts.toString())
            setProperty(RECOVERY_DELIVERY_CLAIMED_KEY, deliveryClaimed.toString())
        }

    private fun deleteFile(
        path: File,
        description: String,
    ): Boolean =
        try {
            if (path.isFile && !fileDeleter(path)) {
                throw IOException("Failed to delete native crash $description")
            }
            true
        } catch (error: Exception) {
            Log.w(RumConstants.OTEL_RUM_LOG_TAG, "Failed to delete native crash $description", error)
            false
        }

    private companion object {
        const val SIGNAL_NUMBER_KEY = "signal.number"
        const val TIMESTAMP_EPOCH_NANOS_KEY = "timestamp.epoch_nanos"
        const val RECOVERY_MARKER_IDENTITY_KEY = "marker.identity"
        const val RECOVERY_ATTEMPTS_KEY = "attempts"
        const val RECOVERY_DELIVERY_CLAIMED_KEY = "delivery.claimed"
        const val MAX_RECOVERY_ATTEMPTS = 3
    }
}

private data class RecoveryAttemptState(
    val markerIdentity: String,
    val attempts: Int,
    val deliveryClaimed: Boolean,
)

internal data class NativeCrashRecord(
    val signalNumber: Int,
    val timestamp: Instant,
) {
    val signalName: String =
        when (signalNumber) {
            4 -> "SIGILL"
            5 -> "SIGTRAP"
            6 -> "SIGABRT"
            7 -> "SIGBUS"
            8 -> "SIGFPE"
            11 -> "SIGSEGV"
            31 -> "SIGSYS"
            else -> "SIG$signalNumber"
        }
}

internal data class NativeCrashContext(
    val sessionId: String?,
    val serviceVersion: String?,
    val osName: String?,
    val osVersion: String?,
) {
    fun isEmpty(): Boolean =
        sessionId == null &&
            serviceVersion == null &&
            osName == null &&
            osVersion == null

    fun addTo(attributes: AttributesBuilder) {
        attributes.putIfNotNull(SESSION_ID, sessionId)
        attributes.putIfNotNull(SERVICE_VERSION, serviceVersion)
        attributes.putIfNotNull(OS_NAME, osName)
        attributes.putIfNotNull(OS_VERSION, osVersion)
    }
}

private fun Context.currentCrashContext(openTelemetryRum: OpenTelemetryRum): NativeCrashContext {
    val packageInfo =
        try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (_: Exception) {
            null
        }
    return NativeCrashContext(
        sessionId = openTelemetryRum.sessionProvider.getSessionId().takeIf { it.isNotBlank() },
        serviceVersion = packageInfo?.versionName,
        osName = "Android",
        osVersion = Build.VERSION.RELEASE,
    )
}

private fun AttributesBuilder.putIfNotNull(
    key: String,
    value: String?,
) {
    value?.takeIf { it.isNotBlank() }?.let { put(stringKey(key), it) }
}

private fun Properties.setIfNotNull(
    key: String,
    value: String?,
) {
    value?.takeIf { it.isNotBlank() }?.let { setProperty(key, it) }
}

private fun Properties.nonBlankProperty(key: String): String? = getProperty(key)?.takeIf { it.isNotBlank() }

private fun File.readProperties(): Properties =
    Properties().also { properties ->
        FileInputStream(this).use { properties.load(it) }
    }

private fun File.identity(): String? = takeIf(File::isFile)?.let { "${it.lastModified()}:${it.length()}" }

private fun File.readExactBytes(expectedSize: Int): ByteArray? =
    FileInputStream(this).use { input ->
        if (input.channel.size() != expectedSize.toLong()) {
            return null
        }
        val result = ByteArray(expectedSize)
        var offset = 0
        while (offset < result.size) {
            val read = input.read(result, offset, result.size - offset)
            if (read <= 0) {
                throw IOException("Native crash snapshot changed while it was being read")
            }
            offset += read
        }
        if (input.read() != -1) {
            throw IOException("Native crash snapshot changed while it was being read")
        }
        result
    }

private const val NANOS_PER_SECOND = 1_000_000_000L

private fun Long.toInstant(): Instant = Instant.ofEpochSecond(Math.floorDiv(this, NANOS_PER_SECOND), Math.floorMod(this, NANOS_PER_SECOND))
