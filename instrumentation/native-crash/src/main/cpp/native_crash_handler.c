/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

#include <errno.h>
#include <elf.h>
#include <fcntl.h>
#include <jni.h>
#include <link.h>
#include <limits.h>
#include <stddef.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdbool.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/ucontext.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>

// Compile the snapshot format's layout assertions before capture uses the record.
#include "native_crash_snapshot.h"

#define SIGNAL_COUNT 7
#define TEMPORARY_SUFFIX ".tmp"
#define MARKER_BUFFER_SIZE 128
#define NANOS_PER_SECOND UINT64_C(1000000000)
#define ALTERNATE_STACK_SIZE (SIGSTKSZ * 2)

#ifndef SA_EXPOSE_TAGBITS
#define SA_EXPOSE_TAGBITS 0x00000800
#endif

static const int handled_signals[SIGNAL_COUNT] = {
    SIGILL,
    SIGTRAP,
    SIGABRT,
    SIGBUS,
    SIGFPE,
    SIGSEGV,
    SIGSYS,
};

static char crash_record_path[PATH_MAX];
static char temporary_crash_record_path[PATH_MAX];
static char crash_snapshot_path[PATH_MAX];
static char temporary_crash_snapshot_path[PATH_MAX];
static struct sigaction previous_actions[SIGNAL_COUNT];
static atomic_bool handler_active[SIGNAL_COUNT];
static unsigned char alternate_signal_stack[ALTERNATE_STACK_SIZE];
static pthread_mutex_t install_mutex = PTHREAD_MUTEX_INITIALIZER;
static bool handlers_installed = false;
static bool alternate_signal_stack_installed = false;
static atomic_flag handling_signal = ATOMIC_FLAG_INIT;
static struct otel_native_crash_snapshot crash_snapshot;
static bool crash_snapshot_prepared = false;

#ifdef OTEL_NATIVE_CRASH_TESTING
static volatile sig_atomic_t test_previous_handler_count[SIGNAL_COUNT];
static volatile sig_atomic_t test_previous_handler_saw_context[SIGNAL_COUNT];
static volatile sig_atomic_t test_previous_handler_saw_mask[SIGNAL_COUNT];
static volatile sig_atomic_t test_paused_signal = 0;
static atomic_int test_pause_state;
#endif

static int find_signal_index(int signal_number) {
    for (int index = 0; index < SIGNAL_COUNT; index++) {
        if (handled_signals[index] == signal_number) {
            return index;
        }
    }
    return -1;
}

static bool append_bytes(
    char *buffer,
    size_t capacity,
    size_t *length,
    const char *value,
    size_t value_length) {
    if (value_length > capacity - *length) {
        return false;
    }
    for (size_t index = 0; index < value_length; index++) {
        buffer[(*length)++] = value[index];
    }
    return true;
}

static bool append_uint64(
    char *buffer,
    size_t capacity,
    size_t *length,
    uint64_t value) {
    char digits[20];
    size_t digit_count = 0;
    do {
        digits[digit_count++] = (char) ('0' + (value % 10));
        value /= 10;
    } while (value != 0);

    if (digit_count > capacity - *length) {
        return false;
    }
    while (digit_count > 0) {
        buffer[(*length)++] = digits[--digit_count];
    }
    return true;
}

static bool write_all(int file_descriptor, const char *buffer, size_t length) {
    size_t written = 0;
    while (written < length) {
        ssize_t result = write(file_descriptor, buffer + written, length - written);
        if (result > 0) {
            written += (size_t) result;
        } else if (result < 0 && errno == EINTR) {
            continue;
        } else {
            return false;
        }
    }
    return true;
}

static bool write_atomic_file(
    const char *path,
    const char *temporary_path,
    const void *bytes,
    size_t length,
    bool sync_file) {
    int file_descriptor = open(temporary_path, O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC, 0600);
    if (file_descriptor < 0) {
        return false;
    }

    bool complete = write_all(file_descriptor, (const char *) bytes, length);
    if (complete && sync_file) {
        complete = fsync(file_descriptor) == 0;
    }
    if (close(file_descriptor) != 0) {
        complete = false;
    }
    if (!complete || rename(temporary_path, path) != 0) {
        unlink(temporary_path);
        return false;
    }
    return true;
}

static void copy_module_name(char *destination, const char *source) {
    static const char main_module_name[] = "<main>";
    if (source == NULL || source[0] == '\0') {
        source = main_module_name;
    } else {
        const char *basename = source;
        for (const char *cursor = source; *cursor != '\0'; cursor++) {
            if (*cursor == '/') {
                basename = cursor + 1;
            }
        }
        if (*basename != '\0') {
            source = basename;
        }
    }

    size_t index = 0;
    while (index + 1 < OTEL_NCS_MODULE_NAME_SIZE && source[index] != '\0') {
        unsigned char byte = (unsigned char) source[index];
        destination[index] = byte >= 0x20 && byte <= 0x7e ? (char) byte : '_';
        index++;
    }
    destination[index] = '\0';
}

