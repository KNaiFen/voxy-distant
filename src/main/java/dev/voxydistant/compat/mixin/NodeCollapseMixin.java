package dev.voxydistant.compat.mixin;

import dev.voxydistant.DebugLog;

import it.unimi.dsi.fastutil.longs.*;
import me.cortex.voxy.client.core.rendering.hierachical.*;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep existing children until the replacement parent's mesh has actually been uploaded. */
@Mixin(value=NodeManager.class,remap=false)
public abstract class NodeCollapseMixin {
    @Shadow @Final private Long2IntOpenHashMap activeSectionMap;
    @Shadow @Final private NodeStore nodeData;
    @Shadow @Final private LongOpenHashSet topLevelNodes;
    @Shadow @Final private me.cortex.voxy.client.core.rendering.ISectionWatcher watcher;
    @Shadow protected abstract void invalidateNode(int nodeId);
    @Shadow public abstract void processRequest(long pos);
    @Shadow public abstract void processChildChange(long pos,byte mask);
    @Unique private final LongOpenHashSet distant$collapse=new LongOpenHashSet();
    @Unique private final Long2LongOpenHashMap distant$rebuilding=new Long2LongOpenHashMap();
    @Unique private boolean distant$ready;
    @Unique private long distant$reportAt;
    @Inject(method="processGeometryResult",at=@At("HEAD"),cancellable=true)
    private void distant$rejectOldMesh(BuiltSection mesh,CallbackInfo ci){
        var stamp=(dev.voxydistant.compat.MeshStamp)(Object)mesh;
        if(stamp.distant$current()){
            if(DebugLog.mesh())DebugLog.log("CLIENT mesh_current node={} level={} generation={} current_generation={} children={} empty={}",mesh.position,me.cortex.voxy.common.world.WorldEngine.getLevel(mesh.position),stamp.distant$generation(),stamp.distant$latestGeneration(),mesh.childExistence&255,mesh.isEmpty());
            return;
        }
        DebugLog.count(DebugLog.Metric.CLIENT_MESH_REJECT);
        if(DebugLog.mesh())DebugLog.log("CLIENT mesh_reject node={} level={} generation={} current_generation={} children={} active={} watched={} empty={}",mesh.position,me.cortex.voxy.common.world.WorldEngine.getLevel(mesh.position),stamp.distant$generation(),stamp.distant$latestGeneration(),mesh.childExistence&255,activeSectionMap.containsKey(mesh.position),watcher.get(mesh.position),mesh.isEmpty());
        ci.cancel();mesh.free();
        if(activeSectionMap.containsKey(mesh.position)&&(watcher.get(mesh.position)&me.cortex.voxy.common.world.WorldEngine.UPDATE_TYPE_BLOCK_BIT)!=0){
            if(!distant$rebuilding.containsKey(mesh.position))distant$rebuilding.put(mesh.position,System.nanoTime());
            DebugLog.count(DebugLog.Metric.CLIENT_MESH_REBUILD);
            watcher.unwatch(mesh.position,me.cortex.voxy.common.world.WorldEngine.UPDATE_TYPE_BLOCK_BIT);
            watcher.watch(mesh.position,me.cortex.voxy.common.world.WorldEngine.UPDATE_TYPE_BLOCK_BIT);
            if(DebugLog.mesh())DebugLog.log("CLIENT mesh_rebuild node={} generation={} current_generation={}",mesh.position,stamp.distant$generation(),stamp.distant$latestGeneration());
        }
    }
    @Inject(method="processRequest",at=@At("HEAD"))
    private void distant$reportWait(long pos,CallbackInfo ci){
        long now=System.nanoTime();
        if(!DebugLog.enabled()||now-distant$reportAt<java.util.concurrent.TimeUnit.SECONDS.toNanos(dev.voxydistant.config.DistantConfig.DEBUG_INTERVAL.get()))return;
        distant$reportAt=now;
        distant$rebuilding.keySet().removeIf((long p)->!activeSectionMap.containsKey(p)||(watcher.get(p)&me.cortex.voxy.common.world.WorldEngine.UPDATE_TYPE_BLOCK_BIT)==0);
        if(distant$rebuilding.isEmpty())return;
        long oldest=0,node=0;for(var entry:distant$rebuilding.long2LongEntrySet()){long wait=now-entry.getLongValue();if(wait>oldest){oldest=wait;node=entry.getLongKey();}}
        DebugLog.log("CLIENT mesh_wait pending={} node={} wait_ms={}",distant$rebuilding.size(),node,DebugLog.millis(oldest));
    }
    @Inject(method="processChildChange",at=@At("HEAD"),cancellable=true)
    private void distant$defer(long pos,byte mask,CallbackInfo ci){
        if(distant$ready)return;
        if(mask!=0){distant$collapse.remove(pos);return;}
        int id=activeSectionMap.get(pos);
        if(id!=-1&&(id&0xc0000000)==0x40000000){
            ci.cancel();if(distant$collapse.add(pos)){if(DebugLog.mesh())DebugLog.log("CLIENT collapse_wait_parent node={}",pos);processRequest(pos);}
        }
    }
    @Inject(method="processGeometryResult",at=@At("RETURN"))
    private void distant$meshReady(BuiltSection mesh,CallbackInfo ci){
        // A cancelled stale result must never publish its child mask.
        var stamp=(dev.voxydistant.compat.MeshStamp)(Object)mesh;
        if(!stamp.distant$current())return;
        long pos=mesh.position;
        int id=activeSectionMap.get(pos);
        if(id==-1||(watcher.get(pos)&me.cortex.voxy.common.world.WorldEngine.UPDATE_TYPE_BLOCK_BIT)==0)return;
        DebugLog.count(DebugLog.Metric.CLIENT_MESH_ACCEPT);
        if(stamp.distant$coverage()!=null)stamp.distant$coverage().acceptedMeshChildren(pos,stamp.distant$generation(),mesh.childExistence);
        long rebuilding=distant$rebuilding.remove(pos);
        if(rebuilding!=0){DebugLog.count(DebugLog.Metric.CLIENT_MESH_RECOVERED);if(DebugLog.mesh())DebugLog.log("CLIENT mesh_recovered node={} wait_ms={}",pos,DebugLog.millis(System.nanoTime()-rebuilding));}
        boolean collapse=distant$collapse.remove(pos);
        // Other children can still be pending. Repair this child's mask before the group completes.
        if((id&0x80000000)!=0){processChildChange(pos,mesh.childExistence);return;}
        if(nodeData.getNodeGeometry(id&0xffffff)==NodeManager.NULL_GEOMETRY_ID)return;
        int before=nodeData.getNodeChildExistence(id&0xffffff)&255;
        if(!collapse&&before==(mesh.childExistence&255))return;
        if(DebugLog.mesh())DebugLog.log("CLIENT mesh_mask node={} before={} children={} collapse={}",pos,before,mesh.childExistence&255,collapse);
        distant$ready=true;try{processChildChange(pos,mesh.childExistence);}finally{distant$ready=false;}
    }
    @Inject(method="removeTopLevelNode",at=@At("RETURN"))
    private void distant$prune(long pos,CallbackInfo ci){distant$collapse.removeIf(p->!activeSectionMap.containsKey(p));distant$rebuilding.keySet().removeIf((long p)->!activeSectionMap.containsKey(p));}
    @Inject(method="updateChildSectionsInner",at=@At("RETURN"))
    private void distant$collapsed(long pos,int nodeId,byte mask,CallbackInfo ci){
        if(mask==0)distant$updateParentLeafFlag(pos);
    }
    @Inject(method="processLeafGeometryRemoval",at=@At("RETURN"))
    private void distant$evicted(long child,CallbackInfo ci){
        long pos=me.cortex.voxy.common.world.WorldEngine.getWorldSectionId(me.cortex.voxy.common.world.WorldEngine.getLevel(child)+1,me.cortex.voxy.common.world.WorldEngine.getX(child)>>1,me.cortex.voxy.common.world.WorldEngine.getY(child)>>1,me.cortex.voxy.common.world.WorldEngine.getZ(child)>>1);
        if((activeSectionMap.get(pos)&0xc0000000)==0)distant$updateParentLeafFlag(pos);
    }
    // Voxy clears the grandparent's flag on split but does not publish that change to the GPU.
    @Redirect(method="finishRequest(ILme/cortex/voxy/client/core/rendering/hierachical/NodeChildRequest;)V",at=@At(value="INVOKE",target="Lme/cortex/voxy/client/core/rendering/hierachical/NodeStore;setAllChildrenAreLeaf(IZ)V",ordinal=1))
    private void distant$publishSplit(NodeStore store,int id,boolean value){store.setAllChildrenAreLeaf(id,value);invalidateNode(id);}
    @Unique private void distant$updateParentLeafFlag(long pos){
        if(topLevelNodes.contains(pos))return;
        long parent=me.cortex.voxy.common.world.WorldEngine.getWorldSectionId(me.cortex.voxy.common.world.WorldEngine.getLevel(pos)+1,me.cortex.voxy.common.world.WorldEngine.getX(pos)>>1,me.cortex.voxy.common.world.WorldEngine.getY(pos)>>1,me.cortex.voxy.common.world.WorldEngine.getZ(pos)>>1);
        int id=activeSectionMap.get(parent)&0xffffff;
        int ptr=nodeData.getChildPtr(id),count=nodeData.getChildPtrCount(id);
        // Only direct siblings change eligibility; at most eight entries, on the node owner thread.
        boolean allLeaf=true;
        for(int i=0;i<count;i++)if((activeSectionMap.get(nodeData.nodePosition(ptr+i))&0xc0000000)!=0){allLeaf=false;break;}
        if(nodeData.getAllChildrenAreLeaf(id)!=allLeaf){nodeData.setAllChildrenAreLeaf(id,allLeaf);invalidateNode(id);}
    }
    @Inject(method="removeNodeGeometry",at=@At("HEAD"),cancellable=true)
    private void distant$keepReplacement(long pos,CallbackInfo ci){
        long parent=pos;
        for(int l=me.cortex.voxy.common.world.WorldEngine.getLevel(pos);l<=4;l++){
            if(distant$collapse.contains(parent)){ci.cancel();return;}
            parent=me.cortex.voxy.common.world.WorldEngine.getWorldSectionId(l+1,me.cortex.voxy.common.world.WorldEngine.getX(parent)>>1,me.cortex.voxy.common.world.WorldEngine.getY(parent)>>1,me.cortex.voxy.common.world.WorldEngine.getZ(parent)>>1);
        }
    }
}
