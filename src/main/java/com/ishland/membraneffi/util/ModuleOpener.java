package com.ishland.membraneffi.util;

import java.lang.reflect.Method;

/**
 * Opens {@code jdk.internal.vm.ci/jdk.vm.ci.*} packages to unnamed modules
 * at runtime, without needing {@code --add-exports} JVM flags.
 * <p>
 * This class itself does NOT reference any {@code jdk.vm.ci.*} type, so it
 * can be loaded and executed BEFORE the JVMCI packages are opened.
 */
public final class ModuleOpener {

    private static final String[] JVMCI_PACKAGES = {
            "jdk.vm.ci.code",
            "jdk.vm.ci.code.site",
            "jdk.vm.ci.hotspot",
            "jdk.vm.ci.meta",
            "jdk.vm.ci.runtime",
    };

    private static volatile boolean done = false;

    private ModuleOpener() {}

    /**
     * Open all required JVMCI packages to every reachable unnamed module.
     * Safe to call multiple times.
     */
    public static synchronized void openJvmci() {
        if (done) return;

        Module jvmci = ModuleLayer.boot().findModule("jdk.internal.vm.ci")
                .orElse(null);
        if (jvmci == null) {
            // JVMCI not available — nothing to open; failures will surface later
            done = true;
            return;
        }

        try {
            Method addExports = Module.class.getDeclaredMethod(
                    "implAddExports", String.class, Module.class);
            JavaInternals.setAccessible(addExports);

            Module system   = ClassLoader.getSystemClassLoader().getUnnamedModule();
            Module platform = ClassLoader.getPlatformClassLoader().getUnnamedModule();
            Module here     = ModuleOpener.class.getModule();

            for (String pkg : JVMCI_PACKAGES) {
                addExports.invoke(jvmci, pkg, system);
                if (platform != system) {
                    addExports.invoke(jvmci, pkg, platform);
                }
                if (here != system && here != platform) {
                    addExports.invoke(jvmci, pkg, here);
                }
            }
            done = true;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "Could not locate java.lang.Module.implAddExports — " +
                    "add --add-exports=jdk.internal.vm.ci/jdk.vm.ci.*=ALL-UNNAMED " +
                    "JVM flags manually.", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to open JVMCI packages reflectively", e);
        }
    }
}
