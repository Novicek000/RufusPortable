package pl.rufusportable;

import android.content.Context;
import android.os.*;
import android.os.storage.StorageManager;
import android.system.*;
import java.io.*;
import java.util.concurrent.*;

/** A writable AppFuse view of an NTFS file on the target disk; owns the file. */
final class ProxyNtfsFile implements Closeable {
    private final NtfsVolume.RandomFile file;
    private final HandlerThread thread;
    private final ParcelFileDescriptor proxy;
    private final Handler handler;
    private final CountDownLatch released=new CountDownLatch(1);
    private volatile IOException ioFailure;

    ProxyNtfsFile(Context context,NtfsVolume.RandomFile file) throws IOException {
        this.file=file;
        Thread owner=Thread.currentThread();
        thread=new HandlerThread("rufus-ntfs-file");thread.start();
        handler=new Handler(thread.getLooper());
        try {
            StorageManager storage=(StorageManager)context.getSystemService(Context.STORAGE_SERVICE);
            proxy=storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_WRITE,
                    new ProxyFileDescriptorCallback() {
                        private void check() throws IOException {
                            if(owner.isInterrupted()) throw new InterruptedIOException("Przerwano modyfikację WIM.");
                        }
                        private ErrnoException failure(IOException error) {
                            record(error);return new ErrnoException("NTFS proxy",OsConstants.EIO);
                        }
                        private void range(long offset,int size,byte[] data) throws ErrnoException {
                            if(offset<0 || size<0 || size>data.length || size>Long.MAX_VALUE-offset)
                                throw new ErrnoException("NTFS proxy",OsConstants.EINVAL);
                        }
                        @Override public long onGetSize() throws ErrnoException {
                            try {check();return file.size();}catch(IOException error){throw failure(error);}
                        }
                        @Override public int onRead(long offset,int size,byte[] data) throws ErrnoException {
                            range(offset,size,data);
                            try {check();return file.read(offset,data,0,size);}catch(IOException error){throw failure(error);}
                        }
                        @Override public int onWrite(long offset,int size,byte[] data) throws ErrnoException {
                            range(offset,size,data);
                            try {check();file.write(offset,data,0,size);return size;}catch(IOException error){throw failure(error);}
                        }
                        @Override public void onFsync() throws ErrnoException {
                            try {check();file.sync();}catch(IOException error){throw failure(error);}
                        }
                        @Override public void onRelease() {
                            try {file.close();}catch(IOException error){record(error);}
                            finally {released.countDown();thread.quitSafely();}
                        }
                    },handler);
        } catch(IOException | RuntimeException error) {
            thread.quitSafely();
            try {file.close();}catch(IOException closing){error.addSuppressed(closing);}
            throw error;
        }
    }
    private synchronized void record(IOException failure) {
        if(ioFailure==null) ioFailure=failure;
        else if(ioFailure!=failure) ioFailure.addSuppressed(failure);
    }
    String path() {return "/proc/self/fd/"+proxy.getFd();}
    IOException failure() {return ioFailure;}
    void completeUpdate(long length) throws IOException {
        if(length<212)throw new IOException("Nieprawidłowy rozmiar zmodyfikowanego WIM.");
        file.truncate(length);file.sync();
    }
    @Override public void close() throws IOException {
        IOException failure=null;
        handler.postDelayed(thread::quitSafely,5000);
        // Native WIM calls have released their duplicate descriptors by now.
        // Close synchronously so a metadata-flush error cannot be hidden in
        // the asynchronous AppFuse onRelease callback.
        try {file.close();}catch(IOException error){failure=error;}
        try {proxy.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        try {if(Thread.currentThread()!=thread)released.await(2,TimeUnit.SECONDS);}
        catch(InterruptedException cancelled){Thread.currentThread().interrupt();}
        if(failure!=null) throw failure;
    }
}
