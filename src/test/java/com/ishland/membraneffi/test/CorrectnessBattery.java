package com.ishland.membraneffi.test;

import com.ishland.membraneffi.api.MembraneLinker;
import com.ishland.membraneffi.api.annotations.Link;
import com.ishland.membraneffi.impl.FramedX86_64CallingConvention;
import com.ishland.membraneffi.impl.LinuxX86_64CallingConvention;
import com.ishland.membraneffi.util.CallingConventionOverride;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.SplittableRandom;

import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Three-way correctness battery: every native function is called through
 * Membrane AND through a Panama downcall handle, and (where the semantics are
 * exactly defined) compared against a trusted Java mirror. Any Membrane-vs-
 * Panama bit difference on the same symbol is a Membrane codegen bug by
 * construction.
 *
 * Coverage: integer/FP arguments, mixed signatures, native stack arguments,
 * float/double returns, narrow (boolean/byte/short/char) returns with
 * deliberately dirty upper register bits, and both calling conventions
 * (LinuxX86_64 tail-jump and Framed generic).
 *
 * Requires -Dmembrane.testlibs=<dir> containing liboxidizium.so (workalike),
 * libstack_args.so and libnalim_narrow_returns.so from the Nalim test suite.
 * Run standalone via main(); each convention runs in its own linked class so
 * the two nmethod sets coexist.
 */
public class CorrectnessBattery {

    static int failures = 0;
    static int checked = 0;

    // ── binding sets, one per convention ────────────────────────────────

    static class LinuxConv {
        @Link("positive_ceil_div_int")  static native int    positiveCeilDivInt(int a, int b);
        @Link("positive_ceil_div_long") static native long   positiveCeilDivLong(long a, long b);
        @Link("round_towards_int")      static native int    roundTowardsInt(int a, int b);
        @Link("round_towards_long")     static native long   roundTowardsLong(long a, long b);
        @Link("floor_div")              static native int    floorDiv(int a, int b);
        @Link("ceil_div")               static native int    ceilDiv(int a, int b);
        @Link("round_up_to_multiple")   static native int    roundUpToMultiple(int a, int b);
        @Link("wrap_degrees_90")        static native float  wrapDegrees90(float f);
        @Link("wrap_degrees_float")     static native float  wrapDegreesFloat(float f);
        @Link("wrap_degrees_double")    static native double wrapDegreesDouble(double d);
        @Link("wrap_degrees_int")       static native int    wrapDegreesInt(int d);
        @Link("wrap_degrees_long")      static native float  wrapDegreesLong(long d);
        @Link("atan_2")                 static native double atan2(double y, double x);
        @Link("sin_float")              static native float  sinFloat(float x);
        @Link("sin_double")             static native float  sinDouble(double x);
        @Link("cos_float")              static native float  cosFloat(float x);
        @Link("floor_float")            static native int    floorFloat(float x);
        @Link("magnitude_int")          static native double magnitudeInt(int a, double b, int c);
        @Link("round_down_to_multiple") static native int    roundDownToMultiple(double a, int b);
        @Link("clamp_int")              static native int    clampInt(int v, int lo, int hi);
        @Link("abs_int")                static native int    absInt(int x);
        @Link("is_power_of_2")          static native boolean isPowerOf2(long v);
        @Link("is_power_of_two")        static native boolean isPowerOfTwo(int v);
        @Link("approximately_equals_float")  static native boolean approxEqF(float a, float b);
        @Link("approximately_equals_double") static native boolean approxEqD(double a, double b);

        @Link("nalim_dirty_bool_false") static native boolean dirtyBoolFalse();
        @Link("nalim_dirty_bool_true")  static native boolean dirtyBoolTrue();
        @Link("nalim_dirty_byte")       static native byte    dirtyByte();
        @Link("nalim_dirty_short")      static native short   dirtyShort();
        @Link("nalim_dirty_char")       static native char    dirtyChar();
        @Link("nalim_stack_is_aligned") static native boolean stackAligned();

        // narrow return + native stack arguments: the Linux adapter delegates
        // these to the Framed convention (a tail-jump bridge would shift the
        // stack slots).
        @Link("nalim_narrow_stack7")  static native byte narrowStack7(
                long a, long b, long c, long d, long e, long f, long seventh);
        @Link("nalim_narrow_stack9d") static native byte narrowStack9d(
                double a, double b, double c, double d, double e,
                double f, double g, double h, double ninth);

