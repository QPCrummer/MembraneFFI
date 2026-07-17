package com.ishland.membraneffi.api;

import com.github.icedland.iced.x86.asm.AsmRegisters;
import com.github.icedland.iced.x86.asm.CodeAssembler;
import com.ishland.membraneffi.util.CallingConventionOverride;

import java.io.ByteArrayOutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Objects;

public interface CallingConventionAdapter {

    void emit(ByteArrayOutputStream out, Argument[] arguments, Class<?> returnType, long address, boolean isVarargCall);

    /**
     * Whether {@code returnType} needs sign/zero extension before control
     * returns to compiled Java. The System V and Win64 ABIs only define
     * AL/AX for sub-int return values — everything above may be garbage —
     * while HotSpot's Java calling convention expects the callee to hand
     * back a full, canonical EAX (booleans strictly 0/1). Returning the raw
     * native register produces corrupted booleans/bytes/shorts/chars,
     * including "true" values that fail {@code == true}.
     */
    static boolean needsReturnNormalization(Class<?> returnType) {
        return returnType == boolean.class || returnType == byte.class
                || returnType == short.class || returnType == char.class;
    }

    /**
     * Emit the extension that converts a System V narrow return value in
     * AL/AX into the canonical Java EAX form. Must run after the native call
     * returns and before control goes back to compiled Java.
     */
    static void emitNarrowReturnNormalization(CodeAssembler as, Class<?> returnType) {
        if (returnType == boolean.class) {
            as.movzx(AsmRegisters.eax, AsmRegisters.al);
        } else if (returnType == byte.class) {
            as.movsx(AsmRegisters.eax, AsmRegisters.al);
        } else if (returnType == short.class) {
            as.movsx(AsmRegisters.eax, AsmRegisters.ax);
        } else if (returnType == char.class) {
            as.movzx(AsmRegisters.eax, AsmRegisters.ax);
        } else {
            throw new IllegalArgumentException("Not a narrow return type: " + returnType);
        }
    }

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
