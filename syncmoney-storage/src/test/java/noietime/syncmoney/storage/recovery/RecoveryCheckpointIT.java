package noietime.syncmoney.storage.recovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryCheckpointIT {
    @TempDir Path temp;

    @Test
    void pendingRecordsSurviveReopenUntilExplicitAcknowledgement() throws Exception {
        Path journal = temp.resolve("recovery-v1.jsonl");
        Object first = newSpool(journal);
        invoke(first, "append", new Class<?>[] { String.class, String.class }, "op-1", "{\"stage\":\"CONFIRMED\"}");
        invoke(first, "append", new Class<?>[] { String.class, String.class }, "op-2", "{\"stage\":\"AMBIGUOUS\"}");

        Object reopened = newSpool(journal);
        List<?> pending = (List<?>) invoke(reopened, "pending", new Class<?>[0]);
        assertEquals(2, pending.size(), "reading must not clear pending recovery records");
        assertEquals(2, ((List<?>) invoke(reopened, "pending", new Class<?>[0])).size());

        invoke(reopened, "acknowledge", new Class<?>[] { String.class }, "op-1");
        List<?> afterAck = (List<?>) invoke(newSpool(journal), "pending", new Class<?>[0]);
        assertEquals(1, afterAck.size());
        assertEquals("op-2", invoke(afterAck.get(0), "id", new Class<?>[0]));
    }

    @Test
    void partialLastLineFailsClosedAndPreservesOriginalBytes() throws Exception {
        Path journal = temp.resolve("recovery-v1.jsonl");
        Object spool = newSpool(journal);
        invoke(spool, "append", new Class<?>[] { String.class, String.class }, "op-1", "payload");
        Files.writeString(journal, "{\"partial\"", StandardOpenOption.APPEND);
        byte[] before = Files.readAllBytes(journal);

        IOException failure = assertThrows(IOException.class,
                () -> invoke(newSpool(journal), "pending", new Class<?>[0]));
        assertTrue(failure.getMessage().toLowerCase().contains("partial")
                || failure.getMessage().toLowerCase().contains("line"));
        assertArrayEquals(before, Files.readAllBytes(journal));
    }

    @Test
    void failedReadDoesNotMarkCorruptSpoolLoadedOrAllowAppend() throws Exception {
        Path journal = temp.resolve("recovery-v1.jsonl");
        RecoverySpool writer = new RecoverySpool(journal);
        writer.append("op-1", "known-good");
        Files.writeString(journal, "{broken-json}\n", StandardOpenOption.APPEND);
        byte[] before = Files.readAllBytes(journal);

        RecoverySpool reader = new RecoverySpool(journal);
        assertThrows(IOException.class, reader::pending);
        assertThrows(IOException.class, () -> reader.append("op-2", "must-not-append"));
        assertArrayEquals(before, Files.readAllBytes(journal),
                "a failed read must never turn a corrupt journal into a writable one");
    }

    @Test
    void spoolLimitRefusesAnOversizedCheckpointWithoutDroppingPendingRecords() throws Exception {
        Path journal = temp.resolve("recovery-v1.jsonl");
        RecoverySpool spool = new RecoverySpool(journal, new RecoverySpool.IoHooks() { }, 256L);
        spool.append("op-1", "pending");

        assertThrows(IOException.class, () -> spool.append("op-2", "x".repeat(400)));
        assertEquals(List.of("op-1"), spool.pending().stream().map(RecoverySpool.Entry::id).toList());
        assertEquals(List.of("op-1"), new RecoverySpool(journal, new RecoverySpool.IoHooks() { }, 256L)
                .pending().stream().map(RecoverySpool.Entry::id).toList());
    }

    @Test
    void diskFullAndAtomicCompactionFailureKeepExistingPendingRecord() throws Exception {
        Path journal = temp.resolve("recovery-v1.jsonl");
        Object normal = newSpool(journal);
        invoke(normal, "append", new Class<?>[] { String.class, String.class }, "op-1", "payload-1");
        byte[] before = Files.readAllBytes(journal);

        Object diskFull = newSpoolFailingAt(journal, "beforeAppend");
        assertThrows(IOException.class, () -> invoke(diskFull, "append",
                new Class<?>[] { String.class, String.class }, "op-2", "payload-2"));
        assertArrayEquals(before, Files.readAllBytes(journal));

        Object moveFailure = newSpoolFailingAt(journal, "beforeAtomicReplace");
        assertThrows(IOException.class, () -> invoke(moveFailure, "compact", new Class<?>[0]));
        assertArrayEquals(before, Files.readAllBytes(journal));
        assertEquals(1, ((List<?>) invoke(newSpool(journal), "pending", new Class<?>[0])).size());
    }

    private Object newSpool(Path path) throws Exception {
        return spoolType().getConstructor(Path.class).newInstance(path);
    }

    private Object newSpoolFailingAt(Path path, String operation) throws Exception {
        Class<?> type = spoolType();
        Class<?> hooksType = Class.forName(type.getName() + "$IoHooks");
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals(operation)) throw new IOException("simulated " + operation + " failure");
            return null;
        };
        Object hooks = Proxy.newProxyInstance(hooksType.getClassLoader(), new Class<?>[] { hooksType }, handler);
        Constructor<?> constructor = type.getDeclaredConstructor(Path.class, hooksType);
        constructor.setAccessible(true);
        return constructor.newInstance(path, hooks);
    }

    private Class<?> spoolType() {
        try {
            return Class.forName("noietime.syncmoney.storage.recovery.RecoverySpool");
        } catch (ClassNotFoundException missing) {
            fail("RecoverySpool must preserve pending records through acknowledgement", missing);
            throw new AssertionError(missing);
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Method method = target.getClass().getMethod(name, parameterTypes);
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw wrapped;
        }
    }
}
