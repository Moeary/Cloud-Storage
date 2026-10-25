package dev.cloudstorage.test;

import net.fabricmc.api.ClientModInitializer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test-only mod: validate lazy Mixin targets on the render thread after joining a disposable world. */
public final class DependencyProbe implements ClientModInitializer {
    private final AtomicBoolean finished=new AtomicBoolean();

    @Override public void onInitializeClient() {
        Thread.ofPlatform().daemon().name("cloud-dependency-probe").start(() -> {
            try {
                for (int attempt=0;attempt<360 && !finished.get();attempt++) {
                    Thread.sleep(500);
                    Class<?> type=Class.forName("net.minecraft.class_310");
                    Object client=type.getMethod("method_1551").invoke(null);
                    if (client instanceof Executor executor) executor.execute(() -> inspect(type,client));
                }
            } catch (Throwable error) { fail(error); }
        });
    }

    private void inspect(Class<?> type,Object client) {
        if (finished.get()) return;
        try {
            if (type.getField("field_1687").get(client)==null || type.getField("field_1724").get(client)==null) return;
            if (!finished.compareAndSet(false,true)) return;
            // These are the actual installed classes, transformed by every enabled production Mixin.
            Class.forName("fi.dy.masa.litematica.util.WorldUtils");
            Class.forName("fi.dy.masa.litematica.util.EasyPlaceUtils");
            Files.writeString(Path.of("dependency-probe.json"),"{\"status\":\"passed\",\"world_present\":true,\"player_present\":true}");
            System.out.println("CLOUD_DEPENDENCY_PROBE: world joined; WorldUtils and EasyPlaceUtils initialized");
        } catch (Throwable error) { fail(error); }
    }

    private void fail(Throwable error) {
        finished.set(true);
        System.err.println("CLOUD_DEPENDENCY_PROBE: failed");
        error.printStackTrace();
        try { Files.writeString(Path.of("dependency-probe.json"),"{\"status\":\"failed\"}"); }
        catch (Exception ignored) { }
    }
}
