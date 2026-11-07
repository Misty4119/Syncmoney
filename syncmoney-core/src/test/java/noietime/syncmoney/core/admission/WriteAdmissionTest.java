package noietime.syncmoney.core.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WriteAdmissionTest {

    @Test
    void holdsCapacityUntilLeaseClosesAndReleaseIsIdempotent() {
        WriteAdmission admission = new WriteAdmission(1);
        WriteAdmission.Lease lease = admission.tryAcquire();

        assertNotNull(lease);
        assertEquals(0, admission.availableCapacity());
        assertNull(admission.tryAcquire());

        lease.close();
        lease.close();

        assertEquals(1, admission.availableCapacity());
        try (WriteAdmission.Lease reacquired = admission.tryAcquire()) {
            assertNotNull(reacquired);
        }
    }

    @Test
    void timedAcquireReturnsNullWhenCapacityDoesNotBecomeAvailable() throws Exception {
        WriteAdmission admission = new WriteAdmission(1);
        try (WriteAdmission.Lease ignored = admission.tryAcquire()) {
            assertNull(admission.acquire(10, TimeUnit.MILLISECONDS));
        }

        assertEquals(1, admission.availableCapacity());
    }
}
