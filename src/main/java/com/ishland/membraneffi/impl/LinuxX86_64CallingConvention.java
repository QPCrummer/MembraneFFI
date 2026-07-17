package com.ishland.membraneffi.impl;

import com.github.icedland.iced.x86.asm.AsmRegisters;
import com.github.icedland.iced.x86.asm.CodeAssembler;
import com.github.icedland.iced.x86.asm.CodeAssemblerResult;
import com.ishland.membraneffi.api.CallingConventionAdapter;
import com.ishland.membraneffi.util.JVMCIUtils;

import java.io.ByteArrayOutputStream;

public class LinuxX86_64CallingConvention implements CallingConventionAdapter {

    // x64 calling convention (Linux, macOS):
    //     Java: rsi, rdx, rcx,  r8,  r9, rdi, stack
    //   Native: rdi, rsi, rdx, rcx,  r8,  r9, stack

    @Override
    public void emit(ByteArrayOutputStream out, Argument[] arguments, Class<?> returnType, long address, boolean isVarargCall) {
        // Narrow returns (boolean/byte/short/char) need a sign/zero extension
        // AFTER the native function returns, which a tail jump cannot provide.
        // Register-only signatures get a real-call bridge below; signatures
        // with native stack arguments would see their [rsp]-relative slots
        // shifted by that bridge, so they take the Framed convention, which
        // rebuilds the frame generically and normalizes after its call.
        boolean normalizeReturn = CallingConventionAdapter.needsReturnNormalization(returnType);
        if (normalizeReturn && needsNativeStackArguments(arguments)) {
            new FramedX86_64CallingConvention().emit(out, arguments, returnType, address, isVarargCall);
            return;
        }

        CodeAssembler as = new CodeAssembler(64);

        // Only integer-class arguments consume the integer registers
        // (rdi, rsi, rdx, rcx, r8, r9); float/double live in XMM registers
        // and cannot cause the 6th-argument clash.
        int integerArgCount = 0;
        for (Argument arg : arguments) {
            if (!arg.type().isPrimitive() || (arg.type() != float.class && arg.type() != double.class)) {
                integerArgCount++;
            }
        }
        if (integerArgCount >= 6) {
            // 6th Java argument clashes with the 1st native arg
            as.mov(AsmRegisters.rax, AsmRegisters.rdi);
        }

        int generalPurposeArgIndex = 0;
        int xmmArgIndex = 0;
        int stackArgIndex = 0;
        for (Argument arg : arguments) {
            if (arg.type().isPrimitive()) {
                if (arg.type() == float.class || arg.type() == double.class) { // floating points are passed in xmm registers
                    if (xmmArgIndex++ >= 8) { // xmm8+ are passed on stack
                        stackArgIndex ++;
                    }
                } else {
                    if (generalPurposeArgIndex < 6) { // adapt calling convention
//                        emit(buf, (type == long.class ? MOVE_LONG_ARG_REG : MOVE_INT_ARG_REG)[generalPurposeArgIndex++]);
                        switch (generalPurposeArgIndex++) {
                            case 0:
                                as.mov(AsmRegisters.rdi, AsmRegisters.rsi);
                                break;
                            case 1:
                                as.mov(AsmRegisters.rsi, AsmRegisters.rdx);
                                break;
                            case 2:
                                as.mov(AsmRegisters.rdx, AsmRegisters.rcx);
                                break;
                            case 3:
                                as.mov(AsmRegisters.rcx, AsmRegisters.r8);
                                break;
                            case 4:
                                as.mov(AsmRegisters.r8, AsmRegisters.r9);
                                break;
                            case 5:
                                as.mov(AsmRegisters.r9, AsmRegisters.rax);
                                break;
                            default:
                                throw new AssertionError();
                        }
                    } else { // the rest is passed on stack
                        stackArgIndex++;
                    }
                }
            } else {
                final int baseOffset = JVMCIUtils.baseOffset(arg);
                if (generalPurposeArgIndex < 6) { // adapt calling convention + offset
                    switch (generalPurposeArgIndex++) {
                        case 0:
                            as.lea(AsmRegisters.rdi, AsmRegisters.rsi.add(baseOffset));
                            break;
                        case 1:
                            as.lea(AsmRegisters.rsi, AsmRegisters.rdx.add(baseOffset));
                            break;
                        case 2:
                            as.lea(AsmRegisters.rdx, AsmRegisters.rcx.add(baseOffset));
                            break;
                        case 3:
                            as.lea(AsmRegisters.rcx, AsmRegisters.r8.add(baseOffset));
                            break;
                        case 4:
                            as.lea(AsmRegisters.r8, AsmRegisters.r9.add(baseOffset));
                            break;
                        case 5:
                            as.lea(AsmRegisters.r9, AsmRegisters.rax.add(baseOffset));
                            break;
                        default:
                            throw new AssertionError();
                    }
                } else { // the rest is passed on stack
                    int adjustedStackOffset = (stackArgIndex + 1) * 8;
                    // add qword ptr [rsp-adjustedStackOffset], baseOffset
                    as.add(AsmRegisters.qword_ptr(AsmRegisters.rsp, adjustedStackOffset), baseOffset);
                    stackArgIndex++;
                }
            }
        }

        if (isVarargCall)
            as.mov(AsmRegisters.al, Math.min(xmmArgIndex, 8));

        // The argument shuffle is position-independent, so it can be assembled
        // on its own and the call/jump/inline tail decided afterwards.
        byte[] shuffle = assembleToBytes(as);

        // Auto-inline: when the target is a small verified leaf, copy its body
        // directly into the nmethod after the shuffle instead of tail-jumping
        // to it (see SmallFunctionInliner). Narrow returns are normalized in
        // place at the function's single terminal ret; ineligible functions
        // fall back to the stub paths below.
        java.nio.ByteBuffer inlineBuf = java.nio.ByteBuffer.allocate(1024)
                .order(java.nio.ByteOrder.nativeOrder());
        inlineBuf.put(shuffle, 0, shuffle.length);
        if (SmallFunctionInliner.tryInline(inlineBuf, address, "0x" + Long.toHexString(address), returnType)) {
            out.write(inlineBuf.array(), 0, inlineBuf.position());
            return;
        }

        out.write(shuffle, 0, shuffle.length);
        CodeAssembler tail = new CodeAssembler(64);
        if (normalizeReturn) {
            // Real-call bridge: the nmethod is entered with RSP%16 == 8, so
            // reserve one slot to give the System V callee its required
            // 16-byte-aligned call site, then extend AL/AX to a canonical EAX.
            tail.sub(AsmRegisters.rsp, 8);
            tail.mov(AsmRegisters.rax, address);
            tail.call(AsmRegisters.rax);
            tail.add(AsmRegisters.rsp, 8);
            CallingConventionAdapter.emitNarrowReturnNormalization(tail, returnType);
            tail.ret();
        } else {
            tail.mov(AsmRegisters.rax, address);
            tail.jmp(AsmRegisters.rax);
        }
        byte[] tailBytes = assembleToBytes(tail);
        out.write(tailBytes, 0, tailBytes.length);
    }

