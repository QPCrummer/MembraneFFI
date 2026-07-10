package com.ishland.membraneffi.util;

import com.ishland.membraneffi.api.CallingConventionAdapter;

import java.lang.reflect.Array;
import java.lang.reflect.Method;

public class JVMCIUtils {

    public static int baseOffset(CallingConventionAdapter.Argument argument) {
        if (argument.type().isArray() && argument.type().getComponentType().isPrimitive()) {
            return arrayBaseOffset(argument.type());
        }

        throw new UnsupportedOperationException("Unsupported type: " + argument.type());
    }

    public static int arrayBaseOffset(Class<?> arrayType) {
        final Object componentType = JVMCIAccess.javaKind$fromJavaClass(arrayType.getComponentType());
        return JVMCIAccess.metaAccessProvider$getArrayBaseOffset(componentType);
    }

    /**
     * Install code into the JVMCI code cache.
     *
     * @param m           the method to install code for
     * @param code        the full byte array (code + optional trailing barrier)
     * @param length      total length of valid bytes in the code array
     * @param barrierOffset offset where the entry barrier begins, or -1 if no barrier (pre-JDK-21)
     */
    public static void installCode(Method m, byte[] code, int length, int barrierOffset) {
        final Object resolvedJavaMethod = JVMCIAccess.metaAccessProvider$lookupJavaMethod(m);

        // Build Site[] with Mark entries for JDK 21+ barrier
        Object sites = buildSites(barrierOffset);

        final Object resolvedJavaMethodArray = JVMCIAccess.resolvedJavaMethodArray$constructor(1);
        JVMCIAccess.resolvedJavaMethod$array$set(resolvedJavaMethodArray, 0, resolvedJavaMethod);
        Object hotspotCompiledNmethod = JVMCIAccess.hotSpotCompiledNmethod$constructor(
                m.getName(),
                code,
                length,
                sites,
                JVMCIAccess.assumptions$AssumptionArray$constructor(0),
                resolvedJavaMethodArray,
                JVMCIAccess.hotSpotCompiledCode$CommentArray$constructor(0),
                new byte[0],
                1,
                JVMCIAccess.dataPatchArray$constructor(0),
                true,
                0,
                null,
                resolvedJavaMethod,
                JVMCIAccess.jvmciCompiler$INVOCATION_ENTRY_BCI$get(),
                JVMCIAccess.hotspotResolvedJavaMethod$allocateCompileId(resolvedJavaMethod, JVMCIAccess.jvmciCompiler$INVOCATION_ENTRY_BCI$get()),
                0,
                false
        );

        JVMCIAccess.codeCacheProvider$setDefaultCode(resolvedJavaMethod, hotspotCompiledNmethod);

        JVMCIAccess.hotspotResolvedJavaMethod$setNotInlinableOrCompilable(resolvedJavaMethod);
    }

    /**
     * Build the Site[] array with Mark entries for the entry barrier.
     * On JDK 21+, emits FRAME_COMPLETE and ENTRY_BARRIER_PATCH marks.
     * On pre-JDK-21, returns an empty array.
     */
    private static Object buildSites(int barrierOffset) {
        Integer frameComplete = JVMCIAccess.markId$FRAME_COMPLETE();
        Integer entryBarrierPatch = JVMCIAccess.markId$ENTRY_BARRIER_PATCH();

        if (entryBarrierPatch == null || barrierOffset < 0) {
            // Pre-Loom JDK — no entry barrier required
            return JVMCIAccess.siteArray$constructor(0);
        }

        int siteCount = 1;
        if (frameComplete != null) {
            siteCount++;
        }

        Object sites = JVMCIAccess.siteArray$constructor(siteCount);
        int idx = 0;
        if (frameComplete != null) {
            Array.set(sites, idx++,
                    JVMCIAccess.mark$constructor(barrierOffset, frameComplete));
        }
        Array.set(sites, idx,
                JVMCIAccess.mark$constructor(barrierOffset, entryBarrierPatch));
        return sites;
    }

}
