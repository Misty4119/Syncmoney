package noietime.syncmoney.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApiContractTest {

    @Test
    void exposesOnlyTheApprovedReadQuerySurface() {
        assertEquals(Set.of("economy"), methodNames(SyncmoneyApi.class));
        assertEquals(Set.of("cachedBalance", "loadBalance"), methodNames(EconomyService.class));
        assertTrue(CompletionStage.class.isAssignableFrom(
                method(EconomyService.class, "loadBalance").getReturnType()));
        assertEquals(Optional.class,
                method(EconomyService.class, "cachedBalance").getReturnType());
    }

    @Test
    void snapshotRequiresIdentityAndBalanceAndPreservesNormalizedScale() {
        UUID accountId = UUID.randomUUID();
        BigDecimal balance = new BigDecimal("12.3400");
        BalanceSnapshot snapshot = new BalanceSnapshot(accountId, balance);

        assertSame(accountId, snapshot.accountId());
        assertSame(balance, snapshot.balance());
        assertEquals(new BigDecimal("12.3400"), snapshot.balance());
        assertThrows(NullPointerException.class, () -> new BalanceSnapshot(null, balance));
        assertThrows(NullPointerException.class, () -> new BalanceSnapshot(accountId, null));
        assertNotNull(snapshot.toString());
    }

    @Test
    void publicApiDoesNotExposeMutationOrInternalExecutionTypes() {
        assertFalse(Set.of(SyncmoneyApi.class.getDeclaredClasses()).stream()
                .anyMatch(type -> type.getSimpleName().contains("Mutation")));
        assertFalse(Set.of(EconomyService.class.getDeclaredMethods()).stream()
                .anyMatch(method -> method.getName().matches("deposit|withdraw|transfer|setBalance|operationId")));
        assertFalse(Set.of(SyncmoneyApi.class.getDeclaredMethods()).stream()
                .anyMatch(method -> method.getName().equals("info")));
    }

    private static Set<String> methodNames(Class<?> type) {
        return Set.of(type.getDeclaredMethods()).stream()
                .map(java.lang.reflect.Method::getName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static java.lang.reflect.Method method(Class<?> type, String name) {
        return java.util.Arrays.stream(type.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
