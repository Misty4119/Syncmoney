package noietime.syncmoney.core.state;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Concurrent account snapshots and stable-order per-account mutation leases. */
public final class AccountStateStore {

    private static final class AccountLock {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger references = new AtomicInteger();
    }

    private final ConcurrentMap<UUID, AccountState> accounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, AccountLock> accountLocks = new ConcurrentHashMap<>();

    public AccountState get(UUID accountId) {
        return accounts.get(accountId);
    }

    public AccountState compute(UUID accountId,
            BiFunction<UUID, AccountState, AccountState> update) {
        return accounts.compute(accountId, update);
    }

    public AccountState computeIfAbsent(UUID accountId,
            Function<UUID, AccountState> create) {
        return accounts.computeIfAbsent(accountId, create);
    }

    public AccountState put(UUID accountId, AccountState state) {
        return accounts.put(accountId, state);
    }

    public AccountState remove(UUID accountId) {
        return accounts.remove(accountId);
    }

    public boolean remove(UUID accountId, AccountState expected) {
        return accounts.remove(accountId, expected);
    }

    public boolean containsKey(UUID accountId) {
        return accounts.containsKey(accountId);
    }

    public int size() {
        return accounts.size();
    }

    public Set<UUID> keySet() {
        return accounts.keySet();
    }

    public Set<Map.Entry<UUID, AccountState>> entrySet() {
        return accounts.entrySet();
    }

    /** Acquires one or more account locks in a deterministic UUID order. */
    public MutationLease lockAccounts(UUID... accountIds) {
        List<UUID> ordered = orderedUnique(accountIds);
        Map<UUID, AccountLock> acquired = new LinkedHashMap<>();
        for (UUID accountId : ordered) {
            AccountLock accountLock = retain(accountId);
            acquired.put(accountId, accountLock);
        }
        for (AccountLock accountLock : acquired.values()) {
            accountLock.lock.lock();
        }
        return new MutationLease(acquired);
    }

    /** Attempts to lock one account without waiting; returns null on contention. */
    public MutationLease tryLockAccount(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("Account UUID must not be null");
        }
        AccountLock accountLock = retain(accountId);
        if (!accountLock.lock.tryLock()) {
            release(accountId, accountLock);
            return null;
        }
        return new MutationLease(Map.of(accountId, accountLock));
    }

    private static List<UUID> orderedUnique(UUID[] accountIds) {
        TreeSet<UUID> ordered = new TreeSet<>(Comparator.naturalOrder());
        for (UUID accountId : accountIds) {
            if (accountId == null) {
                throw new IllegalArgumentException("Account UUID must not be null");
            }
            ordered.add(accountId);
        }
        return new ArrayList<>(ordered);
    }

    private AccountLock retain(UUID accountId) {
        return accountLocks.compute(accountId, (ignored, existing) -> {
            AccountLock selected = existing == null ? new AccountLock() : existing;
            selected.references.incrementAndGet();
            return selected;
        });
    }

    private void release(UUID accountId, AccountLock accountLock) {
        accountLocks.computeIfPresent(accountId, (ignored, current) -> {
            if (current != accountLock) {
                return current;
            }
            return current.references.decrementAndGet() == 0 ? null : current;
        });
    }

    public final class MutationLease implements AutoCloseable {

        private final Map<UUID, AccountLock> locks;
        private boolean closed;

        private MutationLease(Map<UUID, AccountLock> locks) {
            this.locks = locks;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            List<Map.Entry<UUID, AccountLock>> entries = new ArrayList<>(locks.entrySet());
            for (int i = entries.size() - 1; i >= 0; i--) {
                entries.get(i).getValue().lock.unlock();
            }
            for (Map.Entry<UUID, AccountLock> entry : entries) {
                release(entry.getKey(), entry.getValue());
            }
        }
    }
}
