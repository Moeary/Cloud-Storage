package dev.cloudstorage.lifecycle;

import dev.cloudstorage.storage.ShutdownReceipt;
import org.bukkit.Server;
import java.lang.reflect.Field;
import java.nio.file.Path;

/** Paper 1.21.1 sets these volatile flags after player saves and RegionFileIOThread.deinit(). */
public final class PaperShutdownCheckpoint implements AutoCloseable {
    private final Object server;
    private final Field fullyShutdown,abnormalExit;
    private final Path database;
    private volatile String session;
    private final Thread hook;

    public PaperShutdownCheckpoint(Server bukkit,Path database) throws ReflectiveOperationException {
        this.database=database;
        server=bukkit.getClass().getMethod("getServer").invoke(bukkit);
        fullyShutdown=server.getClass().getField("hasFullyShutdown");
        abnormalExit=server.getClass().getField("abnormalExit");
        if (fullyShutdown.getType()!=boolean.class || abnormalExit.getType()!=boolean.class)
            throw new NoSuchFieldException("Paper shutdown flags are not booleans");
        hook=new Thread(this::finish,"CloudStorage shutdown checkpoint");
        Runtime.getRuntime().addShutdownHook(hook);
    }

    public void arm(String session) { this.session=session; }

    private void finish() {
        try {
            // A candidate alone, pending transfers, hot unload, crash or early SIGTERM cannot arm proof.
            if (session==null || !fullyShutdown.getBoolean(server) || abnormalExit.getBoolean(server)) return;
            ShutdownReceipt.write(database,session);
        } catch (Throwable error) {
            System.err.println("[CloudStorage] Shutdown proof unavailable; retaining dirty protection: "+error);
        }
    }

    @Override public void close() { Runtime.getRuntime().removeShutdownHook(hook); }
}
