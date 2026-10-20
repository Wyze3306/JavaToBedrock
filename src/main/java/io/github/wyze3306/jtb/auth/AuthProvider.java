package io.github.wyze3306.jtb.auth;

import io.github.wyze3306.jtb.session.ProxySession;

import java.util.concurrent.CompletableFuture;

/**
 * Produces the Bedrock identity of a Java player. Implementations may complete asynchronously
 * (e.g. while the player signs in to Microsoft); the future must never complete on the caller's
 * stack with blocking network work.
 */
public interface AuthProvider {

    CompletableFuture<LoginData> login(ProxySession session);
}
