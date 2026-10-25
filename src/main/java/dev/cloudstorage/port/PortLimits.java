package dev.cloudstorage.port;

import dev.cloudstorage.storage.Ledger;
import dev.cloudstorage.storage.StorageException;
import java.util.List;

/** Quotas aggregate a UUID's bindings across every world and selected vault, per direction. */
public record PortLimits(int inputs,int outputs) {
    public PortLimits { inputs=Math.max(0,inputs); outputs=Math.max(0,outputs); }
    public int limit(String mode) { return switch (mode) { case "input" -> inputs; case "output" -> outputs; default -> throw new IllegalArgumentException("Invalid port mode"); }; }
    public long count(List<Ledger.Port> ports,String owner,String mode) {
        return ports.stream().filter(p -> p.owner().equals(owner) && p.mode().equals(mode)).count();
    }
    public void requireRoom(List<Ledger.Port> ports,String owner,String mode) {
        if (count(ports,owner,mode)>=limit(mode)) throw new StorageException("已达个人"+(mode.equals("input")?"输入":"输出")+"接口上限（"+limit(mode)+" 个）；输入/输出分别计数");
    }
}
