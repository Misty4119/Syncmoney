package noietime.syncmoney.benchmark;

import org.bukkit.plugin.Plugin;
import noietime.syncmoney.benchmark.TransactionBenchmarkAdapter.Receipt;
import org.mockito.Mockito;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Proxy;
import java.util.logging.Logger;
import java.util.logging.Level;

import static org.mockito.Mockito.when;

/** Isolated, reflection-only adapter for the actual 1.3.2 facade artifact. */
public final class LegacyFacadeBenchmarkAdapter implements TransactionBenchmarkAdapter {

    private static final Logger LOGGER = Logger.getLogger("SyncmoneyLegacyBenchmark");

    static {
        LOGGER.setLevel(Level.SEVERE);
    }

    private final URLClassLoader loader;
    private final Object facade;
    private final Object queue;
    private final Object eventSource;
    private final Method updateMemoryState;
    private final Method getBalance;
    private final Method getMemoryState;
    private final Method deposit;
    private final Method withdraw;
    private final Method setBalance;
    private final Method atomicTransfer;
    private final Method poll;
    private final Method clearPendingTracking;
    private final Method queueSize;
    private final Method shutdown;
    private final Map<String, Object> cancellationListeners = new ConcurrentHashMap<>();
    private Object cancellationEventBus;
    private Class<?> cancellationEventType;
    private Class<?> cancellationListenerType;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong inFlight = new AtomicLong();
    private final Object drainStateLock = new Object();
    private final AtomicLong drained = new AtomicLong();
    private final AtomicReference<Throwable> drainFailure = new AtomicReference<>();
    private final ConcurrentMap<UUID, Receipt> latestReceipts = new ConcurrentHashMap<>();
    private final Thread drainer;

    public LegacyFacadeBenchmarkAdapter() {
        String artifactPath = System.getProperty("syncmoney.benchmarks.baselineArtifact");
        if (artifactPath == null || artifactPath.isBlank()) {
            throw new IllegalStateException("Legacy benchmark needs a built 1.3.2 artifact from runBaselineComparison.");
        }
        try {
            Path artifact = Path.of(artifactPath).toRealPath();
            loader = new ChildFirstSyncmoneyClassLoader(new URL[] {artifact.toUri().toURL()},
                    LegacyFacadeBenchmarkAdapter.class.getClassLoader());
            Class<?> queueType = load("noietime.syncmoney.economy.EconomyWriteQueue");
            queue = queueType.getConstructor(int.class).newInstance(50_000);

            Plugin plugin = Mockito.mock(Plugin.class);
            when(plugin.getLogger()).thenReturn(LOGGER);
            Object config = mockChild("noietime.syncmoney.config.SyncmoneyConfig");
            Object cache = mockChild("noietime.syncmoney.storage.CacheManager");
            Object redis = mockChild("noietime.syncmoney.storage.RedisManager");
            Object database = mockChild("noietime.syncmoney.storage.db.DatabaseManager");
            Object fallback = mockChild("noietime.syncmoney.economy.FallbackEconomyWrapper");
            Object overflow = mockChild("noietime.syncmoney.economy.OverflowLogInterface");
            eventSource = Arrays.stream(load("noietime.syncmoney.economy.EconomyEvent$EventSource").getEnumConstants())
                    .filter(value -> ((Enum<?>) value).name().equals("TEST"))
                    .findFirst().orElseThrow();

            Class<?> facadeType = load("noietime.syncmoney.economy.EconomyFacade");
            Constructor<?> constructor = Arrays.stream(facadeType.getConstructors())
                    .filter(candidate -> candidate.getParameterCount() == 10)
                    .findFirst().orElseThrow(() -> new IllegalStateException("Baseline facade lacks the characterized 10-argument constructor"));
            facade = constructor.newInstance(plugin, config, cache, redis, database, null, queue, fallback, null, overflow);

            Class<?> uuidType = UUID.class;
            Class<?> decimalType = BigDecimal.class;
            Class<?> sourceType = load("noietime.syncmoney.economy.EconomyEvent$EventSource");
            updateMemoryState = facadeType.getMethod("updateMemoryState", uuidType, decimalType, long.class);
            getBalance = facadeType.getMethod("getBalance", uuidType);
            getMemoryState = facadeType.getMethod("getMemoryState", uuidType);
            deposit = facadeType.getMethod("deposit", uuidType, decimalType, sourceType);
            withdraw = facadeType.getMethod("withdraw", uuidType, decimalType, sourceType);
            setBalance = facadeType.getMethod("setBalance", uuidType, decimalType, sourceType);
            atomicTransfer = facadeType.getMethod("atomicTransfer", uuidType, uuidType, decimalType, sourceType);
            shutdown = facadeType.getMethod("shutdown");

            Class<?> eventType = load("noietime.syncmoney.economy.EconomyEvent");
            poll = queueType.getMethod("poll", long.class, TimeUnit.class);
            clearPendingTracking = queueType.getMethod("clearPendingTracking", UUID.class);
            queueSize = queueType.getMethod("size");
            drainer = new Thread(() -> drain(eventType), "syncmoney-legacy-benchmark-receipt-adapter");
            drainer.setDaemon(true);
            drainer.start();
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new IllegalStateException("Could not initialize isolated 1.3.2 benchmark facade", failure);
        }
    }

