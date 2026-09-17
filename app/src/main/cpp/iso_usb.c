// Transferencias USB isocronas para Tracker Bridge.
//
// La API USB de Android (Java) solo tiene transferencias de control, bulk e interrupt. Las webcams UVC
// normales mandan el video por endpoints isocronos, asi que aca los pedidos (URB) se envian directo al
// kernel con ioctl sobre el descriptor de archivo de UsbDeviceConnection. El permiso USB ya lo dio
// Android al abrir la conexion.
//
// usbfs deja los datos de cada paquete en su propia porcion del buffer (paquete i en i * packet_size),
// no uno tras otro; read() los compacta para Kotlin.

#include <errno.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>

typedef struct {
    int fd;
    int packet_size;
    int packets_per_urb;
    int urb_count;
    struct usbdevfs_urb **urbs;
    int *in_flight;
    jint *lengths;
    jint *statuses;
} iso_stream;

static struct usbdevfs_urb *urb_alloc(int endpoint, int packet_size, int packets) {
    struct usbdevfs_urb *urb =
        calloc(1, sizeof(struct usbdevfs_urb) + (size_t) packets * sizeof(struct usbdevfs_iso_packet_desc));
    if (urb == NULL) return NULL;
    urb->buffer = malloc((size_t) packet_size * (size_t) packets);
    if (urb->buffer == NULL) {
        free(urb);
        return NULL;
    }
    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = (unsigned char) endpoint;
    urb->buffer_length = packet_size * packets;
    urb->number_of_packets = packets;
    return urb;
}

// Deja el pedido listo para (re)enviarlo: el kernel sobrescribe largos y estados al completarlo.
static void urb_reset(struct usbdevfs_urb *urb, int packet_size, int packets) {
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->status = 0;
    urb->actual_length = 0;
    urb->start_frame = 0;
    urb->error_count = 0;
    for (int i = 0; i < packets; i++) {
        urb->iso_frame_desc[i].length = (unsigned int) packet_size;
        urb->iso_frame_desc[i].actual_length = 0;
        urb->iso_frame_desc[i].status = 0;
    }
}

static int urb_index(const iso_stream *s, const struct usbdevfs_urb *urb) {
    for (int i = 0; i < s->urb_count; i++) {
        if (s->urbs[i] == urb) return i;
    }
    return -1;
}

// Cancela los pedidos en curso y los recoge. Devuelve cuantos quedaron sin recoger.
static int stream_cancel(iso_stream *s) {
    int pending = 0;
    for (int i = 0; i < s->urb_count; i++) {
        if (s->in_flight[i]) {
            ioctl(s->fd, USBDEVFS_DISCARDURB, s->urbs[i]);
            pending++;
        }
    }
    for (int tries = 0; pending > 0 && tries < 100; tries++) {
        struct usbdevfs_urb *urb = NULL;
        if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &urb) == 0) {
            int idx = urb_index(s, urb);
            if (idx >= 0 && s->in_flight[idx]) {
                s->in_flight[idx] = 0;
                pending--;
            }
            continue;
        }
        // Camara desconectada: el kernel ya no tiene pedidos para devolver
        if (errno != EAGAIN) break;
        struct pollfd pfd = {.fd = s->fd, .events = POLLOUT};
        poll(&pfd, 1, 10);
    }
    return pending;
}

static void stream_free(iso_stream *s, int pending) {
    // Si quedo algun pedido sin recoger, el kernel podria seguir usando sus buffers: mejor no liberar nada
    if (s == NULL || pending > 0) return;
    if (s->urbs != NULL) {
        for (int i = 0; i < s->urb_count; i++) {
            if (s->urbs[i] != NULL) {
                free(s->urbs[i]->buffer);
                free(s->urbs[i]);
            }
        }
    }
    free(s->urbs);
    free(s->in_flight);
    free(s->lengths);
    free(s->statuses);
    free(s);
}

