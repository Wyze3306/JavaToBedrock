package io.github.wyze3306.jtb.session;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Identifies one kind of per-session state and knows how to create it. Each subsystem declares its
 * own key as a constant, e.g.
 * <pre>{@code
 * public static final StateKey<WorldState> KEY = StateKey.of("world", WorldState::new);
 * }</pre>
 * and reads it with {@code session.get(WorldState.KEY)}. States are created lazily on first access
 * and stored in a flat array indexed by {@link #index()}, so a lookup is one array read.
 *
 * @param <T> the state type
 */
public final class StateKey<T extends SessionState> {

    private static final AtomicInteger NEXT_INDEX = new AtomicInteger();

    private final String name;
    private final int index;
    private final Function<ProxySession, T> factory;

    private StateKey(String name, Function<ProxySession, T> factory) {
        this.name = name;
        this.factory = factory;
        this.index = NEXT_INDEX.getAndIncrement();
    }

    public static <T extends SessionState> StateKey<T> of(String name, Function<ProxySession, T> factory) {
        return new StateKey<>(name, factory);
    }

    public String name() {
        return this.name;
    }

    int index() {
        return this.index;
    }

    T create(ProxySession session) {
        return this.factory.apply(session);
    }

    static int count() {
        return NEXT_INDEX.get();
    }

    @Override
    public String toString() {
        return "StateKey[" + this.name + "]";
    }
}
