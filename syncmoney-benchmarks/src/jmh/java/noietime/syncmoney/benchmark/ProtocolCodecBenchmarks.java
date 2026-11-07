package noietime.syncmoney.benchmark;

import noietime.syncmoney.protocol.LegacyBalanceMessage;
import noietime.syncmoney.protocol.LegacyCmiMessage;
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

/** Measures protocol-v1 codec paths and keeps malformed input on a decode-only case. */
public class ProtocolCodecBenchmarks {

    @State(Scope.Thread)
    public static class Fixture {
        @Param({"legacy-minimal", "legacy-full", "cmi"})
        public String shape;

        LegacyBalanceMessage balanceMessage;
        LegacyCmiMessage cmiMessage;
        String payload;
        String observedEncoding;
        Optional<?> observedDecode;

        @Setup(Level.Trial)
        public void setup() {
            String uuid = UUID.nameUUIDFromBytes("protocol-codec-benchmark".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            boolean full = shape.equals("legacy-full") || shape.equals("cmi");
            balanceMessage = new LegacyBalanceMessage(uuid, new BigDecimal("123456.78"), 101L,
                    full ? "benchmark-node" : null, full ? "benchmark-message-101" : null,
                    1_760_000_000_000L, full ? "DEPOSIT" : null, full ? 1.25d : 0.0d,
                    full ? "benchmark" : null, full ? "synthetic-player" : null);
            cmiMessage = new LegacyCmiMessage(uuid, balanceMessage.balance(), balanceMessage.version(),
                    balanceMessage.sourceServer(), balanceMessage.messageId(), balanceMessage.timestamp(),
                    balanceMessage.eventType(), balanceMessage.amount(), balanceMessage.sourcePlugin(),
                    balanceMessage.sourcePlayerName());
            payload = encodeExpected();
            assertEquivalent(decode(payload));
        }

        @Setup(Level.Invocation)
        public void clearObservedResult() {
            observedEncoding = null;
            observedDecode = null;
        }

        @TearDown(Level.Invocation)
        public void verifyObservedResult() {
            if (observedEncoding != null) assertEquivalent(decode(observedEncoding));
            if (observedDecode != null) assertEquivalent(observedDecode);
            if (observedEncoding == null && observedDecode == null) {
                throw new IllegalStateException("Protocol benchmark did not record an operation result");
            }
        }

        String encodeExpected() {
            return shape.equals("cmi") ? LegacyProtocolCodec.encodeCmi(cmiMessage)
                    : LegacyProtocolCodec.encodeBalance(balanceMessage);
        }

        Optional<?> decode(String source) {
            return shape.equals("cmi") ? LegacyProtocolCodec.decodeCmi(source)
                    : LegacyProtocolCodec.decodeBalance(source);
        }

        void assertEquivalent(Optional<?> decoded) {
            Object expected = shape.equals("cmi") ? cmiMessage : balanceMessage;
            if (decoded.isEmpty() || !expected.equals(decoded.orElseThrow())) {
                throw new IllegalStateException("Protocol-v1 round-trip changed message fields for " + shape);
            }
        }
    }

    @State(Scope.Thread)
    public static class MalformedFixture {
        String payload = "{";
        Optional<?> observedDecode;

        @Setup(Level.Trial)
        public void setup() {
            if (LegacyProtocolCodec.decodeBalance(payload).isPresent()) {
                throw new IllegalStateException("Malformed protocol fixture was accepted");
            }
        }

        @Setup(Level.Invocation)
        public void clearObservedResult() {
            observedDecode = null;
        }

        @TearDown(Level.Invocation)
        public void verifyMalformedResult() {
            if (observedDecode == null || observedDecode.isPresent()) {
                throw new IllegalStateException("Malformed protocol decode postcondition failed");
            }
        }
    }

    @Benchmark
    public String encode(Fixture fixture) {
        String encoded = fixture.encodeExpected();
        fixture.observedEncoding = encoded;
        return encoded;
    }

    @Benchmark
    public Optional<?> decode(Fixture fixture) {
        Optional<?> decoded = fixture.decode(fixture.payload);
        fixture.observedDecode = decoded;
        return decoded;
    }

    @Benchmark
    public Optional<?> encodeDecode(Fixture fixture) {
        Optional<?> decoded = fixture.decode(fixture.encodeExpected());
        fixture.observedDecode = decoded;
        return decoded;
    }

    @Benchmark
    public Optional<?> decodeMalformed(MalformedFixture fixture) {
        Optional<?> decoded = LegacyProtocolCodec.decodeBalance(fixture.payload);
        fixture.observedDecode = decoded;
        return decoded;
    }
}