    @Override
    public void seed(UUID account, BigDecimal balance, long version) {
        if (!Boolean.TRUE.equals(invoke(updateMemoryState, facade, account, balance, version))) {
            throw new IllegalStateException("1.3.2 facade rejected benchmark seed for " + account);
        }
    }

    @Override
    public boolean updateMemoryState(UUID account, BigDecimal balance, long version) {
        return Boolean.TRUE.equals(invoke(updateMemoryState, facade, account, balance, version));
    }

    @Override
    public BigDecimal balance(UUID account) {
        return (BigDecimal) invoke(getBalance, facade, account);
    }

    @Override
    public long version(UUID account) {
        Object state = invoke(getMemoryState, facade, account);
        if (state == null) throw new IllegalStateException("1.3.2 facade lost benchmark account " + account);
        return ((Number) invoke(method(state.getClass(), "version"), state)).longValue();
    }

    @Override
    public Receipt latestReceipt(UUID account) {
        return latestReceipts.get(account);
    }

    @Override
    public BigDecimal deposit(UUID account, BigDecimal amount) {
        return (BigDecimal) invoke(deposit, facade, account, amount, eventSource);
    }

    @Override
    public BigDecimal withdraw(UUID account, BigDecimal amount) {
        return (BigDecimal) invoke(withdraw, facade, account, amount, eventSource);
    }

    @Override
    public BigDecimal set(UUID account, BigDecimal balance) {
        return (BigDecimal) invoke(setBalance, facade, account, balance, eventSource);
    }

    @Override
    public BigDecimal transfer(UUID from, UUID to, BigDecimal amount) {
        return (BigDecimal) invoke(atomicTransfer, facade, from, to, amount, eventSource);
    }

    @Override
    public synchronized void enablePreTransactionCancellation(String transactionType) {
        try {
            Class<?> eventType = load("noietime.syncmoney.event.AsyncPreTransactionEvent");
            boolean supported = Arrays.stream(load("noietime.syncmoney.event.AsyncPreTransactionEvent$TransactionType").getEnumConstants())
                    .anyMatch(value -> ((Enum<?>) value).name().equals(transactionType));
            if (!supported) throw new IllegalArgumentException("Unknown pre-transaction type: " + transactionType);

            Class<?> busType = load("noietime.syncmoney.event.SyncmoneyEventBus");
            if (!Boolean.TRUE.equals(invoke(busType.getMethod("isInitialized"), null))) {
                Object testPlugin = mockChild("noietime.syncmoney.Syncmoney");
                when(((Plugin) testPlugin).getLogger()).thenReturn(LOGGER);
                invoke(busType.getMethod("init", load("noietime.syncmoney.Syncmoney")), null, testPlugin);
            }
            Object bus = invoke(busType.getMethod("getInstance"), null);
            Class<?> listenerType = load("noietime.syncmoney.event.SyncmoneyListener");
            Object listener = Proxy.newProxyInstance(loader, new Class<?>[] {listenerType}, (proxy, invokedMethod, args) -> {
                if (invokedMethod.getName().equals("onEvent") && args != null && args.length == 1) {
                    Object event = args[0];
                    Object eventValue = invoke(method(event.getClass(), "getType"), event);
                    if (((Enum<?>) eventValue).name().equals(transactionType)) {
                        Method setCancelled = eventType.getMethod("setCancelled", boolean.class, String.class);
                        invoke(setCancelled, event, true, "benchmark cancellation");
                    }
                    return null;
                }
                return switch (invokedMethod.getName()) {
                    case "toString" -> "Syncmoney benchmark cancellation listener";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            });
            Class<?> priorityType = load("noietime.syncmoney.event.EventPriority");
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object highest = Enum.valueOf((Class<? extends Enum>) priorityType, "HIGHEST");
            Method register = busType.getMethod("register", Class.class, listenerType, priorityType);
            invoke(register, bus, eventType, listener, highest);
            cancellationEventBus = bus;
            cancellationEventType = eventType;
            cancellationListenerType = listenerType;
            cancellationListeners.put(transactionType, listener);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not register isolated 1.3.2 pre-transaction cancellation", failure);
        }
    }

    @Override
    public void awaitDrain(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!isDrained() && System.nanoTime() < deadline) {
            throwIfDrainFailed();
            Thread.sleep(1L);
        }
        throwIfDrainFailed();
        if (!isDrained()) {
            throw new IllegalStateException("1.3.2 benchmark receipt adapter did not drain within " + timeoutMillis + "ms");
        }
    }

