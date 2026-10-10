package dev.voxydistant.generation;

import dev.voxydistant.movement.RequestShape;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalDiscoveryTest {
    @Test void predictionMovementOnlyChecksNewColumns(){
        var discovery=new LocalDiscovery();var first=new RequestShape(0,0,1,0,1,0,1,32,2048);var seen=new HashSet<Long>();
        discovery.move(first,2);
        for(int tick=0;tick<10000&&discovery.busy();tick++){
            discovery.step(4096,256,(rx,rz)->true,(x,z)->true);
            net.minecraft.world.level.ChunkPos p;while((p=discovery.poll())!=null){assertTrue(first.contains(p.x,p.z));assertTrue(seen.add(p.toLong()));}
        }
        assertFalse(discovery.busy());long before=discovery.checks;
        discovery.move(first,2);while(discovery.busy())discovery.step(4096,256,(rx,rz)->true,(x,z)->{fail("stationary recheck");return true;});assertEquals(before,discovery.checks);
        var next=new RequestShape(1,0,2,0,1,0,1,32,2048);discovery.move(next,2);
        for(int tick=0;tick<10000&&discovery.busy();tick++){
            discovery.step(4096,256,(rx,rz)->true,(x,z)->{assertFalse(seen.contains(net.minecraft.world.level.ChunkPos.asLong(x,z)));return true;});
            while(discovery.poll()!=null){}
        }
        assertTrue(discovery.checks-before<seen.size()/4);assertFalse(discovery.busy());
    }
    @Test void turnRetriesCancelledWorkAndClipsVanilla(){
        var discovery=new LocalDiscovery();var seen=new HashSet<Long>();
        for(var shape:List.of(new RequestShape(-2,-3,-1,-3,1,0,1,24,2048),new RequestShape(-2,-3,-3,-3,-1,0,1,24,2048))){
            seen.clear();discovery.move(shape,2);
            for(int tick=0;tick<10000&&discovery.busy();tick++){
                discovery.step(4096,256,(rx,rz)->true,(x,z)->true);
                net.minecraft.world.level.ChunkPos p;while((p=discovery.poll())!=null){assertTrue(shape.contains(p.x,p.z));assertTrue(Math.max(Math.abs(p.x+2),Math.abs(p.z+3))>2);seen.add(p.toLong());}
            }
            assertFalse(discovery.busy());
        }
        var retry=new net.minecraft.world.level.ChunkPos(-6,-3);discovery.retry(retry);discovery.step(4096,256,(rx,rz)->true,(x,z)->true);assertEquals(retry,discovery.poll());
    }
}