static bool checked_note_size(uint32_t value, size_t *aligned) {
    if ((size_t) value > SIZE_MAX - 3) {
        return false;
    }
    *aligned = ((size_t) value + 3) & ~(size_t) 3;
    return true;
}

static void copy_build_id(
    const struct dl_phdr_info *info,
    struct otel_native_crash_module *module) {
    uint64_t load_bias = (uint64_t) info->dlpi_addr;
    for (ElfW(Half) index = 0; index < info->dlpi_phnum; index++) {
        const ElfW(Phdr) *header = &info->dlpi_phdr[index];
        if (header->p_type != PT_NOTE || header->p_memsz < sizeof(ElfW(Nhdr))) {
            continue;
        }
        uint64_t virtual_address = (uint64_t) header->p_vaddr;
        uint64_t segment_size = (uint64_t) header->p_memsz;
        if (load_bias > UINT64_MAX - virtual_address ||
            load_bias + virtual_address > (uint64_t) UINTPTR_MAX ||
            segment_size > (uint64_t) SIZE_MAX) {
            continue;
        }

        const unsigned char *cursor =
            (const unsigned char *) (uintptr_t) (load_bias + virtual_address);
        size_t remaining = (size_t) segment_size;
        while (remaining >= sizeof(ElfW(Nhdr))) {
            ElfW(Nhdr) note;
            memcpy(&note, cursor, sizeof(note));
            size_t name_size;
            size_t description_size;
            if (!checked_note_size(note.n_namesz, &name_size) ||
                !checked_note_size(note.n_descsz, &description_size) ||
                name_size > remaining - sizeof(note) ||
                description_size > remaining - sizeof(note) - name_size) {
                break;
            }
            const unsigned char *name = cursor + sizeof(note);
            const unsigned char *description = name + name_size;
            if (note.n_type == NT_GNU_BUILD_ID && note.n_namesz == 4 &&
                memcmp(name, "GNU", 4) == 0 && note.n_descsz > 0 &&
                note.n_descsz <= OTEL_NCS_BUILD_ID_SIZE) {
                module->build_id_size = note.n_descsz;
                memcpy(module->build_id, description, note.n_descsz);
                return;
            }
            size_t consumed = sizeof(note) + name_size + description_size;
            cursor += consumed;
            remaining -= consumed;
        }
    }
}

static bool path_starts_with(const char *path, const char *prefix) {
    while (*prefix != '\0') {
        if (*path++ != *prefix++) {
            return false;
        }
    }
    return true;
}

static bool is_app_owned_module(const char *path) {
    if (path == NULL || path[0] == '\0') {
        return true;
    }
    return path_starts_with(path, "/data/app/") ||
        path_starts_with(path, "/data/data/") ||
        path_starts_with(path, "/data/user/") ||
        path_starts_with(path, "/mnt/expand/") || strstr(path, "!/lib/") != NULL;
}

struct module_collection {
    struct otel_native_crash_snapshot *snapshot;
    bool app_owned;
};

static int collect_executable_module(
    struct dl_phdr_info *info,
    size_t info_size,
    void *data) {
    (void) info_size;
    struct module_collection *collection = (struct module_collection *) data;
    if (is_app_owned_module(info->dlpi_name) != collection->app_owned) {
        return 0;
    }
    struct otel_native_crash_snapshot *snapshot = collection->snapshot;
    uint64_t load_bias = (uint64_t) info->dlpi_addr;
    for (ElfW(Half) index = 0; index < info->dlpi_phnum; index++) {
        const ElfW(Phdr) *header = &info->dlpi_phdr[index];
        if (header->p_type != PT_LOAD || (header->p_flags & PF_X) == 0) {
            continue;
        }
        if (snapshot->module_count >= OTEL_NCS_MAX_MODULES) {
            return 1;
        }
        uint64_t virtual_address = (uint64_t) header->p_vaddr;
        uint64_t memory_size = (uint64_t) header->p_memsz;
        if (memory_size == 0) {
            continue;
        }
        if (load_bias > UINT64_MAX - virtual_address ||
            load_bias + virtual_address > UINT64_MAX - memory_size) {
            continue;
        }
        uint64_t segment_start = load_bias + virtual_address;
        uint64_t segment_end = segment_start + memory_size;
        struct otel_native_crash_module *module = &snapshot->modules[snapshot->module_count++];
        module->load_bias = load_bias;
        module->executable_start = segment_start;
        module->executable_end = segment_end;
        copy_module_name(module->name, info->dlpi_name);
        copy_build_id(info, module);
    }
    return 0;
}

