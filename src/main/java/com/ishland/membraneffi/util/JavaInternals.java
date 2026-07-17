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

    // Offset of AccessibleObject.override, discovered empirically: the field
    // is filtered from reflection on modern JDKs (getDeclaredField("override")
    // throws), and its offset is not stable across versions. Probing two
    // Method objects that differ only in their accessible flag finds it
    // without ever naming the field. 0 = probe failed, use plain
    // setAccessible and hope the package is open.
    private static final long ACCESSIBLE_OFFSET = probeAccessibleOffset();

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

    @SuppressWarnings("deprecation")
    private static long probeAccessibleOffset() {
        try {
            java.lang.reflect.Method m0 = JavaInternals.class.getDeclaredMethod("probeAccessibleOffset");
            java.lang.reflect.Method m1 = JavaInternals.class.getDeclaredMethod("probeAccessibleOffset");
            m1.setAccessible(true);   // own class — always permitted

            for (long offset = 8; offset < 128; offset++) {
                if (UNSAFE.getByte(m0, offset) == 0 && UNSAFE.getByte(m1, offset) == 1) {
                    return offset;
                }
            }
        } catch (Throwable t) {
            // fall through
        }
        return 0;
    }

    /**
     * Forcefully makes an {@link AccessibleObject} accessible, bypassing the
     * {@code setAccessible} module check by writing the
     * {@code AccessibleObject.override} flag directly via {@link Unsafe}.
     * This is what allows {@link ModuleOpener} to reach
     * {@code Module.implAddExports} without {@code --add-opens} flags or an
     * instrumentation agent.
     */
    @SuppressWarnings("deprecation")
    public static void setAccessible(AccessibleObject obj) {
        if (ACCESSIBLE_OFFSET != 0) {
            UNSAFE.putByte(obj, ACCESSIBLE_OFFSET, (byte) 1);
        } else {
            obj.setAccessible(true);
        }
    }

    /**
     * Read one byte of native memory. Used by the small-function inliner to
     * examine a target function's machine code.
     */
    @SuppressWarnings("deprecation")
    public static byte getByte(long address) {
        return UNSAFE.getByte(address);
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
