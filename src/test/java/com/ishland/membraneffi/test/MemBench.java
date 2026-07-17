package com.ishland.membraneffi.test;

import com.ishland.membraneffi.api.MembraneLinker;
import com.ishland.membraneffi.api.annotations.Link;
import com.ishland.membraneffi.impl.FramedX86_64CallingConvention;
import com.ishland.membraneffi.impl.LinuxX86_64CallingConvention;
import com.ishland.membraneffi.util.CallingConventionOverride;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Call-overhead benchmark on identical native functions, one static method
 * per measurement (no shared lambdas — megamorphic dispatch would poison
 * the numbers). Compares Membrane's two conventions against Panama and
 * Panama-critical in the same JVM; run the Nalim benchmark separately with
 * the same loops for the cross-library figure.
 *
 * Requires -Dmembrane.testlibs=<dir> with libnalim_bench.so and
 * libbench_rng_rust.so.
 */
public class MemBench {

    static class Lin {
        @Link("raw_add")           static native int  add(int a, int b);
        @Link("raw_abs")           static native int  abs(int x);
        @Link("raw_noop")          static native int  noop();
        @Link("rust_splitmix_mix") static native long mix(long z);
        @Link("is_power_of_2")     static native boolean pow2(long v);
    }

    static class Frm {
        @Link("raw_add")           static native int  add(int a, int b);
        @Link("raw_abs")           static native int  abs(int x);
        @Link("raw_noop")          static native int  noop();
        @Link("rust_splitmix_mix") static native long mix(long z);
        @Link("is_power_of_2")     static native boolean pow2(long v);
    }

    static MethodHandle pAdd, pAddCrit;

    static final long GAMMA = 0x9E3779B97F4A7C15L;

    public static void main(String[] args) throws Throwable {
        String libDir = System.getProperty("membrane.testlibs");
        System.out.println("java.version=" + System.getProperty("java.version"));
        System.load(libDir + "/libnalim_bench.so");
        System.load(libDir + "/libbench_rng_rust.so");
        System.load(libDir + "/liboxidizium.so");

        CallingConventionOverride.setCallingConventionOverride(LinuxX86_64CallingConvention.class);
        try {
            MembraneLinker.linkClass(Lin.class);
        } finally {
            CallingConventionOverride.setCallingConventionOverride(null);
        }
        CallingConventionOverride.setCallingConventionOverride(FramedX86_64CallingConvention.class);
        try {
            MembraneLinker.linkClass(Frm.class);
        } finally {
            CallingConventionOverride.setCallingConventionOverride(null);
        }

        var ln = java.lang.foreign.Linker.nativeLinker();
        var lookup = SymbolLookup.loaderLookup();
        pAdd = ln.downcallHandle(lookup.find("raw_add").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
        pAddCrit = ln.downcallHandle(lookup.find("raw_add").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT),
                java.lang.foreign.Linker.Option.critical(false));

        // sanity
        if (Lin.add(3, 4) != 7 || Frm.add(3, 4) != 7) throw new AssertionError("add");
        if (Lin.pow2(64) != true || Frm.pow2(65) != false) throw new AssertionError("pow2");

        long sink = 0;
        for (int i = 0; i < 3_000_000; i++) {
            sink += Lin.add(1, i) + Lin.abs(-i) + Lin.noop() + Lin.mix(i * GAMMA) + (Lin.pow2(i) ? 1 : 0);
            sink += Frm.add(1, i) + Frm.abs(-i) + Frm.noop() + Frm.mix(i * GAMMA) + (Frm.pow2(i) ? 1 : 0);
            sink += (int) pAdd.invokeExact(1, i);
            sink += (int) pAddCrit.invokeExact(1, i);
            sink += javaAdd(1, i);
        }
        System.out.println("warmup sink = " + sink);

        benchJavaAdd();
        benchLinAdd();
        benchFrmAdd();
        benchPanamaAdd();
        benchPanamaCritAdd();
        benchLinNoop();
        benchFrmNoop();
        benchLinMix();
        benchFrmMix();
        benchLinPow2();
        benchFrmPow2();
    }

    static int javaAdd(int a, int b) { return a + b; }

