package noietime.syncmoney.economy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPersistenceJournalReadinessTest {

    @TempDir
    Path tempDir;

    @Test
    void missingJournalDoesNotBlockLocalRecovery() {
        assertFalse(LocalPersistenceJournal.existsForDatabase(databasePath()));
    }

    @Test
    void emptyRegularJournalDoesNotBlockLocalRecovery() throws Exception {
        Files.createFile(journalPath());

        assertFalse(LocalPersistenceJournal.existsForDatabase(databasePath()));
    }

    @Test
    void nonEmptyJournalBlocksLocalRecovery() throws Exception {
        Files.writeString(journalPath(), "pending recovery");

        assertTrue(LocalPersistenceJournal.existsForDatabase(databasePath()));
    }

    @Test
    void nonRegularJournalPathFailsClosed() throws Exception {
        Files.createDirectory(journalPath());

        assertTrue(LocalPersistenceJournal.existsForDatabase(databasePath()));
    }

    private String databasePath() {
        return tempDir.resolve("players.db").toString();
    }

    private Path journalPath() {
        return tempDir.resolve("local-persistence.journal");
    }
}
