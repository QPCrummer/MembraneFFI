package com.ishland.membraneffi.api;

import com.ishland.membraneffi.util.CallingConventionOverride;

import java.io.ByteArrayOutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Objects;

public interface CallingConventionAdapter {

    void emit(ByteArrayOutputStream out, Argument[] arguments, Class<?> returnType, long address, boolean isVarargCall);

    /**
     * Emit the "no-op" nmethod entry barrier required by JVMCI since JDK 21.
     * Loom / JEP 444 made {@code BarrierSetNMethod} mandatory for every GC,
     * so {@code CompilerToVM.installCode0} now rejects any nmethod missing
     * a Mark with id {@code CodeInstaller::ENTRY_BARRIER_PATCH}.
     * <p>
     * The barrier is emitted AFTER the actual code, so the CPU never executes
     * it; it exists solely to satisfy JVMCI validation.
     *
     * @return offset within the output stream where the barrier begins
     */
    int emitNMethodBarrier(ByteArrayOutputStream out);

    public static CallingConventionAdapter get() {
        final Class<?> override = CallingConventionOverride.getCallingConventionOverride();
        if (override != null) {
            try {
                return (CallingConventionAdapter) override.getConstructor().newInstance();
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                throw new RuntimeException("Failed to instantiate calling convention adapter", e);
            }
        }

        switch (OperatingSystem.get()) {
            case LINUX:
            case OSX:
                switch (Architecture.get()) {
                    case X86_64:
                        return new com.ishland.membraneffi.impl.LinuxX86_64CallingConvention();
                    default:
                        throw new UnsupportedOperationException("Unsupported architecture: " + Architecture.get());
                }
            default: // includes windows
                switch (Architecture.get()) {
                    case X86_64:
                        return new com.ishland.membraneffi.impl.FramedX86_64CallingConvention();
                    default:
                        throw new UnsupportedOperationException("Unsupported architecture: " + Architecture.get());
                }
        }
    }

    public static final class Argument {
        private final int index;
        private final Class<?> type;
        private final Annotation[] annotations;

        public Argument(int index, Class<?> type, Annotation[] annotations) {
            this.index = index;
            this.type = type;
            this.annotations = annotations;
        }

        public int index() {
            return index;
        }

        public Class<?> type() {
            return type;
        }

        public Annotation[] annotations() {
            return annotations;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj == null || obj.getClass() != this.getClass()) return false;
            Argument that = (Argument) obj;
            return this.index == that.index &&
                   Objects.equals(this.type, that.type) &&
                   Arrays.equals(this.annotations, that.annotations);
        }

        @Override
        public int hashCode() {
            return Objects.hash(index, type, annotations);
        }

        @Override
        public String toString() {
            return "Argument[" +
                   "index=" + index + ", " +
                   "type=" + type + ", " +
                   "annotations=" + annotations + ']';
        }

    }

}
