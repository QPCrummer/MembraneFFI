package com.ishland.membraneffi.api;

import com.ishland.membraneffi.api.annotations.HasJavaFallback;
import com.ishland.membraneffi.api.annotations.InstallMachineCode;
import com.ishland.membraneffi.api.annotations.Link;
import com.ishland.membraneffi.api.annotations.OsArchPair;
import com.ishland.membraneffi.api.annotations.VarargCall;
import com.ishland.membraneffi.util.JavaInternals;
import com.ishland.membraneffi.util.JVMCIUtils;
import com.ishland.membraneffi.util.ModuleOpener;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Arrays;

public class MembraneLinker {

    // Open jdk.internal.vm.ci/jdk.vm.ci.* to our unnamed module BEFORE any
    // jdk.vm.ci.* type is first resolved by the JVM.
    static {
        ModuleOpener.openJvmci();
    }

    static {
        System.loadLibrary("java");
    }

    // JDK 21+ requires every installed nmethod to carry an entry barrier.
    // The barrier bytes are emitted by the calling convention adapter and
    // appended after the actual code. We record the offset so installCode
    // can create the required Mark sites.
    private static final CallingConventionAdapter callingConvention = CallingConventionAdapter.get();

    // ClassLoader.findNative signature evolved across JDK versions:
    //   JDK <= 21:  static long findNative(ClassLoader, String)
    //   JDK >= 22:  static long findNative(ClassLoader, Class<?>, String, String)
    // We resolve once at class init, then dispatch on parameter count.
    private static final Method FIND_NATIVE = resolveFindNative();

    private static Method resolveFindNative() {
        try {
            return JavaInternals.getPrivateMethod(ClassLoader.class, "findNative",
                    ClassLoader.class, Class.class, String.class, String.class);
        } catch (RuntimeException e1) {
            try {
                return JavaInternals.getPrivateMethod(ClassLoader.class, "findNative",
                        ClassLoader.class, String.class);
            } catch (RuntimeException e2) {
                throw new IllegalStateException("Cannot resolve ClassLoader.findNative", e2);
            }
        }
    }

    public static void linkClass(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getAnnotation(Link.class) != null || method.getAnnotation(InstallMachineCode.class) != null) {
                linkMethod(method);
            }
        }

    }

    public static void linkMethod(Method method) {
        boolean ignoreLinkFailures = method.getAnnotation(HasJavaFallback.class) != null;
        if (ignoreLinkFailures && Modifier.isNative(method.getModifiers())) {
            throw new IllegalArgumentException("Method " + method + " is native and has @HasJavaFallback annotation");
        }

        InstallMachineCode installMachineCode = method.getAnnotation(InstallMachineCode.class);
        Link link = method.getAnnotation(Link.class);

        if (installMachineCode != null && link != null) {
            throw new IllegalArgumentException("Method " + method + " is annotated with both @InstallMachineCode and @Link");
        }

        if (installMachineCode != null) {
            final Architecture architecture = Architecture.get();
            final OperatingSystem operatingSystem = OperatingSystem.get();
            for (InstallMachineCode.Entry entry : installMachineCode.value()) {
                for (OsArchPair pair : entry.targets()) {
                    if (pair.arch() == architecture && pair.os() == operatingSystem) {
                        if (Modifier.isSynchronized(method.getModifiers())) {
                            // Installing a raw nmethod bypasses HotSpot's synchronized-native
                            // wrapper, so accepting this modifier would silently drop locking.
                            throw new IllegalArgumentException(
                                    "Synchronized native methods are not supported: " + method);
                        }
                        installMachineCode0(method, parseHexString(entry.code()));
                        return;
                    }
                }
            }
            if (!ignoreLinkFailures) {
                throw new UnsatisfiedLinkError(String.format("Cannot find machine code for method %s", method));
            }
            return;
        }

        if (link == null) {
            if (!ignoreLinkFailures) {
                throw new IllegalArgumentException("Method " + method + " is not annotated with @Link");
            }
            return;
        }

        boolean isVarargCall = method.getAnnotation(VarargCall.class) != null;
        long address = 0;
        for (String symbol : link.value()) {
            address = findAddress(symbol);
            if (address != 0) break;
        }
        if (address == 0) {
            if (!ignoreLinkFailures) {
                throw new UnsatisfiedLinkError(String.format("Cannot find symbol for method %s, tried %s", method, Arrays.toString(link.value())));
            }
            return;
        }

        if (Modifier.isSynchronized(method.getModifiers())) {
            // Installing a raw nmethod bypasses HotSpot's synchronized-native
            // wrapper, so accepting this modifier would silently drop locking.
            throw new IllegalArgumentException(
                    "Synchronized native methods are not supported: " + method);
        }
        linkMethod0(method, address, isVarargCall);
    }

    public static void installMachineCode0(Method method, byte[] code) {
        // Append barrier bytes after the user-supplied machine code
        ByteArrayOutputStream out = new ByteArrayOutputStream(code.length + 32);
        // ByteArrayOutputStream never actually throws IOException, but the
        // inherited signature declares it (from OutputStream.write(byte[])).
        try {
            out.write(code);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        int barrierOffset = callingConvention.emitNMethodBarrier(out);
        byte[] full = out.toByteArray();
        JVMCIUtils.installCode(method, full, full.length, barrierOffset);
    }

    public static void linkMethod0(Method method, String symbol, boolean isVarargCall) {
        final long address = findAddress(symbol);
        if (address == 0) {
            throw new UnsatisfiedLinkError(String.format("Cannot find symbol %s", symbol));
        }
        linkMethod0(method, address, isVarargCall);
    }

    public static void linkMethod0(Method method, long address, boolean isVarargCall) {
        if (address == 0) {
            throw new NullPointerException();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Parameter[] parameters = method.getParameters();
        CallingConventionAdapter.Argument[] arguments = new CallingConventionAdapter.Argument[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            arguments[i] = new CallingConventionAdapter.Argument(i, parameters[i].getType(), parameters[i].getAnnotations());
        }
        callingConvention.emit(out, arguments, method.getReturnType(), address, isVarargCall);

        // Emit the entry barrier required by JVMCI since JDK 21
        int barrierOffset = callingConvention.emitNMethodBarrier(out);
        byte[] full = out.toByteArray();
        JVMCIUtils.installCode(method, full, full.length, barrierOffset);
    }

    private static long findAddress(String symbol) {
        ClassLoader cl = MembraneLinker.class.getClassLoader();
        try {
            if (FIND_NATIVE.getParameterCount() == 4) {
                // JDK 22+: (loader, caller, entryName, javaName).
                // entryName is the symbol passed to dlsym; javaName is for diagnostics.
                return (long) FIND_NATIVE.invoke(null, cl, MembraneLinker.class, symbol, symbol);
            } else {
                return (long) FIND_NATIVE.invoke(null, cl, symbol);
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(String.format("Failed to locate symbol %s", symbol), e);
        }
    }

    // parse strings used for @InstallMachineCode, spaces are allowed
    private static byte[] parseHexString(String hexString) {
        hexString = hexString.replaceAll("\\s+", "");
        if (hexString.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must have even length");
        }
        byte[] result = new byte[hexString.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(hexString.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

}
