package pl.rufusportable;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Offline Windows registry access for BCD and Windows To Go configuration. */
final class RegistryHive implements Closeable {
    static {System.loadLibrary("rufus_hive");}
    static final int STRING=1,EXPAND_STRING=2,BINARY=3,DWORD=4,MULTI_STRING=7,QWORD=11;
    private long handle;
    static final class Value {
        final int type;
        final byte[] data;
        Value(int type,byte[] data){this.type=type;this.data=data;}
        int dword() throws IOException {
            if(type!=DWORD || data.length!=4)throw new IOException("Nieprawidłowa wartość DWORD rejestru.");
            return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt();
        }
        String string() throws IOException {
            if((type!=STRING && type!=EXPAND_STRING) || data.length<2 || data.length%2!=0 || data[data.length-1]!=0 || data[data.length-2]!=0)
                throw new IOException("Nieprawidłowy ciąg tekstowy rejestru.");
            return new String(data,0,data.length-2,StandardCharsets.UTF_16LE);
        }
    }
    RegistryHive(File file,boolean writable) throws IOException {handle=open(encoded(file.getAbsolutePath()),writable);}
    private void requireOpen() throws IOException {if(handle==0)throw new IOException("Plik rejestru jest zamknięty.");}
    private static byte[] encoded(String text) throws IOException {
        if(text==null || text.indexOf(0)>=0)throw new IOException("Nieprawidłowa nazwa rejestru.");
        for(int i=0;i<text.length();i++) {
            char value=text.charAt(i);
            if(Character.isHighSurrogate(value)){if(++i>=text.length() || !Character.isLowSurrogate(text.charAt(i)))throw new IOException("Nieprawidłowy znak Unicode w rejestrze.");}
            else if(Character.isLowSurrogate(value))throw new IOException("Nieprawidłowy znak Unicode w rejestrze.");
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }
    private static byte[] path(String text) throws IOException {
        byte[] bytes=encoded(text);
        if(!text.isEmpty())for(String part:text.split("\\\\",-1))if(part.isEmpty())throw new IOException("Pusta część ścieżki rejestru.");
        return bytes;
    }
    synchronized Value get(String path,String name) throws IOException {
        requireOpen();byte[][] value=read(handle,path(path),encoded(name));
        return value==null?null:new Value(ByteBuffer.wrap(value[0]).order(ByteOrder.LITTLE_ENDIAN).getInt(),value[1]);
    }
    synchronized List<String> children(String path) throws IOException {
        requireOpen();byte[][] names=list(handle,path(path));List<String> result=new ArrayList<>();
        for(byte[] name:names)result.add(new String(name,StandardCharsets.UTF_8));return Collections.unmodifiableList(result);
    }
    synchronized void put(String path,String name,int type,byte[] data) throws IOException {
        requireOpen();if(type<0 || type>11 || data==null)throw new IOException("Nieprawidłowy typ wartości rejestru.");
        write(handle,path(path),encoded(name),type,data);
    }
    synchronized void putDword(String path,String name,int value) throws IOException {
        put(path,name,DWORD,ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    }
    synchronized void putString(String path,String name,String value) throws IOException {
        encoded(value);byte[] bytes=value.getBytes(StandardCharsets.UTF_16LE);put(path,name,STRING,Arrays.copyOf(bytes,bytes.length+2));
    }
    synchronized boolean deleteKey(String path) throws IOException {
        requireOpen();if(path.isEmpty())throw new IOException("Nie można usunąć korzenia rejestru.");return remove(handle,path(path));
    }
    synchronized void commit(File destination) throws IOException {requireOpen();save(handle,encoded(destination.getAbsolutePath()));}
    @Override public synchronized void close() throws IOException {
        if(handle!=0){long previous=handle;handle=0;release(previous);}
    }
    private static native long open(byte[] filename,boolean writable) throws IOException;
    private static native byte[][] read(long handle,byte[] path,byte[] name) throws IOException;
    private static native byte[][] list(long handle,byte[] path) throws IOException;
    private static native void write(long handle,byte[] path,byte[] name,int type,byte[] data) throws IOException;
    private static native boolean remove(long handle,byte[] path) throws IOException;
    private static native void save(long handle,byte[] filename) throws IOException;
    private static native void release(long handle) throws IOException;
}
