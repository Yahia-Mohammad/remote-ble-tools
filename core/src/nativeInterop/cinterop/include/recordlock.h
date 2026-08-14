#ifndef REMOTEBLE_RECORDLOCK_H
#define REMOTEBLE_RECORDLOCK_H

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <unistd.h>

/*
 * POSIX record locks are owned by the *process*, not by the thread or the descriptor that took
 * them. Two threads locking the same region therefore both succeed, and closing either descriptor
 * drops every lock the process holds on that file. This mutex serializes threads before they reach
 * fcntl, so exactly one descriptor and one record lock exist at a time and the fcntl lock means
 * what the cross-process callers assume it means.
 *
 * Deliberately non-recursive: withFileLock has no nested callers, and a recursive mutex would let a
 * future one deadlock the file lock's semantics silently instead of failing.
 */
static pthread_mutex_t rble_process_lock = PTHREAD_MUTEX_INITIALIZER;
static volatile sig_atomic_t rble_interrupted = 0;
static volatile sig_atomic_t rble_sigint_handler_installed = 0;
static void (*rble_previous_sigint_handler)(int) = SIG_DFL;

static void rble_handle_sigint(int signal_number) {
    (void)signal_number;
    rble_interrupted = 1;
}

static inline int rble_install_sigint_handler(void) {
    if (rble_sigint_handler_installed) return 1;
    rble_interrupted = 0;
    rble_previous_sigint_handler = signal(SIGINT, rble_handle_sigint);
    if (rble_previous_sigint_handler == SIG_ERR) return 0;
    rble_sigint_handler_installed = 1;
    return 1;
}

static inline void rble_restore_sigint_handler(void) {
    if (!rble_sigint_handler_installed) return;
    signal(SIGINT, rble_previous_sigint_handler);
    rble_sigint_handler_installed = 0;
    rble_interrupted = 0;
}

static inline int rble_take_interrupt(void) {
    int interrupted = rble_interrupted;
    rble_interrupted = 0;
    return interrupted;
}

static inline int rble_process_lock_acquire(void) {
    return pthread_mutex_lock(&rble_process_lock);
}

static inline int rble_process_lock_release(void) {
    return pthread_mutex_unlock(&rble_process_lock);
}

static inline int rble_try_write_lock(int fd) {
    struct flock lock = {0};
    lock.l_type = F_WRLCK;
    lock.l_whence = SEEK_SET;
    lock.l_start = 0;
    lock.l_len = 0;
    return fcntl(fd, F_SETLK, &lock);
}

static inline int rble_unlock(int fd) {
    struct flock lock = {0};
    lock.l_type = F_UNLCK;
    lock.l_whence = SEEK_SET;
    lock.l_start = 0;
    lock.l_len = 0;
    return fcntl(fd, F_SETLK, &lock);
}

static inline int rble_lock_is_contended(int value) {
    return value == EACCES || value == EAGAIN;
}

#endif
