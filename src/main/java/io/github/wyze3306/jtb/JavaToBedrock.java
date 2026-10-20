package io.github.wyze3306.jtb;

import io.github.wyze3306.jtb.assets.BedrockAssets;
import io.github.wyze3306.jtb.auth.AuthProvider;
import io.github.wyze3306.jtb.auth.AuthProviders;
import io.github.wyze3306.jtb.config.ProxyConfig;
import io.github.wyze3306.jtb.mapping.MappingData;
import io.github.wyze3306.jtb.network.bedrock.BedrockConnector;
import io.github.wyze3306.jtb.network.bedrock.BedrockPinger;
import io.github.wyze3306.jtb.network.java.JavaServer;
import io.github.wyze3306.jtb.session.SessionManager;
import io.github.wyze3306.jtb.translator.TranslatorRegistry;
import io.github.wyze3306.jtb.translator.Translators;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Entry point and service container of the proxy.
 */
public final class JavaToBedrock {

    private static final Logger LOGGER = LoggerFactory.getLogger(JavaToBedrock.class);
    public static final String NAME = "JavaToBedrock";
    public static final String VERSION = versionFromManifest();
    private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private static volatile JavaToBedrock instance;

    private final Path dataDirectory;
    private final ProxyConfig config;
    private final SessionManager sessions = new SessionManager();
    private final List<AutoCloseable> services = new ArrayList<>();
    private TranslatorRegistry translators;
    private AuthProvider authProvider;
    private EventLoopGroup sessionLoops;
    private ExecutorService workers;
    private BedrockConnector bedrockConnector;
    private BedrockPinger pinger;
    private JavaServer javaServer;
    private volatile boolean running;

    public JavaToBedrock(Path dataDirectory, ProxyConfig config) {
        this.dataDirectory = dataDirectory;
        this.config = config;
    }

    public static void main(String[] args) throws Exception {
        Path dataDirectory = Path.of(System.getProperty("jtb.data", ".")).toAbsolutePath().normalize();
        ProxyConfig config = ProxyConfig.load(dataDirectory.resolve("config.yml"));
        JavaToBedrock proxy = new JavaToBedrock(dataDirectory, config);
        Runtime.getRuntime().addShutdownHook(new Thread(proxy::stop, "JavaToBedrock shutdown"));
        proxy.start();
        proxy.readConsole();
    }

    /** The running proxy, for code that cannot receive it (e.g. static helpers). */
    public static JavaToBedrock get() {
        return instance;
    }

    public void start() throws Exception {
        long start = System.nanoTime();
        instance = this;
        LOGGER.info("Starting {} {} (Java {} <-> Bedrock {})", NAME, VERSION,
                JavaServer.javaVersionName(), BedrockConnector.defaultCodec().getMinecraftVersion());

        BedrockAssets.setCacheRoot(this.dataDirectory.resolve("cache"));
        MappingData.load();
        this.translators = Translators.createRegistry();
        LOGGER.info("Loaded {} Bedrock and {} Java packet translators", this.translators.bedrockCount(), this.translators.javaCount());

        int cores = Runtime.getRuntime().availableProcessors();
        this.sessionLoops = new DefaultEventLoopGroup(Math.max(2, cores), new DefaultThreadFactory("jtb-session", true));
        int workerThreads = this.config.world().chunkThreads() > 0 ? this.config.world().chunkThreads() : Math.max(1, cores - 1);
        this.workers = Executors.newFixedThreadPool(workerThreads, new DefaultThreadFactory("jtb-worker", true));

        this.authProvider = AuthProviders.create(this);
        this.bedrockConnector = new BedrockConnector(this);
        this.pinger = new BedrockPinger(this);
        this.pinger.start();

        for (ProxyService service : ProxyServices.create(this)) {
            service.start();
            this.services.add(service);
        }

        this.javaServer = new JavaServer(this);
        this.javaServer.bind();
        this.running = true;
        LOGGER.info("Ready in {} ms. Java players can join on {}:{} -> Bedrock server {}:{} (auth: {})",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                this.config.java().bind(), this.config.java().port(),
                this.config.bedrock().address(), this.config.bedrock().port(),
                this.config.bedrock().auth().name().toLowerCase(Locale.ROOT));
    }

    public synchronized void stop() {
        if (!this.running) {
            return;
        }
        this.running = false;
        LOGGER.info("Stopping...");
        for (var session : List.copyOf(this.sessions.all())) {
            session.disconnect("Proxy shutting down");
        }
        if (this.javaServer != null) {
            this.javaServer.close();
        }
        for (AutoCloseable service : this.services) {
            try {
                service.close();
            } catch (Exception e) {
                LOGGER.warn("Error while stopping {}", service, e);
            }
        }
        if (this.pinger != null) {
            this.pinger.close();
        }
        if (this.bedrockConnector != null) {
            this.bedrockConnector.close();
        }
        if (this.workers != null) {
            this.workers.shutdownNow();
        }
        if (this.sessionLoops != null) {
            this.sessionLoops.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
        LOGGER.info("Stopped.");
    }

    private void readConsole() throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while (this.running && (line = reader.readLine()) != null) {
            String command = line.trim().toLowerCase(Locale.ROOT);
            switch (command) {
                case "" -> {
                }
                case "stop", "end", "exit" -> {
                    stop();
                    System.exit(0);
                }
                case "list" -> LOGGER.info("{} player(s): {}", this.sessions.size(),
                        String.join(", ", this.sessions.all().stream().map(s -> s.getName()).toList()));
                default -> LOGGER.info("Commands: list, stop");
            }
        }
    }

    // ------------------------------------------------------------------ accessors

    public ProxyConfig config() {
        return this.config;
    }

    public Path dataDirectory() {
        return this.dataDirectory;
    }

    public SessionManager sessions() {
        return this.sessions;
    }

    public TranslatorRegistry translators() {
        return this.translators;
    }

    public EventLoopGroup sessionLoops() {
        return this.sessionLoops;
    }

    /**
     * Shared pool for CPU-heavy work (chunk conversion, lighting, pack conversion). Results must be
     * handed back to the session with {@code session.execute(...)}.
     */
    public ExecutorService workers() {
        return this.workers;
    }

    public AuthProvider authProvider() {
        return this.authProvider;
    }

    public BedrockConnector bedrockConnector() {
        return this.bedrockConnector;
    }

    public BedrockPinger pinger() {
        return this.pinger;
    }

    public long tickPeriodNanos() {
        return TICK_NANOS;
    }

    @SuppressWarnings("unchecked")
    public <T extends ProxyService> T service(Class<T> type) {
        for (AutoCloseable service : this.services) {
            if (type.isInstance(service)) {
                return (T) service;
            }
        }
        throw new IllegalStateException("Service not running: " + type.getSimpleName());
    }

    private static String versionFromManifest() {
        String version = JavaToBedrock.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }
}
