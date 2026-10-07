package dev.voxydistant.smoke;

import dev.voxydistant.client.RemoteClient;
import dev.voxydistant.compat.*;
import dev.voxydistant.data.*;
import dev.voxydistant.network.Protocol;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Runs against the real client receive lane before the normal acceptance session starts. */
public final class RequestCompletionCheck {
    private static Field field(Class<?> type,String name)throws ReflectiveOperationException{var f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    @SuppressWarnings("unchecked")
    public static CompletableFuture<Void> run()throws ReflectiveOperationException{
        var mc=Minecraft.getInstance();var sessionField=field(RemoteClient.class,"session");
        check(sessionField.get(null)==null,"check must run before enabling reception");
        var paused=field(RemoteClient.class,"maintenancePaused");boolean oldPaused=paused.getBoolean(null);
        boolean oldReceive=dev.voxydistant.config.DistantConfig.RECEIVE.get();
        boolean oldIngest=me.cortex.voxy.client.config.VoxyConfig.CONFIG.ingestEnabled;
        me.cortex.voxy.client.config.VoxyConfig.CONFIG.ingestEnabled=false;
        var engine=VoxyBridge.acquire(mc.level);check(engine!=null,"remote Voxy engine remains available with automatic local ingest disabled");
        var type=Class.forName("dev.voxydistant.client.RemoteClient$Session");var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
        var greeting=RemoteClient.greeting();var hello=new Protocol.Hello(greeting.world(),greeting.dimension(),64,mc.level.getMinSection(),mc.level.getMaxSection(),List.of("4:0","64:2"));
        Object session=constructor.newInstance(engine,mc.level,hello);int epoch=900000;
        field(type,"epoch").setInt(session,epoch);field(type,"radius").setInt(session,64);field(type,"x").setInt(session,0);
        field(type,"shape").set(session,dev.voxydistant.movement.RequestShape.circle(0,0,64));
        field(type,"generation").setInt(session,2);sessionField.set(null,session);paused.setBoolean(null,true);
        dev.voxydistant.config.DistantConfig.RECEIVE.set(true);
        var worker=(ThreadPoolExecutor)field(type,"worker").get(session);
        var invalid=(Set<Long>)field(type,"invalid").get(session);var checked=(Map<Long,Integer>)field(type,"checked").get(session);
        var versions=(Map<Long,Long>)field(type,"versions").get(session);var fullRetry=(Set<Long>)field(type,"fullRetry").get(session);
        var retries=(dev.voxydistant.client.RetryQueue)field(type,"retries").get(session);
        var pending=(Map<Long,Object>)field(type,"pending").get(session);
        var pendingType=Class.forName("dev.voxydistant.client.RemoteClient$Pending");var pendingConstructor=pendingType.getDeclaredConstructors()[0];pendingConstructor.setAccessible(true);
        var vanillaChunk=mc.level.getChunk(0,0);
        var frameType=Class.forName("dev.voxydistant.client.RemoteClient$Frame");var frameConstructor=frameType.getDeclaredConstructors()[0];frameConstructor.setAccessible(true);
        Object input=frameConstructor.newInstance(0,0,2,64,64,false);
        // An older position requires L2; move into L0 range before delivering its response.
        mc.player.setPos(-256,180,0);
        int oldTarget=dev.voxydistant.config.DistanceBands.parse(hello.bands()).select(4,0,mc.player.chunkPosition().x,mc.player.chunkPosition().z);
        check(oldTarget==2,"old player position requests coarse level");
        long p=ChunkPos.asLong(4,0);Object original=pendingConstructor.newInstance(System.nanoTime(),oldTarget,1,0L);pending.put(p,original);
        mc.player.setPos(0,180,0);
        long version=1_000_000;
        var chain=CompletableFuture.completedFuture(null).thenRun(()->packet(epoch,1,column(mc,4,0,version,2),false));
        chain=chain.thenRunAsync(()->{},worker).thenRunAsync(()->{
            check(invalid.contains(p)&&checked.get(p)==2,"delayed single coarse response refines while stationary");invalid.clear();pending.put(p,original);
            packet(epoch,2,column(mc,4,0,version,2),true);
        },mc);
        chain=chain.thenRunAsync(()->{},worker).thenRunAsync(()->{
            check(invalid.contains(p),"delayed batch response refines while stationary");invalid.clear();pending.put(p,original);
            RemoteClient.reply(new Protocol.Reply(epoch,4,0,version,2,0));
        },mc);
        chain=chain.thenRunAsync(()->{},worker).thenRunAsync(()->{
            check(invalid.contains(p),"unchanged coarse reply refines while stationary");invalid.clear();pending.put(p,original);
            versions.put(p,version+1);RemoteClient.reply(new Protocol.Reply(epoch,4,0,version,0,0));
        },mc);
        chain=chain.thenRunAsync(()->{},worker).thenRunAsync(()->{
            check(invalid.contains(p)&&!checked.containsKey(p),"old unchanged reply cannot confirm new version");pending.put(p,original);
            packet(epoch,3,column(mc,4,0,version,0),true);
        },mc);
        chain=chain.thenRunAsync(()->{},worker).thenRunAsync(()->{
            check(invalid.contains(p)&&!checked.containsKey(p),"old batch cannot confirm new version");pending.put(p,original);
            packet(epoch,4,column(mc,4,0,version+1,0),false);
        },mc);
        chain=chain.thenRunAsync(()->{},worker).thenRunAsync(()->{
            check(!invalid.contains(p)&&checked.get(p)==0,"latest fine response satisfies stationary demand");
        },mc).thenRunAsync(()->{
            long overlap=ChunkPos.asLong(55,0);
            try{pending.put(overlap,pendingConstructor.newInstance(System.nanoTime(),2,2,2L));}
            catch(ReflectiveOperationException e){throw new CompletionException(e);}
            reply(session,type,new Protocol.Reply(epoch,55,0,0,5,1L,1));
            check(pending.containsKey(overlap),"late retry must not clear a newer request at the same coordinate");
            long missing=ChunkPos.asLong(60,0);pending.put(missing,original);reply(session,type,new Protocol.Reply(epoch,60,0,0,5,2));
            check(!checked.containsKey(missing)&&retries.scheduled(missing),"unavailable is delayed, never fake L0");
            versions.put(missing,version);try{var dirty=RemoteClient.class.getDeclaredMethod("dirty",type,Protocol.Dirty.class,long.class,long.class);dirty.setAccessible(true);dirty.invoke(null,session,new Protocol.Dirty(hello.dimension(),60,0,version,false),missing,0L);}catch(ReflectiveOperationException e){throw new CompletionException(e);}
            check(!retries.scheduled(missing)&&invalid.contains(missing),"dirty wakes sleeping retry");
            ((Set<Long>)uncheckedField(session,type,"loadedFull")).add(0L);pending.put(0L,original);reply(session,type,new Protocol.Reply(epoch,0,0,version,5,2));
            check(fullRetry.contains(0L),"available vanilla chunk queues ingest");
        },worker).thenRunAsync(()->RemoteClient.unload(vanillaChunk),mc).thenRunAsync(()->{
            try{var tick=RemoteClient.class.getDeclaredMethod("tick",type,frameType);tick.setAccessible(true);tick.invoke(null,session,input);}catch(ReflectiveOperationException e){throw new CompletionException(e);}
            check(invalid.contains(0L)&&!fullRetry.contains(0L),"vanilla unload rechecks coverage");
            invalid.clear();pending.clear();
            try{
                for(int z=32;z<40;z++)for(int x=32;x<40;x++)invalid.add(ChunkPos.asLong(x,z));
                Object discovery=field(type,"discovery").get(session);var move=discovery.getClass().getDeclaredMethod("move",int.class,int.class,int.class);move.setAccessible(true);move.invoke(discovery,0,0,64);
                var regions=(Map<?,?>)field(discovery.getClass(),"regions").get(discovery);
                var local=discovery.getClass().getDeclaredMethod("local",Class.forName("dev.voxydistant.client.RegionDiscovery$Region"),CoverageStore.Directory.class);local.setAccessible(true);
                for(Object region:regions.values()){int rx=field(region.getClass(),"x").getInt(region),rz=field(region.getClass(),"z").getInt(region);local.invoke(discovery,region,VoxyBridge.coverage(engine).directory(rx,rz,false));}
                field(type,"requestBudget").setInt(session,64);
                var request=RemoteClient.class.getDeclaredMethod("requestMore",type);request.setAccessible(true);
                paused.setBoolean(null,false);request.invoke(null,session);paused.setBoolean(null,true);
                check(pending.size()==64,"one batch fills reserved scan and repair slots");
                long repairs=pending.keySet().stream().filter(v->ChunkPos.getX(v)>=32&&ChunkPos.getZ(v)>=32).count();
                check(repairs==32,"repair traffic leaves half batch for unvisited scan");
                request.invoke(null,session);check(pending.size()==64,"paused scheduler adds no work");
                sessionField.set(null,null);
            }catch(ReflectiveOperationException e){throw new CompletionException(e);}
            invalid.clear();
            packet(epoch,5,column(mc,4,0,version+2,2),false);
            RemoteClient.reply(new Protocol.Reply(epoch,4,0,version+2,2,0));
            check(invalid.isEmpty(),"old session packets ignored");
            try{sessionField.set(null,session);}catch(IllegalAccessException e){throw new CompletionException(e);}
            pending.clear();invalid.clear();
            System.out.println("DISTANT_REQUEST_COMPLETION_PASS: single/batch/unchanged stationary refinement, stale revision, unavailable, vanilla unload, dirty wake, scan fairness, session switch");
        },worker);
        // The client is already near, but the server can still classify its old position as distant.
        // An identical coarse reply must not put refinement to sleep until another move or Dirty.
        chain=chain.thenRunAsync(()->{
            try{pending.put(p,pendingConstructor.newInstance(System.nanoTime(),2,2,30L));}
            catch(ReflectiveOperationException e){throw new CompletionException(e);}
            packet(epoch,30,column(mc,4,0,version+6,2),false,30L);
        },mc).thenRunAsync(()->{},worker).thenRunAsync(()->{
            invalid.clear();versions.put(p,version+6);
            try{
                Object refinement=pendingConstructor.newInstance(System.nanoTime(),0,2,31L);
                field(pendingType,"before").set(refinement,new CoverageStore.Stamp(version+6,2));
                field(pendingType,"dirtyBefore").setLong(refinement,version+6);pending.put(p,refinement);
                reply(session,type,new Protocol.Reply(epoch,4,0,version+6,2,31L,0));
                check(retries.scheduled(p)&&retries.poll(System.nanoTime())==null,"no-progress refinement respects retry delay");
                var retry=retries.oldest(1).getFirst();
                check(retry.position()==p&&retry.due()!=Long.MAX_VALUE&&retry.due()-System.nanoTime()<=java.util.concurrent.TimeUnit.SECONDS.toNanos(2),"identical coarse reply must retry while stationary");
                check(retries.poll(retry.due())==p,"stationary refinement becomes eligible without movement or Dirty");
                pending.put(p,refinement);reply(session,type,new Protocol.Reply(epoch,4,0,version+6,2,31L,0));
                check(retries.poll(System.nanoTime())==null,"repeated coarse reply cannot busy-loop");
                pending.put(p,pendingConstructor.newInstance(System.nanoTime(),0,2,32L));
            }catch(ReflectiveOperationException e){throw new CompletionException(e);}
        },worker).thenRunAsync(()->packet(epoch,32,column(mc,4,0,version+6,0),false,32L),mc)
                .thenRunAsync(()->{},worker).thenRunAsync(()->{
                    check(!pending.containsKey(p)&&!retries.scheduled(p)&&checked.get(p)==0,"stationary fine response clears no-progress retry");
                    System.out.println("DISTANT_STATIONARY_RETRY_PASS: repeated identical coarse replies retry with delay and fine data completes without movement or Dirty");
                },worker);
        chain=chain.thenRunAsync(()->{
            try{
                Object discovery=field(type,"discovery").get(session);var regions=(Map<?,?>)field(discovery.getClass(),"regions").get(discovery);Object region=regions.get(0L);
                var local=(CoverageStore.Directory)field(region.getClass(),"local").get(region);
                long[] oldVersions=local.versions.clone();byte[] masks=new byte[1024];Arrays.fill(masks,(byte)31);
                versions.put(p,version+7);var dirty=RemoteClient.class.getDeclaredMethod("dirty",type,Protocol.Dirty.class,long.class,long.class);dirty.setAccessible(true);
                dirty.invoke(null,session,new Protocol.Dirty(hello.dimension(),4,0,version+7,true),p,version+6);
                // Prediction can discard a revision outside its footprint before the vanilla packet arrives.
                versions.remove(p);
                var queryType=Class.forName("dev.voxydistant.client.RemoteClient$RegionRequest");var query=queryType.getDeclaredConstructors()[0];query.setAccessible(true);
                ((Map<Long,Object>)field(type,"regionQueries").get(session)).put(900L,query.newInstance(region,System.nanoTime()));
                var summary=RemoteClient.class.getDeclaredMethod("regionSummary",type,Protocol.RegionSummary.class);summary.setAccessible(true);
                summary.invoke(null,session,new Protocol.RegionSummary(epoch,900L,0,0,oldVersions,masks));
                check(VoxyBridge.coverage(engine).directoryColumn(4,0).level()==5,"dirty revision leaves real cache awaiting refresh");
                check(local.levels[4]==5&&!checked.containsKey(p),"late directory summary cannot confirm the old stamp after revision pruning");
                check((((long[])field(region.getClass(),"needs").get(region))[0]&(1L<<4))!=0,"invalidated column remains discoverable after late summary");
                invalid.clear();fullRetry.remove(p);
                System.out.println("DISTANT_DIRTY_DIRECTORY_PASS: delayed summary cannot revive invalidated cache or lose its refresh candidate");
                var loaded=(Set<Long>)field(type,"loadedFull").get(session);long retained=ChunkPos.asLong(1000,0);loaded.add(retained);versions.put(retained,version+8);
                long unloaded=ChunkPos.asLong(1001,0);versions.put(unloaded,version+8);
                Object outside=frameConstructor.newInstance(0,0,2,0,64,false);
                var tick=RemoteClient.class.getDeclaredMethod("tick",type,frameType);tick.setAccessible(true);
                var prediction=dev.voxydistant.movement.MovementPrediction.CLIENT;var snapshot=field(prediction.getClass(),"snapshot");var oldMotion=snapshot.get(prediction);
                var motion=new dev.voxydistant.movement.MovementPrediction.Snapshot(0,0,0,0,1,0,0,0,Long.MAX_VALUE,false,mc.level);
                snapshot.set(prediction,motion);paused.setBoolean(null,false);try{tick.invoke(null,session,outside);}finally{paused.setBoolean(null,true);snapshot.set(prediction,oldMotion);}
                check(versions.get(retained)==version+8&&!versions.containsKey(unloaded),"range pruning retains vanilla revisions while removing unloaded ones: retained="+versions.get(retained)+" unloaded="+versions.get(unloaded)+" radius="+field(type,"radius").getInt(session));
                field(type,"radius").setInt(session,64);field(type,"shape").set(session,dev.voxydistant.movement.RequestShape.circle(0,0,64));field(type,"input").set(session,input);
                loaded.remove(retained);versions.remove(retained);
                System.out.println("DISTANT_LOADED_REVISION_PASS: range pruning keeps loaded vanilla captures eligible without retaining unloaded revisions");
            }catch(ReflectiveOperationException e){throw new CompletionException(e);}
        },worker);
        long phantom=ChunkPos.asLong(18,1);
        chain=chain.thenRunAsync(()->{
            try{
                var loaded=(Set<Long>)field(type,"loadedFull").get(session);loaded.add(phantom);versions.put(phantom,version+8);
                pending.clear();invalid.clear();retries.clear();checked.remove(phantom);fullRetry.remove(phantom);
                Object discovery=field(type,"discovery").get(session);
                var reset=discovery.getClass().getDeclaredMethod("reset");reset.setAccessible(true);reset.invoke(discovery);
                var move=discovery.getClass().getDeclaredMethod("move",int.class,int.class,int.class);move.setAccessible(true);move.invoke(discovery,0,0,64);
                var regions=(Map<?,?>)field(discovery.getClass(),"regions").get(discovery);
                var local=discovery.getClass().getDeclaredMethod("local",Class.forName("dev.voxydistant.client.RegionDiscovery$Region"),CoverageStore.Directory.class);local.setAccessible(true);
                for(Object region:regions.values()){int rx=field(region.getClass(),"x").getInt(region),rz=field(region.getClass(),"z").getInt(region);local.invoke(discovery,region,VoxyBridge.coverage(engine).directory(rx,rz,false));}
                var request=RemoteClient.class.getDeclaredMethod("requestMore",type);request.setAccessible(true);paused.setBoolean(null,false);
                var update=discovery.getClass().getDeclaredMethod("updated",long.class,CoverageStore.Stamp.class);update.setAccessible(true);update.invoke(discovery,phantom,new CoverageStore.Stamp(version+8,5));
                try{for(int i=0;i<100&&!fullRetry.contains(phantom);i++){pending.clear();field(type,"requestBudget").setInt(session,64);request.invoke(null,session);}}
                finally{paused.setBoolean(null,true);}
                check(fullRetry.contains(phantom),"discovered gap rechecks stale vanilla loaded hint: state="+field(type,"requestState").get(session)+" loaded="+loaded.contains(phantom)+" radius="+field(type,"radius").getInt(session));
            }catch(ReflectiveOperationException e){throw new CompletionException(e);}
        },worker).thenRunAsync(()->{
            try{
                check(mc.level.getChunkSource().getChunk(18,1,net.minecraft.world.level.chunk.ChunkStatus.FULL,false)==null,"phantom chunk is outside vanilla storage");
                ((Set<Long>)field(type,"captureRequested").get(session)).add(phantom);
                var capture=RemoteClient.class.getDeclaredMethod("capture",type);capture.setAccessible(true);paused.setBoolean(null,false);try{capture.invoke(null,session);}finally{paused.setBoolean(null,true);}
                check(!((Set<Long>)field(type,"loadedFull").get(session)).contains(phantom)&&((Set<Long>)field(type,"unloaded").get(session)).contains(phantom),"missing vanilla chunk stops suppressing remote requests");
                pending.clear();fullRetry.clear();versions.remove(phantom);
                System.out.println("DISTANT_PHANTOM_LOADED_PASS: discovered gap rechecks vanilla hint and missing chunk returns to remote scheduling");
            }catch(ReflectiveOperationException e){throw new CompletionException(e);}
        },mc);
        long delayed=ChunkPos.asLong(55,0);
        chain=chain.thenRunAsync(()->{
            try{pending.put(delayed,pendingConstructor.newInstance(System.nanoTime(),2,2,2L));}
            catch(ReflectiveOperationException e){throw new CompletionException(e);}
            RemoteClient.reply(new Protocol.Reply(epoch,55,0,0,5,1L,0));
            packet(epoch,20,column(mc,55,0,version+3,2),false,1L);
            packet(epoch,21,column(mc,55,0,version+3,2),true,1L);
        },mc);
        chain=chain.thenRunAsync(()->{},worker).thenRunAsync(()->{
            check(pending.containsKey(delayed),"late unchanged and data cannot complete newer request");
            packet(epoch,22,column(mc,55,0,version+4,2),false,2L);
        },mc);
        chain=chain.thenRunAsync(()->{},worker).thenRunAsync(()->{
            check(!pending.containsKey(delayed),"newer request completes after late old responses");
            try{pending.put(delayed,pendingConstructor.newInstance(System.nanoTime(),2,2,4L));}
            catch(ReflectiveOperationException e){throw new CompletionException(e);}
            byte[] partial={1};
            RemoteClient.fragment(new Protocol.Fragment(epoch,23,55,0,version+5,2,3L,false,100,100,0,partial));
            RemoteClient.abort(new Protocol.Abort(epoch,23));
            check(pending.containsKey(delayed),"late abort cannot clear newer request");
        },mc);
        chain=chain.thenCompose(unused->{
            var locked=new CompletableFuture<Void>();var release=new java.util.concurrent.CountDownLatch(1);
            worker.execute(()->{synchronized(VoxyBridge.coverage(engine)){
                locked.complete(null);
                try{check(release.await(5,java.util.concurrent.TimeUnit.SECONDS),"main-thread hooks must not wait for coverage worker");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new CompletionException(e);}
            }});
            return locked.thenRunAsync(()->{
                try{
                    long started=System.nanoTime();
                    RemoteClient.tick();check(sessionField.get(null)==session,"automatic local ingest disabled must not close remote reception");
                    RemoteClient.indexStatus();RemoteClient.receiveSpeed();RemoteClient.full(engine,vanillaChunk);RemoteClient.close();
                    long elapsed=System.nanoTime()-started;
                    check(elapsed<50_000_000L,"tick, status, hooks and close must not wait for background coverage lock: "+elapsed);
                    System.out.println("DISTANT_ASYNC_MAIN_PASS: background coverage locked, main hooks elapsed_us="+(elapsed/1000));
                }catch(IllegalAccessException e){throw new CompletionException(e);}finally{release.countDown();}
            },mc);
        });
        return chain.whenCompleteAsync((unused,error)->{
            try{sessionField.set(null,null);paused.setBoolean(null,oldPaused);field(type,"closed").setBoolean(session,true);}
            catch(ReflectiveOperationException e){throw new CompletionException(e);}
            finally{me.cortex.voxy.client.config.VoxyConfig.CONFIG.ingestEnabled=oldIngest;dev.voxydistant.config.DistantConfig.RECEIVE.set(oldReceive);if(!worker.isShutdown()){worker.execute(engine::releaseRef);worker.shutdown();}}
        },mc);
    }
    private static LodColumn column(Minecraft mc,int x,int z,long version,int level){
        long[][][] sections=new long[mc.level.getSectionsCount()][5][];
        for(var section:sections){section[level]=new long[4096>>(level*3)];Arrays.fill(section[level],LodColumn.voxel(1,0,0xf3,15));}
        return new LodColumn(x,z,mc.level.getMinSection(),version,List.of(Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState()),List.of("minecraft:plains"),sections);
    }
    private static void reply(Object session,Class<?> type,Protocol.Reply packet){
        try{var method=RemoteClient.class.getDeclaredMethod("reply",type,Protocol.Reply.class);method.setAccessible(true);method.invoke(null,session,packet);}
        catch(ReflectiveOperationException e){throw new CompletionException(e);}
    }
    private static Object uncheckedField(Object session,Class<?> type,String name){try{return field(type,name).get(session);}catch(ReflectiveOperationException e){throw new CompletionException(e);}}
    private static void packet(int epoch,long transfer,LodColumn column,boolean batch){packet(epoch,transfer,column,batch,0L);}
    private static void packet(int epoch,long transfer,LodColumn column,boolean batch,long requestId){
        int level=column.minimumLevel();byte[] raw=ColumnCodec.encodeRaw(column,1<<level);
        var encoded=batch?BatchCodec.encode(List.of(raw),1):ColumnCodec.compress(raw,1);
        if(batch)RemoteClient.batchFragment(new Protocol.BatchFragment(epoch,transfer,encoded.compressed(),encoded.rawLength(),encoded.bytes().length,0,List.of(new Protocol.Member(column.x(),column.z(),column.version(),level,requestId,raw.length)),encoded.bytes()));
        else RemoteClient.fragment(new Protocol.Fragment(epoch,transfer,column.x(),column.z(),column.version(),level,requestId,encoded.compressed(),encoded.rawLength(),encoded.bytes().length,0,encoded.bytes()));
    }
}
