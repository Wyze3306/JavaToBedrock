package io.github.wyze3306.jtb.world;

import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.jspecify.annotations.Nullable;

/**
 * CONTRACT (owner: world subsystem). A Bedrock item frame as the Java client sees it: an
 * {@code item_frame} or {@code glow_item_frame} entity standing for the frame block at
 * {@link #position()}. {@link WorldState#itemFrame(int)} returns it for the Java entity id the client
 * attacks or uses, so the player module acts on the Bedrock block instead (a Bedrock client clicks the
 * block: left click takes the item out or breaks the frame, right click puts an item in or turns it).
 *
 * @param position the frame block
 * @param facing   the side the frame faces, which is the face a Bedrock client clicks (0 down, 1 up,
 *                 2 north, 3 south, 4 west, 5 east)
 * @param glow     whether it is a glow item frame
 * @param hasItem  whether the frame holds an item, as the server last said
 */
public record ItemFrame(Vector3i position, int facing, boolean glow, boolean hasItem) {

    /** Distance from the block centre to the Java entity's position, towards the block the frame hangs on. */
    static final double WALL_OFFSET = 0.46875;

    /**
     * Where on the frame block a Java hit lands: {@code hit} is relative to the entity's position (the
     * location of {@code ServerboundInteract}), the result is the click position of a Bedrock item use
     * (0 to 1 on each axis, from the block's corner).
     */
    public Vector3f clickPosition(@Nullable Vector3d hit) {
        double x = 0.5 - WALL_OFFSET * stepX(this.facing);
        double y = 0.5 - WALL_OFFSET * stepY(this.facing);
        double z = 0.5 - WALL_OFFSET * stepZ(this.facing);
        if (hit != null) {
            x += hit.getX();
            y += hit.getY();
            z += hit.getZ();
        }
        return Vector3f.from(Math.clamp(x, 0, 1), Math.clamp(y, 0, 1), Math.clamp(z, 0, 1));
    }

    static int stepX(int facing) {
        return facing == 4 ? -1 : facing == 5 ? 1 : 0;
    }

    static int stepY(int facing) {
        return facing == 0 ? -1 : facing == 1 ? 1 : 0;
    }

    static int stepZ(int facing) {
        return facing == 2 ? -1 : facing == 3 ? 1 : 0;
    }
}
