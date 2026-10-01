#ifndef SPECTRUM_RESET_RETRY_H
#define SPECTRUM_RESET_RETRY_H
/* Retry only transient libusb I/O, timeout and interrupted errors, once.
 * Both FIFO control writes must be repeated by reset_fn before any IQ read. */
static int spectrum_reset_retry(void *dev, int (*reset_fn)(void *),
                               void (*pause_fn)(void), int *attempts) {
    *attempts = 1;
    int rc = reset_fn(dev);
    if (rc == -1 || rc == -7 || rc == -10) {
        pause_fn();
        *attempts = 2;
        rc = reset_fn(dev);
    }
    return rc;
}
#endif
