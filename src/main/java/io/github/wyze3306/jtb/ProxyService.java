package io.github.wyze3306.jtb;

/**
 * A proxy-wide service with a start/stop lifecycle (resource pack HTTP server, auth caches...).
 * Services are listed in {@link ProxyServices}.
 */
public interface ProxyService extends AutoCloseable {

    void start() throws Exception;

    @Override
    void close() throws Exception;
}
