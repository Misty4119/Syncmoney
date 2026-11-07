package noietime.syncmoney.exception;

/**
 * Exception thrown when a database operation fails.
 */
public class DatabaseException extends SyncmoneyException {

    public DatabaseException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public DatabaseException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    /**
     * Creates a database connection failed exception.
     */
    public static DatabaseException connectionFailed(String message) {
        return new DatabaseException(ErrorCode.DATABASE_ERROR, "Database connection failed: " + message);
    }

    /**
     * Creates a database query failed exception.
     */
    public static DatabaseException queryFailed(String query, Throwable cause) {
        return new DatabaseException(ErrorCode.DATABASE_ERROR, "Query failed: " + query, cause);
    }

    /**
     * Creates a database update failed exception.
     */
    public static DatabaseException updateFailed(String table, Throwable cause) {
        return new DatabaseException(ErrorCode.DATABASE_ERROR, "Update failed on table: " + table, cause);
    }

    /**
     * Creates a database transaction failed exception.
     */
    public static DatabaseException transactionFailed(Throwable cause) {
        return new DatabaseException(ErrorCode.DATABASE_ERROR, "Transaction failed", cause);
    }
}
