package org.nornsmobile.app;

/**
 * Bounded synchronous CDC writer. It preserves byte order across short transfers,
 * tolerates a small number of zero-length backpressure responses, and stops as
 * soon as the owning port is closed.
 */
final class UsbCdcWritePump {
    interface Transfer {
        int write(int offset, int length);
    }
    interface OpenState {
        boolean isOpen();
    }

    private static final int CHUNK_BYTES = 16384;
    private static final int MAX_ZERO_PROGRESS = 3;

    private UsbCdcWritePump() {}

    static boolean write(byte[] bytes, Transfer transfer, OpenState open) {
        if (bytes == null || bytes.length == 0 || bytes.length > 65536
                || transfer == null || open == null) return false;
        int offset = 0;
        int zeroProgress = 0;
        while (offset < bytes.length) {
            if (!open.isOpen()) return false;
            int requested = Math.min(bytes.length - offset, CHUNK_BYTES);
            int written = transfer.write(offset, requested);
            if (written < 0 || written > requested) return false;
            if (written == 0) {
                if (++zeroProgress >= MAX_ZERO_PROGRESS) return false;
                continue;
            }
            offset += written;
            zeroProgress = 0;
        }
        return true;
    }
}
