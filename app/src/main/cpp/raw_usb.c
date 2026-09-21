// Acceso USB directo al kernel (usbfs) para interfaces que Android no muestra.
//
// Visto en Pico: la interfaz de video de una placa OpenIris aparece en los descriptores, pero Android
// no la incluye en UsbDevice, asi que la API de Java no puede reclamarla ni leer su endpoint. Con el
// mismo descriptor de archivo de UsbDeviceConnection se puede hacer todo por numero de interfaz.

#include <errno.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>

// Reclama la interfaz, soltando antes el driver del kernel que la tenga (uvcvideo, por ejemplo),
// igual que claimInterface(iface, true) de Android. Devuelve 0 o -errno.
JNIEXPORT jint JNICALL
Java_dev_rabbit_trackerbridge_RawUsb_claimInterface(JNIEnv *env, jclass clazz, jint fd, jint iface) {
    (void) env;
    (void) clazz;
    struct usbdevfs_disconnect_claim dc;
    memset(&dc, 0, sizeof(dc));
    dc.interface = (unsigned int) iface;
    dc.flags = USBDEVFS_DISCONNECT_CLAIM_EXCEPT_DRIVER;
    strcpy(dc.driver, "usbfs");
    if (ioctl(fd, USBDEVFS_DISCONNECT_CLAIM, &dc) == 0) return 0;

    // Kernels sin DISCONNECT_CLAIM: soltar el driver (puede no haber ninguno) y reclamar
    struct usbdevfs_ioctl cmd;
    memset(&cmd, 0, sizeof(cmd));
    cmd.ifno = iface;
    cmd.ioctl_code = USBDEVFS_DISCONNECT;
    ioctl(fd, USBDEVFS_IOCTL, &cmd);
    unsigned int n = (unsigned int) iface;
    if (ioctl(fd, USBDEVFS_CLAIMINTERFACE, &n) == 0) return 0;
    return -errno;
}

JNIEXPORT jint JNICALL
Java_dev_rabbit_trackerbridge_RawUsb_releaseInterface(JNIEnv *env, jclass clazz, jint fd, jint iface) {
    (void) env;
    (void) clazz;
    unsigned int n = (unsigned int) iface;
    return ioctl(fd, USBDEVFS_RELEASEINTERFACE, &n) == 0 ? 0 : -errno;
}

JNIEXPORT jint JNICALL
Java_dev_rabbit_trackerbridge_RawUsb_setInterface(JNIEnv *env, jclass clazz, jint fd, jint iface, jint alt) {
    (void) env;
    (void) clazz;
    struct usbdevfs_setinterface si;
    si.interface = (unsigned int) iface;
    si.altsetting = (unsigned int) alt;
    return ioctl(fd, USBDEVFS_SETINTERFACE, &si) == 0 ? 0 : -errno;
}

// Lectura bulk sincronica, como bulkTransfer() de Android. Devuelve los bytes leidos, 0 si se agoto el
// tiempo, o -errno. El buffer intermedio evita bloquear el recolector de Java durante la espera.
JNIEXPORT jint JNICALL
Java_dev_rabbit_trackerbridge_RawUsb_bulkRead(JNIEnv *env, jclass clazz, jint fd, jint endpoint,
                                              jbyteArray data, jint length, jint timeout_ms) {
    (void) clazz;
    jsize capacity = (*env)->GetArrayLength(env, data);
    if (length > capacity) length = capacity;
    if (length <= 0) return -EINVAL;
    void *buf = malloc((size_t) length);
    if (buf == NULL) return -ENOMEM;

    struct usbdevfs_bulktransfer bt;
    bt.ep = (unsigned int) endpoint;
    bt.len = (unsigned int) length;
    bt.timeout = (unsigned int) timeout_ms;
    bt.data = buf;
    int n = ioctl(fd, USBDEVFS_BULK, &bt);
    int err = errno;
    if (n > 0) (*env)->SetByteArrayRegion(env, data, 0, n, (const jbyte *) buf);
    free(buf);
    if (n >= 0) return n;
    return err == ETIMEDOUT ? 0 : -err;
}