static bool prepare_crash_snapshot_at(struct otel_native_crash_snapshot *snapshot) {
    static const unsigned char magic[OTEL_NCS_MAGIC_SIZE] = OTEL_NCS_MAGIC;
    memset(snapshot, 0, sizeof(*snapshot));
    memcpy(snapshot->magic, magic, sizeof(magic));
    snapshot->version = OTEL_NCS_VERSION;
    snapshot->architecture = OTEL_NCS_ARCH_CURRENT;
    snapshot->record_size = (uint32_t) sizeof(*snapshot);
    if (snapshot->architecture == 0) {
        return false;
    }
    struct module_collection collection = {
        .snapshot = snapshot,
        .app_owned = true,
    };
    dl_iterate_phdr(collect_executable_module, &collection);
    if (snapshot->module_count < OTEL_NCS_MAX_MODULES) {
        collection.app_owned = false;
        dl_iterate_phdr(collect_executable_module, &collection);
    }
    return snapshot->module_count > 0;
}

static bool prepare_crash_snapshot(void) {
    return prepare_crash_snapshot_at(&crash_snapshot);
}

static bool capture_registers(
    void *user_context,
    struct otel_native_crash_snapshot *snapshot) {
    if (user_context == NULL) {
        return false;
    }
    const ucontext_t *context = (const ucontext_t *) user_context;
#if defined(__arm__)
    snapshot->program_counter = (uint64_t) (uint32_t) context->uc_mcontext.arm_pc;
    snapshot->stack_pointer = (uint64_t) (uint32_t) context->uc_mcontext.arm_sp;
    snapshot->frame_pointer = (uint64_t) (uint32_t) context->uc_mcontext.arm_fp;
    snapshot->link_register = (uint64_t) (uint32_t) context->uc_mcontext.arm_lr;
#elif defined(__aarch64__)
    snapshot->program_counter = (uint64_t) context->uc_mcontext.pc;
    snapshot->stack_pointer = (uint64_t) context->uc_mcontext.sp;
    snapshot->frame_pointer = (uint64_t) context->uc_mcontext.regs[29];
    snapshot->link_register = (uint64_t) context->uc_mcontext.regs[30];
#elif defined(__i386__)
    snapshot->program_counter = (uint64_t) (uint32_t) context->uc_mcontext.gregs[REG_EIP];
    snapshot->stack_pointer = (uint64_t) (uint32_t) context->uc_mcontext.gregs[REG_ESP];
    snapshot->frame_pointer = (uint64_t) (uint32_t) context->uc_mcontext.gregs[REG_EBP];
    snapshot->link_register = 0;
#elif defined(__x86_64__)
    snapshot->program_counter = (uint64_t) context->uc_mcontext.gregs[REG_RIP];
    snapshot->stack_pointer = (uint64_t) context->uc_mcontext.gregs[REG_RSP];
    snapshot->frame_pointer = (uint64_t) context->uc_mcontext.gregs[REG_RBP];
    snapshot->link_register = 0;
#else
    return false;
#endif
    return snapshot->stack_pointer != 0;
}

static uint32_t snapshot_checksum(const struct otel_native_crash_snapshot *snapshot) {
    const unsigned char *bytes = (const unsigned char *) snapshot;
    uint32_t checksum = OTEL_NCS_FNV_OFFSET_BASIS;
    for (size_t index = 0; index < offsetof(struct otel_native_crash_snapshot, checksum); index++) {
        checksum ^= bytes[index];
        checksum *= OTEL_NCS_FNV_PRIME;
    }
    return checksum;
}

static size_t capture_stack(uint64_t stack_pointer, unsigned char *destination) {
    if (stack_pointer == 0 || stack_pointer > (uint64_t) UINTPTR_MAX) {
        return 0;
    }
    struct iovec local = {
        .iov_base = destination,
        .iov_len = OTEL_NCS_STACK_CAPACITY,
    };
    struct iovec remote = {
        .iov_base = (void *) (uintptr_t) stack_pointer,
        .iov_len = OTEL_NCS_STACK_CAPACITY,
    };
    ssize_t copied =
        syscall(SYS_process_vm_readv, getpid(), &local, 1UL, &remote, 1UL, 0UL);
    if (copied <= 0) {
        return 0;
    }
    if ((size_t) copied > OTEL_NCS_STACK_CAPACITY) {
        return OTEL_NCS_STACK_CAPACITY;
    }
    return (size_t) copied;
}

static bool write_crash_snapshot(
    int signal_number,
    uint64_t timestamp_epoch_nanos,
    void *user_context) {
    if (!crash_snapshot_prepared || !capture_registers(user_context, &crash_snapshot)) {
        return false;
    }
    crash_snapshot.signal_number = (uint32_t) signal_number;
    crash_snapshot.timestamp_epoch_nanos = timestamp_epoch_nanos;
    crash_snapshot.stack_start = crash_snapshot.stack_pointer;
    crash_snapshot.stack_size =
        (uint32_t) capture_stack(crash_snapshot.stack_pointer, crash_snapshot.stack);
    crash_snapshot.checksum = snapshot_checksum(&crash_snapshot);
    return write_atomic_file(
        crash_snapshot_path,
        temporary_crash_snapshot_path,
        &crash_snapshot,
        sizeof(crash_snapshot),
        false);
}

