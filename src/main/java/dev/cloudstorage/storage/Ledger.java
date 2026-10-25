package dev.cloudstorage.storage;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** One connection, serialized transactions. External inventory writes are journaled, never replayed. */
public final class Ledger implements AutoCloseable {
    public static final long MAX_AMOUNT = 9_000_000_000_000_000L;
    public record Entry(ItemData item, long amount) {}
    public record Pending(String id, String vault, String key, long delta, String endpoint, long created) {}
    public record Port(String world, int x, int y, int z, String owner, String vault, String mode, String filter) {
        public String id() { return world + ":" + x + ":" + y + ":" + z; }
    }

    private final Connection db;
    private final long capacity;
    private final int maxTypes;
    private boolean frozen;
    private boolean failed;
    private boolean confirmedShutdown;
    private final String session=UUID.randomUUID().toString();

    public Ledger(Path path, long capacity, int maxTypes) throws SQLException {
        if (capacity < 1 || capacity > MAX_AMOUNT || maxTypes < 1) throw new IllegalArgumentException("Invalid capacity");
        this.capacity = capacity;
        this.maxTypes = maxTypes;
        db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=FULL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
            try (ResultSet r = s.executeQuery("PRAGMA user_version")) {
                if (r.next() && r.getInt(1) > 1) throw new SQLException("Database schema is newer than this plugin");
            }
            s.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS items (key TEXT PRIMARY KEY,material TEXT NOT NULL,data BLOB NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS stock (vault TEXT NOT NULL,key TEXT NOT NULL REFERENCES items(key),amount INTEGER NOT NULL CHECK(amount>0 AND amount<=9000000000000000),PRIMARY KEY(vault,key))");
            s.execute("CREATE TABLE IF NOT EXISTS journal (id TEXT PRIMARY KEY,vault TEXT NOT NULL,key TEXT NOT NULL REFERENCES items(key),delta INTEGER NOT NULL,endpoint TEXT NOT NULL,before BLOB NOT NULL,after BLOB NOT NULL,state TEXT NOT NULL CHECK(state IN ('PENDING','DONE','REVERSED')),created INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS journal_state ON journal(state)");
            s.execute("CREATE TABLE IF NOT EXISTS preferences (owner TEXT PRIMARY KEY,mode TEXT NOT NULL CHECK(mode IN ('personal','shared')))");
            s.execute("CREATE TABLE IF NOT EXISTS ports (id TEXT PRIMARY KEY,world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,owner TEXT NOT NULL,vault TEXT NOT NULL,mode TEXT NOT NULL CHECK(mode IN ('input','output')),filter TEXT)");
            s.execute("PRAGMA user_version=1");
        } catch (SQLException | RuntimeException e) {
            db.close();
            throw e;
        }
        try {
            boolean hasPending=!pending().isEmpty();
            confirmedShutdown=!hasPending && ShutdownReceipt.completed(path,meta("session"),meta("shutdown_ready"));
            frozen = ("true".equals(meta("active")) && !confirmedShutdown) || hasPending;
            putMeta("session",session);
            putMeta("shutdown_ready","");
            putMeta("active", "true");
        } catch (SQLException | RuntimeException e) {
            db.close();
            throw e;
        }
    }

    public synchronized boolean frozen() { return frozen || failed; }
    public boolean confirmedShutdown() { return confirmedShutdown; }
    public String session() { return session; }
    public synchronized void freeze() { frozen = true; }

