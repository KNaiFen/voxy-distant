package dev.voxydistant.client;

import dev.voxydistant.compat.CoverageStore;
import dev.voxydistant.config.DistanceBands;
import dev.voxydistant.generation.NearbyChunks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Production directory discovery over a persisted 192-chunk cache. */
class CacheDiscoveryTest {
    private static final int CACHED=192,RADIUS=256,MIN_Y=-4,MAX_Y=20;
    private static final DistanceBands BANDS=DistanceBands.parse(List.of("32:0","64:1","96:2"));
    @TempDir Path directory;

    @Test void cachedCircleRequestsEveryMissingOuterColumnOnce() {
        Path file=directory.resolve("coverage.bin");
        var cache=new CoverageStore(file);
        cache.remote(MIN_Y,MAX_Y,256);
        var fill=new NearbyChunks(CACHED,-1);
        NearbyChunks.Offset offset;
        int cached=0;
        while((offset=fill.next())!=null){
            int level=BANDS.select(offset.x(),offset.z(),0,0);
            for(int y=MIN_Y;y<MAX_Y;y++)cache.received(offset.x(),y,offset.z(),1,level,false);
            cached++;
        }
        cache.saveAfterWorldClosed();
        assertEquals(115781,cached);

        var cacheDirectory=new CoverageStore(file);cacheDirectory.remote(MIN_Y,MAX_Y,256);
        var discovery=new RegionDiscovery(BANDS);discovery.move(0,0,RADIUS);
        for(var region:discovery.regions.values())discovery.local(region,cacheDirectory.directory(region.x,region.z,false));
        long expected=0;for(var region:discovery.regions.values())expected+=region.needed;
        assertEquals(90080,expected);assertEquals(0,cacheDirectory.usage().pages());
        var first=discovery.poll();assertNotNull(first);
        long firstX=RegionDiscovery.x(first),firstZ=RegionDiscovery.z(first);
        assertTrue(firstX*firstX+firstZ*firstZ>(long)CACHED*CACHED);
        var wants=new HashSet<Long>();wants.add(first);
        Long next;while((next=discovery.poll())!=null)assertTrue(wants.add(next),"duplicate request");
        assertEquals(90080,wants.size());
        var verify=new NearbyChunks(RADIUS,-1);
        while((offset=verify.next())!=null)assertEquals(offset.distanceSquared()>(long)CACHED*CACHED,
                wants.contains(RegionDiscovery.key(offset.x(),offset.z())),"column="+offset);
        assertEquals(0,cacheDirectory.usage().pages());
        cacheDirectory.closeUnconfirmed();
    }
}