static bool write_crash_marker_at(
    const char *record_path,
    const char *temporary_record_path,
    int signal_number,
    uint64_t timestamp_epoch_nanos) {
    char marker[MARKER_BUFFER_SIZE];
    size_t marker_length = 0;
    static const char signal_key[] = "signal.number=";
    static const char timestamp_key[] = "\ntimestamp.epoch_nanos=";
    static const char newline[] = "\n";
    if (!append_bytes(marker, sizeof(marker), &marker_length, signal_key, sizeof(signal_key) - 1) ||
        !append_uint64(marker, sizeof(marker), &marker_length, (uint64_t) signal_number) ||
        !append_bytes(
            marker,
            sizeof(marker),
            &marker_length,
            timestamp_key,
            sizeof(timestamp_key) - 1) ||
        !append_uint64(marker, sizeof(marker), &marker_length, timestamp_epoch_nanos) ||
        !append_bytes(marker, sizeof(marker), &marker_length, newline, sizeof(newline) - 1)) {
        return false;
    }

    return write_atomic_file(record_path, temporary_record_path, marker, marker_length, true);
}

static void record_crash(int signal_number, void *user_context) {
    // The marker and snapshot must share this exact timestamp; see SNAPSHOT_FORMAT.md.
    struct timespec crash_time;
    if (clock_gettime(CLOCK_REALTIME, &crash_time) != 0 || crash_time.tv_sec < 0 ||
        crash_time.tv_nsec < 0) {
        return;
    }

    uint64_t seconds = (uint64_t) crash_time.tv_sec;
    uint64_t nanoseconds = (uint64_t) crash_time.tv_nsec;
    if (seconds > (UINT64_MAX - nanoseconds) / NANOS_PER_SECOND) {
        return;
    }
    uint64_t timestamp_epoch_nanos = seconds * NANOS_PER_SECOND + nanoseconds;
    if (!write_crash_marker_at(
            crash_record_path,
            temporary_crash_record_path,
            signal_number,
            timestamp_epoch_nanos)) {
        return;
    }
    if (!write_crash_snapshot(signal_number, timestamp_epoch_nanos, user_context)) {
        unlink(crash_snapshot_path);
    }
}

static void rollback_installed_handlers(void) {
    for (int index = 0; index < SIGNAL_COUNT; index++) {
        if (atomic_load_explicit(&handler_active[index], memory_order_relaxed)) {
            sigaction(handled_signals[index], &previous_actions[index], NULL);
        }
    }
}

static void remove_alternate_signal_stack(void) {
    if (!alternate_signal_stack_installed) {
        return;
    }
    stack_t disabled_stack;
    memset(&disabled_stack, 0, sizeof(disabled_stack));
    disabled_stack.ss_flags = SS_DISABLE;
    sigaltstack(&disabled_stack, NULL);
    alternate_signal_stack_installed = false;
}

static bool prepare_alternate_signal_stack(void) {
    stack_t current_stack;
    if (sigaltstack(NULL, &current_stack) != 0) {
        return false;
    }
    if ((current_stack.ss_flags & SS_DISABLE) == 0) {
        return true;
    }

    stack_t new_stack;
    memset(&new_stack, 0, sizeof(new_stack));
    new_stack.ss_sp = alternate_signal_stack;
    new_stack.ss_size = sizeof(alternate_signal_stack);
    if (sigaltstack(&new_stack, NULL) != 0) {
        return false;
    }
    alternate_signal_stack_installed = true;
    return true;
}

static bool signal_will_reraise_autonomously(
    int signal_number,
    const siginfo_t *signal_info) {
    if (signal_info == NULL ||
        (signal_number != SIGBUS && signal_number != SIGFPE &&
         signal_number != SIGILL && signal_number != SIGSEGV)) {
        return false;
    }

    int signal_code = signal_info->si_code;
    return signal_code > 0 && signal_code != SI_ASYNCIO && signal_code != SI_MESGQ &&
        signal_code != SI_QUEUE && signal_code != SI_TIMER && signal_code != SI_USER &&
#ifdef SI_DETHREAD
        signal_code != SI_DETHREAD &&
#endif
#ifdef SI_KERNEL
        signal_code != SI_KERNEL &&
#endif
#ifdef SI_SIGIO
        signal_code != SI_SIGIO &&
#endif
#ifdef SI_TKILL
        signal_code != SI_TKILL &&
#endif
        true;
}

