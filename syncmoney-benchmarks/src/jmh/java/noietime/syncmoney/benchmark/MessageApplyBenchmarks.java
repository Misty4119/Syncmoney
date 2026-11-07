package noietime.syncmoney.benchmark;

import noietime.syncmoney.economy.CurrentFacadeBenchmarkAdapter;
import noietime.syncmoney.protocol.LegacyBalanceMessage;
import noietime.syncmoney.protocol.LegacyProtocolCodec;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/** Measures the actual protocol-v1 decoder and the facade's versioned remote-message apply boundary. */
public class MessageApplyBenchmarks {

    @State(Scope.Thread)
    public static class Fixture {
        private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000.00");

        @Param({"newer", "duplicate", "older"})
        public String messageKind;

        CurrentFacadeBenchmarkAdapter adapter;
        UUID account;
        long expectedVersion;
        BigDecimal expectedBalance;
        String payload;

        @Setup(Level.Trial)
        public void setup() {
            account = UUID.nameUUIDFromBytes("message-apply-benchmark".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            adapter = new CurrentFacadeBenchmarkAdapter();
            adapter.seed(account, INITIAL_BALANCE, 100L);
            expectedVersion = 100L;
            expectedBalance = INITIAL_BALANCE;
        }

        @Setup(Level.Invocation)
        public void prepareMessage() {
            long version = switch (messageKind) {
                case "newer" -> expectedVersion + 1L;
                case "duplicate" -> expectedVersion;
                case "older" -> Math.max(0L, expectedVersion - 1L);
                default -> throw new IllegalArgumentException("Unknown message kind " + messageKind);
            };
            BigDecimal balance = messageKind.equals("newer") ? expectedBalance.add(BigDecimal.ONE)
                    : messageKind.equals("older") ? BigDecimal.ZERO : expectedBalance;
            payload = LegacyProtocolCodec.encodeBalance(new LegacyBalanceMessage(account.toString(), balance,
                    version, "benchmark-node", "message-" + version, version, "DEPOSIT", 1.0d,
                    "benchmark", "synthetic-player"));
        }

        @TearDown(Level.Trial)
        public void closeAndVerify() {
            try {
                if (adapter.version(account) != expectedVersion
                        || adapter.balance(account).compareTo(expectedBalance) != 0) {
                    throw new IllegalStateException("Message apply postcondition changed the wrong authority snapshot");
                }
            } finally {
                adapter.close();
            }
        }
    }

    @Benchmark
    public boolean decodeAndApply(Fixture fixture) {
        Optional<LegacyBalanceMessage> decoded = LegacyProtocolCodec.decodeBalance(fixture.payload);
        LegacyBalanceMessage message = decoded.orElseThrow(() -> new IllegalStateException("Benchmark message failed to decode"));
        boolean applied = fixture.adapter.updateMemoryState(UUID.fromString(message.uuid()),
                message.balance(), message.version());
        if (fixture.messageKind.equals("newer")) {
            if (!applied) throw new IllegalStateException("Newer benchmark message was rejected");
            fixture.expectedVersion = message.version();
            fixture.expectedBalance = message.balance();
        } else if (fixture.messageKind.equals("older") && applied) {
            throw new IllegalStateException("Stale benchmark message was applied");
        }
        return applied;
    }
}
