package com.nexbox.core.core.system.xposed;

import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

final class AtomicXposedConfigStorage implements XposedConfigStore.Storage {
    private final AtomicFile file;

    AtomicXposedConfigStorage(File path) { file = new AtomicFile(path); }

    @Override public byte[] read() throws IOException {
        try (FileInputStream input = file.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > XposedConfigState.MAX_BYTES) throw new IOException("Config exceeds size limit");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (FileNotFoundException e) {
            if (file.getBaseFile().exists()) throw e;
            return null;
        }
    }

    @Override public void write(byte[] bytes) throws IOException {
        File parent = file.getBaseFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create Xposed config directory");
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
        } catch (IOException | RuntimeException e) {
            file.failWrite(output);
            throw new IOException("Atomic Xposed config write failed", e);
        }
    }
}
