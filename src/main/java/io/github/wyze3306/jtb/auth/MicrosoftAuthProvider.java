package io.github.wyze3306.jtb.auth;

import io.github.wyze3306.jtb.JavaToBedrock;
import io.github.wyze3306.jtb.session.ProxySession;

import java.util.concurrent.CompletableFuture;

/**
 * Real Xbox Live identity for each Java player (Microsoft device code sign-in, cached tokens).
 */
public final class MicrosoftAuthProvider implements AuthProvider {

    private final JavaToBedrock proxy;

    public MicrosoftAuthProvider(JavaToBedrock proxy) {
        this.proxy = proxy;
    }

    @Override
    public CompletableFuture<LoginData> login(ProxySession session) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("microsoft auth not implemented yet"));
    }
}
