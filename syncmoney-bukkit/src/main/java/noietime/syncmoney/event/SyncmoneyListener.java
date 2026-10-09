package noietime.syncmoney.event;

/**
 * Functional interface for Syncmoney event listeners.
 * @param <T> The event type to listen for
 */
@FunctionalInterface
public interface SyncmoneyListener<T extends SyncmoneyEvent> {
    /**
     * Called when the event is fired.
     * @param event The event object
     */
    void onEvent(T event);
}
