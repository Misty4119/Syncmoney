package noietime.syncmoney.expansion;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection utility with method caching for performance optimization.
 * Reduces overhead of repeated reflection calls.
 */
public final class ReflectionCache {

    private static final ConcurrentHashMap<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    private ReflectionCache() {}

    /**
     * Generate cache key for method lookup.
     */
    public static String generateKey(Object obj, String methodName, Object... args) {
        StringBuilder sb = new StringBuilder();
        sb.append(obj.getClass().getName()).append(':').append(methodName);
        for (Object arg : args) {
            sb.append(':').append(arg != null ? arg.getClass().getName() : "null");
        }
        return sb.toString();
    }

    /**
     * Get cached method or null if not in cache.
     */
    public static Method getCachedMethod(String key) {
        return METHOD_CACHE.get(key);
    }

    /**
     * Cache a method.
     */
    public static void cacheMethod(String key, Method method) {
        METHOD_CACHE.put(key, method);
    }

    /**
     * Remove a method from cache (e.g., when invocation fails).
     */
    public static void removeMethod(String key) {
        METHOD_CACHE.remove(key);
    }

    /**
     * Check if two classes are both UUID types.
     */
    public static boolean isUuidMatch(Class<?> paramClass, Class<?> argClass) {
        if (paramClass == UUID.class || "java.util.UUID".equals(paramClass.getName())) {
            return argClass == UUID.class || "java.util.UUID".equals(argClass.getName());
        }
        return false;
    }

    /**
     * Check if primitive type matches wrapper class.
     */
    public static boolean isPrimitiveMatch(Class<?> paramClass, Class<?> argClass) {
        if (paramClass == int.class) return argClass == Integer.class;
        if (paramClass == long.class) return argClass == Long.class;
        if (paramClass == double.class) return argClass == Double.class;
        if (paramClass == boolean.class) return argClass == Boolean.class;
        return false;
    }
}
