package com.ishland.membraneffi.test;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;

import com.ishland.membraneffi.api.Architecture;

/**
 * Runs the standalone correctness batteries under JUnit when the native
 * fixture libraries are available. Build them with test-native/build.sh and
 * run:
 *
 * <pre>
 *   ./gradlew test -Dmembrane.testlibs=$(pwd)/test-native
 * </pre>
 *
 * Without the property the batteries are skipped (the ordinary unit tests
 * still run).
 */
public class BatteryJUnitWrapper {

    private static String libs() {
        String dir = System.getProperty("membrane.testlibs");
        Assumptions.assumeTrue(dir != null, "-Dmembrane.testlibs not set; skipping battery");
        Assumptions.assumeTrue(Files.exists(Path.of(dir, "liboxidizium.so")),
                "fixture libraries not built; run test-native/build.sh");
        return dir;
    }

    private boolean isX86_64Linux() {
        return Architecture.get() == Architecture.X86_64;
    }

    @Test
    @EnabledOnOs({OS.LINUX})
    @EnabledIf("isX86_64Linux")
    public void correctnessBattery() throws Throwable {
        libs();
        CorrectnessBattery.main(new String[0]);
    }

    @Test
    @EnabledOnOs({OS.LINUX})
    @EnabledIf("isX86_64Linux")
    public void inlineFixturesBattery() throws Throwable {
        libs();
        InlineFixturesBattery.main(new String[0]);
    }
}
