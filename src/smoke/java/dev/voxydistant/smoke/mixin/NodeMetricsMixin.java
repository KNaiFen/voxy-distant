package dev.voxydistant.smoke.mixin;

import dev.voxydistant.smoke.LodBenchmark;
import dev.voxydistant.smoke.LodMetrics;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.NodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.NodeStore;
import me.cortex.voxy.common.world.WorldEngine;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.io.*;
import java.nio.file.*;

/** Sample only on the node owner thread, after Voxy's own integrity check. */
@Mixin(value=AsyncNodeManager.class,remap=false)
public class NodeMetricsMixin {
    @Shadow @Final private NodeManager manager;
    @Unique private long distant$lastSample;
    @Inject(method="run",at=@At("RETURN"))
    private void distant$nodes(CallbackInfo ci){
        if(!Boolean.getBoolean("voxyDistant.nodeDiagnostic"))return;
        long now=System.nanoTime();if(now-distant$lastSample<1_000_000_000L)return;distant$lastSample=now;
        var map=(Long2IntMap)LodMetrics.field(manager,"activeSectionMap");
        var data=(NodeStore)LodMetrics.field(manager,"nodeData");int[] leaves=new int[5],inner=new int[5],requests=new int[5];
        for(var entry:map.long2IntEntrySet()){
            int level=WorldEngine.getLevel(entry.getLongKey()),value=entry.getIntValue();
            if((value&0x80000000)!=0)requests[level]++;
            else if((value&0x40000000)!=0)inner[level]++;
            else leaves[level]++;
        }
        int collapse=((java.util.Set<?>)LodMetrics.field(manager,"distant$collapse")).size();
        int rebuilding=((it.unimi.dsi.fastutil.longs.Long2LongMap)LodMetrics.field(manager,"distant$rebuilding")).size();
        try{
            var file=LodBenchmark.OUTPUT.resolve("node-samples.jsonl");
            Files.writeString(file,"{\"epoch_ms\":"+System.currentTimeMillis()+",\"nodes\":"+data.getNodeCount()+",\"active\":"+map.size()+",\"leaves\":"+java.util.Arrays.toString(leaves)+",\"inner\":"+java.util.Arrays.toString(inner)+",\"requests\":"+java.util.Arrays.toString(requests)+",\"collapse_wait\":"+collapse+",\"mesh_rebuild\":"+rebuilding+"}\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
}
