package io.github.wyze3306.jtb.e2e;

import org.geysermc.mcprotocollib.network.packet.Packet;

/**
 * A scripted behaviour of the headless client. Scenarios live in the {@code scenario} package and are
 * selected with {@code -Dscenario=<name>}: name {@code foo_bar} loads {@code scenario.FooBarScenario}.
 * Record expectations with {@link ClientContext#check}; any failed check makes the run exit with 1.
 */
public interface E2eScenario {

    /** Every packet received, on the network thread, after the client's own handling. */
    default void onPacket(ClientContext context, Packet packet) {
    }

    /** Once, when the client is in the world (first position received). */
    default void onJoined(ClientContext context) {
    }

    /** Every 50 ms while in the world; {@code tick} starts at 0 after joining. */
    default void tick(ClientContext context, int tick) {
    }

    /** At the end, before the report: final checks. */
    default void finish(ClientContext context) {
    }
}
