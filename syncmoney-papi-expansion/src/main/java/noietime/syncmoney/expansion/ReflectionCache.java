package noietime.syncmoney.expansion;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [SYNC-PAPI-007] Reflection utility with method caching for performance optimization.
 * Reduces overhead of repeated reflection calls.
 */
public final class ReflectionCache {

    private static final ConcurrentHashMap<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    private ReflectionCache() {}

    /**
     * [SYNC-PAPI-008] Generate cache key for method lookup.
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
     * [SYNC-PAPI-009] Get cached method or null if not in cache.
     */
    public static Method getCachedMethod(String key) {
        return METHOD_CACHE.get(key);
    }

    /**
     * [SYNC-PAPI-010] Cache a method.
     */
    public static void cacheMethod(String key, Method method) {
        METHOD_CACHE.put(key, method);
    }

    /**
     * [SYNC-PAPI-011] Remove a method from cache (e.g., when invocation fails).
     */
    public static void removeMethod(String key) {
        METHOD_CACHE.remove(key);
    }

    /**
     * [SYNC-PAPI-012] Check if two classes are both UUID types.
     */
    public static boolean isUuidMatch(Class<?> paramClass, Class<?> argClass) {
        if (paramClass == UUID.class || "java.util.UUID".equals(paramClass.getName())) {
            return argClass == UUID.class || "java.util.UUID".equals(argClass.getName());
        }
        return false;
    }

    /**
     * [SYNC-PAPI-013] Check if primitive type matches wrapper class.
     */
    public static boolean isPrimitiveMatch(Class<?> paramClass, Class<?> argClass) {
        if (paramClass == int.class) return argClass == Integer.class;
        if (paramClass == long.class) return argClass == Long.class;
        if (paramClass == double.class) return argClass == Double.class;
        if (paramClass == boolean.class) return argClass == Boolean.class;
        return false;
    }
}
