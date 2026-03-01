package noietime.syncmoney.expansion;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Helper class for reflection operations with caching.
 */
public final class ReflectionHelper {

    private ReflectionHelper() {}

    /**
     * Invoke a method on an object with caching support.
     */
    public static Object invokeMethod(Object obj, String methodName, Object... args) {
        if (obj == null) return null;

        String key = ReflectionCache.generateKey(obj, methodName, args);

        Method cachedMethod = ReflectionCache.getCachedMethod(key);
        if (cachedMethod != null) {
            try {
                return cachedMethod.invoke(obj, args);
            } catch (Exception ignored) {
                ReflectionCache.removeMethod(key);
            }
        }

        Method method = findMethod(obj.getClass(), methodName, args);
        if (method != null) {
            ReflectionCache.cacheMethod(key, method);
            try {
                return method.invoke(obj, args);
            } catch (Exception ignored) {}
        }

        return null;
    }

    /**
     * Find method using reflection with various type matching strategies.
     */
    private static Method findMethod(Class<?> clazz, String methodName, Object[] args) {
        Class<?>[] argClasses = new Class[args.length];
        for (int i = 0; i < args.length; i++) {
            if (args[i] != null) {
                argClasses[i] = args[i].getClass();
            }
        }

        try {
            Method m = clazz.getMethod(methodName, argClasses);
            return m;
        } catch (NoSuchMethodException ignored) {}

        Class<?>[] primitiveClasses = new Class[args.length];
        for (int i = 0; i < args.length; i++) {
            if (args[i] != null) {
                Class<?> argClass = args[i].getClass();
                if (ReflectionCache.isPrimitiveMatch(argClass, argClass)) {
                    if (argClass == Integer.class) primitiveClasses[i] = int.class;
                    else if (argClass == Long.class) primitiveClasses[i] = long.class;
                    else if (argClass == Double.class) primitiveClasses[i] = double.class;
                    else if (argClass == Boolean.class) primitiveClasses[i] = boolean.class;
                    else primitiveClasses[i] = argClass;
                } else {
                    primitiveClasses[i] = argClass;
                }
            }
        }

        try {
            Method m = clazz.getMethod(methodName, primitiveClasses);
            return m;
        } catch (NoSuchMethodException ignored) {}

        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(methodName)) {
                if (args.length == 0) {
                    return m;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length == args.length) {
                    boolean match = true;
                    for (int i = 0; i < params.length; i++) {
                        if (args[i] != null) {
                            Class<?> paramClass = params[i];
                            Class<?> argClass = args[i].getClass();
                            boolean primitiveMatch = ReflectionCache.isPrimitiveMatch(paramClass, argClass);
                            boolean uuidMatch = ReflectionCache.isUuidMatch(paramClass, argClass);
                            if (!primitiveMatch && !uuidMatch && !paramClass.isAssignableFrom(argClass)) {
                                match = false;
                                break;
                            }
                        }
                    }
                    if (match) {
                        return m;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Get field value from object.
     */
    public static Object getField(Object obj, String fieldName) {
        if (obj == null) return null;
        try {
            var field = obj.getClass().getField(fieldName);
            return field.get(obj);
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Get balance from RankEntry record (supports Java records).
     */
    public static double getRecordBalance(Object entry) {
        if (entry == null) return -1;

        try {
            Method m = entry.getClass().getMethod("balance");
            Object result = m.invoke(entry);
            if (result instanceof Number) {
                return ((Number) result).doubleValue();
            }
        } catch (Exception ignored) {}

        try {
            var field = entry.getClass().getField("balance");
            Object result = field.get(entry);
            if (result instanceof Number) {
                return ((Number) result).doubleValue();
            }
        } catch (Exception ignored) {}

        return -1;
    }
}
