package pl.rufusportable;

import android.content.Context;
import android.os.*;
import android.os.storage.StorageManager;
import android.system.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;

/** A seekable view of one ISO/UDF file, exposed to native code through Android AppFuse. */
final class ProxyImageFile implements Closeable {
    private final FileInputStream source;
    private final HandlerThread thread;
    private final ParcelFileDescriptor proxy;
    private volatile IOException readFailure;

    ProxyImageFile(Context context, ImageFile image, IsoTree.Entry entry) throws IOException {
        ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(image.uri, "r");
        if (descriptor == null) {
            throw new IOException("Nie można otworzyć obrazu źródłowego.");
        }
        source = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
        FileChannel channel = source.getChannel();
        Thread owner = Thread.currentThread();
        thread = new HandlerThread("rufus-image-reader");
        thread.start();
        try {
            StorageManager storage = (StorageManager)context.getSystemService(Context.STORAGE_SERVICE);
            proxy = storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,
                    new ProxyFileDescriptorCallback() {
                        @Override
                        public long onGetSize() {
                            return entry.length;
                        }

                        @Override
                        public int onRead(long offset, int size, byte[] data) throws ErrnoException {
                            if (offset < 0 || size < 0 || size > data.length) {
                                throw new ErrnoException("WIM read", OsConstants.EINVAL);
                            }
                            if (offset >= entry.length || size == 0) {
                                return 0;
                            }
                            int requested = (int)Math.min(size, entry.length - offset);
                            long logical = 0;
                            int copied = 0;
                            try {
                                for (IsoTree.Extent extent : entry.extents) {
                                    if (owner.isInterrupted()) {
                                        throw new InterruptedIOException("Przerwano odczyt obrazu.");
                                    }
                                    if (offset >= logical + extent.length) {
                                        logical += extent.length;
                                        continue;
                                    }
                                    long within = offset - logical;
                                    int count = (int)Math.min(requested - copied, extent.length - within);
                                    ByteBuffer buffer = ByteBuffer.wrap(data, copied, count);
                                    long position = extent.offset + within;
                                    while (buffer.hasRemaining()) {
                                        int read = channel.read(buffer, position);
                                        if (read < 0) {
                                            throw new EOFException("Niepełny plik WIM w obrazie.");
                                        }
                                        if (read == 0) {
                                            throw new IOException("Źródło nie udostępnia odczytu pozycyjnego.");
                                        }
                                        position += read;
                                    }
                                    copied += count;
                                    offset += count;
                                    logical += extent.length;
                                    if (copied == requested) {
                                        return copied;
                                    }
                                }
                                throw new EOFException("Niepełne ekstenty pliku WIM.");
                            } catch (IOException error) {
                                readFailure = error;
                                throw new ErrnoException("WIM read", OsConstants.EIO);
                            }
                        }

                        @Override
                        public void onRelease() {
                            try {
                                source.close();
                            } catch (IOException error) {
                                readFailure = error;
                            }
                        }
                    }, new Handler(thread.getLooper()));
        } catch (IOException | RuntimeException error) {
            thread.quitSafely();
            try {
                source.close();
            } catch (IOException closing) {
                error.addSuppressed(closing);
            }
            throw error;
        }
    }

    String path() {
        return "/proc/self/fd/" + proxy.getFd();
    }

    IOException sourceFailure() {
        return readFailure;
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            proxy.close();
        } catch (IOException error) {
            failure = error;
        } finally {
            thread.quitSafely();
        }
        try {
            source.close();
        } catch (IOException error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }
}
