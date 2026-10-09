package noietime.syncmoney.economy;

import noietime.syncmoney.benchmark.TransactionBenchmarkAdapter;
import noietime.syncmoney.benchmark.TransactionBenchmarkAdapter.Receipt;
import noietime.syncmoney.benchmark.BenchmarkAdapterClose;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.event.AsyncPreTransactionEvent;
import noietime.syncmoney.event.EventPriority;
import noietime.syncmoney.event.SyncmoneyEventBus;
import noietime.syncmoney.event.SyncmoneyListener;
import org.bukkit.plugin.Plugin;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.logging.Level;

import static org.mockito.Mockito.when;

/** Exercises the current public facade with a deterministic, asynchronous receipt adapter. */
public final class CurrentFacadeBenchmarkAdapter implements TransactionBenchmarkAdapter {

    private static final Logger LOGGER = Logger.getLogger("SyncmoneyBenchmark");

    static {
        LOGGER.setLevel(Level.SEVERE);
    }

    private final EconomyWriteQueue queue = new EconomyWriteQueue(50_000, LOGGER);
    private final EconomyFacade facade;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong inFlight = new AtomicLong();
    private final AtomicLong drained = new AtomicLong();
    private final Object drainStateLock = new Object();
    private final AtomicReference<Throwable> drainFailure = new AtomicReference<>();
    private final ConcurrentMap<UUID, Receipt> latestReceipts = new ConcurrentHashMap<>();
    private final Thread drainer;
    private SyncmoneyEventBus cancellationEventBus;
    private SyncmoneyListener<AsyncPreTransactionEvent> cancellationListener;

    private record DrainSnapshot(int queued, long inFlight, long drained) {
        boolean isDrained() {
            return queued == 0 && inFlight == 0;
        }
    }

    public CurrentFacadeBenchmarkAdapter() {
        Plugin plugin = Mockito.mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(LOGGER);
        FallbackEconomyWrapper fallback = Mockito.mock(FallbackEconomyWrapper.class);
        when(fallback.isDegraded()).thenReturn(false);
        facade = new EconomyFacade(plugin, Mockito.mock(SyncmoneyConfig.class), null, null, null, null,
                queue, fallback, null, Mockito.mock(OverflowLogInterface.class));
        drainer = new Thread(this::drain, "syncmoney-benchmark-receipt-adapter");
        drainer.setDaemon(true);
        drainer.start();
    }

    @Override
    public void seed(UUID account, BigDecimal balance, long version) {
        if (!facade.updateMemoryState(account, balance, version)) {
            throw new IllegalStateException("Current facade rejected benchmark seed for " + account);
        }
    }

    @Override
    public boolean updateMemoryState(UUID account, BigDecimal balance, long version) {
        return facade.updateMemoryState(account, balance, version);
    }

    @Override
    public BigDecimal balance(UUID account) {
        return facade.getBalance(account);
    }

    @Override
    public long version(UUID account) {
        EconomyFacade.EconomyState state = facade.getMemoryState(account);
        if (state == null) throw new IllegalStateException("Current facade lost benchmark account " + account);
        return state.version();
    }

    @Override
    public Receipt latestReceipt(UUID account) {
        return latestReceipts.get(account);
    }

    @Override
    public BigDecimal deposit(UUID account, BigDecimal amount) {
        return facade.deposit(account, amount, EconomyEvent.EventSource.TEST);
    }

    @Override
    public BigDecimal withdraw(UUID account, BigDecimal amount) {
        return facade.withdraw(account, amount, EconomyEvent.EventSource.TEST);
    }

    @Override
    public BigDecimal set(UUID account, BigDecimal balance) {
        return facade.setBalance(account, balance, EconomyEvent.EventSource.TEST);
    }

    @Override
    public BigDecimal transfer(UUID from, UUID to, BigDecimal amount) {
        return facade.atomicTransfer(from, to, amount, EconomyEvent.EventSource.TEST);
    }

    @Override
    public synchronized void enablePreTransactionCancellation(String transactionType) {
        boolean supported = java.util.Arrays.stream(AsyncPreTransactionEvent.TransactionType.values())
                .anyMatch(type -> type.name().equals(transactionType));
        if (!supported) throw new IllegalArgumentException("Unknown pre-transaction type: " + transactionType);
        if (!SyncmoneyEventBus.isInitialized()) {
            Syncmoney testPlugin = Mockito.mock(Syncmoney.class);
            when(testPlugin.getLogger()).thenReturn(LOGGER);
            SyncmoneyEventBus.init(testPlugin);
        }
        cancellationEventBus = SyncmoneyEventBus.getInstance();
        cancellationListener = event -> {
            if (event.getType().name().equals(transactionType)) {
                event.setCancelled(true, "benchmark cancellation");
            }
        };
        cancellationEventBus.register(AsyncPreTransactionEvent.class, cancellationListener, EventPriority.HIGHEST);
    }

