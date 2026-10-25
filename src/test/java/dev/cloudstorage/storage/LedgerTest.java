package dev.cloudstorage.storage;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

class LedgerTest {
    @TempDir Path dir;
    private static final ItemData STONE=ItemData.of("STONE",new byte[]{1,2,3});
    private static final String ALICE="player:"+UUID.randomUUID();
    private static final String BOB="player:"+UUID.randomUUID();
    private Ledger ledger;
    @BeforeEach void open() throws Exception { ledger=new Ledger(dir.resolve("vault.db"),10000,8); }
    @AfterEach void close() throws Exception { ledger.close(); }
    private String transfer(String vault,ItemData item,long delta) {
        String id=UUID.randomUUID().toString();
        ledger.prepare(id,vault,item,delta,"test",new byte[]{1},new byte[]{2});
        ledger.settle(id); return id;
    }

    @Test void uuidAndSharedVaultsAreIsolated() {
        transfer(ALICE,STONE,128); transfer(BOB,STONE,3); transfer("shared",STONE,42);
        assertEquals(128,ledger.amount(ALICE,STONE.key()));
        assertEquals(3,ledger.amount(BOB,STONE.key()));
        assertEquals(42,ledger.amount("shared",STONE.key()));
    }

    @Test void failedWithdrawalRollsBackWithoutJournal() {
        transfer(ALICE,STONE,64);
        assertThrows(StorageException.class,() -> transfer(ALICE,STONE,-65));
        assertEquals(64,ledger.amount(ALICE,STONE.key()));
        assertTrue(ledger.pending().isEmpty());
        assertFalse(ledger.frozen());
    }

    @Test void capacityAndTypeLimitsAreEnforced() {
        transfer(ALICE,STONE,10000);
        assertThrows(StorageException.class,() -> transfer(ALICE,STONE,1));
        for (int i=0;i<8;i++) transfer(BOB,ItemData.of("STONE",new byte[]{(byte)(10+i)}),1);
        assertThrows(StorageException.class,() -> transfer(BOB,STONE,1));
        assertEquals(10000,ledger.amount(ALICE,STONE.key()));
        assertEquals(8,ledger.entries(BOB).size());
    }

    @Test void fullMetadataIsPartOfIdentityAndBytesAreImmutable() {
        byte[] raw={3,4,5}; ItemData renamed=ItemData.of("STONE",raw); raw[0]=99;
        transfer(ALICE,STONE,10); transfer(ALICE,renamed,20);
        byte[] copy=renamed.bytes(); copy[0]=88;
        assertEquals(2,ledger.entries(ALICE).size());
        assertEquals(3,renamed.bytes()[0]);
        assertEquals(20,ledger.amount(ALICE,renamed.key()));
    }

    @Test void invalidQuantitiesAndOverflowCannotChangeStock() {
        transfer(ALICE,STONE,5);
        for (long n:new long[]{0,Long.MAX_VALUE,Long.MIN_VALUE,Ledger.MAX_AMOUNT+1,-Ledger.MAX_AMOUNT-1})
            assertThrows(IllegalArgumentException.class,() -> transfer(ALICE,STONE,n));
        assertThrows(StorageException.class,() -> transfer(ALICE,STONE,Ledger.MAX_AMOUNT));
        assertEquals(5,ledger.amount(ALICE,STONE.key()));
    }

    @Test void duplicateOperationCannotCreditTwice() {
        String id=transfer(ALICE,STONE,64);
        assertThrows(StorageException.class,() -> ledger.prepare(id,ALICE,STONE,64,"test",new byte[]{1},new byte[]{2}));
        assertEquals(64,ledger.amount(ALICE,STONE.key()));
    }

    @Test void interruptedTransferPersistsAndFreezesAcrossRestart() throws Exception {
        String id=UUID.randomUUID().toString();
        ledger.prepare(id,ALICE,STONE,64,"player:test",new byte[]{11},new byte[]{22});
        ledger.close(); ledger=new Ledger(dir.resolve("vault.db"),10000,8);
        assertTrue(ledger.frozen()); assertEquals(1,ledger.pending().size());
        assertThrows(StorageException.class,() -> transfer(ALICE,STONE,-1));
        assertThrows(StorageException.class,ledger::unlock);
        ledger.resolve(id,false); // Actual source still BEFORE => reverse cloud credit, never replay source.
        assertEquals(0,ledger.amount(ALICE,STONE.key()));
        assertTrue(ledger.frozen()); ledger.unlock(); assertFalse(ledger.frozen());
        assertThrows(StorageException.class,() -> ledger.resolve(id,false));
    }