        @Link("take7")   static native long   take7(long a, long b, long c, long d, long e, long f, long g);
        @Link("take8")   static native long   take8(long a, long b, long c, long d, long e, long f, long g, long h);
        @Link("take9d")  static native double take9d(double a, double b, double c, double d, double e,
                                                     double f, double g, double h, double i);
        @Link("mixed10") static native double mixed10(int a, double b, long c, double d, int e,
                                                      double f, long g, double h, int i, double j);
    }

    static class FramedConv {
        @Link("positive_ceil_div_int")  static native int    positiveCeilDivInt(int a, int b);
        @Link("positive_ceil_div_long") static native long   positiveCeilDivLong(long a, long b);
        @Link("round_towards_int")      static native int    roundTowardsInt(int a, int b);
        @Link("round_towards_long")     static native long   roundTowardsLong(long a, long b);
        @Link("floor_div")              static native int    floorDiv(int a, int b);
        @Link("ceil_div")               static native int    ceilDiv(int a, int b);
        @Link("round_up_to_multiple")   static native int    roundUpToMultiple(int a, int b);
        @Link("wrap_degrees_90")        static native float  wrapDegrees90(float f);
        @Link("wrap_degrees_float")     static native float  wrapDegreesFloat(float f);
        @Link("wrap_degrees_double")    static native double wrapDegreesDouble(double d);
        @Link("wrap_degrees_int")       static native int    wrapDegreesInt(int d);
        @Link("wrap_degrees_long")      static native float  wrapDegreesLong(long d);
        @Link("atan_2")                 static native double atan2(double y, double x);
        @Link("sin_float")              static native float  sinFloat(float x);
        @Link("sin_double")             static native float  sinDouble(double x);
        @Link("cos_float")              static native float  cosFloat(float x);
        @Link("floor_float")            static native int    floorFloat(float x);
        @Link("magnitude_int")          static native double magnitudeInt(int a, double b, int c);
        @Link("round_down_to_multiple") static native int    roundDownToMultiple(double a, int b);
        @Link("clamp_int")              static native int    clampInt(int v, int lo, int hi);
        @Link("abs_int")                static native int    absInt(int x);
        @Link("is_power_of_2")          static native boolean isPowerOf2(long v);
        @Link("is_power_of_two")        static native boolean isPowerOfTwo(int v);
        @Link("approximately_equals_float")  static native boolean approxEqF(float a, float b);
        @Link("approximately_equals_double") static native boolean approxEqD(double a, double b);

        @Link("nalim_dirty_bool_false") static native boolean dirtyBoolFalse();
        @Link("nalim_dirty_bool_true")  static native boolean dirtyBoolTrue();
        @Link("nalim_dirty_byte")       static native byte    dirtyByte();
        @Link("nalim_dirty_short")      static native short   dirtyShort();
        @Link("nalim_dirty_char")       static native char    dirtyChar();
        @Link("nalim_stack_is_aligned") static native boolean stackAligned();

        @Link("take7")   static native long   take7(long a, long b, long c, long d, long e, long f, long g);
        @Link("take8")   static native long   take8(long a, long b, long c, long d, long e, long f, long g, long h);
        @Link("take9d")  static native double take9d(double a, double b, double c, double d, double e,
                                                     double f, double g, double h, double i);
        @Link("mixed10") static native double mixed10(int a, double b, long c, double d, int e,
                                                      double f, long g, double h, int i, double j);
    }

    // ── panama reference handles ────────────────────────────────────────

    static MethodHandle pCeilI, pCeilL, pRoundI, pRoundL, pFloorDiv, pCeilDiv, pRoundUp,
            pWrap90, pWrapF, pWrapD, pWrapI, pWrapL, pAtan2, pSinF, pSinD, pCosF, pFloorF,
            pMagI, pRDown, pClampI, pAbsI, pPow2L, pPow2I, pAeqF, pAeqD,
            pDirtyBF, pDirtyBT, pDirtyByte, pDirtyShort, pDirtyChar, pStackAligned,
            pTake7, pTake8, pTake9d, pMixed10;

