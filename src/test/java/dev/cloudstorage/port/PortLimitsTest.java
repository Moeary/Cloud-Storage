package dev.cloudstorage.port;

import dev.cloudstorage.storage.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PortLimitsTest {
    private Ledger.Port port(int x,String owner,String vault,String mode) { return new Ledger.Port("world",x,0,0,owner,vault,mode,null); }
    @Test void fiveInputsAndFiveOutputsAreIndependentAcrossVaults() {
        var limits=new PortLimits(5,5);
        List<Ledger.Port> ports=new ArrayList<>();
        for (int i=0;i<5;i++) ports.add(port(i,"alice",i%2==0?"shared":"player:alice","input"));
        assertThrows(StorageException.class,()->limits.requireRoom(ports,"alice","input"));
        assertDoesNotThrow(()->limits.requireRoom(ports,"alice","output"));
        for (int i=0;i<5;i++) ports.add(port(i+5,"alice","shared","output"));
        assertThrows(StorageException.class,()->limits.requireRoom(ports,"alice","output"));
        assertDoesNotThrow(()->limits.requireRoom(ports,"bob","input"));
        assertDoesNotThrow(()->limits.requireRoom(ports,"bob","output"));
        ports.removeFirst();
        assertDoesNotThrow(()->limits.requireRoom(ports,"alice","input"));
        assertThrows(StorageException.class,()->limits.requireRoom(ports,"alice","output"));
    }
    @Test void zeroLimitsDisableOnlyTheirDirection() {
        var limits=new PortLimits(-1,5);
        assertThrows(StorageException.class,()->limits.requireRoom(List.of(),"alice","input"));
        assertDoesNotThrow(()->limits.requireRoom(List.of(),"alice","output"));
    }
}
