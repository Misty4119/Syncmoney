package noietime.syncmoney.exception;

/**
 * Exception thrown when an economy operation fails.
 */
public class EconomyException extends SyncmoneyException {

    public EconomyException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public EconomyException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    /**
     * Creates a player not found exception.
     */
    public static EconomyException playerNotFound(String playerIdentifier) {
        return new EconomyException(ErrorCode.PLAYER_NOT_FOUND, "Player not found: " + playerIdentifier);
    }

    /**
     * Creates an insufficient balance exception.
     */
    public static EconomyException insufficientBalance(double available, double requested) {
        return new EconomyException(ErrorCode.INSUFFICIENT_BALANCE,
                String.format("Insufficient balance: available=%.2f, requested=%.2f", available, requested));
    }

    /**
     * Creates an invalid amount exception.
     */
    public static EconomyException invalidAmount(String amount) {
        return new EconomyException(ErrorCode.INVALID_AMOUNT, "Invalid amount: " + amount);
    }

    /**
     * Creates a transfer locked exception.
     */
    public static EconomyException transferLocked(String reason) {
        return new EconomyException(ErrorCode.TRANSFER_LOCKED, "Transfer locked: " + reason);
    }

    /**
     * Creates a circuit breaker open exception.
     */
    public static EconomyException circuitBreakerOpen() {
        return new EconomyException(ErrorCode.CIRCUIT_BREAKER_OPEN, "Circuit breaker is open, operation rejected");
    }
}