static void restore_previous_handler_and_reraise(
    int signal_number,
    siginfo_t *signal_info) {
    int index = find_signal_index(signal_number);
    if (index < 0) {
        return;
    }

    struct sigaction previous = previous_actions[index];
    // Re-deliver through the kernel so the previous action keeps its own mask and flags. Only the
    // crashing signal is restored; registrations for the other fatal signals remain untouched.
    if (sigaction(signal_number, &previous, NULL) != 0) {
        _exit(128 + signal_number);
    }
    if (previous.sa_handler == SIG_IGN) {
        return;
    }

#if defined(SYS_rt_tgsigqueueinfo) && defined(SYS_gettid)
    if (signal_info != NULL) {
        if (syscall(
                SYS_rt_tgsigqueueinfo,
                getpid(),
                syscall(SYS_gettid),
                signal_number,
                signal_info) == 0) {
            return;
        }
        // Linux kernels before 3.9 reject self-sent siginfo with EPERM. Other failures are
        // unexpected, so stop rather than invoke the previous handler with altered semantics.
        if (errno != EPERM) {
            _exit(128 + signal_number);
        }
    }
#endif

    // A synchronous hardware fault will recur when this handler returns. Raising it here would
    // replace the original fault details, including si_addr, on older kernels.
    if (!signal_will_reraise_autonomously(signal_number, signal_info) &&
        raise(signal_number) != 0) {
        _exit(128 + signal_number);
    }
}

static void handle_signal(
    int signal_number,
    siginfo_t *signal_info,
    void *user_context) {
    int saved_errno = errno;
    bool first_signal =
        !atomic_flag_test_and_set_explicit(&handling_signal, memory_order_relaxed);
#ifdef OTEL_NATIVE_CRASH_TESTING
    if (first_signal && test_paused_signal == signal_number) {
        atomic_store_explicit(&test_pause_state, 1, memory_order_release);
        while (atomic_load_explicit(&test_pause_state, memory_order_acquire) == 1) {
        }
    }
#endif
    if (first_signal) {
        record_crash(signal_number, user_context);
    }
    errno = saved_errno;
    restore_previous_handler_and_reraise(signal_number, signal_info);
    errno = saved_errno;
}

static bool install_handlers(void) {
    if (!prepare_alternate_signal_stack()) {
        return false;
    }

    struct sigaction action;
    memset(&action, 0, sizeof(action));
    if (sigfillset(&action.sa_mask) != 0) {
        remove_alternate_signal_stack();
        return false;
    }
    action.sa_sigaction = handle_signal;
    action.sa_flags = SA_RESTART | SA_SIGINFO | SA_ONSTACK | SA_RESETHAND | SA_EXPOSE_TAGBITS;

    for (int index = 0; index < SIGNAL_COUNT; index++) {
        if (!atomic_is_lock_free(&handler_active[index])) {
            remove_alternate_signal_stack();
            return false;
        }
        if (sigaction(handled_signals[index], NULL, &previous_actions[index]) != 0) {
            remove_alternate_signal_stack();
            return false;
        }
        atomic_store_explicit(&handler_active[index], false, memory_order_relaxed);
    }

    for (int index = 0; index < SIGNAL_COUNT; index++) {
        if (previous_actions[index].sa_handler == SIG_IGN) {
            continue;
        }
        atomic_store_explicit(&handler_active[index], true, memory_order_relaxed);
        if (sigaction(handled_signals[index], &action, NULL) != 0) {
            atomic_store_explicit(&handler_active[index], false, memory_order_relaxed);
            rollback_installed_handlers();
            remove_alternate_signal_stack();
            return false;
        }
    }
    return true;
}

static bool build_crash_record_paths(
    const char *path,
    size_t path_length,
    char *record_path,
    size_t record_path_capacity,
    char *temporary_record_path,
    size_t temporary_record_path_capacity) {
    size_t suffix_length = sizeof(TEMPORARY_SUFFIX) - 1;
    if (path == NULL || path_length == 0 || path_length >= record_path_capacity ||
        suffix_length >= temporary_record_path_capacity ||
        path_length >= temporary_record_path_capacity - suffix_length) {
        return false;
    }
    memcpy(record_path, path, path_length);
    record_path[path_length] = '\0';
    memcpy(temporary_record_path, path, path_length);
    memcpy(temporary_record_path + path_length, TEMPORARY_SUFFIX, suffix_length + 1);
    return true;
}

static bool install_for_paths(
    const char *marker_path,
    size_t marker_path_length,
    const char *snapshot_path,
    size_t snapshot_path_length) {
    pthread_mutex_lock(&install_mutex);
    bool installed;
    if (handlers_installed) {
        installed =
            strcmp(crash_record_path, marker_path) == 0 &&
            strcmp(crash_snapshot_path, snapshot_path) == 0;
    } else {
        installed =
            build_crash_record_paths(
                marker_path,
                marker_path_length,
                crash_record_path,
                sizeof(crash_record_path),
                temporary_crash_record_path,
                sizeof(temporary_crash_record_path)) &&
            build_crash_record_paths(
                snapshot_path,
                snapshot_path_length,
                crash_snapshot_path,
                sizeof(crash_snapshot_path),
                temporary_crash_snapshot_path,
                sizeof(temporary_crash_snapshot_path));
        if (installed) {
            crash_snapshot_prepared = prepare_crash_snapshot();
            installed = install_handlers();
        }
        handlers_installed = installed;
    }
    pthread_mutex_unlock(&install_mutex);
    return installed;
}

