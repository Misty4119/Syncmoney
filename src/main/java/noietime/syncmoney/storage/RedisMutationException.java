package noietime.syncmoney.storage;

/** Describes whether an authority mutation may have reached Redis without a receipt. */
public final class RedisMutationException extends RuntimeException {
    public enum ExecutionState {
        NOT_SENT,
        REJECTED,
        SENT_UNKNOWN
    }

    private final ExecutionState executionState;

    public RedisMutationException(ExecutionState executionState, String message, Throwable cause) {
        super(message, cause);
        this.executionState = java.util.Objects.requireNonNull(executionState, "executionState");
    }

    public ExecutionState executionState() {
        return executionState;
    }
}