JNIEXPORT jlong JNICALL
Java_dev_rabbit_trackerbridge_IsoUsb_open(JNIEnv *env, jclass clazz, jint fd, jint endpoint,
                                          jint packet_size, jint packets_per_urb, jint urb_count) {
    (void) env;
    (void) clazz;
    if (fd < 0 || packet_size <= 0 || packets_per_urb <= 0 || urb_count <= 0) return -EINVAL;

    iso_stream *s = calloc(1, sizeof(iso_stream));
    if (s == NULL) return -ENOMEM;
    s->fd = fd;
    s->packet_size = packet_size;
    s->packets_per_urb = packets_per_urb;
    s->urb_count = urb_count;
    s->urbs = calloc((size_t) urb_count, sizeof(struct usbdevfs_urb *));
    s->in_flight = calloc((size_t) urb_count, sizeof(int));
    s->lengths = calloc((size_t) packets_per_urb, sizeof(jint));
    s->statuses = calloc((size_t) packets_per_urb, sizeof(jint));
    if (s->urbs == NULL || s->in_flight == NULL || s->lengths == NULL || s->statuses == NULL) {
        stream_free(s, 0);
        return -ENOMEM;
    }

    for (int i = 0; i < urb_count; i++) {
        s->urbs[i] = urb_alloc(endpoint, packet_size, packets_per_urb);
        if (s->urbs[i] == NULL) {
            stream_free(s, stream_cancel(s));
            return -ENOMEM;
        }
        urb_reset(s->urbs[i], packet_size, packets_per_urb);
        if (ioctl(fd, USBDEVFS_SUBMITURB, s->urbs[i]) < 0) {
            int err = errno;
            stream_free(s, stream_cancel(s));
            return -err;
        }
        s->in_flight[i] = 1;
    }
    return (jlong) (intptr_t) s;
}

JNIEXPORT jint JNICALL
Java_dev_rabbit_trackerbridge_IsoUsb_read(JNIEnv *env, jclass clazz, jlong handle, jbyteArray data,
                                          jintArray lengths, jintArray statuses, jint timeout_ms) {
    (void) clazz;
    iso_stream *s = (iso_stream *) (intptr_t) handle;
    if (s == NULL) return -EINVAL;

    struct usbdevfs_urb *urb = NULL;
    if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &urb) < 0) {
        if (errno != EAGAIN) return -errno;
        struct pollfd pfd = {.fd = s->fd, .events = POLLOUT};
        int ready = poll(&pfd, 1, timeout_ms);
        if (ready < 0) return errno == EINTR ? 0 : -errno;
        if (ready == 0) return 0;
        if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &urb) < 0) {
            if (errno != EAGAIN) return -errno;
            return (pfd.revents & (POLLERR | POLLHUP)) ? -ENODEV : 0;
        }
    }

    int idx = urb_index(s, urb);
    if (idx < 0) return -EIO;
    s->in_flight[idx] = 0;

    int packets = urb->number_of_packets;
    if (packets > s->packets_per_urb) packets = s->packets_per_urb;
    jsize max_packets = (*env)->GetArrayLength(env, lengths);
    jsize status_slots = (*env)->GetArrayLength(env, statuses);
    if (status_slots < max_packets) max_packets = status_slots;
    if (packets > max_packets) packets = max_packets;
    jsize capacity = (*env)->GetArrayLength(env, data);

    int count = 0;
    jbyte *out = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (out != NULL) {
        const unsigned char *src = urb->buffer;
        int pos = 0;
        for (int i = 0; i < packets; i++) {
            const struct usbdevfs_iso_packet_desc *d = &urb->iso_frame_desc[i];
            int len = (int) d->actual_length;
            if (len < 0 || len > s->packet_size || pos + len > capacity) len = 0;
            memcpy(out + pos, src + (size_t) i * (size_t) s->packet_size, (size_t) len);
            pos += len;
            s->lengths[i] = len;
            s->statuses[i] = (jint) d->status;
        }
        (*env)->ReleasePrimitiveArrayCritical(env, data, out, 0);
        (*env)->SetIntArrayRegion(env, lengths, 0, packets, s->lengths);
        (*env)->SetIntArrayRegion(env, statuses, 0, packets, s->statuses);
        count = packets;
    }

    // La camara se desconecto: no tiene sentido volver a enviar el pedido
    if (urb->status == -ESHUTDOWN || urb->status == -ENODEV) return -ENODEV;

    urb_reset(urb, s->packet_size, s->packets_per_urb);
    if (ioctl(s->fd, USBDEVFS_SUBMITURB, urb) < 0) return -errno;
    s->in_flight[idx] = 1;
    return count;
}

JNIEXPORT void JNICALL
Java_dev_rabbit_trackerbridge_IsoUsb_close(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    iso_stream *s = (iso_stream *) (intptr_t) handle;
    if (s == NULL) return;
    stream_free(s, stream_cancel(s));
}