    @Test void afterRecoveryKeepsDebitAndDoesNotReplay() {
        transfer(ALICE,STONE,64);
        String id=UUID.randomUUID().toString();
        ledger.prepare(id,ALICE,STONE,-32,"test",new byte[]{1},new byte[]{2});
        ledger.freeze(); ledger.resolve(id,true); ledger.unlock();
        assertEquals(32,ledger.amount(ALICE,STONE.key()));
        assertTrue(ledger.pending().isEmpty());
    }

    @Test void cleanShutdownAllowsTradingButDirtySettledShutdownRequiresAudit() throws Exception {
        transfer(ALICE,STONE,17); ledger.markClean(); ledger.close();
        ledger=new Ledger(dir.resolve("vault.db"),10000,8);
        assertFalse(ledger.frozen()); assertEquals(17,ledger.amount(ALICE,STONE.key()));
        ledger.close(); ledger=new Ledger(dir.resolve("vault.db"),10000,8);
        assertTrue(ledger.frozen()); // Even DONE operations may disagree with external world saves after a crash.
    }

    @Test void simultaneousWithdrawalsNeverOverdraw() throws Exception {
        transfer("shared",STONE,100);
        ExecutorService pool=Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for (int i=0;i<32;i++) results.add(pool.submit(() -> {
                synchronized (ledger) {
                    try { transfer("shared",STONE,-10); return true; } catch (StorageException e) { return false; }
                }
            }));
            int success=0; for (Future<Boolean> f:results) if (f.get()) success++;
            assertEquals(10,success); assertEquals(0,ledger.amount("shared",STONE.key()));
        } finally { pool.shutdownNow(); }
    }

    @Test void randomizedConservationWithRejectedTransfers() {
        Random random=new Random(20261024); long external=5000;
        for (int i=0;i<1000;i++) {
            long delta=random.nextInt(129)-64;
            if (delta==0 || delta>external) continue;
            long before=ledger.amount(ALICE,STONE.key());
            try { transfer(ALICE,STONE,delta); external-=delta; }
            catch (StorageException e) { assertEquals(before,ledger.amount(ALICE,STONE.key())); }
            assertEquals(5000,external+ledger.amount(ALICE,STONE.key()));
            assertTrue(external>=0); assertTrue(ledger.pending().isEmpty());
        }
    }

    @Test void preferencesAndPortsSurviveCleanRestartWithoutChangingOwner() throws Exception {
        UUID owner=UUID.randomUUID(); ledger.setMode(owner,"shared");
        Ledger.Port port=new Ledger.Port(UUID.randomUUID().toString(),1,64,3,owner.toString(),ALICE,"input",null);
        ledger.bind(port);
        assertThrows(StorageException.class,() -> ledger.bind(port));
        ledger.markClean(); ledger.close(); ledger=new Ledger(dir.resolve("vault.db"),10000,8);
        assertEquals("shared",ledger.mode(owner,"personal"));
        assertEquals(port,ledger.ports().getFirst());
        ledger.unbind(new Ledger.Port(port.world(),1,64,3,UUID.randomUUID().toString(),ALICE,"input",null));
        assertEquals(1,ledger.ports().size());
        ledger.unbind(port); assertTrue(ledger.ports().isEmpty());
    }

    @Test void newerSchemaIsRejectedWithoutRewritingIt() throws Exception {
        Path future=dir.resolve("future.db");
        try (Connection c=DriverManager.getConnection("jdbc:sqlite:"+future); Statement s=c.createStatement()) { s.execute("PRAGMA user_version=99"); }
        assertThrows(SQLException.class,() -> new Ledger(future,10000,8));
    }

    @Test void pluginShutdownCandidateAloneDoesNotClearDirtyMarker() throws Exception {
        transfer(ALICE,STONE,64);
        ledger.prepareShutdown(); ledger.close(); // JVM might still be flushing world files here.
        ledger=new Ledger(dir.resolve("vault.db"),10000,8);
        assertTrue(ledger.frozen());
        assertEquals(64,ledger.amount(ALICE,STONE.key()));
    }
}
