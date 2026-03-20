package noietime.syncmoney.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Base class for all Syncmoney events.
 * Extends Bukkit's Event system for compatibility.
 */
public abstract class SyncmoneyEvent extends Event {
    private static final HandlerList handlers = new HandlerList();

    private final String eventName;
    private final long timestamp;

    protected SyncmoneyEvent(String eventName) {
        this.eventName = eventName;
        this.timestamp = System.currentTimeMillis();
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public String getEventName() {
        return eventName;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