    static void benchJavaAdd() {
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            int s = 0; long t0 = System.nanoTime();
            for (int i = 0; i < 200_000_000; i++) s += javaAdd(1, i);
            best = Math.min(best, (System.nanoTime() - t0) / 200_000_000.0); sink = s;
        }
        report("java add (C2 inline)", best, sink);
    }

    static void benchLinAdd() {
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            int s = 0; long t0 = System.nanoTime();
            for (int i = 0; i < 200_000_000; i++) s += Lin.add(1, i);
            best = Math.min(best, (System.nanoTime() - t0) / 200_000_000.0); sink = s;
        }
        report("add  Membrane Linux", best, sink);
    }

    static void benchFrmAdd() {
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            int s = 0; long t0 = System.nanoTime();
            for (int i = 0; i < 100_000_000; i++) s += Frm.add(1, i);
            best = Math.min(best, (System.nanoTime() - t0) / 100_000_000.0); sink = s;
        }
        report("add  Membrane Framed", best, sink);
    }

    static void benchPanamaAdd() {
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            int s = 0; long t0 = System.nanoTime();
            try {
                for (int i = 0; i < 100_000_000; i++) s += (int) pAdd.invokeExact(1, i);
            } catch (Throwable t) { throw new RuntimeException(t); }
            best = Math.min(best, (System.nanoTime() - t0) / 100_000_000.0); sink = s;
        }
        report("add  Panama", best, sink);
    }

    static void benchPanamaCritAdd() {
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            int s = 0; long t0 = System.nanoTime();
            try {
                for (int i = 0; i < 100_000_000; i++) s += (int) pAddCrit.invokeExact(1, i);
            } catch (Throwable t) { throw new RuntimeException(t); }
            best = Math.min(best, (System.nanoTime() - t0) / 100_000_000.0); sink = s;
        }
        report("add  Panama critical", best, sink);
    }

    static void benchLinNoop() {
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            int s = 0; long t0 = System.nanoTime();
            for (int i = 0; i < 200_000_000; i++) s += Lin.noop();
            best = Math.min(best, (System.nanoTime() - t0) / 200_000_000.0); sink = s;
        }
        report("noop Membrane Linux", best, sink);
    }

    static void benchFrmNoop() {
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            int s = 0; long t0 = System.nanoTime();
            for (int i = 0; i < 100_000_000; i++) s += Frm.noop();
            best = Math.min(best, (System.nanoTime() - t0) / 100_000_000.0); sink = s;
        }
        report("noop Membrane Framed", best, sink);
    }

    static void benchLinMix() {
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            long s = 0, st = 0x123456789ABCDEF0L;
            long t0 = System.nanoTime();
            for (int i = 0; i < 100_000_000; i++) { st += GAMMA; s += Lin.mix(st); }
            best = Math.min(best, (System.nanoTime() - t0) / 100_000_000.0); sink = s;
        }
        report("splitmix chained Membrane Linux", best, sink);
    }

    static void benchFrmMix() {
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            long s = 0, st = 0x123456789ABCDEF0L;
            long t0 = System.nanoTime();
            for (int i = 0; i < 50_000_000; i++) { st += GAMMA; s += Frm.mix(st); }
            best = Math.min(best, (System.nanoTime() - t0) / 50_000_000.0); sink = s;
        }
        report("splitmix chained Membrane Framed", best, sink);
    }

    static void benchLinPow2() {
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            int s = 0; long t0 = System.nanoTime();
            for (int i = 0; i < 100_000_000; i++) s += Lin.pow2(i) ? 1 : 0;
            best = Math.min(best, (System.nanoTime() - t0) / 100_000_000.0); sink = s;
        }
        report("bool(pow2) Membrane Linux (bridge)", best, sink);
    }

    static void benchFrmPow2() {
        double best = Double.MAX_VALUE; long sink = 0;
        for (int r = 0; r < 5; r++) {
            int s = 0; long t0 = System.nanoTime();
            for (int i = 0; i < 50_000_000; i++) s += Frm.pow2(i) ? 1 : 0;
            best = Math.min(best, (System.nanoTime() - t0) / 50_000_000.0); sink = s;
        }
        report("bool(pow2) Membrane Framed", best, sink);
    }

    static void report(String name, double ns, long sink) {
        System.out.printf("  %-36s : %6.3f ns/op (sink=%d)%n", name, ns, sink);
    }
}
