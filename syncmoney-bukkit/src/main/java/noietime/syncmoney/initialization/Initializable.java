package noietime.syncmoney.initialization;

/**
 * Interface for plugin initialization steps.
 * Each step represents a distinct phase of plugin initialization or shutdown.
 */
public interface Initializable {

    /**
     * Initialize this component.
     * @throws InitializationException if initialization fails
     */
    void initialize() throws InitializationException;

    /**
     * Shutdown this component.
     * Should perform cleanup and release resources.
     */
    void shutdown();

    /**
     * Exception thrown when initialization fails.
     */
    class InitializationException extends Exception {
        public InitializationException(String message) {
            super(message);
        }

        public InitializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
