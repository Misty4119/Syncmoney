package noietime.syncmoney.exception;

/**
 * Base exception class for Syncmoney plugin.
 * Provides a structured error code system for better error handling and debugging.
 */
public class SyncmoneyException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * Error codes for categorizing exceptions.
     */
    public enum ErrorCode {
        PLAYER_NOT_FOUND("Player not found"),
        INSUFFICIENT_BALANCE("Insufficient balance"),
        DATABASE_ERROR("Database operation failed"),
        REDIS_ERROR("Redis operation failed"),
        CIRCUIT_BREAKER_OPEN("Circuit breaker is open"),
        TRANSFER_LOCKED("Transfer is currently locked"),
        INVALID_AMOUNT("Invalid amount"),
        CONFIGURATION_ERROR("Configuration error"),
        NETWORK_ERROR("Network operation failed"),
        UNKNOWN_ERROR("Unknown error occurred");

        private final String defaultMessage;

        ErrorCode(String defaultMessage) {
            this.defaultMessage = defaultMessage;
        }

        public String getDefaultMessage() {
            return defaultMessage;
        }
    }

    public SyncmoneyException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public SyncmoneyException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    @Override
    public String toString() {
        return "SyncmoneyException{" +
                "errorCode=" + errorCode +
                ", message='" + getMessage() + '\'' +
                '}';
    }
}
