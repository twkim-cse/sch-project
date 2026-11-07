package com.insurance.explainer.persistence;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;

/** Refuse two Spring writers sharing a checkpoint directory. OS releases on process exit. */
@Component
public final class LocalStateLease implements DisposableBean,AutoCloseable {
    private FileChannel channel;
    private FileLock lock;
    public LocalStateLease(@Value("${ai.checkpoint-dir:}") String root) {
        if(root==null || root.isBlank())return;
        try {
            Path directory=Path.of(root).toAbsolutePath().normalize();Files.createDirectories(directory);
            if(Files.isSymbolicLink(directory))throw new LocalStateFiles.StorageFailure();
            channel=FileChannel.open(directory.resolve(".writer.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            lock=channel.tryLock();if(lock==null)throw new LocalStateFiles.StorageFailure();
        }catch(Exception e){close();throw new LocalStateFiles.StorageFailure();}
    }
    @Override public void destroy(){close();}
    @Override public void close(){
        try{if(lock!=null)lock.release();}catch(Exception ignored){}
        try{if(channel!=null)channel.close();}catch(Exception ignored){}
        lock=null;channel=null;
    }
}
