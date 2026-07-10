package com.ishland.membraneffi.util;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import sun.misc.Unsafe;

/**
 * Low-level utilities that bypass Java access checks via {@link Unsafe}.
 */
public final class JavaInternals {

    private static final Unsafe UNSAFE;

    static {
        UNSAFE = getUnsafe();
    }

    @SuppressWarnings("deprecation")
    private static Unsafe getUnsafe() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot access Unsafe.theUnsafe", e);
        }
    }

    /**
     * Forcefully makes an {@link AccessibleObject} accessible, bypassing the
     * {@code setAccessible} check by writing directly to the
     * {@code AccessibleObject.override} field via {@link Unsafe}.
     */
    public static void setAccessible(AccessibleObject obj) {
        try {
            Field override = AccessibleObject.class.getDeclaredField("override");
            long offset = UNSAFE.staticFieldOffset(override);
            UNSAFE.putBoolean(obj, offset, true);
        } catch (NoSuchFieldException e) {
            // Fallback for older JDKs where the field name differs
            obj.setAccessible(true);
        }
    }

    /**
     * Retrieve a private method from a class.
     */
    public static java.lang.reflect.Method getPrivateMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        try {
            java.lang.reflect.Method m = clazz.getDeclaredMethod(name, parameterTypes);
            setAccessible(m);
            return m;
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private JavaInternals() {}
}