    private boolean isDrained() {
        synchronized (drainStateLock) {
            return queuedEvents() == 0 && inFlight.get() == 0;
        }
    }

    @Override
    public long drainedEvents() {
        return drained.get();
    }

    @Override
    public int queuedEvents() {
        return ((Number) invoke(queueSize, queue)).intValue();
    }

    @Override
    public void throwIfDrainFailed() {
        Throwable failure = drainFailure.get();
        if (failure != null) throw new IllegalStateException("1.3.2 benchmark queue adapter failed", failure);
    }

    private void drain(Class<?> eventType) {
        try {
            Method playerUuid = eventType.getMethod("playerUuid");
            Method balanceAfter = eventType.getMethod("balanceAfter");
            Method version = eventType.getMethod("version");
            Method type = eventType.getMethod("type");
            while (running.get() || queuedEvents() != 0) {
                Object event = null;
                try {
                    synchronized (drainStateLock) {
                        event = invoke(poll, queue, 50L, TimeUnit.MILLISECONDS);
                        if (event != null) inFlight.incrementAndGet();
                    }
                    if (event == null) continue;
                    String eventTypeName = ((Enum<?>) invoke(type, event)).name();
                    if (eventTypeName.equals("TRANSFER_IN") || eventTypeName.equals("TRANSFER_OUT")) {
                        Object paired = invoke(poll, queue, 1L, TimeUnit.SECONDS);
                        if (paired == null) throw new IllegalStateException("Accepted 1.3.2 transfer lacked its paired event");
                        inFlight.incrementAndGet();
                        recordReceipt(event, playerUuid, balanceAfter, version);
                        recordReceipt(paired, playerUuid, balanceAfter, version);
                        invoke(clearPendingTracking, queue, invoke(playerUuid, event));
                        invoke(clearPendingTracking, queue, invoke(playerUuid, paired));
                        drained.addAndGet(2L);
                        inFlight.decrementAndGet();
                    } else {
                        recordReceipt(event, playerUuid, balanceAfter, version);
                        invoke(clearPendingTracking, queue, invoke(playerUuid, event));
                        drained.incrementAndGet();
                    }
                } finally {
                    if (event != null) inFlight.decrementAndGet();
                }
            }
        } catch (Throwable failure) {
            drainFailure.compareAndSet(null, failure);
        }
    }

    private void recordReceipt(Object event, Method playerUuid, Method balanceAfter, Method version) {
        UUID account = (UUID) invoke(playerUuid, event);
        BigDecimal balance = (BigDecimal) invoke(balanceAfter, event);
        long eventVersion = ((Number) invoke(version, event)).longValue();
        latestReceipts.compute(account, (ignored, previous) -> {
            if (previous == null || eventVersion > previous.version()) {
                return new Receipt(balance, eventVersion);
            }
            if (eventVersion == previous.version() && balance.compareTo(previous.balance()) != 0) {
                throw new IllegalStateException("1.3.2 benchmark emitted conflicting balances at one account version");
            }
            return previous;
        });
    }

    private Object mockChild(String className) throws ClassNotFoundException {
        return Mockito.mock(load(className));
    }

    private Class<?> load(String className) throws ClassNotFoundException {
        return Class.forName(className, true, loader);
    }

    private static Method method(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Missing baseline state accessor " + name, failure);
        }
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Baseline invocation failed: " + method, cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Baseline invocation failed: " + method, failure);
        }
    }

    @Override
    public void close() {
        BenchmarkAdapterClose.run("Could not close isolated 1.3.2 benchmark adapter",
                () -> awaitDrain(10_000),
                () -> running.set(false),
                () -> {
                    drainer.interrupt();
                    drainer.join(2_000L);
                    if (drainer.isAlive()) throw new IllegalStateException("1.3.2 benchmark receipt drainer did not stop");
                },
                () -> {
                    if (cancellationEventBus == null) return;
                    Throwable unregisterFailure = null;
                    try {
                        Method unregister = cancellationEventBus.getClass().getMethod("unregister", Class.class,
                                cancellationListenerType);
                        for (Object listener : cancellationListeners.values()) {
                            try {
                                invoke(unregister, cancellationEventBus, cancellationEventType, listener);
                            } catch (Throwable failure) {
                                if (unregisterFailure == null) unregisterFailure = failure;
                                else unregisterFailure.addSuppressed(failure);
                            }
                        }
                    } finally {
                        cancellationListeners.clear();
                        cancellationEventBus = null;
                        cancellationEventType = null;
                        cancellationListenerType = null;
                    }
                    if (unregisterFailure != null) throw unregisterFailure;
                },
                () -> invoke(shutdown, facade),
                loader::close);
    }

    private static final class ChildFirstSyncmoneyClassLoader extends URLClassLoader {
        ChildFirstSyncmoneyClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("noietime.syncmoney.")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException notInBaseline) {
                        loaded = super.loadClass(name, false);
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }
}
