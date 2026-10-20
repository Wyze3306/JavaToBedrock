package io.github.wyze3306.jtb.session;

import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * All live sessions. Thread-safe.
 */
public final class SessionManager {

    private final Map<UUID, ProxySession> byUuid = new ConcurrentHashMap<>();

    public void add(ProxySession session) {
        this.byUuid.put(session.getProfile().getId(), session);
    }

    public void remove(ProxySession session) {
        this.byUuid.remove(session.getProfile().getId(), session);
    }

    public @Nullable ProxySession get(UUID uuid) {
        return this.byUuid.get(uuid);
    }

    public @Nullable ProxySession getByName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (ProxySession session : this.byUuid.values()) {
            if (session.getName().toLowerCase(Locale.ROOT).equals(lower)) {
                return session;
            }
        }
        return null;
    }

    public Collection<ProxySession> all() {
        return Collections.unmodifiableCollection(this.byUuid.values());
    }

    public int size() {
        return this.byUuid.size();
    }
}