    private String meta(String key) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            p.setString(1, key);
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }

    private void putMeta(String key, String value) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("INSERT INTO meta VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            p.setString(1, key); p.setString(2, value); p.executeUpdate();
        }
    }

    public synchronized long amount(String vault, String key) {
        try (PreparedStatement p = db.prepareStatement("SELECT amount FROM stock WHERE vault=? AND key=?")) {
            p.setString(1, vault); p.setString(2, key);
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getLong(1) : 0; }
        } catch (SQLException e) { throw failure(e); }
    }

    public synchronized List<Entry> entries(String vault) {
        List<Entry> entries = new ArrayList<>();
        try (PreparedStatement p = db.prepareStatement("SELECT i.key,i.material,i.data,s.amount FROM stock s JOIN items i USING(key) WHERE s.vault=? ORDER BY i.material,i.key")) {
            p.setString(1, vault);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) entries.add(new Entry(new ItemData(r.getString(1),r.getString(2),r.getBytes(3)),r.getLong(4)));
            }
            return List.copyOf(entries);
        } catch (SQLException e) { throw failure(e); }
    }

    public synchronized Optional<ItemData> item(String key) {
        try (PreparedStatement p = db.prepareStatement("SELECT material,data FROM items WHERE key=?")) {
            p.setString(1, key);
            try (ResultSet r = p.executeQuery()) { return r.next() ? Optional.of(new ItemData(key,r.getString(1),r.getBytes(2))) : Optional.empty(); }
        } catch (SQLException e) { throw failure(e); }
    }

    /** Commits a vault delta and external before/after images in the SAME SQLite transaction. */
    public synchronized void prepare(String id, String vault, ItemData item, long delta, String endpoint, byte[] before, byte[] after) {
        requireWritable();
        if (id == null || vault == null || vault.isBlank() || endpoint == null || before == null || after == null
                || delta == 0 || delta > MAX_AMOUNT || delta < -MAX_AMOUNT) throw new IllegalArgumentException("Invalid transfer");
        transaction(() -> {
            try (PreparedStatement p = db.prepareStatement("INSERT INTO items VALUES (?,?,?) ON CONFLICT(key) DO NOTHING")) {
                p.setString(1,item.key()); p.setString(2,item.material()); p.setBytes(3,item.bytes()); p.executeUpdate();
            }
            ItemData saved = item(item.key()).orElseThrow();
            if (!saved.material().equals(item.material()) || !Arrays.equals(saved.bytes(),item.bytes()))
                throw new StorageException("Item key collision");
            long next = Math.addExact(amount(vault,item.key()),delta);
            if (next < 0 || next > MAX_AMOUNT) throw new StorageException("库存不足或数量超出上限");
            if (delta > 0) {
                List<Entry> all = entries(vault);
                long total = 0;
                for (Entry e : all) total = Math.addExact(total,e.amount());
                if (delta > capacity - total) throw new StorageException("云仓容量已满");
                if (amount(vault,item.key()) == 0 && all.size() >= maxTypes) throw new StorageException("云仓种类已满");
            }
            setAmount(vault,item.key(),next);
            try (PreparedStatement p = db.prepareStatement("INSERT INTO journal VALUES (?,?,?,?,?,?,?,'PENDING',?)")) {
                p.setString(1,id); p.setString(2,vault); p.setString(3,item.key()); p.setLong(4,delta); p.setString(5,endpoint);
                p.setBytes(6,before); p.setBytes(7,after); p.setLong(8,System.currentTimeMillis()); p.executeUpdate();
            }
        });
    }

    private void setAmount(String vault, String key, long amount) throws SQLException {
        if (amount == 0) {
            try (PreparedStatement p = db.prepareStatement("DELETE FROM stock WHERE vault=? AND key=?")) {
                p.setString(1,vault); p.setString(2,key); p.executeUpdate();
            }
        } else {
            try (PreparedStatement p = db.prepareStatement("INSERT INTO stock VALUES (?,?,?) ON CONFLICT(vault,key) DO UPDATE SET amount=excluded.amount")) {
                p.setString(1,vault); p.setString(2,key); p.setLong(3,amount); p.executeUpdate();
            }
        }
    }

    public synchronized void settle(String id) {
        try (PreparedStatement p = db.prepareStatement("UPDATE journal SET state='DONE' WHERE id=? AND state='PENDING'")) {
            p.setString(1,id);
            if (p.executeUpdate() != 1) throw new StorageException("转移不存在或已完成");
        } catch (SQLException e) { throw failure(e); }
    }

    public synchronized List<Pending> pending() {
        List<Pending> out = new ArrayList<>();
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT id,vault,key,delta,endpoint,created FROM journal WHERE state='PENDING' ORDER BY created")) {
            while (r.next()) out.add(new Pending(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(5),r.getLong(6)));
            return List.copyOf(out);
        } catch (SQLException e) { throw failure(e); }
    }

    /** Operator attestation only. NEVER writes or replays an external inventory. */
    public synchronized void resolve(String id, boolean externalAfter) {
        transaction(() -> {
            Pending op = pending().stream().filter(p -> p.id().equals(id)).findFirst()
                    .orElseThrow(() -> new StorageException("未找到待恢复转移"));
            if (!externalAfter) {
                long next = Math.subtractExact(amount(op.vault(),op.key()),op.delta());
                if (next < 0 || next > MAX_AMOUNT) throw new StorageException("回退数量不合法，请离线审计");
                setAmount(op.vault(),op.key(),next);
            }
            try (PreparedStatement p = db.prepareStatement("UPDATE journal SET state=? WHERE id=?")) {
                p.setString(1,externalAfter ? "DONE" : "REVERSED"); p.setString(2,id); p.executeUpdate();
            }
        });
    }

    public synchronized void unlock() {
        if (failed) throw new StorageException("数据库故障，修复后重启再审计");
        if (!pending().isEmpty()) throw new StorageException("仍有未核对的 PENDING 转移");
        frozen = false;
    }

    public synchronized String mode(UUID owner, String defaultMode) {
        try (PreparedStatement p = db.prepareStatement("SELECT mode FROM preferences WHERE owner=?")) {
            p.setString(1,owner.toString());
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getString(1) : defaultMode; }
        } catch (SQLException e) { throw failure(e); }
    }

    public synchronized void setMode(UUID owner, String mode) {
        requireWritable();
        if (!Set.of("personal","shared").contains(mode)) throw new IllegalArgumentException("Invalid mode");
        try (PreparedStatement p = db.prepareStatement("INSERT INTO preferences VALUES (?,?) ON CONFLICT(owner) DO UPDATE SET mode=excluded.mode")) {
            p.setString(1,owner.toString()); p.setString(2,mode); p.executeUpdate();
        } catch (SQLException e) { throw failure(e); }
    }

    public synchronized List<Port> ports() {
        List<Port> ports = new ArrayList<>();
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT world,x,y,z,owner,vault,mode,filter FROM ports ORDER BY id")) {
            while (r.next()) ports.add(new Port(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8)));
            return List.copyOf(ports);
        } catch (SQLException e) { throw failure(e); }
    }

    public synchronized void bind(Port port) {
        requireWritable();
        // Existing binding can only be changed by explicitly unbinding first.
        if (ports().stream().anyMatch(p -> p.id().equals(port.id()))) throw new StorageException("端口已绑定");
        try (PreparedStatement p = db.prepareStatement("INSERT INTO ports VALUES (?,?,?,?,?,?,?,?,?)")) {
            p.setString(1,port.id()); p.setString(2,port.world()); p.setInt(3,port.x()); p.setInt(4,port.y()); p.setInt(5,port.z());
            p.setString(6,port.owner()); p.setString(7,port.vault()); p.setString(8,port.mode()); p.setString(9,port.filter()); p.executeUpdate();
        } catch (SQLException e) { throw failure(e); }
    }

    public synchronized void unbind(Port port) {
        requireWritable();
        try (PreparedStatement p = db.prepareStatement("DELETE FROM ports WHERE id=? AND owner=?")) {
            p.setString(1,port.id()); p.setString(2,port.owner()); p.executeUpdate();
        } catch (SQLException e) { throw failure(e); }
    }

    private void requireWritable() {
        if (frozen()) throw new StorageException("云仓已冻结；请管理员核对 journal 后执行 recover");
        if (!pending().isEmpty()) { frozen = true; throw new StorageException("存在未完成转移，已冻结"); }
    }

    @FunctionalInterface private interface SqlWork { void run() throws SQLException; }
    private void transaction(SqlWork work) {
        try {
            db.setAutoCommit(false);
            try { work.run(); db.commit(); }
            catch (SQLException | RuntimeException e) { db.rollback(); throw e; }
            finally { db.setAutoCommit(true); }
        } catch (SQLException e) { throw failure(e); }
    }

    private StorageException failure(SQLException e) {
        failed = true; frozen = true;
        return new StorageException("SQLite 操作失败，已暂停交易",e);
    }

    public synchronized void markClean() {
        if (frozen() || !pending().isEmpty()) return;
        try { putMeta("active","false"); } catch (SQLException e) { throw failure(e); }
    }

    /** Only a candidate: the process supervisor clears active AFTER the JVM has exited normally. */
    public synchronized boolean prepareShutdown() {
        if (frozen() || !pending().isEmpty()) return false;
        try { putMeta("shutdown_ready",session); return true; } catch (SQLException e) { throw failure(e); }
    }

    @Override public synchronized void close() throws SQLException { db.close(); }
}
