package noietime.syncmoney.storage.db;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DbWriteQueueAdmissionTest {
    @Test
    void reservationsRespectExistingEightyPercentHighWatermark() {
        DbWriteQueue queue = new DbWriteQueue(10);
        List<DbWriteQueue.Reservation> reservations = new ArrayList<>();

        try {
            for (int index = 0; index < 8; index++) {
                DbWriteQueue.Reservation reservation = queue.tryReserve();
                assertNotNull(reservation);
                reservations.add(reservation);
            }
            assertNull(queue.tryReserve());

            reservations.get(0).close();
            DbWriteQueue.Reservation replacement = queue.tryReserve();
            assertNotNull(replacement);
            replacement.close();
        } finally {
            reservations.forEach(DbWriteQueue.Reservation::close);
        }
    }
}