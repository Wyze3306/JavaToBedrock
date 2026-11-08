package io.github.wyze3306.jtb.network.bedrock;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockClientInitializer;

import java.util.List;

/**
 * The Bedrock client pipeline on a NetherNet (WebRTC data channel) connection.
 * <p>
 * Above the framing everything is the RakNet pipeline: a data channel message is one batch, the same
 * body RakNet carries after its {@code 0xFE} frame id, so {@link NetherNetFrameCodec} replaces the
 * RakNet frame codec and compression, batching and the packet codec stay as they are. The protocol
 * library reads the RakNet protocol version from the channel options to choose compression and the
 * packet codec; a NetherNet channel has none, so the version is given here (NetherNet follows the
 * rules of RakNet protocol 11, the one current codecs declare).
 */
public abstract class NetherNetClientInitializer extends BedrockClientInitializer {

    private final int rakVersion;

    protected NetherNetClientInitializer(int rakVersion) {
        this.rakVersion = rakVersion;
    }

    @Override
    protected void preInitChannel(Channel channel) {
        // Not super: BedrockClientInitializer adds the RakNet frame codec.
        channel.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.SERVER_BOUND);
        channel.pipeline()
                .addLast(NetherNetFrameCodec.NAME, NetherNetFrameCodec.INSTANCE)
                .addLast(CompressionCodec.NAME, new CompressionCodec(
                        BedrockChannelInitializer.getCompression(PacketCompressionAlgorithm.ZLIB, this.rakVersion, true), false));
    }

    @Override
    protected void initPacketCodec(Channel channel) {
        channel.pipeline().addLast(BedrockPacketCodec.NAME, new BedrockPacketCodec_v3());
    }

    @Override
    protected BedrockPeer createPeer(Channel channel) {
        int version = this.rakVersion;
        return new BedrockPeer(channel, this::createSession) {
            @Override
            public int getRakVersion() {
                return version;
            }
        };
    }

    /** One data channel message = one compressed batch, without RakNet's frame id. */
    @ChannelHandler.Sharable
    static final class NetherNetFrameCodec extends MessageToMessageCodec<ByteBuf, BedrockBatchWrapper> {

        static final String NAME = "nethernet-frame-codec";
        static final NetherNetFrameCodec INSTANCE = new NetherNetFrameCodec();

        private NetherNetFrameCodec() {
        }

        @Override
        protected void encode(ChannelHandlerContext ctx, BedrockBatchWrapper msg, List<Object> out) {
            if (msg.getCompressed() == null) {
                throw new IllegalStateException("Bedrock batch was not compressed");
            }
            out.add(msg.getCompressed().retainedSlice());
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
            if (msg.isReadable()) {
                out.add(BedrockBatchWrapper.newInstance(msg.readRetainedSlice(msg.readableBytes()), null));
            }
        }
    }
}
