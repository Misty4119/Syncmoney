package noietime.syncmoney.benchmark;

import noietime.syncmoney.economy.CurrentFacadeBenchmarkAdapter;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.TimeUnit;
import noietime.syncmoney.benchmark.TransactionBenchmarkAdapter.Receipt;

import static org.openjdk.jmh.annotations.Mode.Throughput;
import static org.openjdk.jmh.annotations.Mode.SampleTime;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.OutputTimeUnit;

/** Paired latency/throughput measurements over the real 1.3.2 and 1.4 facade paths. */
@BenchmarkMode({Throughput, SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class TransactionBenchmarks {

    private static final BigDecimal START_BALANCE = new BigDecimal("1000000000000000.00");
    private static final BigDecimal AMOUNT = new BigDecimal("1.00");
    private static final BigDecimal SET_A = new BigDecimal("1000.00");
    private static final BigDecimal SET_B = new BigDecimal("2000.00");

    @State(Scope.Benchmark)
    public static class Fixture {
        @Param({"current"})
        public String variant;

        @Param({"1", "1000", "10000"})
        public int accountCount;

        @Param({"uniform", "hot"})
        public String accessPattern;

        @Param({"balance", "deposit", "withdraw", "set", "transfer", "reject-withdraw",
                "cancel-deposit", "cancel-withdraw"})
        public String operation;

        private TransactionBenchmarkAdapter adapter;
        private List<UUID> accounts;
        private java.util.Map<UUID, Integer> accountIndices;
        // Fixed per-account targets make the expected value unambiguous for every accepted version.
        private java.util.Map<UUID, BigDecimal> setTargets;
        private UUID rejectedAccount;
        private AtomicInteger cursor;
        private LongAdder acceptedWrites;
        private LongAdder rejectedWrites;
        private LongAdder offeredTransactions;
        private LongAdder acceptedTransactions;
        private LongAdder rejectedTransactions;
        private ConcurrentHashMap<UUID, LongAdder> versionsAdded;
        private ConcurrentHashMap<UUID, LongAdder> balanceDeltaCents;

        @Setup(Level.Trial)
        public void setup() {
            adapter = switch (variant) {
                case "current" -> new CurrentFacadeBenchmarkAdapter();
                case "legacy132" -> new LegacyFacadeBenchmarkAdapter();
                default -> throw new IllegalArgumentException("Unknown transaction variant: " + variant);
            };
            int effectiveCount = operation.equals("transfer") ? Math.max(2, accountCount) : accountCount;
            accounts = new ArrayList<>(effectiveCount);
            accountIndices = new java.util.HashMap<>(effectiveCount);
            setTargets = new java.util.HashMap<>(effectiveCount);
            cursor = new AtomicInteger();
            acceptedWrites = new LongAdder();
            rejectedWrites = new LongAdder();
            offeredTransactions = new LongAdder();
            acceptedTransactions = new LongAdder();
            rejectedTransactions = new LongAdder();
            versionsAdded = new ConcurrentHashMap<>();
            balanceDeltaCents = new ConcurrentHashMap<>();
            for (int index = 0; index < effectiveCount; index++) {
                UUID account = UUID.nameUUIDFromBytes(("syncmoney-bench-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                accounts.add(account);
                accountIndices.put(account, index);
                setTargets.put(account, index % 2 == 0 ? SET_A : SET_B);
                adapter.seed(account, START_BALANCE, 100L);
            }
            rejectedAccount = UUID.nameUUIDFromBytes("syncmoney-bench-rejected".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            adapter.seed(rejectedAccount, BigDecimal.ZERO.setScale(2), 1L);
            if (operation.equals("cancel-deposit")) adapter.enablePreTransactionCancellation("DEPOSIT");
            if (operation.equals("cancel-withdraw")) adapter.enablePreTransactionCancellation("WITHDRAW");
        }

        UUID nextAccount() {
            if (accessPattern.equals("hot")) return accounts.getFirst();
            return accounts.get(Math.floorMod(cursor.getAndIncrement(), accounts.size()));
        }

        UUID nextTransferSender() {
            if (accessPattern.equals("hot")) return accounts.getFirst();
            return accounts.get(Math.floorMod(cursor.getAndIncrement(), accounts.size()));
        }

        UUID transferReceiver(UUID sender) {
            int index = accountIndices.get(sender);
            return accounts.get((index + 1) % accounts.size());
        }

        BigDecimal setTarget(UUID account) {
            BigDecimal target = setTargets.get(account);
            if (target == null) throw new IllegalStateException("Missing SET target for benchmark account " + account);
            return target;
        }

        void acceptedWrite(UUID account, long centsDelta) {
            acceptedWrites.increment();
            versionsAdded.computeIfAbsent(account, ignored -> new LongAdder()).increment();
            if (centsDelta != Long.MIN_VALUE) {
                balanceDeltaCents.computeIfAbsent(account, ignored -> new LongAdder()).add(centsDelta);
            }
        }

        void rejectedWrite() {
            rejectedWrites.increment();
        }

        void offeredTransaction() {
            offeredTransactions.increment();
        }

        void acceptedTransaction() {
            acceptedTransactions.increment();
        }

        void rejectedTransaction() {
            rejectedTransactions.increment();
        }

        @TearDown(Level.Trial)
        public void verifyAndClose() throws InterruptedException {
            try {
                adapter.awaitDrain(10_000L);
                adapter.throwIfDrainFailed();
                if (adapter.queuedEvents() != 0) throw new IllegalStateException("Accepted benchmark events remain queued");
                verifyDrainedWrites(acceptedWrites.sum(), adapter.drainedEvents());
                if (offeredTransactions.sum() != acceptedTransactions.sum() + rejectedTransactions.sum()) {
                    throw new IllegalStateException("Benchmark transaction outcome counts do not conserve offered operations");
                }
                if (!operation.equals("balance") && acceptedTransactions.sum() == 0L
                        && !operation.equals("reject-withdraw") && !isCancellationOperation(operation)) {
                    throw new IllegalStateException("Benchmark produced no accepted transaction samples");
                }
                for (UUID account : accounts) {
                    long expectedVersion = 100L + value(versionsAdded.get(account));
                    if (adapter.version(account) != expectedVersion) {
                        throw new IllegalStateException("Version postcondition failed for " + account
                                + ": expected " + expectedVersion + ", observed " + adapter.version(account));
                    }
                    BigDecimal balance = adapter.balance(account);
                    if (balance.signum() < 0) throw new IllegalStateException("Benchmark produced a negative balance for " + account);
                    if (operation.equals("balance") || operation.equals("reject-withdraw")
                            || isCancellationOperation(operation)) {
                        if (balance.compareTo(START_BALANCE) != 0) {
                            throw new IllegalStateException("Read/rejection benchmark changed account balance " + account);
                        }
                        if (isCancellationOperation(operation)
                                && (expectedVersion != 100L || adapter.latestReceipt(account) != null)) {
                            throw new IllegalStateException("Cancelled transaction changed account version or emitted a receipt for " + account);
                        }
                    } else if (operation.equals("set")) {
                        Receipt latestReceipt = adapter.latestReceipt(account);
                        verifySetPostcondition(account, 100L, expectedVersion, balance, latestReceipt,
                                setTargets.get(account), START_BALANCE);
                    } else {
                        long cents = balanceDeltaCents.computeIfAbsent(account, ignored -> new LongAdder()).sum();
                        BigDecimal expected = START_BALANCE.add(BigDecimal.valueOf(cents, 2));
                        if (balance.compareTo(expected) != 0) {
                            throw new IllegalStateException("Funds postcondition failed for " + account
                                    + ": expected " + expected + ", observed " + balance);
                        }
                        verifyLatestReceipt(account, 100L, expectedVersion, expected, adapter.latestReceipt(account));
                    }
                }
                if (adapter.version(rejectedAccount) != 1L || adapter.balance(rejectedAccount).signum() != 0) {
                    throw new IllegalStateException("Rejected withdrawal mutated its zero-balance control account");
                }
                if ((operation.equals("balance") || operation.equals("reject-withdraw"))
                        && acceptedWrites.sum() != 0L) {
                    throw new IllegalStateException("Read/rejection benchmark unexpectedly accepted a write");
                }
                if (operation.equals("reject-withdraw") && (acceptedWrites.sum() != 0 || rejectedWrites.sum() == 0)) {
                    throw new IllegalStateException("Insufficient-funds control did not produce only rejected writes");
                }
                if (operation.equals("reject-withdraw")
                        && (acceptedTransactions.sum() != 0L || rejectedTransactions.sum() != offeredTransactions.sum()
                                || offeredTransactions.sum() == 0L)) {
                    throw new IllegalStateException("Insufficient-funds control did not reject every offered transaction");
                }
                if (isCancellationOperation(operation)
                        && (acceptedWrites.sum() != 0L || acceptedTransactions.sum() != 0L
                                || rejectedTransactions.sum() != offeredTransactions.sum()
                                || offeredTransactions.sum() == 0L)) {
                    throw new IllegalStateException("Pre-transaction cancellation did not reject every operation without queue admission");
                }
            } finally {
                adapter.close();
            }
        }

    private static long value(LongAdder adder) {
        return adder == null ? 0L : adder.sum();
    }
    }

    static void verifySetPostcondition(UUID account, long seedVersion, long expectedVersion,
            BigDecimal balance, Receipt latestReceipt, BigDecimal expectedTarget, BigDecimal startBalance) {
        if (expectedVersion < seedVersion) {
            throw new IllegalStateException("Set benchmark version regressed for " + account);
        }
        if (expectedVersion == seedVersion) {
            if (latestReceipt != null || balance.compareTo(startBalance) != 0) {
                throw new IllegalStateException("Set benchmark recorded a receipt without an accepted version for " + account);
            }
        } else if (latestReceipt == null || latestReceipt.version() != expectedVersion
                || latestReceipt.balance().compareTo(expectedTarget) != 0
                || balance.compareTo(expectedTarget) != 0) {
            throw new IllegalStateException("Set benchmark highest accepted version does not match its submitted target for " + account);
        }
    }

    static void verifyDrainedWrites(long acceptedWriteLegs, long drainedEventLegs) {
        if (acceptedWriteLegs < 0L || drainedEventLegs != acceptedWriteLegs) {
            throw new IllegalStateException("Drained benchmark event count does not match accepted write legs: accepted="
                    + acceptedWriteLegs + ", drained=" + drainedEventLegs);
        }
    }

    static void verifyLatestReceipt(UUID account, long seedVersion, long expectedVersion,
            BigDecimal expectedBalance, Receipt latestReceipt) {
        if (expectedVersion < seedVersion) {
            throw new IllegalStateException("Benchmark receipt version regressed for " + account);
        }
        if (expectedVersion == seedVersion) {
            if (latestReceipt != null) {
                throw new IllegalStateException("Benchmark emitted a receipt without an accepted write for " + account);
            }
        } else if (latestReceipt == null || latestReceipt.version() != expectedVersion
                || latestReceipt.balance().compareTo(expectedBalance) != 0) {
            throw new IllegalStateException("Latest benchmark receipt does not match the final accepted state for " + account);
        }
    }

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class Counts {
        public long offered;
        public long accepted;
        public long rejected;

        @Setup(Level.Iteration)
        public void reset() {
            offered = 0L;
            accepted = 0L;
            rejected = 0L;
        }
    }

    @Benchmark
    public BigDecimal execute(Fixture fixture, Counts counts) {
        return switch (fixture.operation) {
            case "balance" -> fixture.adapter.balance(fixture.nextAccount());
            case "deposit" -> mutate(fixture, counts, fixture.nextAccount(), fixture.adapter::deposit, AMOUNT, AMOUNT);
            case "withdraw" -> mutate(fixture, counts, fixture.nextAccount(), fixture.adapter::withdraw,
                    AMOUNT, AMOUNT.negate());
            case "set" -> {
                UUID account = fixture.nextAccount();
                BigDecimal target = fixture.setTarget(account);
                counts.offered++;
                fixture.offeredTransaction();
                BigDecimal result = fixture.adapter.set(account, target);
                if (isRejected(result)) {
                    counts.rejected++;
                    fixture.rejectedWrite();
                    fixture.rejectedTransaction();
                    yield result;
                }
                if (result.compareTo(target) != 0) {
                    throw new IllegalStateException("Accepted SET result differs from its submitted target for " + account);
                }
                counts.accepted++;
                fixture.acceptedTransaction();
                fixture.acceptedWrite(account, Long.MIN_VALUE);
                yield result;
            }
            case "transfer" -> {
                UUID from = fixture.nextTransferSender();
                UUID to = fixture.transferReceiver(from);
                counts.offered++;
                fixture.offeredTransaction();
                BigDecimal result = fixture.adapter.transfer(from, to, AMOUNT);
                if (isRejected(result)) {
                    counts.rejected++;
                    fixture.rejectedWrite();
                    fixture.rejectedTransaction();
                    yield result;
                }
                counts.accepted++;
                fixture.acceptedTransaction();
                fixture.acceptedWrite(from, -100L);
                fixture.acceptedWrite(to, 100L);
                yield result;
            }
            case "reject-withdraw" -> {
                counts.offered++;
                fixture.offeredTransaction();
                BigDecimal result = fixture.adapter.withdraw(fixture.rejectedAccount, AMOUNT);
                if (!isRejected(result)) {
                    counts.accepted++;
                    fixture.acceptedTransaction();
                    fixture.acceptedWrite(fixture.rejectedAccount, -100L);
                    throw new IllegalStateException("Insufficient-funds control unexpectedly succeeded");
                }
                counts.rejected++;
                fixture.rejectedWrite();
                fixture.rejectedTransaction();
                yield result;
            }
            case "cancel-deposit" -> mutate(fixture, counts, fixture.nextAccount(), fixture.adapter::deposit,
                    AMOUNT, AMOUNT);
            case "cancel-withdraw" -> mutate(fixture, counts, fixture.nextAccount(), fixture.adapter::withdraw,
                    AMOUNT, AMOUNT.negate());
            default -> throw new IllegalArgumentException("Unknown benchmark operation: " + fixture.operation);
        };
    }

    private static BigDecimal mutate(Fixture fixture, Counts counts, UUID account,
            java.util.function.BiFunction<UUID, BigDecimal, BigDecimal> mutation,
            BigDecimal amount, BigDecimal delta) {
        counts.offered++;
        fixture.offeredTransaction();
        BigDecimal result = mutation.apply(account, amount);
        if (isRejected(result)) {
            counts.rejected++;
            fixture.rejectedWrite();
            fixture.rejectedTransaction();
            return result;
        }
        counts.accepted++;
        fixture.acceptedTransaction();
        fixture.acceptedWrite(account, delta.movePointRight(2).longValueExact());
        return result;
    }

    private static boolean isRejected(BigDecimal result) {
        return result == null || result.signum() < 0;
    }

    private static boolean isCancellationOperation(String operation) {
        return operation.equals("cancel-deposit") || operation.equals("cancel-withdraw");
    }
}
