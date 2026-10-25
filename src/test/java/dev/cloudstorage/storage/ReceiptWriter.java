package dev.cloudstorage.storage;

import java.nio.file.Path;

/** Test child JVM: its exit is independently observed by the parent test. */
public final class ReceiptWriter {
    public static void main(String[] args) throws Exception { ShutdownReceipt.write(Path.of(args[0]),args[1]); }
}