    @Override
    public void awaitDrain(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!isDrained() && System.nanoTime() < deadline) {
            throwIfDrainFailed();
            Thread.sleep(1L);
        }
        throwIfDrainFailed();
        DrainSnapshot snapshot = drainSnapshot();
        if (!snapshot.isDrained()) {
            Thread.State drainerState = drainer.getState();
            String drainerStack = java.util.Arrays.stream(drainer.getStackTrace())
                    .limit(4)
                    .map(StackTraceElement::toString)
                    .collect(java.util.stream.Collectors.joining(" <- "));
            throw new IllegalStateException("Current benchmark receipt adapter did not drain within " + timeoutMillis
                    + "ms: queueSize=" + snapshot.queued() + "/" + queue.getCapacity()
                    + ", inFlight=" + snapshot.inFlight() + ", drained=" + snapshot.drained()
                    + ", drainerAlive=" + drainer.isAlive() + ", drainerState=" + drainerState
                    + ", drainerStack=[" + drainerStack + "]");
        }
    }

    private boolean isDrained() {
        return drainSnapshot().isDrained();
    }

    private DrainSnapshot drainSnapshot() {
        synchronized (drainStateLock) {
            return new DrainSnapshot(queue.size(), inFlight.get(), drained.get());
        }
    }

    @Override
    public long drainedEvents() {
        return drained.get();
    }

    @Override
    public int queuedEvents() {
        return queue.size();
    }

    @Override
    public void throwIfDrainFailed() {
        Throwable failure = drainFailure.get();
        if (failure != null) throw new IllegalStateException("Current benchmark receipt adapter failed", failure);
    }

    private void drain() {
        while (running.get() || queue.size() != 0) {
            EconomyEvent event = null;
            try {
                synchronized (drainStateLock) {
                    event = queue.poll(50, TimeUnit.MILLISECONDS);
                    if (event != null) inFlight.incrementAndGet();
                }
                if (event == null) continue;
                if (event.type() == EconomyEvent.EventType.TRANSFER_IN
                        || event.type() == EconomyEvent.EventType.TRANSFER_OUT) {
                    EconomyEvent paired = queue.poll(1, TimeUnit.SECONDS);
                    if (paired == null) throw new IllegalStateException("Accepted transfer was missing its paired event");
                    inFlight.incrementAndGet();
                    recordReceipt(event);
                    recordReceipt(paired);
                    if (!queue.completePair(event, event.balanceAfter(), event.version(),
                            paired, paired.balanceAfter(), paired.version())) {
                        throw new IllegalStateException("Current queue rejected its accepted transfer receipt pair");
                    }
                    drained.addAndGet(2);
                    inFlight.decrementAndGet();
                } else {
                    recordReceipt(event);
                    queue.complete(event, event.balanceAfter(), event.version());
                    drained.incrementAndGet();
                }
            } catch (InterruptedException interrupted) {
                if (!running.get()) return;
            } catch (Throwable failure) {
                drainFailure.compareAndSet(null, failure);
                return;
            } finally {
                if (event != null) inFlight.decrementAndGet();
            }
        }
    }

    private void recordReceipt(EconomyEvent event) {
        latestReceipts.compute(event.playerUuid(), (account, previous) -> {
            if (previous == null || event.version() > previous.version()) {
                return new Receipt(event.balanceAfter(), event.version());
            }
            if (event.version() == previous.version()
                    && event.balanceAfter().compareTo(previous.balance()) != 0) {
                throw new IllegalStateException("Current benchmark emitted conflicting balances at one account version");
            }
            return previous;
        });
    }

    @Override
    public void close() {
        BenchmarkAdapterClose.run("Could not close current benchmark receipt adapter",
                () -> awaitDrain(10_000),
                () -> running.set(false),
                () -> {
                    if (cancellationEventBus != null && cancellationListener != null) {
                        try {
                            cancellationEventBus.unregister(AsyncPreTransactionEvent.class, cancellationListener);
                        } finally {
                            cancellationListener = null;
                            cancellationEventBus = null;
                        }
                    }
                },
                () -> {
                    drainer.interrupt();
                    drainer.join(2_000L);
                    if (drainer.isAlive()) throw new IllegalStateException("Current benchmark receipt drainer did not stop");
                },
                facade::shutdown);
    }
}
