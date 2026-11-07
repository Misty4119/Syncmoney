package noietime.syncmoney.benchmark;

import org.junit.jupiter.api.Test;
import org.openjdk.jmh.infra.ThreadParams;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueueBenchmarksActorTest {
    @Test
    void createsANewEventIdentityForEveryQueueAdmission() {
        ThreadParams threadParams = mock(ThreadParams.class);
        when(threadParams.getThreadIndex()).thenReturn(0);
        QueueBenchmarks.Actor actor = new QueueBenchmarks.Actor();
        actor.setup(threadParams);

        var first = actor.nextEvent();
        var second = actor.nextEvent();

        assertNotSame(first, second);
        assertEquals(first.playerUuid(), second.playerUuid());
        assertEquals(first.version() + 1L, second.version());
        assertNotEquals(first.requestId(), second.requestId());
    }
}
