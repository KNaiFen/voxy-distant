package dev.voxydistant.smoke.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Allow the isolated test player's movement packets to reach vanilla chunk tracking. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ExtremeMovementMixin {
    @Shadow protected abstract boolean isSingleplayerOwner();
    @Redirect(method="handleMovePlayer",at=@At(value="INVOKE",target="Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isSingleplayerOwner()Z"))
    private boolean distant$movement(ServerGamePacketListenerImpl listener){
        return Boolean.getBoolean("voxyDistant.extremeMovement")||isSingleplayerOwner();
    }
}
