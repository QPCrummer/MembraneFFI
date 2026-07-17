package com.ishland.membraneffi.test;

import com.ishland.membraneffi.api.MembraneLinker;
import com.ishland.membraneffi.api.annotations.Link;

// Minimal isolated bench: one linked native, one loop shape identical to
// nalim's BenchFloor, nothing else in the JVM. Distinguishes real call
// overhead from same-JVM co-warmup artifacts in the bigger MemBench.
public class MemBenchIso {

    @Link("raw_add")
    static native int add(int a, int b);

    public static void main(String[] args) {
        System.load(System.getProperty("membrane.testlibs") + "/libnalim_bench.so");
        MembraneLinker.linkClass(MemBenchIso.class);
        if (add(3, 4) != 7) throw new AssertionError();

        long warm = 0;
        for (int i = 0; i < 5_000_000; i++) warm += add(1, i);
        System.out.println("warmup sink = " + warm);

        double best = Double.MAX_VALUE;
        long sink = 0;
        for (int run = 0; run < 5; run++) {
            int s = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < 200_000_000; i++) s += add(1, i);
            long t1 = System.nanoTime();
            sink = s;
            best = Math.min(best, (t1 - t0) / 200_000_000.0);
        }
        System.out.printf("  add Membrane (auto convention)   : %6.3f ns/op (sink=%d)%n", best, sink);
    }
}
