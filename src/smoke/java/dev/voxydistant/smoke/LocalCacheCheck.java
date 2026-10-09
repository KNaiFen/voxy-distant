package dev.voxydistant.smoke;

import dev.voxydistant.compat.*;
import dev.voxydistant.generation.GenerationController;
import dev.voxydistant.movement.RequestShape;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.world.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

/** Real local scheduler with a persisted index and no renderer to warm its pages. */
final class LocalCacheCheck {
    static void run(ServerLevel level)throws Exception{
        check(level,false,false);
        check(level,true,true);
        check(level.getServer().getLevel(Level.NETHER),true,false);
        System.out.println("LOCAL_COLD_CACHE_PASS");
    }
    private static void check(ServerLevel level,boolean missing,boolean legacy)throws Exception{
        Path root=Files.createTempDirectory(LodBenchmark.OUTPUT,"local-cache-check-");
        var path=root.resolve("coverage");var first=new CoverageStore(path);
        for(int z=-8;z<=8;z++)for(int x=-8;x<=8;x++)for(int y=level.getMinSection();y<level.getMaxSection();y++){
            if(missing&&z==0&&(x==-8||x==8&&y==level.getMaxSection()-1))continue;
            first.markFull(x,y,z,true);
        }
        first.saveAfterWorldClosed();
        if(legacy)try(var options=new org.rocksdb.Options();var db=org.rocksdb.RocksDB.open(options,root.resolve("coverage.rocksdb").toString())){
            for(int rz=-1;rz<=0;rz++)for(int rx=-1;rx<=0;rx++)for(int py=Math.floorDiv(level.getMinSection(),32);py<=Math.floorDiv(level.getMaxSection()-1,32);py++)
                db.delete(ByteBuffer.allocate(9).put((byte)4).putLong(CoverageStore.node(4,rx,py,rz)).array());
        }
        var coverage=new CoverageStore(path);
        var storage=new SectionStorage(){
            public int loadSection(WorldSection section){return 1;}
            public void saveSection(WorldSection section){}
            public void putIdMapping(int id,ByteBuffer bytes){}
            public it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<byte[]> getIdMappingsData(){return new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();}
            public void flush(){}public void close(){}public void iteratePositions(int lod,java.util.function.LongConsumer callback){}
        };
        var engine=new WorldEngine(storage);((EngineAccess)engine).distant$setCoverage(coverage);
        var type=Class.forName("dev.voxydistant.generation.GenerationController$Session");
        var ctor=type.getDeclaredConstructor(ServerLevel.class,WorldEngine.class);ctor.setAccessible(true);
        Object session=ctor.newInstance(level,engine);var df=type.getDeclaredField("discovery");df.setAccessible(true);Object discovery=df.get(session);
        var move=discovery.getClass().getDeclaredMethod("move",RequestShape.class,int.class);move.setAccessible(true);move.invoke(discovery,RequestShape.circle(0,0,8),2);
        var refill=type.getDeclaredMethod("refill",int.class);refill.setAccessible(true);
        try{
            var expected=missing?Set.of(new ChunkPos(-8,0),new ChunkPos(8,0)):Set.<ChunkPos>of();
            var found=scan(session,refill,discovery);
            System.out.println("LOCAL_COLD_CACHE dimension="+level.dimension().location()+" legacy="+legacy+" candidates="+found+" renderPages="+coverage.usage().pages()+" migrations="+coverage.directoryUsage().migrations());
            if(!found.equals(expected))throw new AssertionError("Incorrect local coverage candidates: "+found+" expected: "+expected);
            if(coverage.usage().pages()!=0)throw new AssertionError("Directory lookup loaded render pages");
            if(coverage.remote())throw new AssertionError("Local coverage switched to remote mode");
            if(legacy&&coverage.directoryUsage().migrations()==0)throw new AssertionError("Legacy pages were not migrated");
            if(missing){
                for(var pos:expected)for(int y=level.getMinSection();y<level.getMaxSection();y++)coverage.markFull(pos.x,y,pos.z,true);
                coverage.saveAfterWorldClosed();coverage=new CoverageStore(path);coverage.height(level.getMinSection(),level.getMaxSection());
                ((EngineAccess)engine).distant$setCoverage(coverage);
                move.invoke(discovery,RequestShape.circle(8,0,8),2);scan(session,refill,discovery);
                move.invoke(discovery,RequestShape.circle(0,0,8),2);
                if(!scan(session,refill,discovery).isEmpty())throw new AssertionError("Re-entered full columns were scheduled again");
                var clear=discovery.getClass().getDeclaredMethod("clear");clear.setAccessible(true);clear.invoke(discovery);
                move.invoke(discovery,RequestShape.circle(0,0,8),2);
                if(!scan(session,refill,discovery).isEmpty())throw new AssertionError("Cleared discovery did not restore persisted coverage");
                if(coverage.usage().pages()!=0)throw new AssertionError("Re-entering loaded render pages");
                System.out.println("LOCAL_COLD_CACHE_REENTRY_PASS dimension="+level.dimension().location());
            }
        }finally{
            var close=type.getDeclaredMethod("close");close.setAccessible(true);close.invoke(session);
            var workers=type.getDeclaredField("workers");workers.setAccessible(true);
            if(!((java.util.concurrent.ExecutorService)workers.get(session)).awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Directory workers did not stop");
            engine.free();
            try(var paths=Files.walk(root)){for(var p:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(p);}
        }
    }
    private static Set<ChunkPos> scan(Object session,java.lang.reflect.Method refill,Object discovery)throws Exception{
        var busy=discovery.getClass().getDeclaredMethod("busy");busy.setAccessible(true);
        var poll=discovery.getClass().getDeclaredMethod("poll");poll.setAccessible(true);
        var found=new HashSet<ChunkPos>();long deadline=System.nanoTime()+10_000_000_000L;
        while((boolean)busy.invoke(discovery)&&System.nanoTime()<deadline){
            refill.invoke(session,256);ChunkPos pos;
            while((pos=(ChunkPos)poll.invoke(discovery))!=null)if(!found.add(pos))throw new AssertionError("Duplicate local candidate: "+pos);
            Thread.sleep(2);
        }
        if((boolean)busy.invoke(discovery))throw new AssertionError("Local discovery did not finish");
        return found;
    }
}