    static void setupPanama() {
        var ln = java.lang.foreign.Linker.nativeLinker();
        SymbolLookup lookup = SymbolLookup.loaderLookup();
        pCeilI    = dh(ln, lookup, "positive_ceil_div_int",  FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
        pCeilL    = dh(ln, lookup, "positive_ceil_div_long", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG));
        pRoundI   = dh(ln, lookup, "round_towards_int",      FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
        pRoundL   = dh(ln, lookup, "round_towards_long",     FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG));
        pFloorDiv = dh(ln, lookup, "floor_div",              FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
        pCeilDiv  = dh(ln, lookup, "ceil_div",               FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
        pRoundUp  = dh(ln, lookup, "round_up_to_multiple",   FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
        pWrap90   = dh(ln, lookup, "wrap_degrees_90",        FunctionDescriptor.of(JAVA_FLOAT, JAVA_FLOAT));
        pWrapF    = dh(ln, lookup, "wrap_degrees_float",     FunctionDescriptor.of(JAVA_FLOAT, JAVA_FLOAT));
        pWrapD    = dh(ln, lookup, "wrap_degrees_double",    FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE));
        pWrapI    = dh(ln, lookup, "wrap_degrees_int",       FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        pWrapL    = dh(ln, lookup, "wrap_degrees_long",      FunctionDescriptor.of(JAVA_FLOAT, JAVA_LONG));
        pAtan2    = dh(ln, lookup, "atan_2",                 FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
        pSinF     = dh(ln, lookup, "sin_float",              FunctionDescriptor.of(JAVA_FLOAT, JAVA_FLOAT));
        pSinD     = dh(ln, lookup, "sin_double",             FunctionDescriptor.of(JAVA_FLOAT, JAVA_DOUBLE));
        pCosF     = dh(ln, lookup, "cos_float",              FunctionDescriptor.of(JAVA_FLOAT, JAVA_FLOAT));
        pFloorF   = dh(ln, lookup, "floor_float",            FunctionDescriptor.of(JAVA_INT, JAVA_FLOAT));
        pMagI     = dh(ln, lookup, "magnitude_int",          FunctionDescriptor.of(JAVA_DOUBLE, JAVA_INT, JAVA_DOUBLE, JAVA_INT));
        pRDown    = dh(ln, lookup, "round_down_to_multiple", FunctionDescriptor.of(JAVA_INT, JAVA_DOUBLE, JAVA_INT));
        pClampI   = dh(ln, lookup, "clamp_int",              FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        pAbsI     = dh(ln, lookup, "abs_int",                FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        pPow2L    = dh(ln, lookup, "is_power_of_2",          FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_LONG));
        pPow2I    = dh(ln, lookup, "is_power_of_two",        FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_INT));
        pAeqF     = dh(ln, lookup, "approximately_equals_float",  FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_FLOAT, JAVA_FLOAT));
        pAeqD     = dh(ln, lookup, "approximately_equals_double", FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_DOUBLE, JAVA_DOUBLE));
        pDirtyBF  = dh(ln, lookup, "nalim_dirty_bool_false", FunctionDescriptor.of(JAVA_BOOLEAN));
        pDirtyBT  = dh(ln, lookup, "nalim_dirty_bool_true",  FunctionDescriptor.of(JAVA_BOOLEAN));
        pDirtyByte = dh(ln, lookup, "nalim_dirty_byte",      FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_BYTE));
        pDirtyShort = dh(ln, lookup, "nalim_dirty_short",    FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_SHORT));
        pDirtyChar = dh(ln, lookup, "nalim_dirty_char",      FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_CHAR));
        pStackAligned = dh(ln, lookup, "nalim_stack_is_aligned", FunctionDescriptor.of(JAVA_BOOLEAN));
        pTake7    = dh(ln, lookup, "take7",  FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));
        pTake8    = dh(ln, lookup, "take8",  FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));
        pTake9d   = dh(ln, lookup, "take9d", FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
        pMixed10  = dh(ln, lookup, "mixed10", FunctionDescriptor.of(JAVA_DOUBLE, JAVA_INT, JAVA_DOUBLE, JAVA_LONG, JAVA_DOUBLE, JAVA_INT, JAVA_DOUBLE, JAVA_LONG, JAVA_DOUBLE, JAVA_INT, JAVA_DOUBLE));
    }

    static MethodHandle dh(java.lang.foreign.Linker ln, SymbolLookup lookup, String name, FunctionDescriptor fd) {
        return ln.downcallHandle(lookup.find(name).orElseThrow(() -> new IllegalStateException(name)), fd);
    }

    // ── comparison helpers ──────────────────────────────────────────────

    static void cmpI(String what, int membrane, int panama, long a, long b) {
        checked++;
        if (membrane != panama && failures++ < 30) {
            System.out.printf("MISMATCH %s(%d, %d): membrane=%d panama=%d%n", what, a, b, membrane, panama);
        }
    }

    static void cmpL(String what, long membrane, long panama, long a, long b) {
        checked++;
        if (membrane != panama && failures++ < 30) {
            System.out.printf("MISMATCH %s(%d, %d): membrane=%d panama=%d%n", what, a, b, membrane, panama);
        }
    }

    static void cmpF(String what, float membrane, float panama, double in) {
        checked++;
        if (Float.floatToRawIntBits(membrane) != Float.floatToRawIntBits(panama) && failures++ < 30) {
            System.out.printf("MISMATCH %s(%s): membrane=%s panama=%s%n", what, in, membrane, panama);
        }
    }

    static void cmpD(String what, double membrane, double panama, double in) {
        checked++;
        if (Double.doubleToRawLongBits(membrane) != Double.doubleToRawLongBits(panama) && failures++ < 30) {
            System.out.printf("MISMATCH %s(%s): membrane=%s panama=%s%n", what, in, membrane, panama);
        }
    }

    static void cmpB(String what, boolean membrane, boolean panama, long in) {
        checked++;
        if (membrane != panama && failures++ < 30) {
            System.out.printf("MISMATCH %s(%d): membrane=%s panama=%s%n", what, in, membrane, panama);
        }
    }

    static void expect(String what, boolean condition) {
        checked++;
        if (!condition && failures++ < 30) {
            System.out.printf("MISMATCH %s%n", what);
        }
    }

    // ── the battery, parameterized by convention via lambdas is verbose —
    //    run the same checks twice through the two @Link classes ─────────

    interface Section { void run() throws Throwable; }

    static void section(String name, Section s) {
        int before = failures;
        try {
            s.run();
        } catch (Throwable t) {
            failures++;
            System.out.printf("SECTION %s THREW: %s%n", name, t);
        }
        System.out.printf("  %-44s %s%n", name, failures == before ? "PASS" : "FAIL(+" + (failures - before) + ")");
    }

    public static void main(String[] args) throws Throwable {
        String libDir = System.getProperty("membrane.testlibs");
        if (libDir == null) {
            throw new IllegalStateException("-Dmembrane.testlibs=<dir> is required");
        }
        System.out.println("java.version=" + System.getProperty("java.version"));
        System.load(libDir + "/liboxidizium.so");
        System.load(libDir + "/libstack_args.so");
        System.load(libDir + "/libnalim_narrow_returns.so");
        setupPanama();

        System.out.println("--- LinuxX86_64CallingConvention (tail-jump) ---");
        CallingConventionOverride.setCallingConventionOverride(LinuxX86_64CallingConvention.class);
        try {
            MembraneLinker.linkClass(LinuxConv.class);
        } finally {
            CallingConventionOverride.setCallingConventionOverride(null);
        }
        runLinuxSections();

        System.out.println("--- FramedX86_64CallingConvention (generic) ---");
        CallingConventionOverride.setCallingConventionOverride(FramedX86_64CallingConvention.class);
        try {
            MembraneLinker.linkClass(FramedConv.class);
        } finally {
            CallingConventionOverride.setCallingConventionOverride(null);
        }
        runFramedSections();

        System.out.println(failures == 0
                ? "=== ALL MATCH (" + checked + " comparisons) ==="
                : "=== " + failures + " FAILURES (" + checked + " comparisons) ===");
        if (failures > 0) {
            // Throw rather than System.exit so the JUnit wrapper survives;
            // standalone invocations still exit nonzero via the uncaught error.
            throw new AssertionError(failures + " mismatches out of " + checked + " comparisons");
        }
    }

    static void runLinuxSections() {
        section("narrow returns (dirty upper bits)", () -> {
            for (int i = 0; i < 100_000; i++) {
                cmpB("dirty_bool_false", LinuxConv.dirtyBoolFalse(), (boolean) pDirtyBF.invokeExact(), 0);
                cmpB("dirty_bool_true", LinuxConv.dirtyBoolTrue(), (boolean) pDirtyBT.invokeExact(), 0);
                expect("dirty_bool_false must be false", !LinuxConv.dirtyBoolFalse());
                expect("dirty_bool_true must be true", LinuxConv.dirtyBoolTrue());
                expect("dirty_byte must be -128", LinuxConv.dirtyByte() == (byte) 0x80);
                expect("dirty_short must be 0x8001", LinuxConv.dirtyShort() == (short) 0x8001);
                expect("dirty_char must be 0xabcd", LinuxConv.dirtyChar() == 'ꯍ');
                expect("canonical boolean identity", LinuxConv.dirtyBoolTrue() == Boolean.TRUE);
            }
        });
        section("callee stack alignment", () -> {
            for (int i = 0; i < 10_000; i++) {
                expect("rsp%16==8 at callee entry", LinuxConv.stackAligned());
            }
        });
        section("integer two-arg battery", () -> {
            SplittableRandom r = new SplittableRandom(0xC0FFEE);
            for (int i = 0; i < 100_000; i++) {
                int a = r.nextBoolean() ? r.nextInt(-2000, 2000) : r.nextInt();
                int b = r.nextBoolean() ? r.nextInt(-64, 64) : r.nextInt();
                if (b == 0) b = 7;
                if (a == Integer.MIN_VALUE) a++;
                long la = r.nextLong(), lb = r.nextLong();
                if (lb == 0) lb = 7;
                if (la == Long.MIN_VALUE) la++;
                cmpI("positive_ceil_div_int", LinuxConv.positiveCeilDivInt(a, b), (int) pCeilI.invokeExact(a, b), a, b);
                cmpL("positive_ceil_div_long", LinuxConv.positiveCeilDivLong(la, lb), (long) pCeilL.invokeExact(la, lb), la, lb);
                cmpI("round_towards_int", LinuxConv.roundTowardsInt(a, b), (int) pRoundI.invokeExact(a, b), a, b);
                cmpL("round_towards_long", LinuxConv.roundTowardsLong(la, lb), (long) pRoundL.invokeExact(la, lb), la, lb);
                cmpI("floor_div", LinuxConv.floorDiv(a, b), (int) pFloorDiv.invokeExact(a, b), a, b);
                cmpI("ceil_div", LinuxConv.ceilDiv(a, b), (int) pCeilDiv.invokeExact(a, b), a, b);
                cmpI("round_up_to_multiple", LinuxConv.roundUpToMultiple(a, b), (int) pRoundUp.invokeExact(a, b), a, b);
                cmpI("clamp_int", LinuxConv.clampInt(a, -1000, 1000), (int) pClampI.invokeExact(a, -1000, 1000), a, 0);
                cmpI("abs_int", LinuxConv.absInt(a), (int) pAbsI.invokeExact(a), a, 0);
                cmpI("wrap_degrees_int", LinuxConv.wrapDegreesInt(a), (int) pWrapI.invokeExact(a), a, 0);
                // Java reference for the exactly-defined ones
                expect("floorDiv == Math.floorDiv", LinuxConv.floorDiv(a, b) == Math.floorDiv(a, b));
            }
        });
        section("float/double args and returns", () -> {
            SplittableRandom r = new SplittableRandom(0xF00D);
            for (int i = 0; i < 100_000; i++) {
                float f = (r.nextFloat() - 0.5f) * 10000f;
                double d = (r.nextDouble() - 0.5) * 10000;
                long la = r.nextLong();
                cmpF("wrap_degrees_90", LinuxConv.wrapDegrees90(f), (float) pWrap90.invokeExact(f), f);
                cmpF("wrap_degrees_float", LinuxConv.wrapDegreesFloat(f), (float) pWrapF.invokeExact(f), f);
                cmpD("wrap_degrees_double", LinuxConv.wrapDegreesDouble(d), (double) pWrapD.invokeExact(d), d);
                cmpF("wrap_degrees_long", LinuxConv.wrapDegreesLong(la), (float) pWrapL.invokeExact(la), la);
                cmpD("atan_2", LinuxConv.atan2(d, d * 0.7 + 1), (double) pAtan2.invokeExact(d, d * 0.7 + 1), d);
                cmpF("sin_float", LinuxConv.sinFloat(f), (float) pSinF.invokeExact(f), f);
                cmpF("sin_double", LinuxConv.sinDouble(d), (float) pSinD.invokeExact(d), d);
                cmpF("cos_float", LinuxConv.cosFloat(f), (float) pCosF.invokeExact(f), f);
                cmpI("floor_float", LinuxConv.floorFloat(f), (int) pFloorF.invokeExact(f), (long) f, 0);
            }
        });
        section("mixed int/double signatures", () -> {
            SplittableRandom r = new SplittableRandom(0xBEEF);
            for (int i = 0; i < 100_000; i++) {
                int a = r.nextInt(-1000, 1000), c = r.nextInt(-1000, 1000);
                double b = (r.nextDouble() - 0.5) * 1000;
                cmpD("magnitude_int(int,double,int)", LinuxConv.magnitudeInt(a, b, c),
                        (double) pMagI.invokeExact(a, b, c), b);
                int bb = r.nextInt(1, 64);
                cmpI("round_down_to_multiple(double,int)", LinuxConv.roundDownToMultiple(b, bb),
                        (int) pRDown.invokeExact(b, bb), (long) b, bb);
            }
        });
        section("boolean-returning with args", () -> {
            SplittableRandom r = new SplittableRandom(0xD00D);
            for (int i = 0; i < 100_000; i++) {
                long v = r.nextLong();
                int vi = r.nextInt();
                float f = r.nextFloat() * 100;
                double d = r.nextDouble() * 100;
                cmpB("is_power_of_2", LinuxConv.isPowerOf2(v), (boolean) pPow2L.invokeExact(v), v);
                cmpB("is_power_of_2/pow", LinuxConv.isPowerOf2(1L << (i & 62)), (boolean) pPow2L.invokeExact(1L << (i & 62)), 1L << (i & 62));
                cmpB("is_power_of_two", LinuxConv.isPowerOfTwo(vi), (boolean) pPow2I.invokeExact(vi), vi);
                cmpB("approx_eq_float", LinuxConv.approxEqF(f, f * 1.0000001f), (boolean) pAeqF.invokeExact(f, f * 1.0000001f), (long) f);
                cmpB("approx_eq_double", LinuxConv.approxEqD(d, d + 1e-9), (boolean) pAeqD.invokeExact(d, d + 1e-9), (long) d);
            }
        });
        section("native stack arguments", () -> {
            SplittableRandom r = new SplittableRandom(7);
            for (int i = 0; i < 100_000; i++) {
                long a = r.nextLong(1000), b = r.nextLong(1000), c = r.nextLong(1000), d = r.nextLong(1000),
                     e = r.nextLong(1000), f = r.nextLong(1000), g = r.nextLong(1000), h = r.nextLong(1000);
                long ref7 = a * 3 + b * 5 + c * 7 + d * 11 + e * 13 + f * 17 + g * 19;
                cmpL("take7", LinuxConv.take7(a, b, c, d, e, f, g), (long) pTake7.invokeExact(a, b, c, d, e, f, g), a, g);
                expect("take7 == reference", LinuxConv.take7(a, b, c, d, e, f, g) == ref7);
                cmpL("take8", LinuxConv.take8(a, b, c, d, e, f, g, h), (long) pTake8.invokeExact(a, b, c, d, e, f, g, h), a, h);
                double da = r.nextDouble(), db = r.nextDouble(), dc = r.nextDouble(), dd = r.nextDouble(),
                       de = r.nextDouble(), df = r.nextDouble(), dg = r.nextDouble(), dh2 = r.nextDouble(), di = r.nextDouble();
                cmpD("take9d", LinuxConv.take9d(da, db, dc, dd, de, df, dg, dh2, di),
                        (double) pTake9d.invokeExact(da, db, dc, dd, de, df, dg, dh2, di), da);
                int ia = (int) r.nextLong(1000), ie = (int) r.nextLong(1000), ii = (int) r.nextLong(1000);
                long lc = r.nextLong(1000), lg = r.nextLong(1000);
                cmpD("mixed10", LinuxConv.mixed10(ia, db, lc, dd, ie, df, lg, dh2, ii, di),
                        (double) pMixed10.invokeExact(ia, db, lc, dd, ie, df, lg, dh2, ii, di), db);
            }
        });
        section("narrow return + stack args (delegation)", () -> {
            SplittableRandom r = new SplittableRandom(11);
            for (int i = 0; i < 100_000; i++) {
                long seventh = (byte) r.nextInt();          // fixture returns [rsp+8] low byte
                byte n7 = LinuxConv.narrowStack7(1, 2, 3, 4, 5, 6, seventh);
                expect("narrowStack7 == 7th arg low byte", n7 == (byte) seventh);
                double ninth = (byte) r.nextInt(0, 100);    // fixture cvttsd2si's the 9th double
                byte n9 = LinuxConv.narrowStack9d(0, 0, 0, 0, 0, 0, 0, 0, ninth);
                expect("narrowStack9d == (byte) 9th arg", n9 == (byte) ninth);
            }
        });
    }

    static void runFramedSections() {
        section("narrow returns (dirty upper bits)", () -> {
            for (int i = 0; i < 100_000; i++) {
                cmpB("dirty_bool_false", FramedConv.dirtyBoolFalse(), (boolean) pDirtyBF.invokeExact(), 0);
                cmpB("dirty_bool_true", FramedConv.dirtyBoolTrue(), (boolean) pDirtyBT.invokeExact(), 0);
                expect("dirty_bool_false must be false", !FramedConv.dirtyBoolFalse());
                expect("dirty_bool_true must be true", FramedConv.dirtyBoolTrue());
                expect("dirty_byte must be -128", FramedConv.dirtyByte() == (byte) 0x80);
                expect("dirty_short must be 0x8001", FramedConv.dirtyShort() == (short) 0x8001);
                expect("dirty_char must be 0xabcd", FramedConv.dirtyChar() == 'ꯍ');
                expect("canonical boolean identity", FramedConv.dirtyBoolTrue() == Boolean.TRUE);
            }
        });
        section("callee stack alignment", () -> {
            for (int i = 0; i < 10_000; i++) {
                expect("rsp%16==8 at callee entry", FramedConv.stackAligned());
            }
        });
        section("integer two-arg battery", () -> {
            SplittableRandom r = new SplittableRandom(0xC0FFEE);
            for (int i = 0; i < 100_000; i++) {
                int a = r.nextBoolean() ? r.nextInt(-2000, 2000) : r.nextInt();
                int b = r.nextBoolean() ? r.nextInt(-64, 64) : r.nextInt();
                if (b == 0) b = 7;
                if (a == Integer.MIN_VALUE) a++;
                long la = r.nextLong(), lb = r.nextLong();
                if (lb == 0) lb = 7;
                if (la == Long.MIN_VALUE) la++;
                cmpI("positive_ceil_div_int", FramedConv.positiveCeilDivInt(a, b), (int) pCeilI.invokeExact(a, b), a, b);
                cmpL("positive_ceil_div_long", FramedConv.positiveCeilDivLong(la, lb), (long) pCeilL.invokeExact(la, lb), la, lb);
                cmpI("round_towards_int", FramedConv.roundTowardsInt(a, b), (int) pRoundI.invokeExact(a, b), a, b);
                cmpL("round_towards_long", FramedConv.roundTowardsLong(la, lb), (long) pRoundL.invokeExact(la, lb), la, lb);
                cmpI("floor_div", FramedConv.floorDiv(a, b), (int) pFloorDiv.invokeExact(a, b), a, b);
                cmpI("ceil_div", FramedConv.ceilDiv(a, b), (int) pCeilDiv.invokeExact(a, b), a, b);
                cmpI("round_up_to_multiple", FramedConv.roundUpToMultiple(a, b), (int) pRoundUp.invokeExact(a, b), a, b);
                cmpI("clamp_int", FramedConv.clampInt(a, -1000, 1000), (int) pClampI.invokeExact(a, -1000, 1000), a, 0);
                cmpI("abs_int", FramedConv.absInt(a), (int) pAbsI.invokeExact(a), a, 0);
                cmpI("wrap_degrees_int", FramedConv.wrapDegreesInt(a), (int) pWrapI.invokeExact(a), a, 0);
                expect("floorDiv == Math.floorDiv", FramedConv.floorDiv(a, b) == Math.floorDiv(a, b));
            }
        });
        section("float/double args and returns", () -> {
            SplittableRandom r = new SplittableRandom(0xF00D);
            for (int i = 0; i < 100_000; i++) {
                float f = (r.nextFloat() - 0.5f) * 10000f;
                double d = (r.nextDouble() - 0.5) * 10000;
                long la = r.nextLong();
                cmpF("wrap_degrees_90", FramedConv.wrapDegrees90(f), (float) pWrap90.invokeExact(f), f);
                cmpF("wrap_degrees_float", FramedConv.wrapDegreesFloat(f), (float) pWrapF.invokeExact(f), f);
                cmpD("wrap_degrees_double", FramedConv.wrapDegreesDouble(d), (double) pWrapD.invokeExact(d), d);
                cmpF("wrap_degrees_long", FramedConv.wrapDegreesLong(la), (float) pWrapL.invokeExact(la), la);
                cmpD("atan_2", FramedConv.atan2(d, d * 0.7 + 1), (double) pAtan2.invokeExact(d, d * 0.7 + 1), d);
                cmpF("sin_float", FramedConv.sinFloat(f), (float) pSinF.invokeExact(f), f);
                cmpF("sin_double", FramedConv.sinDouble(d), (float) pSinD.invokeExact(d), d);
                cmpF("cos_float", FramedConv.cosFloat(f), (float) pCosF.invokeExact(f), f);
                cmpI("floor_float", FramedConv.floorFloat(f), (int) pFloorF.invokeExact(f), (long) f, 0);
            }
        });
        section("mixed int/double signatures", () -> {
            SplittableRandom r = new SplittableRandom(0xBEEF);
            for (int i = 0; i < 100_000; i++) {
                int a = r.nextInt(-1000, 1000), c = r.nextInt(-1000, 1000);
                double b = (r.nextDouble() - 0.5) * 1000;
                cmpD("magnitude_int(int,double,int)", FramedConv.magnitudeInt(a, b, c),
                        (double) pMagI.invokeExact(a, b, c), b);
                int bb = r.nextInt(1, 64);
                cmpI("round_down_to_multiple(double,int)", FramedConv.roundDownToMultiple(b, bb),
                        (int) pRDown.invokeExact(b, bb), (long) b, bb);
            }
        });
        section("boolean-returning with args", () -> {
            SplittableRandom r = new SplittableRandom(0xD00D);
            for (int i = 0; i < 100_000; i++) {
                long v = r.nextLong();
                int vi = r.nextInt();
                float f = r.nextFloat() * 100;
                double d = r.nextDouble() * 100;
                cmpB("is_power_of_2", FramedConv.isPowerOf2(v), (boolean) pPow2L.invokeExact(v), v);
                cmpB("is_power_of_2/pow", FramedConv.isPowerOf2(1L << (i & 62)), (boolean) pPow2L.invokeExact(1L << (i & 62)), 1L << (i & 62));
                cmpB("is_power_of_two", FramedConv.isPowerOfTwo(vi), (boolean) pPow2I.invokeExact(vi), vi);
                cmpB("approx_eq_float", FramedConv.approxEqF(f, f * 1.0000001f), (boolean) pAeqF.invokeExact(f, f * 1.0000001f), (long) f);
                cmpB("approx_eq_double", FramedConv.approxEqD(d, d + 1e-9), (boolean) pAeqD.invokeExact(d, d + 1e-9), (long) d);
            }
        });
        section("native stack arguments", () -> {
            SplittableRandom r = new SplittableRandom(7);
            for (int i = 0; i < 100_000; i++) {
                long a = r.nextLong(1000), b = r.nextLong(1000), c = r.nextLong(1000), d = r.nextLong(1000),
                     e = r.nextLong(1000), f = r.nextLong(1000), g = r.nextLong(1000), h = r.nextLong(1000);
                long ref7 = a * 3 + b * 5 + c * 7 + d * 11 + e * 13 + f * 17 + g * 19;
                cmpL("take7", FramedConv.take7(a, b, c, d, e, f, g), (long) pTake7.invokeExact(a, b, c, d, e, f, g), a, g);
                expect("take7 == reference", FramedConv.take7(a, b, c, d, e, f, g) == ref7);
                cmpL("take8", FramedConv.take8(a, b, c, d, e, f, g, h), (long) pTake8.invokeExact(a, b, c, d, e, f, g, h), a, h);
                double da = r.nextDouble(), db = r.nextDouble(), dc = r.nextDouble(), dd = r.nextDouble(),
                       de = r.nextDouble(), df = r.nextDouble(), dg = r.nextDouble(), dh2 = r.nextDouble(), di = r.nextDouble();
                cmpD("take9d", FramedConv.take9d(da, db, dc, dd, de, df, dg, dh2, di),
                        (double) pTake9d.invokeExact(da, db, dc, dd, de, df, dg, dh2, di), da);
                int ia = (int) r.nextLong(1000), ie = (int) r.nextLong(1000), ii = (int) r.nextLong(1000);
                long lc = r.nextLong(1000), lg = r.nextLong(1000);
                cmpD("mixed10", FramedConv.mixed10(ia, db, lc, dd, ie, df, lg, dh2, ii, di),
                        (double) pMixed10.invokeExact(ia, db, lc, dd, ie, df, lg, dh2, ii, di), db);
            }
        });
    }
}