    private static byte[] assembleToBytes(CodeAssembler as) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final Object result = as.assemble(bytes::write, 0);
        if (result instanceof String) {
            String error = (String) result;
            throw new RuntimeException(error);
        } else if (result instanceof CodeAssemblerResult) {
            return bytes.toByteArray();
        }
        throw new AssertionError(String.format("Unexpected result type: %s", result.getClass().getName()));
    }

    private static boolean needsNativeStackArguments(Argument[] arguments) {
        int integerArgs = 0;
        int xmmArgs = 0;
        for (Argument arg : arguments) {
            if (arg.type().isPrimitive()
                    && (arg.type() == float.class || arg.type() == double.class)) {
                xmmArgs++;
            } else {
                integerArgs++;
            }
        }
        return integerArgs > 6 || xmmArgs > 8;
    }

    @Override
    public int emitNMethodBarrier(ByteArrayOutputStream out) {
        // 4-byte alignment required for atomic patching of disp8/imm32.
        int pos = out.size();
        while ((pos & 3) != 0) {
            out.write(0x90); // nop
            pos++;
        }
        // cmp dword ptr [r15 + 0], 0x00000000   (8 bytes)
        //   41         REX.B (extends r/m to r15)
        //   81 /7      cmp r/m32, imm32  (opcode-extension /7 = CMP)
        //   7F         ModR/M  mod=01, reg=/7, rm=111 -> [r15+disp8]
        //   00         disp8   (HotSpot patches this to threadDisarmedOffset)
        //   00 00 00 00 imm32  (HotSpot patches this to the armed-epoch value)
        out.write(0x41);
        out.write(0x81);
        out.write(0x7F);
        out.write(0x00);
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(0);
        return pos;
    }

}
