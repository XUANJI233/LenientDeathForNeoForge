package com.lenientdeath.neoforge;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class EventUtils {
    private EventUtils() {}

    // Cache of all methods for a class (fallback, populated once per class)
    private static final Map<Class<?>, Method[]> methodArrayCache = new ConcurrentHashMap<>();
    // Cache of method lookups by class + signature-key
    private static final Map<Class<?>, Map<String, Method>> methodLookupCache = new ConcurrentHashMap<>();
    // Cache of cancel-method candidates per class (Invoker holder)
    private static final Map<Class<?>, CancelInvoker> cancelInvokerCache = new ConcurrentHashMap<>();
    // Cache of chosen enum cancel value per enum class
    private static final Map<Class<?>, Object> enumCancelValueCache = new ConcurrentHashMap<>();
    // Cache of zero-arg method handles: class -> (name#0#null -> MethodHandle)
    private static final Map<Class<?>, Map<String, MethodHandle>> methodHandleCache = new ConcurrentHashMap<>();

    private static Method[] methodsFor(final Class<?> cls) {
        return methodArrayCache.computeIfAbsent(cls, Class::getMethods);
    }

    // Functional invoker taking (Object target, Object arg)
    @FunctionalInterface
    private interface Invoker {
        void invoke(Object target, Object arg) throws Throwable;
    }

    // Holder that can invoke via a generated Invoker, MethodHandle, or fallback to Method
    private static final class CancelInvoker {
        final Invoker inv; // may be null
        final MethodHandle mh; // may be null
        final Method method;   // may be null
        final Class<?> paramType; // parameter type for the invoker
        final boolean noArg; // true if underlying target method takes no args

        CancelInvoker(final Invoker inv, final MethodHandle mh, final Method m, final Class<?> paramType, final boolean noArg) {
            this.inv = inv;
            this.mh = mh;
            this.method = m;
            this.paramType = paramType;
            this.noArg = noArg;
        }

        @SuppressWarnings("unused") // callers expect a method to request boolean-cancellation; always use true
        boolean invokeWithBoolean(final Object target) {
            try {
                if (inv != null) {
                    inv.invoke(target, Boolean.TRUE);
                    return true;
                }
                if (mh != null) {
                    if (noArg) mh.invokeWithArguments(target);
                    else mh.invokeWithArguments(target, Boolean.TRUE);
                    return true;
                }
                if (method != null) {
                    if (noArg) method.invoke(target);
                    else method.invoke(target, Boolean.TRUE);
                    return true;
                }
            } catch (Throwable ignored) {}
            return false;
        }

        boolean invokeWithEnum(final Object target, final Object enumValue) {
            try {
                if (inv != null) {
                    inv.invoke(target, enumValue);
                    return true;
                }
                if (mh != null) {
                    mh.invokeWithArguments(target, enumValue);
                    return true;
                }
                if (method != null) {
                    method.invoke(target, enumValue);
                    return true;
                }
            } catch (Throwable ignored) {}
            return false;
        }
    }

    // Find a method by name (case-insensitive with ROOT locale), required param count and optional exact param type
    private static Method findMethodByName(final Class<?> cls, final String name, final int paramCount, final Class<?> paramType) {
        final Map<String, Method> map = methodLookupCache.computeIfAbsent(cls, k -> new ConcurrentHashMap<>());
        final String key = name.toLowerCase(Locale.ROOT) + "#" + paramCount + "#" + (paramType == null ? "null" : paramType.getName());
        return map.computeIfAbsent(key, n -> {
            for (final Method m : methodsFor(cls)) {
                if (m.getParameterCount() != paramCount) continue;
                if (!m.getName().equalsIgnoreCase(name)) continue;
                if (paramCount == 0) {
                    try { m.setAccessible(true); } catch (Throwable ignored) {}
                    return m;
                } else {
                    final Class<?> p = m.getParameterTypes()[0];
                    if (paramType == null || p == paramType) {
                        try { m.setAccessible(true); } catch (Throwable ignored) {}
                        return m;
                    }
                }
            }
            return null;
        });
    }

    /**
     * Try to get a property by several candidate method names. Returns null if none found or invocation fails.
     * Uses a cached MethodHandle for zero-arg methods when possible to improve performance; falls back to reflection.
     */
    public static Object tryGet(final Object target, final String... names) {
        if (target == null || names == null) return null;
        final Class<?> cls = target.getClass();
        for (final String name : names) {
            final Method m = findMethodByName(cls, name, 0, null);
            if (m != null) {
                // Try MethodHandle fast path
                try {
                    final Map<String, MethodHandle> inner = methodHandleCache.computeIfAbsent(cls, k -> new ConcurrentHashMap<>());
                    final String key = name.toLowerCase(Locale.ROOT) + "#0#null";
                    MethodHandle mh = inner.get(key);
                    if (mh == null) {
                        try {
                            MethodHandles.Lookup lookup = MethodHandles.lookup();
                            // If method or declaring class is non-public, try a private lookup
                            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers()) || !java.lang.reflect.Modifier.isPublic(m.getDeclaringClass().getModifiers())) {
                                try {
                                    lookup = MethodHandles.privateLookupIn(m.getDeclaringClass(), lookup);
                                } catch (Throwable ignored) {
                                    // ignore and use public lookup
                                }
                            }
                            mh = lookup.unreflect(m).asType(MethodType.methodType(Object.class, Object.class));
                        } catch (Throwable ignored) {
                            // leave mh as null
                        }
                        if (mh != null) inner.put(key, mh);
                    }
                    if (mh != null) {
                        try {
                            return mh.invokeWithArguments(target);
                        } catch (Throwable ignored) {
                            // fall through to reflection
                        }
                    }
                } catch (Throwable ignored) {}

                // Fallback: reflection
                try {
                    return m.invoke(target);
                } catch (final Throwable ignored) {}
            }
        }
        return null;
    }

    /**
     * Try to cancel the event using a cached candidate method (setCanceled/setCancelled/cancel) or setResult enum.
     * Prefer a LambdaMetafactory-generated invoker for best performance, with MethodHandle and reflection fallback.
     * Returns true if a cancellation action was performed.
     */
    public static boolean tryCancel(final Object event) {
        if (event == null) return false;
        final Class<?> cls = event.getClass();

        // Fast path: cached cancel invoker
        final CancelInvoker invoker = cancelInvokerCache.get(cls);
        if (invoker != null) {
            // try boolean first
            if (invoker.paramType == boolean.class || invoker.paramType == Boolean.class) {
                return invoker.invokeWithBoolean(event);
            }
            // try enum
            if (invoker.paramType != null && invoker.paramType.isEnum()) {
                final Object enumVal = pickEnumCancelValue(invoker.paramType);
                if (enumVal != null) return invoker.invokeWithEnum(event, enumVal);
            }
            // if invoker has noArg and paramType is void, attempt it
            if (invoker.noArg) {
                return invoker.invokeWithBoolean(event);
            }
        }

        // Candidate method names to try for boolean cancel
        final String[] booleanCandidates = new String[]{"setCanceled", "setCancelled", "cancel", "setCancel"};
        Method found = null;
        boolean foundNoArg = false;
        for (final String cand : booleanCandidates) {
            // first try zero-arg cancel() methods
            final Method candidate0 = findMethodByName(cls, cand, 0, null);
            if (candidate0 != null) {
                found = candidate0;
                foundNoArg = true;
                break;
            }
            // then try boolean/boxed
            found = findMethodByName(cls, cand, 1, boolean.class);
            if (found == null) found = findMethodByName(cls, cand, 1, Boolean.class);
            if (found != null) break;
        }

        if (found != null) {
            final CancelInvoker ci = makeInvokerForMethod(found);
            cancelInvokerCache.put(cls, ci);
            if (foundNoArg) return ci.invokeWithBoolean(event);
            return ci.invokeWithBoolean(event);
        }

        // Try setResult enum
        final Method m = findMethodByName(cls, "setResult", 1, null);
        if (m != null && m.getParameterTypes()[0].isEnum()) {
            try { m.setAccessible(true); } catch (final Throwable ignored) {}
            final CancelInvoker ci = makeInvokerForMethod(m);
            cancelInvokerCache.put(cls, ci);
            final Object enumVal = pickEnumCancelValue(m.getParameterTypes()[0]);
            if (enumVal != null) return ci.invokeWithEnum(event, enumVal);
        }

        return false;
    }

    // Create a CancelInvoker which tries to obtain a LambdaMetafactory-based Invoker, then MethodHandle, then Method
    private static CancelInvoker makeInvokerForMethod(final Method m) {
        try {
            final Class<?>[] params = m.getParameterTypes();
            final boolean noArg = params.length == 0;
            final Class<?> param = params.length > 0 ? params[0] : void.class;
            MethodHandle mh;
            Invoker inv;
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                // If method is not public, attempt to obtain a private lookup for the declaring class
                if (!java.lang.reflect.Modifier.isPublic(m.getModifiers()) || !java.lang.reflect.Modifier.isPublic(m.getDeclaringClass().getModifiers())) {
                    try {
                        lookup = MethodHandles.privateLookupIn(m.getDeclaringClass(), lookup);
                    } catch (Throwable ignored) {
                        // ignore and fall back to public lookup
                    }
                }
                mh = lookup.unreflect(m);
                // adapt to (Object, Object)void so LambdaMetafactory can create an Invoker
                final MethodType samType = MethodType.methodType(void.class, Object.class, Object.class);
                final MethodHandle adapted;
                if (noArg) {
                    // mh has shape (DeclaringClass)void; add a dummy second arg
                    adapted = MethodHandles.dropArguments(mh, 1, Object.class)
                            .asType(MethodType.methodType(void.class, Object.class, Object.class));
                } else {
                    adapted = mh.asType(MethodType.methodType(void.class, Object.class, Object.class));
                }
                try {
                    final CallSite site = LambdaMetafactory.metafactory(
                            lookup,
                            "invoke",
                            MethodType.methodType(Invoker.class),
                            samType,
                            adapted,
                            adapted.type());
                    final MethodHandle factory = site.getTarget();
                    inv = (Invoker) factory.invoke();
                } catch (final Throwable lmfe) {
                    inv = null;
                }
            } catch (final Throwable ignore) {
                // if MethodHandle creation fails, fall back to reflection-only CancelInvoker
                mh = null;
                inv = null;
            }
            try { m.setAccessible(true); } catch (final Throwable ignored) {}
            return new CancelInvoker(inv, mh, m, param, noArg);
        } catch (final Throwable t) {
            // ultimate fallback
            try { m.setAccessible(true); } catch (final Throwable ignored) {}
            return new CancelInvoker(null, null, m, m.getParameterTypes().length > 0 ? m.getParameterTypes()[0] : null, m.getParameterTypes().length == 0);
        }
    }

    // Choose a reasonable enum constant for cancelling: prefer a cached preferred, else compute and cache
    private static Object pickEnumCancelValue(final Class<?> enumCls) {
        final Object cached = enumCancelValueCache.get(enumCls);
        if (cached != null) return cached;
        final Object val = computeEnumCancelValue(enumCls);
        if (val != null) enumCancelValueCache.put(enumCls, val);
        return val;
    }

    private static Object computeEnumCancelValue(final Class<?> enumCls) {
        try {
            final Object[] constants = enumCls.getEnumConstants();
            if (constants == null || constants.length == 0) return null;

            // Prefer canonical names
            final List<String> preferred = Arrays.asList("DENY", "CANCEL", "FAIL", "DISALLOW", "DENIED", "REJECT", "DISALLOWED");
            for (final String p : preferred) {
                for (final Object c : constants) {
                    if (c.toString().equalsIgnoreCase(p)) return c;
                }
            }

            // fallback: choose first constant that's not ALLOW/ALLOWED
            for (final Object c : constants) {
                final String s = c.toString().toUpperCase(Locale.ROOT);
                if (!s.contains("ALLOW")) return c;
            }

            // final fallback: return first constant
            return constants[0];
        } catch (final Throwable ignored) {
            return null;
        }
    }
}