JNIEXPORT jboolean JNICALL
Java_io_opentelemetry_android_instrumentation_nativecrash_NativeCrashJni_install(
    JNIEnv *environment,
    jclass native_crash_jni,
    jstring marker_path,
    jstring snapshot_path) {
    (void) native_crash_jni;
    if (marker_path == NULL || snapshot_path == NULL) {
        return JNI_FALSE;
    }

    jsize marker_path_length = (*environment)->GetStringUTFLength(environment, marker_path);
    jsize snapshot_path_length = (*environment)->GetStringUTFLength(environment, snapshot_path);
    if (marker_path_length <= 0 || snapshot_path_length <= 0) {
        return JNI_FALSE;
    }

    const char *marker = (*environment)->GetStringUTFChars(environment, marker_path, NULL);
    if (marker == NULL) {
        return JNI_FALSE;
    }
    const char *snapshot = (*environment)->GetStringUTFChars(environment, snapshot_path, NULL);
    if (snapshot == NULL) {
        (*environment)->ReleaseStringUTFChars(environment, marker_path, marker);
        return JNI_FALSE;
    }

    bool installed =
        install_for_paths(
            marker,
            (size_t) marker_path_length,
            snapshot,
            (size_t) snapshot_path_length);

    (*environment)->ReleaseStringUTFChars(environment, snapshot_path, snapshot);
    (*environment)->ReleaseStringUTFChars(environment, marker_path, marker);
    return installed ? JNI_TRUE : JNI_FALSE;
}

#ifdef OTEL_NATIVE_CRASH_TESTING
static void test_previous_signal_handler(
    int signal_number,
    siginfo_t *signal_info,
    void *user_context) {
    int saved_errno = errno;
    int index = find_signal_index(signal_number);
    if (index >= 0) {
        test_previous_handler_count[index]++;
        if (signal_info != NULL && signal_info->si_signo == signal_number && user_context != NULL) {
            test_previous_handler_saw_context[index] = 1;
        }
        sigset_t current_mask;
        if (sigprocmask(SIG_SETMASK, NULL, &current_mask) == 0 &&
            sigismember(&current_mask, SIGUSR1) == 1) {
            test_previous_handler_saw_mask[index] = 1;
        }
    }
    errno = saved_errno;
}

static void reset_test_observations(void) {
    for (int index = 0; index < SIGNAL_COUNT; index++) {
        test_previous_handler_count[index] = 0;
        test_previous_handler_saw_context[index] = 0;
        test_previous_handler_saw_mask[index] = 0;
    }
    test_paused_signal = 0;
    atomic_store_explicit(&test_pause_state, 0, memory_order_relaxed);
    atomic_flag_clear_explicit(&handling_signal, memory_order_relaxed);
}

static bool set_test_paths(
    JNIEnv *environment,
    jstring marker_path,
    jstring snapshot_path) {
    if (marker_path == NULL || snapshot_path == NULL) {
        return false;
    }
    jsize marker_path_length = (*environment)->GetStringUTFLength(environment, marker_path);
    jsize snapshot_path_length = (*environment)->GetStringUTFLength(environment, snapshot_path);
    if (marker_path_length <= 0 || snapshot_path_length <= 0) {
        return false;
    }

    const char *marker = (*environment)->GetStringUTFChars(environment, marker_path, NULL);
    if (marker == NULL) {
        return false;
    }
    const char *snapshot = (*environment)->GetStringUTFChars(environment, snapshot_path, NULL);
    if (snapshot == NULL) {
        (*environment)->ReleaseStringUTFChars(environment, marker_path, marker);
        return false;
    }
    bool paths_ready =
        build_crash_record_paths(
            marker,
            (size_t) marker_path_length,
            crash_record_path,
            sizeof(crash_record_path),
            temporary_crash_record_path,
            sizeof(temporary_crash_record_path)) &&
        build_crash_record_paths(
            snapshot,
            (size_t) snapshot_path_length,
            crash_snapshot_path,
            sizeof(crash_snapshot_path),
            temporary_crash_snapshot_path,
            sizeof(temporary_crash_snapshot_path));
    (*environment)->ReleaseStringUTFChars(environment, snapshot_path, snapshot);
    (*environment)->ReleaseStringUTFChars(environment, marker_path, marker);
    return paths_ready;
}

