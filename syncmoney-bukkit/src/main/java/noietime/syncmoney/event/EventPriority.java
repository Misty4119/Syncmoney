package noietime.syncmoney.event;

/**
 * Event listener priority for determining execution order.
 * Listeners with higher priority are executed first.
 */
public enum EventPriority {
    /**
     * Lowest priority - executed last
     */
    LOWEST(0),
    /**
     * Low priority
     */
    LOW(100),
    /**
     * Normal priority (default)
     */
    NORMAL(200),
    /**
     * High priority
     */
    HIGH(300),
    /**
     * Highest priority - executed first
     */
    HIGHEST(400);

    private final int priority;

    EventPriority(int priority) {
        this.priority = priority;
    }

    public int getPriority() {
        return priority;
    }
}
