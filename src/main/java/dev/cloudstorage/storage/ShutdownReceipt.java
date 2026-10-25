package dev.cloudstorage.storage;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Properties;
import java.time.Instant;

/** Late Paper shutdown evidence. The database stays dirty until a subsequent JVM verifies it. */
public final class ShutdownReceipt {
    private ShutdownReceipt() {}
    public static Path file(Path database) { return database.resolveSibling(database.getFileName()+".shutdown.properties"); }

    public static void write(Path database,String session) throws IOException {
        ProcessHandle process=ProcessHandle.current();
        Properties receipt=new Properties();
        receipt.setProperty("format","1");
        receipt.setProperty("proof","paper-1.21.1-completed-normal-shutdown");
        receipt.setProperty("session",session);
        receipt.setProperty("pid",Long.toString(process.pid()));
        receipt.setProperty("started",process.info().startInstant().map(Object::toString).orElse(""));
        Path path=file(database),temporary=path.resolveSibling(path.getFileName()+".tmp");
        try (OutputStream out=Files.newOutputStream(temporary)) { receipt.store(out,"CloudStorage late shutdown receipt; not an inventory backup"); }
        try (FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE)) { channel.force(true); }
        Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }

    public static boolean completed(Path database,String session,String candidate) {
        if (session==null || session.isBlank() || !session.equals(candidate)) return false;
        try {
            Path path=file(database);
            if (!Files.isRegularFile(path) || Files.size(path)>4096) return false;
            Properties receipt=new Properties();
            try (InputStream in=Files.newInputStream(path)) { receipt.load(in); }
            if (!"1".equals(receipt.getProperty("format"))
                    || !"paper-1.21.1-completed-normal-shutdown".equals(receipt.getProperty("proof"))
                    || !session.equals(receipt.getProperty("session"))) return false;
            long pid=Long.parseLong(receipt.getProperty("pid"));
            if (pid<=0) return false;
            ProcessHandle previous=ProcessHandle.of(pid).orElse(null);
            if (previous==null || !previous.isAlive()) return true;
            // A reused PID is not the old JVM. An unknown start time is conservatively alive.
            String started=receipt.getProperty("started","");
            Instant actual=previous.info().startInstant().orElse(null);
            return !started.isBlank() && actual!=null && actual.isAfter(Instant.parse(started));
        } catch (IOException | RuntimeException ignored) { return false; }
    }
}
