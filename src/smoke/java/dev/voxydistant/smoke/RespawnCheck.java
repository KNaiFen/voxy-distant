package dev.voxydistant.smoke;

import com.mojang.authlib.GameProfile;
import dev.voxydistant.network.Protocol;
import dev.voxydistant.server.RemoteServer;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import java.util.*;

/** Exercises Forge's actual player replacement and the following LOD request. */
final class RespawnCheck {
    private static final class Peer extends Connection {
        final EmbeddedChannel channel=new EmbeddedChannel();
        String hello;int replies;
        Peer(){super(PacketFlow.SERVERBOUND);}
        @Override public io.netty.channel.Channel channel(){return channel;}
        @Override public boolean isConnected(){return true;}
        @Override public void send(Packet<?> packet){capture(packet);}
        @Override public void send(Packet<?> packet,PacketSendListener listener){capture(packet);}
        private void capture(Packet<?> packet){
            if(!(packet instanceof ClientboundCustomPayloadPacket payload))return;
            var b=new FriendlyByteBuf(payload.getData().duplicate());int id=b.readVarInt();
            if(id==0){b.readUUID();hello=b.readUtf(256);}
            if(id==3)replies++;
        }
    }
    private static Object field(Object target,String name)throws ReflectiveOperationException{
        var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    @SuppressWarnings("unchecked") static void run(MinecraftServer server,Object service)throws Exception{
        var sessions=(Map<UUID,Object>)field(service,"players");
        var add=RemoteServer.class.getDeclaredMethod("addPlayer",ServerPlayer.class);add.setAccessible(true);
        var accept=RemoteServer.class.getDeclaredMethod("accept",ServerPlayer.class,Protocol.Requests.class);accept.setAccessible(true);
        var append=net.minecraftforge.network.NetworkHooks.class.getDeclaredMethod("appendConnectionData",Connection.class,Map.class,Map.class);append.setAccessible(true);
        for(var dimension:List.of(Level.OVERWORLD,Level.NETHER)){
            // Use the same connection for the old and replacement players, as vanilla does.
            var connection=new Peer();
            try{
                append.invoke(null,connection,Map.of(),Map.of(new ResourceLocation("voxy_distant","lod"),"17"));
                var old=new ServerPlayer(server,server.getLevel(dimension),new GameProfile(UUID.randomUUID(),"RespawnCheck"));
                old.setPos(8192,100,8192);old.connection=new ServerGamePacketListenerImpl(server,connection,old);
                add.invoke(service,old);Object previous=sessions.get(old.getUUID());
                if(previous==null)throw new AssertionError("LOD login session missing");
                RemoteServer.requests(old,new Protocol.Requests(1,64,2,0,32<<20,List.of()));
                connection.hello=null;
                var current=server.getPlayerList().respawn(old,false);current.connection.player=current;
                try{
                    Object active=sessions.get(current.getUUID());
                    if(active==previous||field(active,"player")!=current||field(active,"dimension")!=Level.OVERWORLD)
                        throw new AssertionError("Respawn retained old LOD player/dimension: "+dimension.location());
                    if(!"minecraft:overworld".equals(connection.hello))throw new AssertionError("Respawn Hello missing");
                    int x=current.chunkPosition().x+20,z=current.chunkPosition().z;
                    connection.replies=0;
                    accept.invoke(service,current,new Protocol.Requests(2,64,2,0,32<<20,List.of(new Protocol.Want(x,z,0,5,1))));
                    if(connection.replies!=0||((Map<?,?>)field(active,"pending")).isEmpty())
                        throw new AssertionError("New spawn request rejected");
                    System.out.println("DISTANT_RESPAWN_CASE_PASS "+dimension.location()+" -> minecraft:overworld");
                }finally{server.getPlayerList().remove(current);}
            }finally{connection.channel.finishAndReleaseAll();}
        }
        System.out.println("DISTANT_RESPAWN_PASS");
    }
}