static void restore_test_signal_state(const struct sigaction *original_actions) {
    for (int index = 0; index < SIGNAL_COUNT; index++) {
        (void) sigaction(handled_signals[index], &original_actions[index], NULL);
        atomic_store_explicit(&handler_active[index], false, memory_order_relaxed);
    }
    remove_alternate_signal_stack();
    crash_snapshot_prepared = false;
    reset_test_observations();
}

static bool prepare_test_signal_state(
    JNIEnv *environment,
    jstring marker_path,
    jstring snapshot_path,
    struct sigaction *original_actions) {
    if (handlers_installed || !atomic_is_lock_free(&test_pause_state) ||
        !set_test_paths(environment, marker_path, snapshot_path)) {
        return false;
    }
    unlink(crash_record_path);
    unlink(temporary_crash_record_path);
    unlink(crash_snapshot_path);
    unlink(temporary_crash_snapshot_path);
    reset_test_observations();

    struct sigaction previous;
    memset(&previous, 0, sizeof(previous));
    if (sigemptyset(&previous.sa_mask) != 0 ||
        sigaddset(&previous.sa_mask, SIGUSR1) != 0) {
        return false;
    }
    previous.sa_sigaction = test_previous_signal_handler;
    previous.sa_flags = SA_RESTART | SA_SIGINFO;

    int configured = 0;
    for (; configured < SIGNAL_COUNT; configured++) {
        if (sigaction(handled_signals[configured], NULL, &original_actions[configured]) != 0 ||
            sigaction(handled_signals[configured], &previous, NULL) != 0) {
            break;
        }
    }
    if (configured != SIGNAL_COUNT) {
        for (int index = 0; index < configured; index++) {
            (void) sigaction(handled_signals[index], &original_actions[index], NULL);
        }
        return false;
    }

    crash_snapshot_prepared = prepare_crash_snapshot();
    if (!install_handlers()) {
        restore_test_signal_state(original_actions);
        return false;
    }
    return true;
}

static bool test_observed_previous_handler(int signal_number) {
    int index = find_signal_index(signal_number);
    return index >= 0 && test_previous_handler_count[index] == 1 &&
        test_previous_handler_saw_context[index] == 1 &&
        test_previous_handler_saw_mask[index] == 1;
}

static void *raise_test_signal(void *argument) {
    int signal_number = *(const int *) argument;
    return (void *) (intptr_t) (raise(signal_number) == 0 ? 0 : 1);
}

JNIEXPORT jint JNICALL
Java_io_opentelemetry_android_instrumentation_nativecrash_NativeCrashTestJni_compiledArchitecture(
    JNIEnv *environment,
    jclass native_crash_test_jni) {
    (void) environment;
    (void) native_crash_test_jni;
    return (jint) OTEL_NCS_ARCH_CURRENT;
}

