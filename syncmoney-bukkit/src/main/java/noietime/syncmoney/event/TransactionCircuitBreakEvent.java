package noietime.syncmoney.event;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * Event fired when the economic circuit breaker triggers or changes state.
 * This event provides information about circuit breaker status changes.
 */
public class TransactionCircuitBreakEvent extends SyncmoneyEvent {

    /**
     * Circuit breaker states.
     */
    public enum CircuitState {
        /**
         * Normal operation - all transactions allowed
         */
        NORMAL,
        /**
         * Warning state - increased monitoring
         */
        WARNING,
        /**
         * Locked state - transactions blocked
         */
        LOCKED
    }

    /**
     * Reasons that can trigger circuit breaker.
     */
    public enum TriggerReason {
        /**
         * Single transaction exceeds maximum limit
         */
        SINGLE_TRANSACTION_LIMIT,
        /**
         * Transaction rate exceeds limit
         */
        RATE_LIMIT,
        /**
         * Economic inflation detected
         */
        INFLATION_DETECTED,
        /**
         * Sudden large balance change detected
         */
        SUDDEN_CHANGE,
        /**
         * Manual lock by administrator
         */
        MANUAL_LOCK
    }

    private final CircuitState previousState;
    private final CircuitState currentState;
    private final TriggerReason reason;
    private final String message;
    private final Set<UUID> affectedPlayers;
    private final BigDecimal threshold;
    private final BigDecimal actualValue;

    public TransactionCircuitBreakEvent(
            CircuitState previousState,
            CircuitState currentState,
            TriggerReason reason,
            String message,
            Set<UUID> affectedPlayers,
            BigDecimal threshold,
            BigDecimal actualValue) {
        super("TransactionCircuitBreakEvent");
        this.previousState = previousState;
        this.currentState = currentState;
        this.reason = reason;
        this.message = message;
        this.affectedPlayers = affectedPlayers;
        this.threshold = threshold;
        this.actualValue = actualValue;
    }

    /**
     * Get the previous circuit breaker state.
     */
    public CircuitState getPreviousState() {
        return previousState;
    }

    /**
     * Get the current circuit breaker state.
     */
    public CircuitState getCurrentState() {
        return currentState;
    }

    /**
     * Get the reason for state change.
     */
    public TriggerReason getReason() {
        return reason;
    }

    /**
     * Get the human-readable message.
     */
    public String getMessage() {
        return message;
    }

    /**
     * Get the set of affected player UUIDs.
     */
    public Set<UUID> getAffectedPlayers() {
        return affectedPlayers;
    }

    /**
     * Get the threshold that was exceeded.
     */
    public BigDecimal getThreshold() {
        return threshold;
    }

    /**
     * Get the actual value that triggered the event.
     */
    public BigDecimal getActualValue() {
        return actualValue;
    }

    /**
     * Check if this is a state transition event.
     */
    public boolean isStateTransition() {
        return previousState != currentState;
    }

    /**
     * Check if the system is now locked.
     */
    public boolean isLocked() {
        return currentState == CircuitState.LOCKED;
    }

    /**
     * Check if the system was unlocked (transition from LOCKED).
     */
    public boolean isUnlocked() {
        return previousState == CircuitState.LOCKED && currentState != CircuitState.LOCKED;
    }

    @Override
    public String toString() {
        return "TransactionCircuitBreakEvent{" +
                "previousState=" + previousState +
                ", currentState=" + currentState +
                ", reason=" + reason +
                ", message='" + message + '\'' +
                ", affectedPlayers=" + affectedPlayers.size() +
                ", threshold=" + threshold +
                ", actualValue=" + actualValue +
                ", timestamp=" + getTimestamp() +
                '}';
    }
}
