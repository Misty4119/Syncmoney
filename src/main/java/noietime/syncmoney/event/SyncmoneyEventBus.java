package noietime.syncmoney.event;

import noietime.syncmoney.Syncmoney;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central event bus for Syncmoney.
 * Manages event registration and dispatching.
 * Uses Singleton pattern for global access.
 */
public final class SyncmoneyEventBus {

    private static SyncmoneyEventBus instance;

    private final Syncmoney plugin;
    private final Map<Class<? extends SyncmoneyEvent>, List<ListenerRegistration<?>>> listeners = new ConcurrentHashMap<>();

    private SyncmoneyEventBus(Syncmoney plugin) {
        this.plugin = plugin;
    }

    /**
     * Initialize the event bus with the plugin instance.
     * Must be called once during plugin startup.
     */
    public static void init(Syncmoney plugin) {
        if (instance == null) {
            instance = new SyncmoneyEventBus(plugin);
            instance.plugin.getLogger().fine("SyncmoneyEventBus initialized.");
        }
    }

    /**
     * Get the event bus instance.
     * @return The singleton event bus instance
     * @throws IllegalStateException if not initialized
     */
    public static SyncmoneyEventBus getInstance() {
        if (instance == null) {
            throw new IllegalStateException("SyncmoneyEventBus not initialized. Call init() first.");
        }
        return instance;
    }

    /**
     * Check if the event bus is initialized.
     */
    public static boolean isInitialized() {
        return instance != null;
    }

    /**
     * Register an event listener with a specific priority.
     * @param eventClass The event class to listen for
     * @param listener The listener to register
     * @param priority The priority for execution order
     * @param <T> The event type
     */
    public <T extends SyncmoneyEvent> void register(
            Class<T> eventClass,
            SyncmoneyListener<T> listener,
            EventPriority priority) {

        listeners.computeIfAbsent(eventClass, k -> new CopyOnWriteArrayList<>())
                .add(new ListenerRegistration<>(listener, priority));

        listeners.get(eventClass).sort((a, b) ->
                Integer.compare(b.getPriority().getPriority(), a.getPriority().getPriority()));
    }

    /**
     * Register an event listener with normal priority.
     * @param eventClass The event class to listen for
     * @param listener The listener to register
     * @param <T> The event type
     */
    public <T extends SyncmoneyEvent> void register(Class<T> eventClass, SyncmoneyListener<T> listener) {
        register(eventClass, listener, EventPriority.NORMAL);
    }

    /**
     * Unregister a specific listener for an event type.
     * @param eventClass The event class
     * @param listener The listener to remove
     * @param <T> The event type
     */
    public <T extends SyncmoneyEvent> void unregister(Class<T> eventClass, SyncmoneyListener<T> listener) {
        List<ListenerRegistration<?>> registrations = listeners.get(eventClass);
        if (registrations != null) {
            registrations.removeIf(reg -> reg.getListener().equals(listener));
        }
    }

    /**
     * Fire an event to all registered listeners.
     * @param event The event to fire
     * @param <T> The event type
     */
    @SuppressWarnings("unchecked")
    public <T extends SyncmoneyEvent> void callEvent(T event) {
        List<ListenerRegistration<?>> registrations = listeners.get(event.getClass());
        if (registrations == null || registrations.isEmpty()) {
            return;
        }

        for (ListenerRegistration<?> registration : registrations) {
            try {
                ((SyncmoneyListener<T>) registration.getListener()).onEvent(event);
            } catch (Exception e) {
                plugin.getLogger().severe("Error in event listener: " + e.getMessage());
            }
        }
    }

    /**
     * Fire an event synchronously on the main thread.
     * If already on main thread, executes immediately.
     * Otherwise, schedules on main thread.
     * @param event The event to fire
     * @param <T> The event type
     */
    public <T extends SyncmoneyEvent> void callEventSync(T event) {
        if (Bukkit.isPrimaryThread()) {
            callEvent(event);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> callEvent(event));
        }
    }

    /**
     * Get the number of registered listeners for an event type.
     * @param eventClass The event class
     * @return Number of registered listeners
     */
    public int getListenerCount(Class<? extends SyncmoneyEvent> eventClass) {
        List<ListenerRegistration<?>> registrations = listeners.get(eventClass);
        return registrations != null ? registrations.size() : 0;
    }

    /**
     * Clear all registered listeners.
     * Used during plugin shutdown.
     */
    public void clearAll() {
        listeners.clear();
        plugin.getLogger().fine("All event listeners cleared.");
    }

    /**
     * Listener registration metadata.
     */
    private static class ListenerRegistration<T extends SyncmoneyEvent> {
        private final SyncmoneyListener<T> listener;
        private final EventPriority priority;

        public ListenerRegistration(SyncmoneyListener<T> listener, EventPriority priority) {
            this.listener = listener;
            this.priority = priority;
        }

        public SyncmoneyListener<T> getListener() {
            return listener;
        }

        public EventPriority getPriority() {
            return priority;
        }
    }
}