JNIEXPORT jboolean JNICALL
Java_io_opentelemetry_android_instrumentation_nativecrash_NativeCrashTestJni_runHandlerChainingTest(
    JNIEnv *environment,
    jclass native_crash_test_jni,
    jstring marker_path,
    jstring snapshot_path) {
    (void) native_crash_test_jni;
    struct sigaction original_actions[SIGNAL_COUNT];
    if (!prepare_test_signal_state(
            environment,
            marker_path,
            snapshot_path,
            original_actions)) {
        return JNI_FALSE;
    }

    errno = E2BIG;
    int raise_result = raise(SIGABRT);
    int observed_errno = errno;
    bool passed =
        raise_result == 0 && observed_errno == E2BIG &&
        test_observed_previous_handler(SIGABRT) &&
        access(crash_record_path, F_OK) == 0 &&
        access(crash_snapshot_path, F_OK) == 0;
    restore_test_signal_state(original_actions);
    return passed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_opentelemetry_android_instrumentation_nativecrash_NativeCrashTestJni_runConcurrentSignalTest(
    JNIEnv *environment,
    jclass native_crash_test_jni,
    jstring marker_path,
    jstring snapshot_path) {
    (void) native_crash_test_jni;
    struct sigaction original_actions[SIGNAL_COUNT];
    if (!prepare_test_signal_state(
            environment,
            marker_path,
            snapshot_path,
            original_actions)) {
        return JNI_FALSE;
    }

    int first_signal = SIGABRT;
    int second_signal = SIGSEGV;
    pthread_t first_thread;
    pthread_t second_thread;
    void *first_result = (void *) (intptr_t) 1;
    void *second_result = (void *) (intptr_t) 1;
    bool first_started = false;
    bool second_started = false;
    test_paused_signal = first_signal;

    if (pthread_create(&first_thread, NULL, raise_test_signal, &first_signal) == 0) {
        first_started = true;
        for (int attempt = 0;
             attempt < 1000000 &&
             atomic_load_explicit(&test_pause_state, memory_order_acquire) != 1;
             attempt++) {
            sched_yield();
        }
        if (atomic_load_explicit(&test_pause_state, memory_order_acquire) == 1 &&
            pthread_create(&second_thread, NULL, raise_test_signal, &second_signal) == 0) {
            second_started = true;
            (void) pthread_join(second_thread, &second_result);
        }
        atomic_store_explicit(&test_pause_state, 2, memory_order_release);
        (void) pthread_join(first_thread, &first_result);
    }

    bool passed =
        first_started && second_started && first_result == NULL && second_result == NULL &&
        test_observed_previous_handler(first_signal) &&
        test_observed_previous_handler(second_signal) &&
        access(crash_record_path, F_OK) == 0 &&
        access(crash_snapshot_path, F_OK) == 0;
    restore_test_signal_state(original_actions);
    return passed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_opentelemetry_android_instrumentation_nativecrash_NativeCrashTestJni_writeCrashMarker(
    JNIEnv *environment,
    jclass native_crash_test_jni,
    jstring marker_path,
    jint signal_number,
    jlong timestamp_epoch_nanos) {
    (void) native_crash_test_jni;
    if (marker_path == NULL || signal_number <= 0 || timestamp_epoch_nanos <= 0) {
        return JNI_FALSE;
    }

    jsize path_length = (*environment)->GetStringUTFLength(environment, marker_path);
    if (path_length <= 0) {
        return JNI_FALSE;
    }

    const char *path = (*environment)->GetStringUTFChars(environment, marker_path, NULL);
    if (path == NULL) {
        return JNI_FALSE;
    }

    char test_crash_record_path[PATH_MAX];
    char test_temporary_crash_record_path[PATH_MAX];
    bool written =
        build_crash_record_paths(
            path,
            (size_t) path_length,
            test_crash_record_path,
            sizeof(test_crash_record_path),
            test_temporary_crash_record_path,
            sizeof(test_temporary_crash_record_path)) &&
        write_crash_marker_at(
            test_crash_record_path,
            test_temporary_crash_record_path,
            (int) signal_number,
            (uint64_t) timestamp_epoch_nanos);

    (*environment)->ReleaseStringUTFChars(environment, marker_path, path);
    return written ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_opentelemetry_android_instrumentation_nativecrash_NativeCrashTestJni_captureCrashSnapshot(
    JNIEnv *environment,
    jclass native_crash_test_jni,
    jstring snapshot_path,
    jint signal_number,
    jlong timestamp_epoch_nanos) {
    (void) native_crash_test_jni;
    if (snapshot_path == NULL || signal_number <= 0 || timestamp_epoch_nanos <= 0) {
        return JNI_FALSE;
    }

    jsize path_length = (*environment)->GetStringUTFLength(environment, snapshot_path);
    if (path_length <= 0) {
        return JNI_FALSE;
    }
    const char *path = (*environment)->GetStringUTFChars(environment, snapshot_path, NULL);
    if (path == NULL) {
        return JNI_FALSE;
    }

    char test_snapshot_path[PATH_MAX];
    char test_temporary_snapshot_path[PATH_MAX];
    struct otel_native_crash_snapshot snapshot;
    if (!prepare_crash_snapshot_at(&snapshot)) {
        (*environment)->ReleaseStringUTFChars(environment, snapshot_path, path);
        return JNI_FALSE;
    }
    snapshot.signal_number = (uint32_t) signal_number;
    snapshot.timestamp_epoch_nanos = (uint64_t) timestamp_epoch_nanos;
    snapshot.program_counter = (uint64_t) (uintptr_t) __builtin_return_address(0);
    snapshot.stack_pointer = (uint64_t) (uintptr_t) __builtin_frame_address(0);
    snapshot.frame_pointer = snapshot.stack_pointer;
    snapshot.link_register =
        snapshot.architecture == OTEL_NCS_ARCH_ARM ||
            snapshot.architecture == OTEL_NCS_ARCH_ARM64
        ? snapshot.program_counter
        : 0;
    snapshot.stack_start = snapshot.stack_pointer;
    snapshot.stack_size =
        (uint32_t) capture_stack(snapshot.stack_pointer, snapshot.stack);
    if (snapshot.program_counter == 0 || snapshot.stack_pointer == 0 || snapshot.stack_size == 0) {
        (*environment)->ReleaseStringUTFChars(environment, snapshot_path, path);
        return JNI_FALSE;
    }
    snapshot.checksum = snapshot_checksum(&snapshot);

    bool written =
        build_crash_record_paths(
            path,
            (size_t) path_length,
            test_snapshot_path,
            sizeof(test_snapshot_path),
            test_temporary_snapshot_path,
            sizeof(test_temporary_snapshot_path)) &&
        write_atomic_file(
            test_snapshot_path,
            test_temporary_snapshot_path,
            &snapshot,
            sizeof(snapshot),
            false);
    (*environment)->ReleaseStringUTFChars(environment, snapshot_path, path);
    return written ? JNI_TRUE : JNI_FALSE;
}
#endif
