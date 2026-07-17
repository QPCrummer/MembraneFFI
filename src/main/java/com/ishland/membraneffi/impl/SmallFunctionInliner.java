package com.ishland.membraneffi.impl;

import com.ishland.membraneffi.util.JVMCIAccess;
import com.ishland.membraneffi.util.JavaInternals;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Auto-inline for small native functions on x86-64, adapted from the Nalim
 * linker's verified decoder. When the target of a {@code @Link} method is a
 * small function (up to 128 bytes) consisting only of whitelisted,
 * position-independent instructions, its body is copied directly into the
 * nmethod after the argument shuffle — removing the {@code movabs rax; jmp
 * rax} tail and its indirect branch (~0.2 ns per call on Zen 4).
 *
 * <p>Accepted control flow: conditional branches in either direction
 * (including loop backedges) and unconditional {@code jmp rel8/rel32}, as
 * long as every target lands on a verified instruction boundary inside the
 * window. Accepted encodings: the integer ALU core, SSE/SSE2/SSE4.1 scalar
 * and packed math, the VEX-encoded AVX/AVX2/FMA/BMI1/BMI2 subset,
 * popcnt/lzcnt/tzcnt/bsf/bsr, 16-bit forms, inc/dec, bswap, shld/shrd,
 * rep movs/stos and multi-prefix alignment NOPs. Up to eight RIP-relative
 * constants are relocated from read-only mappings into a literal pool inside
 * the nmethod (aligned up to 32 bytes, gated on the JVM's
 * {@code CodeEntryAlignment}). Anything containing a call, indirect branch,
 * EVEX instruction, {@code lock} prefix, mutable RIP-relative operand or
 * unknown encoding is rejected and the caller emits the ordinary tail-call
 * stub instead.
 *
 * <p>{@code -Dmembrane.noinline=true} disables the inliner entirely;
 * {@code -Dmembrane.trace.inline=true} logs per-symbol decisions with the
 * bail-out opcode.
 */
public final class SmallFunctionInliner {

    private static final int MAX_INLINE_BYTES = 128;
    private static final int MAX_RELOCATIONS = 8;
    private static final boolean TRACE_INLINE = Boolean.getBoolean("membrane.trace.inline");
    private static final boolean NO_INLINE = Boolean.getBoolean("membrane.noinline");

    // Lazy: reading the CodeEntryAlignment flag needs the JVMCI packages
    // opened first, which normally happens in MembraneLinker's initializer —
    // but the inliner must also work when driven directly (tests, probes).
    private static final class CodeEntryAlignmentHolder {
        static final int VALUE;
        static {
            com.ishland.membraneffi.util.ModuleOpener.openJvmci();
            VALUE = JVMCIAccess.codeEntryAlignment();
        }
    }

    // Diagnostics only (linking is effectively single-threaded).
    private static volatile int inlinedMethods;

    public static int getInlinedMethodCount() {
        return inlinedMethods;
    }

    private SmallFunctionInliner() {}

    /**
     * Try to copy the body of the function at {@code address} into {@code buf}
     * (which already contains the argument shuffle). Returns false without
     * touching {@code buf}'s contents beyond its current position when the
     * function is not eligible; the caller then emits the tail-call stub.
     */
    public static boolean tryInline(ByteBuffer buf, long address, String symbol, Class<?> returnType) {
        if (NO_INLINE) {
            return false;
        }

        // Read only inside the executable/read-only mapping that contains the
        // symbol. This permits a small function to cross a page boundary (a
        // common linker placement) without ever probing an unmapped page. If
        // /proc/self/maps is unavailable, retain the conservative single-page
        // fallback.
        int window = mappedReadWindow(address, MAX_INLINE_BYTES);

        byte[] code = new byte[window];
        for (int i = 0; i < window; i++) {
            code[i] = JavaInternals.getByte(address + i);
        }

        // ── pass 1: decode and validate ─────────────────────────────────
        Insn insn = new Insn();
        long[] litSrc  = new long[MAX_RELOCATIONS];  // literal source address
        int[]  litOff  = new int[MAX_RELOCATIONS];   // disp32 field offset within body
        int[]  litNext = new int[MAX_RELOCATIONS];   // offset of next instruction (RIP base)
        int[]  litSize = new int[MAX_RELOCATIONS];   // literal byte size (1/2/4/8/16/32)
        int relocs = 0;
        int end = 0;
        boolean sawRet = false;
        int retCount = 0;
        int terminalRetOffset = -1;
        int terminalRetLength = 0;

        // Reachability walk over a verified CFG. Keeping the copy contiguous
        // preserves every internal displacement without rewriting.
        int[] owner = new int[window];
        int[] decodedLen = new int[window];
        int[] queue = new int[window];
        boolean[] queued = new boolean[window];
        Arrays.fill(owner, -1);
        int head = 0;
        int tail = 0;
        queue[tail++] = 0;
        queued[0] = true;

        while (head < tail) {
            int i = queue[head++];
            for (;;) {
                if (i < 0 || i >= window) return false;
                if (decodedLen[i] != 0) break;       // merges into a verified block
                if (owner[i] != -1) return false;    // branch into instruction middle

                int b = code[i] & 0xFF;
                int length;
                boolean terminator = false;
                boolean branch = false;
                boolean hasFallthrough = false;
                int target = -1;

                if (b == 0xC3) {
                    length = 1;
                    sawRet = true;
                    retCount++;
                    terminalRetOffset = i;
                    terminalRetLength = length;
                    terminator = true;
                } else if (b == 0xF3 && i + 1 < window && (code[i + 1] & 0xFF) == 0xC3) {
                    length = 2;
                    sawRet = true;
                    retCount++;
                    terminalRetOffset = i;
                    terminalRetLength = length;
                    terminator = true;
                } else if (b >= 0x70 && b <= 0x7F && i + 2 <= window) {
                    length = 2;
                    target = i + length + (byte) code[i + 1];
                    terminator = true;
                    branch = true;
                    hasFallthrough = true;
                } else if (b == 0x0F && i + 6 <= window
                        && (code[i + 1] & 0xFF) >= 0x80 && (code[i + 1] & 0xFF) <= 0x8F) {
                    length = 6;
                    int disp = (code[i + 2] & 0xFF)
                            | (code[i + 3] & 0xFF) << 8
                            | (code[i + 4] & 0xFF) << 16
                            | (code[i + 5] & 0xFF) << 24;
                    target = i + length + disp;
                    terminator = true;
                    branch = true;
                    hasFallthrough = true;
                } else if (b == 0xEB && i + 2 <= window) {
                    // jmp rel8 — no fallthrough
                    length = 2;
                    target = i + length + (byte) code[i + 1];
                    terminator = true;
                    branch = true;
                } else if (b == 0xE9 && i + 5 <= window) {
                    // jmp rel32 — no fallthrough
                    length = 5;
                    int disp = (code[i + 1] & 0xFF)
                            | (code[i + 2] & 0xFF) << 8
                            | (code[i + 3] & 0xFF) << 16
                            | (code[i + 4] & 0xFF) << 24;
                    target = i + length + disp;
                    terminator = true;
                    branch = true;
                } else {
                    if (!decode(code, i, window, insn)) {
                        if (TRACE_INLINE) {
                            System.out.printf("[membrane] %s: inline bail at +0x%x (opcode 0x%02x)%n", symbol, i, b);
                        }
                        return false;
                    }
                    length = insn.length;
                }

                if (i + length > window) return false;
                for (int k = i; k < i + length; k++) {
                    if (owner[k] != -1) return false;
                    owner[k] = i;
                }
                decodedLen[i] = length;
                end = Math.max(end, i + length);

                if (!terminator && insn.ripDispOff >= 0 && insn.ripLoadSize > 0) {
                    if (relocs == MAX_RELOCATIONS) return false;
                    // A literal must be alignable inside the nmethod: offsets in
                    // the buffer are only as aligned as the installed entry.
                    if (Math.min(insn.ripLoadSize, 32) > CodeEntryAlignmentHolder.VALUE) {
                        if (TRACE_INLINE) {
                            System.out.printf("[membrane] %s: %dB literal exceeds CodeEntryAlignment %d%n",
                                    symbol, insn.ripLoadSize, CodeEntryAlignmentHolder.VALUE);
                        }
                        return false;
                    }
                    int dispPos = i + insn.ripDispOff;
                    int disp = (code[dispPos] & 0xFF)
                            | (code[dispPos + 1] & 0xFF) << 8
                            | (code[dispPos + 2] & 0xFF) << 16
                            | (code[dispPos + 3] & 0xFF) << 24;
                    long literal = address + i + insn.length + disp;
                    if (!isReadOnlyRange(literal, insn.ripLoadSize)) {
                        if (TRACE_INLINE) {
                            System.out.printf("[membrane] %s: RIP operand at +0x%x is not read-only%n", symbol, i);
                        }
                        return false;
                    }
                    litSrc[relocs] = literal;
                    litOff[relocs] = dispPos;
                    litNext[relocs] = i + insn.length;
                    litSize[relocs] = insn.ripLoadSize;
                    relocs++;
                }

                if (branch) {
                    int fallthrough = i + length;
                    for (int t = 0; t < (hasFallthrough ? 2 : 1); t++) {
                        int dest = (t == 0) ? target : fallthrough;
                        if (dest < 0 || dest >= window) return false;
                        if (owner[dest] != -1) {
                            // Already-decoded destination (loop backedge or a
                            // re-converging forward branch): only a verified
                            // instruction boundary is acceptable.
                            if (decodedLen[dest] == 0) return false;
                        } else if (!queued[dest]) {
                            queued[dest] = true;
                            queue[tail++] = dest;
                        }
                    }
                    break;
                }
                if (terminator) break;
                i += length;
            }
        }
        if (!sawRet) {
            if (TRACE_INLINE) {
                System.out.printf("[membrane] %s: no ret within %d-byte window%n", symbol, window);
            }
            return false;
        }

        boolean normalizeReturn = returnType == boolean.class || returnType == byte.class
                || returnType == short.class || returnType == char.class;
        if (normalizeReturn
                && (retCount != 1 || terminalRetOffset + terminalRetLength != end)) {
            if (TRACE_INLINE) {
                System.out.printf(
                        "[membrane] %s: narrow return needs one terminal ret%n", symbol);
            }
            return false;
        }

        // ── pass 2: copy body, emit literal pool, patch disp32 fields ───
        int bodyStart = buf.position();
        if (normalizeReturn) {
            // Keep every original offset before RET stable. Any forward Jcc
            // targeting the old RET now lands on the normalizer instead.
            buf.put(code, 0, terminalRetOffset);
            emitNarrowReturnNormalization(buf, returnType);
            buf.put((byte) 0xC3);
        } else {
            buf.put(code, 0, end);
        }
        for (int r = 0; r < relocs; r++) {
            // Preserve alignment required by MOVAPS/MOVAPD/MOVDQA (and their
            // 32-byte VEX forms). The installed entry is CodeEntryAlignment-
            // aligned, so a buffer offset aligned to min(litSize, 32) stays
            // aligned after the nmethod is placed; larger requirements were
            // rejected during the decode pass.
            int alignment = Math.min(litSize[r], 32);
            while ((buf.position() & (alignment - 1)) != 0) {
                buf.put((byte) 0);
            }
            int pool = buf.position();
            for (int k = 0; k < litSize[r]; k++) {
                buf.put(JavaInternals.getByte(litSrc[r] + k));
            }
            // disp32 := pool − RIP, RIP = address of the next instruction.
            // Both live in the same nmethod, so the distance survives install.
            buf.putInt(bodyStart + litOff[r], pool - (bodyStart + litNext[r]));
        }

        inlinedMethods++;
        if (TRACE_INLINE) {
            System.out.printf(
                    "[membrane] %s: inlined %dB body, %d literal(s)%n",
                    symbol, buf.position() - bodyStart, relocs);
        }
        return true;
    }

    private static void emitNarrowReturnNormalization(ByteBuffer buf, Class<?> type) {
        if (type == boolean.class) {
            buf.put((byte) 0x0F).put((byte) 0xB6).put((byte) 0xC0);   // movzx eax, al
        } else if (type == byte.class) {
            buf.put((byte) 0x0F).put((byte) 0xBE).put((byte) 0xC0);   // movsx eax, al
        } else if (type == short.class) {
            buf.put((byte) 0x0F).put((byte) 0xBF).put((byte) 0xC0);   // movsx eax, ax
        } else if (type == char.class) {
            buf.put((byte) 0x0F).put((byte) 0xB7).put((byte) 0xC0);   // movzx eax, ax
        } else {
            throw new IllegalArgumentException("Not a narrow return type: " + type);
        }
    }

    /** Decoded form of a single whitelisted instruction. */
    private static final class Insn {
        int length;        // total encoded length in bytes
        int ripDispOff;    // offset of the RIP disp32 field within the instruction, or -1
        int ripLoadSize;   // literal bytes to copy into the pool (0 = operand never accessed)
    }

    /**
     * Decode one instruction at {@code code[off]}, accepting only leaf-safe,
     * position-independent x86-64 encodings. Memory operands must not use
     * absolute addressing; RIP-relative is reported via
     * {@code ripDispOff}/{@code ripLoadSize} and accepted only for pure loads,
     * so the caller can relocate the literal.
     */
    private static boolean decode(byte[] code, int off, int limit, Insn out) {
        out.ripDispOff = -1;
        out.ripLoadSize = 0;
        int i = off;

        int b = u(code, i, limit);
        if (b < 0) return false;

        // In 64-bit mode C4/C5 are always VEX prefixes (LES/LDS don't exist).
        if (b == 0xC4 || b == 0xC5) {
            return decodeVex(code, off, limit, out);
        }

        // Alignment padding emitted inside function bodies (e.g. before loop
        // heads): (66|2E|3E)* 90, (66|2E|3E)* 0F 1F /0, and F3 90 (pause).
        if (b == 0x66 || b == 0x2E || b == 0x3E || b == 0xF3) {
            int nopLen = paddingNopLength(code, off, limit);
            if (nopLen > 0) {
                out.length = nopLen;
                return true;
            }
        }

        // rep movs/stos — gcc/clang memcpy/memset idioms. Implicit rsi/rdi/rcx
        // operands are the function's own state; position-independent.
        if (b == 0xF3) {
            int b1 = u(code, i + 1, limit);
            int strOp = b1;
            int rexAdj = 0;
            if (b1 >= 0x48 && b1 <= 0x4F) {
                strOp = u(code, i + 2, limit);
                rexAdj = 1;
            }
            if (strOp == 0xA4 || strOp == 0xA5 || strOp == 0xAA || strOp == 0xAB) {
                out.length = 2 + rexAdj;
                return true;
            }
        }

        // One mandatory SSE prefix followed by an optional REX. 66 outside the
        // 0F map selects 16-bit operand size for a small accepted subset.
        int prefix = 0;
        if (b == 0x66 || b == 0xF2 || b == 0xF3) {
            prefix = b;
            b = u(code, ++i, limit);
            if (b < 0) return false;
        }

        // Optional REX prefix.
        int rex = 0;
        if ((b & 0xF0) == 0x40) {
            rex = b;
            b = u(code, ++i, limit);
            if (b < 0) return false;
        }
        boolean w = (rex & 8) != 0;

        int imm = 0;                 // trailing immediate bytes
        int loadSize = 0;            // RIP literal size when this op is a pure load
        boolean ripAllowed = false;
        boolean memAllowed = true;
        boolean regAllowed = true;
        boolean absOk = false;       // non-dereferencing ops may use no-base SIB
        int opLen;

        if (b == 0x0F) {
            int op = u(code, i + 1, limit);
            if (op < 0) return false;

            // Three-byte maps (SSSE3/SSE4.1): mandatory 66 prefix for the
            // accepted subset. Different tables, shared ModRM tail.
            if (op == 0x38 || op == 0x3A) {
                return decodeLegacy38or3A(code, off, limit, i, prefix, w, op, out);
            }

            opLen = 2;
            if (prefix == 0xF2 || prefix == 0xF3) {
                // Scalar SSE/SSE2: F3 = single (4B literals), F2 = double (8B).
                int sz = prefix == 0xF3 ? 4 : 8;
                switch (op) {
                    case 0x10:                                   // movss/movsd xmm, m
                        loadSize = sz; ripAllowed = true; break;
                    case 0x11:                                   // movss/movsd m, xmm — store
                        break;
                    case 0x12:                                   // movsldup (F3) / movddup (F2)
                        loadSize = prefix == 0xF3 ? 16 : 8; ripAllowed = true; break;
                    case 0x16:                                   // movshdup (F3 only)
                        if (prefix != 0xF3) return false;
                        loadSize = 16; ripAllowed = true; break;
                    case 0x2A:                                   // cvtsi2ss/sd xmm, r/m (int src)
                        loadSize = w ? 8 : 4; ripAllowed = true; break;
                    case 0x2C: case 0x2D:                        // cvt(t)ss/sd2si
                    case 0x51: case 0x58: case 0x59: case 0x5A:  // sqrt/add/mul/cvt
                    case 0x5C: case 0x5D: case 0x5E: case 0x5F:  // sub/min/div/max
                        loadSize = sz; ripAllowed = true; break;
                    case 0x5B:                                   // cvttps2dq (F3 only)
                        if (prefix != 0xF3) return false;
                        loadSize = 16; ripAllowed = true; break;
                    case 0x6F:                                   // movdqu xmm, m (F3 only)
                        if (prefix != 0xF3) return false;
                        loadSize = 16; ripAllowed = true; break;
                    case 0x70:                                   // pshufhw (F3) / pshuflw (F2)
                        imm = 1; loadSize = 16; ripAllowed = true; break;
                    case 0x7F:                                   // movdqu m, xmm — store (F3 only)
                        if (prefix != 0xF3) return false;
                        break;
                    case 0xB8: case 0xBC: case 0xBD:             // popcnt / tzcnt / lzcnt (F3 only)
                        if (prefix != 0xF3) return false;
                        loadSize = w ? 8 : 4; ripAllowed = true; break;
                    case 0xC2:                                   // cmpss/cmpsd /r, imm8
                        imm = 1; loadSize = sz; ripAllowed = true; break;
                    case 0x7E:                                   // movq xmm, m64 (F3 form only)
                        if (prefix != 0xF3) return false;
                        loadSize = 8; ripAllowed = true; break;
                    case 0xE6:                                   // cvtdq2pd (F3) / cvtpd2dq (F2)
                        loadSize = prefix == 0xF3 ? 8 : 16; ripAllowed = true; break;
                    default:
                        return false;
                }
            } else if (prefix == 0x66) {
                // Packed-double/integer SSE2. Loads of a full XMM operand are
                // relocatable 16-byte literals; store-direction encodings keep
                // non-RIP memory support but may never target the copied pool.
                switch (op) {
                    case 0x10: case 0x28: case 0x6F:              // movupd/movapd/movdqa load
                        loadSize = 16; ripAllowed = true; break;
                    case 0x11: case 0x29: case 0x7F:              // movupd/movapd/movdqa store
                        break;
                    case 0x14: case 0x15:                         // unpcklpd/unpckhpd
                    case 0x51:                                   // sqrtpd
                    case 0x54: case 0x55: case 0x56: case 0x57:  // packed bitwise
                    case 0x58: case 0x59: case 0x5A: case 0x5B:  // add/mul/convert
                    case 0x5C: case 0x5D: case 0x5E: case 0x5F:  // sub/min/div/max
                        loadSize = 16; ripAllowed = true; break;
                    case 0x2E: case 0x2F:                         // ucomisd/comisd
                        loadSize = 8; ripAllowed = true; break;
                    case 0x6E:                                   // movd/movq xmm, r/m
                        loadSize = w ? 8 : 4; ripAllowed = true; break;
                    case 0x7E: case 0xD6:                         // store direction: reg-reg only
                        memAllowed = false; break;
                    case 0xC2: case 0xC6: case 0x70:              // cmp/shuffle /r, imm8
                        imm = 1; loadSize = 16; ripAllowed = true; break;
                    // SSE2 integer core: pack/unpack, compares, arithmetic,
                    // logic and shifts — all load-direction (xmm, xmm/m128).
                    case 0x60: case 0x61: case 0x62: case 0x63:
                    case 0x64: case 0x65: case 0x66: case 0x67:
                    case 0x68: case 0x69: case 0x6A: case 0x6B:
                    case 0x6C: case 0x6D:
                    case 0x74: case 0x75: case 0x76:
                    case 0xD1: case 0xD2: case 0xD3: case 0xD4: case 0xD5:
                    case 0xD8: case 0xD9: case 0xDA: case 0xDB:
                    case 0xDC: case 0xDD: case 0xDE: case 0xDF:
                    case 0xE0: case 0xE1: case 0xE2: case 0xE3:
                    case 0xE4: case 0xE5: case 0xE6:
                    case 0xE8: case 0xE9: case 0xEA: case 0xEB:
                    case 0xEC: case 0xED: case 0xEE: case 0xEF:
                    case 0xF1: case 0xF2: case 0xF3: case 0xF4:
                    case 0xF5: case 0xF6:
                    case 0xF8: case 0xF9: case 0xFA: case 0xFB:
                    case 0xFC: case 0xFD: case 0xFE:
                        loadSize = 16; ripAllowed = true; break;
                    case 0x71: case 0x72: case 0x73:              // psll/psrl/psra imm groups
                        imm = 1; memAllowed = false; break;
                    case 0xD7:                                   // pmovmskb r, xmm — reg only
                        memAllowed = false; break;
                    default:
                        return false;
                }
            } else {
                if (op >= 0x40 && op <= 0x4F) {                  // cmovcc r, r/m
                    loadSize = w ? 8 : 4; ripAllowed = true;
                } else if (op >= 0x90 && op <= 0x9F) {           // setcc r/m8
                    loadSize = 0;
                } else if (op >= 0xC8 && op <= 0xCF) {           // bswap r — no ModRM
                    int len = (i - off) + 2;
                    if (off + len > limit) return false;
                    out.length = len;
                    return true;
                } else {
                    switch (op) {
                        case 0xAF:                               // imul r, r/m
                            loadSize = w ? 8 : 4; ripAllowed = true; break;
                        case 0xB6: case 0xBE:                    // movzx/movsx r, r/m8
                            loadSize = 1; ripAllowed = true; break;
                        case 0xB7: case 0xBF:                    // movzx/movsx r, r/m16
                            loadSize = 2; ripAllowed = true; break;
                        case 0xBC: case 0xBD:                    // bsf/bsr r, r/m
                            loadSize = w ? 8 : 4; ripAllowed = true; break;
                        case 0x1F:                               // multi-byte NOP — operand never accessed
                            ripAllowed = true; absOk = true; break;
                        case 0x2E: case 0x2F:                    // ucomiss/comiss xmm, xmm/m32
                            loadSize = 4; ripAllowed = true; break;
                        case 0x10: case 0x28:                    // movups/movaps load
                        case 0x14: case 0x15:                    // unpcklps/unpckhps
                        case 0x51:                              // sqrtps
                        case 0x54: case 0x55: case 0x56: case 0x57:
                        case 0x58: case 0x59:                    // packed add/mul
                        case 0x5B:                              // cvtdq2ps
                        case 0x5C: case 0x5D: case 0x5E: case 0x5F:
                            loadSize = 16; ripAllowed = true; break;
                        case 0x5A:                              // cvtps2pd reads two floats
                            loadSize = 8; ripAllowed = true; break;
                        case 0x12: case 0x16:                    // movlps/movhps m64 load (reg form: movhlps/movlhps)
                            loadSize = 8; ripAllowed = true; break;
                        case 0x11: case 0x29:                    // movups/movaps store
                        case 0x13: case 0x17:                    // movlps/movhps store
                            break;
                        case 0x50:                              // movmskps r, xmm — reg only
                            memAllowed = false; break;
                        case 0xA4: case 0xAC:                    // shld/shrd r/m, r, imm8 — RMW
                            imm = 1; break;
                        case 0xA5: case 0xAD:                    // shld/shrd r/m, r, cl — RMW
                            break;
                        case 0xC2: case 0xC6:                    // cmpps/shufps /r, imm8
                            imm = 1; loadSize = 16; ripAllowed = true; break;
                        default:
                            return false;
                    }
                }
            }
        } else {
            opLen = 1;

            if (prefix == 0x66) {
                // 16-bit operand-size forms: plain ALU/mov/test on i16 values.
                // Immediates shrink to imm16 for the 81/C7/acc-imm forms.
                if (b == 0x05 || b == 0x0D || b == 0x15 || b == 0x1D
                        || b == 0x25 || b == 0x2D || b == 0x35 || b == 0x3D
                        || b == 0xA9) {
                    int len = (i - off) + 3;                     // opcode + imm16, no ModRM
                    if (off + len > limit) return false;
                    out.length = len;
                    return true;
                }
                switch (b) {
                    case 0x01: case 0x03: case 0x09: case 0x0B:
                    case 0x21: case 0x23: case 0x29: case 0x2B:
                    case 0x31: case 0x33: case 0x39: case 0x3B:
                    case 0x85: case 0x89: case 0x8B:
                        if ((b & 2) != 0 || b == 0x8B) {         // load direction
                            loadSize = 2; ripAllowed = true;
                        }
                        break;
                    case 0x83: imm = 1; break;                   // ALU r/m16, imm8
                    case 0x81: imm = 2; break;                   // ALU r/m16, imm16
                    case 0xC7: {                                 // mov r/m16, imm16 (/0 only)
                        int mr = u(code, i + 1, limit);
                        if (mr < 0 || (mr & 0x38) != 0) return false;
                        imm = 2;
                        break;
                    }
                    default:
                        return false;
                }
            } else if (prefix != 0) {
                return false;                                    // F2/F3 outside 0F map
            } else {

            // ModRM-less opcodes.
            if (b == 0x90 || b == 0x98 || b == 0x99              // nop / cdqe / cqo
                    || (b >= 0x50 && b <= 0x5F)) {               // push/pop r64
                int len = (i - off) + 1;
                if (off + len > limit) return false;
                out.length = len;
                return true;
            }
            if (b >= 0xB8 && b <= 0xBF) {                        // mov r, imm32 / movabs r, imm64
                int len = (i - off) + 1 + (w ? 8 : 4);
                if (off + len > limit) return false;
                out.length = len;
                return true;
            }
            // ALU/test accumulator immediates have no ModRM byte.
            if (b == 0x04 || b == 0x0C || b == 0x14 || b == 0x1C
                    || b == 0x24 || b == 0x2C || b == 0x34 || b == 0x3C
                    || b == 0xA8) {
                int len = (i - off) + 2;
                if (off + len > limit) return false;
                out.length = len;
                return true;
            }
            if (b == 0x05 || b == 0x0D || b == 0x15 || b == 0x1D
                    || b == 0x25 || b == 0x2D || b == 0x35 || b == 0x3D
                    || b == 0xA9) {
                int len = (i - off) + 5;
                if (off + len > limit) return false;
                out.length = len;
                return true;
            }

            switch (b) {
                // ALU/mov/test, store or read-modify-write direction (m, r):
                // memory form ok, RIP form not (would alias a shared global).
                case 0x00: case 0x01: case 0x08: case 0x09:      // add / or
                case 0x20: case 0x21: case 0x28: case 0x29:      // and / sub
                case 0x30: case 0x31: case 0x38: case 0x39:      // xor / cmp
                case 0x84: case 0x85:                            // test
                case 0x88: case 0x89:                            // mov
                    break;
                // ALU/mov, load direction (r, m): RIP literal relocatable.
                case 0x02: case 0x03: case 0x0A: case 0x0B:
                case 0x22: case 0x23: case 0x2A: case 0x2B:
                case 0x32: case 0x33: case 0x3A: case 0x3B:
                case 0x8A: case 0x8B:
                    loadSize = (b & 1) == 0 ? 1 : (w ? 8 : 4);
                    ripAllowed = true;
                    break;
                case 0x63:                                       // movsxd r64, r/m32
                    loadSize = 4; ripAllowed = true; break;
                case 0x8D:                                       // lea — memory form only, no RIP
                    regAllowed = false; absOk = true; break;
                case 0x69:                                       // imul r, r/m, imm32
                    imm = 4; loadSize = w ? 8 : 4; ripAllowed = true; break;
                case 0x6B:                                       // imul r, r/m, imm8
                    imm = 1; loadSize = w ? 8 : 4; ripAllowed = true; break;
                case 0x80: case 0x83: imm = 1; break;            // ALU r/m, imm8
                case 0x81: imm = 4; break;                       // ALU r/m, imm32
                case 0xC0: case 0xC1: imm = 1; break;            // shift/rotate r/m, imm8
                case 0xD0: case 0xD1: case 0xD2: case 0xD3: break; // shift r/m, 1 / cl
                case 0xC6: case 0xC7: {                          // mov r/m, imm
                    // Only ModRM extension /0 is MOV. /7 encodes XABORT
                    // (C6 F8 ib) or XBEGIN (C7 F8 rel32), both control flow.
                    int mr = u(code, i + 1, limit);
                    if (mr < 0 || (mr & 0x38) != 0) return false;
                    imm = b == 0xC6 ? 1 : 4;
                    break;
                }
                case 0xF6: case 0xF7: {                          // grp3: test/not/neg/mul/imul/div/idiv
                    int mr = u(code, i + 1, limit);
                    if (mr < 0) return false;
                    int ext = (mr >> 3) & 7;
                    if (ext == 0 || ext == 1) {                  // TEST r/m, imm
                        imm = (b == 0xF6) ? 1 : 4;
                    }
                    break;
                }
                case 0xFE: case 0xFF: {                          // grp4/5: inc/dec only
                    int mr = u(code, i + 1, limit);
                    if (mr < 0) return false;
                    if (((mr >> 3) & 7) > 1) return false;       // /2../7: call/jmp/push
                    break;
                }
                default:
                    return false;
            }
            }
        }

        return finishModrm(code, off, limit, i + opLen, imm, loadSize,
                ripAllowed, memAllowed, regAllowed, absOk, out);
    }

    /**
     * VEX-encoded subset: AVX/AVX2 counterparts of the whitelisted SSE ops,
     * FMA (map 0F38 0x96-0xBF), vround/vblend/vinsert (map 0F3A), broadcasts,
     * BMI1/BMI2 GPR ops and vzeroupper. EVEX (AVX-512) is not decoded — such
     * bodies stay on the stub path.
     */
    private static boolean decodeVex(byte[] code, int off, int limit, Insn out) {
        int i = off;
        int b0 = u(code, i, limit);
        int map;
        int pp;                     // 0=none, 1=66, 2=F3, 3=F2
        boolean w = false;
        int L;
        int opPos;
        if (b0 == 0xC5) {
            int b1 = u(code, i + 1, limit);
            if (b1 < 0) return false;
            map = 1;
            L = (b1 >> 2) & 1;
            pp = b1 & 3;
            opPos = i + 2;
        } else {
            int b1 = u(code, i + 1, limit);
            int b2 = u(code, i + 2, limit);
            if (b1 < 0 || b2 < 0) return false;
            map = b1 & 0x1F;
            w = (b2 & 0x80) != 0;
            L = (b2 >> 2) & 1;
            pp = b2 & 3;
            opPos = i + 3;
        }
        int op = u(code, opPos, limit);
        if (op < 0) return false;
        int vec = L == 1 ? 32 : 16;

        int imm = 0;
        int loadSize = 0;
        boolean ripAllowed = false;
        boolean memAllowed = true;
        boolean regAllowed = true;

        if (map == 1) {
            if (op == 0x77) {                                    // vzeroupper / vzeroall — no ModRM
                int len = (opPos + 1) - off;
                if (off + len > limit) return false;
                out.length = len;
                return true;
            }
            if (pp == 2 || pp == 3) {
                // Scalar (and a few F3/F2-prefixed vector) ops.
                int sz = pp == 2 ? 4 : 8;
                switch (op) {
                    case 0x10: loadSize = sz; ripAllowed = true; break;
                    case 0x11: break;
                    case 0x12: loadSize = pp == 2 ? vec : (L == 1 ? 32 : 8); ripAllowed = true; break;
                    case 0x16:
                        if (pp != 2) return false;
                        loadSize = vec; ripAllowed = true; break;
                    case 0x2A: loadSize = w ? 8 : 4; ripAllowed = true; break;
                    case 0x2C: case 0x2D:
                    case 0x51: case 0x58: case 0x59: case 0x5A:
                    case 0x5C: case 0x5D: case 0x5E: case 0x5F:
                        loadSize = sz; ripAllowed = true; break;
                    case 0x5B:
                        if (pp != 2) return false;               // vcvttps2dq
                        loadSize = vec; ripAllowed = true; break;
                    case 0x6F:                                   // vmovdqu load (F3)
                        if (pp != 2) return false;
                        loadSize = vec; ripAllowed = true; break;
                    case 0x70:                                   // vpshufhw/lw
                        imm = 1; loadSize = vec; ripAllowed = true; break;
                    case 0x7E:                                   // vmovq xmm, m64 (F3)
                        if (pp != 2) return false;
                        loadSize = 8; ripAllowed = true; break;
                    case 0x7F:                                   // vmovdqu store (F3)
                        if (pp != 2) return false;
                        break;
                    case 0xC2: imm = 1; loadSize = sz; ripAllowed = true; break;
                    case 0xE6: loadSize = pp == 2 ? vec / 2 : vec; ripAllowed = true; break;
                    default:
                        return false;
                }
            } else if (pp == 1) {
                switch (op) {
                    case 0x10: case 0x28: case 0x6F:
                        loadSize = vec; ripAllowed = true; break;
                    case 0x11: case 0x29: case 0x7F:
                        break;
                    case 0x14: case 0x15: case 0x51:
                    case 0x54: case 0x55: case 0x56: case 0x57:
                    case 0x58: case 0x59: case 0x5B:
                    case 0x5C: case 0x5D: case 0x5E: case 0x5F:
                        loadSize = vec; ripAllowed = true; break;
                    case 0x5A:                                   // vcvtpd2ps
                        loadSize = vec; ripAllowed = true; break;
                    case 0x2E: case 0x2F:
                        loadSize = 8; ripAllowed = true; break;
                    case 0x6E:                                   // vmovd/vmovq xmm, r/m
                        loadSize = w ? 8 : 4; ripAllowed = true; break;
                    case 0x7E: case 0xD6:                        // store direction
                        break;
                    case 0xC2: case 0xC6: case 0x70:
                        imm = 1; loadSize = vec; ripAllowed = true; break;
                    case 0x60: case 0x61: case 0x62: case 0x63:
                    case 0x64: case 0x65: case 0x66: case 0x67:
                    case 0x68: case 0x69: case 0x6A: case 0x6B:
                    case 0x6C: case 0x6D:
                    case 0x74: case 0x75: case 0x76:
                    case 0xD1: case 0xD2: case 0xD3: case 0xD4: case 0xD5:
                    case 0xD8: case 0xD9: case 0xDA: case 0xDB:
                    case 0xDC: case 0xDD: case 0xDE: case 0xDF:
                    case 0xE0: case 0xE1: case 0xE2: case 0xE3:
                    case 0xE4: case 0xE5: case 0xE6:
                    case 0xE8: case 0xE9: case 0xEA: case 0xEB:
                    case 0xEC: case 0xED: case 0xEE: case 0xEF:
                    case 0xF1: case 0xF2: case 0xF3: case 0xF4:
                    case 0xF5: case 0xF6:
                    case 0xF8: case 0xF9: case 0xFA: case 0xFB:
                    case 0xFC: case 0xFD: case 0xFE:
                        loadSize = vec; ripAllowed = true; break;
                    case 0x71: case 0x72: case 0x73:
                        imm = 1; memAllowed = false; break;
                    case 0xD7:
                        memAllowed = false; break;
                    default:
                        return false;
                }
            } else {
                switch (op) {
                    case 0x10: case 0x28:
                        loadSize = vec; ripAllowed = true; break;
                    case 0x11: case 0x29:
                        break;
                    case 0x14: case 0x15: case 0x51:
                    case 0x54: case 0x55: case 0x56: case 0x57:
                    case 0x58: case 0x59: case 0x5B:
                    case 0x5C: case 0x5D: case 0x5E: case 0x5F:
                        loadSize = vec; ripAllowed = true; break;
                    case 0x5A:                                   // vcvtps2pd reads half width
                        loadSize = vec / 2; ripAllowed = true; break;
                    case 0x2E: case 0x2F:
                        loadSize = 4; ripAllowed = true; break;
                    case 0x12: case 0x16:                        // vmovlps/vmovhps m64
                        loadSize = 8; ripAllowed = true; break;
                    case 0x13: case 0x17:
                        break;
                    case 0x50:
                        memAllowed = false; break;
                    case 0xC2: case 0xC6:
                        imm = 1; loadSize = vec; ripAllowed = true; break;
                    default:
                        return false;
                }
            }
        } else if (map == 2) {
            boolean isFma = (op >= 0x96 && op <= 0x9F)
                    || (op >= 0xA6 && op <= 0xAF)
                    || (op >= 0xB6 && op <= 0xBF);
            if (isFma) {
                if (pp != 1) return false;
                int lo = op & 0xF;
                boolean scalar = lo == 0x9 || lo == 0xB || lo == 0xD || lo == 0xF;
                loadSize = scalar ? (w ? 8 : 4) : vec;
                ripAllowed = true;
            } else {
                switch (op) {
                    case 0x18: loadSize = 4; ripAllowed = true; break;   // vbroadcastss
                    case 0x19: loadSize = 8; ripAllowed = true; break;   // vbroadcastsd
                    case 0x1A: loadSize = 16; ripAllowed = true; break;  // vbroadcastf128
                    case 0x58: loadSize = 4; ripAllowed = true; break;   // vpbroadcastd
                    case 0x59: loadSize = 8; ripAllowed = true; break;   // vpbroadcastq
                    case 0x78: loadSize = 1; ripAllowed = true; break;   // vpbroadcastb
                    case 0x79: loadSize = 2; ripAllowed = true; break;   // vpbroadcastw
                    case 0x00:                                           // vpshufb
                    case 0x17:                                           // vptest
                    case 0x1C: case 0x1D: case 0x1E:                     // vpabsb/w/d
                    case 0x28: case 0x29: case 0x2B:                     // vpmuldq/vpcmpeqq/vpackusdw
                    case 0x37:                                           // vpcmpgtq
                    case 0x38: case 0x39: case 0x3A: case 0x3B:          // vpmins*/vpminu*
                    case 0x3C: case 0x3D: case 0x3E: case 0x3F:          // vpmaxs*/vpmaxu*
                    case 0x40:                                           // vpmulld
                    case 0x45: case 0x46: case 0x47:                     // vpsrlv/vpsrav/vpsllv
                        loadSize = vec; ripAllowed = true; break;
                    case 0x20: case 0x23: case 0x25:                     // vpmovsxbw/wd/dq
                    case 0x30: case 0x33: case 0x35:                     // vpmovzx
                        loadSize = vec / 2; ripAllowed = true; break;
                    case 0x21: case 0x24: case 0x31: case 0x34:          // vpmovs/zxbd/wq
                        loadSize = vec / 4; ripAllowed = true; break;
                    case 0x22: case 0x32:                                // vpmovs/zxbq
                        loadSize = vec / 8; ripAllowed = true; break;
                    // BMI1/BMI2 on GPRs.
                    case 0xF2:                                           // andn
                        if (pp != 0) return false;
                        loadSize = w ? 8 : 4; ripAllowed = true; break;
                    case 0xF3: {                                         // blsr/blsmsk/blsi (/1,/2,/3)
                        if (pp != 0) return false;
                        int mr = u(code, opPos + 1, limit);
                        if (mr < 0) return false;
                        int ext = (mr >> 3) & 7;
                        if (ext < 1 || ext > 3) return false;
                        loadSize = w ? 8 : 4; ripAllowed = true; break;
                    }
                    case 0xF5:                                           // bzhi/pext/pdep
                    case 0xF7:                                           // bextr/shlx/sarx/shrx
                        loadSize = w ? 8 : 4; ripAllowed = true; break;
                    case 0xF6:                                           // mulx (F2 only)
                        if (pp != 3) return false;
                        loadSize = w ? 8 : 4; ripAllowed = true; break;
                    default:
                        return false;
                }
            }
        } else if (map == 3) {
            imm = 1;                                             // all accepted 0F3A ops carry imm8
            switch (op) {
                case 0x00: case 0x01:                            // vpermq/vpermpd
                    loadSize = 32; ripAllowed = true; break;
                case 0x02:                                       // vpblendd
                case 0x06:                                       // vperm2f128
                case 0x46:                                       // vperm2i128
                case 0x08: case 0x09:                            // vroundps/pd
                case 0x0C: case 0x0D: case 0x0E: case 0x0F:      // vblendps/pd, vpblendw, vpalignr
                case 0x4A: case 0x4B: case 0x4C:                 // vblendvps/pd, vpblendvb
                    loadSize = vec; ripAllowed = true; break;
                case 0x0A: loadSize = 4; ripAllowed = true; break;   // vroundss
                case 0x0B: loadSize = 8; ripAllowed = true; break;   // vroundsd
                case 0x18: case 0x38:                            // vinsertf128/vinserti128
                    loadSize = 16; ripAllowed = true; break;
                case 0x19: case 0x39:                            // vextractf128/i128 — store dir
                    break;
                case 0x14: case 0x15: case 0x16: case 0x17:      // vpextr/vextractps — store dir
                    break;
                case 0x20: loadSize = 1; ripAllowed = true; break;   // vpinsrb
                case 0x21: loadSize = 4; ripAllowed = true; break;   // vinsertps
                case 0x22: loadSize = w ? 8 : 4; ripAllowed = true; break; // vpinsrd/q
                case 0xF0:                                       // rorx (F2 only)
                    if (pp != 3) return false;
                    loadSize = w ? 8 : 4; ripAllowed = true; break;
                default:
                    return false;
            }
        } else {
            return false;
        }

        return finishModrm(code, off, limit, opPos + 1, imm, loadSize,
                ripAllowed, memAllowed, regAllowed, false, out);
    }

    /**
     * Legacy three-byte maps 0F 38 / 0F 3A — the 66-prefixed SSSE3/SSE4.1
     * subset mirroring the VEX tables (round, blend, pmovsx/zx, pmulld, ...).
     */
    private static boolean decodeLegacy38or3A(byte[] code, int off, int limit,
                                              int pos0F, int prefix, boolean w,
                                              int mapByte, Insn out) {
        if (prefix != 0x66) return false;
        int op = u(code, pos0F + 2, limit);
        if (op < 0) return false;

        int imm = 0;
        int loadSize = 0;
        boolean ripAllowed = false;
        boolean memAllowed = true;

        if (mapByte == 0x38) {
            switch (op) {
                case 0x00:                                       // pshufb
                case 0x17:                                       // ptest
                case 0x1C: case 0x1D: case 0x1E:                 // pabs
                case 0x28: case 0x29: case 0x2B:                 // pmuldq/pcmpeqq/packusdw
                case 0x37:                                       // pcmpgtq
                case 0x38: case 0x39: case 0x3A: case 0x3B:
                case 0x3C: case 0x3D: case 0x3E: case 0x3F:      // pmin/pmax families
                case 0x40:                                       // pmulld
                    loadSize = 16; ripAllowed = true; break;
                case 0x20: case 0x23: case 0x25:
                case 0x30: case 0x33: case 0x35:
                    loadSize = 8; ripAllowed = true; break;
                case 0x21: case 0x24: case 0x31: case 0x34:
                    loadSize = 4; ripAllowed = true; break;
                case 0x22: case 0x32:
                    loadSize = 2; ripAllowed = true; break;
                default:
                    return false;
            }
        } else {
            imm = 1;
            switch (op) {
                case 0x08: case 0x09:                            // roundps/pd
                case 0x0C: case 0x0D: case 0x0E: case 0x0F:      // blendps/pd, pblendw, palignr
                    loadSize = 16; ripAllowed = true; break;
                case 0x0A: loadSize = 4; ripAllowed = true; break;   // roundss
                case 0x0B: loadSize = 8; ripAllowed = true; break;   // roundsd
                case 0x14: case 0x15: case 0x16: case 0x17:      // pextr/extractps — store dir
                    break;
                case 0x20: loadSize = 1; ripAllowed = true; break;   // pinsrb
                case 0x21: loadSize = 4; ripAllowed = true; break;   // insertps
                case 0x22: loadSize = w ? 8 : 4; ripAllowed = true; break; // pinsrd/q
                default:
                    return false;
            }
        }

        return finishModrm(code, off, limit, pos0F + 3, imm, loadSize,
                ripAllowed, memAllowed, true, false, out);
    }

    /**
     * Length of an alignment-padding NOP at {@code off}: {@code (66|2E|3E)* 90},
     * {@code (66|2E|3E)* 0F 1F /0} or {@code F3 90} (pause). Returns 0 when the
     * bytes are not such a form.
     */
    private static int paddingNopLength(byte[] code, int off, int limit) {
        int p = off;
        int c = u(code, p, limit);
        if (c == 0xF3) {
            return u(code, p + 1, limit) == 0x90 ? 2 : 0;
        }
        while (c == 0x66 || c == 0x2E || c == 0x3E) {
            c = u(code, ++p, limit);
        }
        if (p == off) return 0;
        if (c == 0x90) {
            return p + 1 - off;
        }
        if (c == 0x0F && u(code, p + 1, limit) == 0x1F) {
            Insn tmp = new Insn();
            if (!finishModrm(code, off, limit, p + 2, 0, 0, true, true, true, true, tmp)) {
                return 0;
            }
            return tmp.length;
        }
        return 0;
    }

    /** ModRM (+ SIB / disp / imm) processing shared by every opcode map. */
    private static boolean finishModrm(byte[] code, int off, int limit, int mrPos,
                                       int imm, int loadSize, boolean ripAllowed,
                                       boolean memAllowed, boolean regAllowed,
                                       boolean absOk, Insn out) {
        int modrm = u(code, mrPos, limit);
        if (modrm < 0) return false;
        int mod = modrm >>> 6;
        int rm = modrm & 7;
        int tail;
        if (mod == 3) {
            if (!regAllowed) return false;
            tail = 1;
        } else {
            if (!memAllowed) return false;
            if (mod == 0 && rm == 5) {                           // RIP-relative
                if (!ripAllowed) return false;
                out.ripDispOff = (mrPos + 1) - off;
                out.ripLoadSize = loadSize;
                tail = 5;
            } else if (rm == 4) {                                // SIB byte
                int sib = u(code, mrPos + 1, limit);
                if (sib < 0) return false;
                if (mod == 0 && (sib & 7) == 5) {
                    // disp32(+index) with no base register: absolute
                    // addressing for real memory operands. Allowed only for
                    // operations that never dereference (LEA, NOP), where the
                    // form is pure position-independent arithmetic like
                    // lea r, [disp32 + index*scale]. Carries a disp32.
                    if (!absOk) return false;
                    tail = 2 + 4;
                } else {
                    tail = 2 + (mod == 1 ? 1 : mod == 2 ? 4 : 0);
                }
            } else {
                tail = 1 + (mod == 1 ? 1 : mod == 2 ? 4 : 0);
            }
        }
        int len = (mrPos - off) + tail + imm;
        if (off + len > limit) return false;
        out.length = len;
        return true;
    }

    private static int u(byte[] code, int idx, int limit) {
        return idx < limit ? code[idx] & 0xFF : -1;
    }

    // Read-only mappings of this process (/proc/self/maps), used to decide
    // whether a RIP-relative load targets an immutable literal. Membrane does
    // not own library loading, so instead of an invalidation hook the cache is
    // re-parsed once whenever an address is not covered by any known mapping
    // (a newly loaded library).
    private static volatile long[][] readOnlyRanges;

    private static boolean isReadOnlyRange(long addr, int size) {
        long[][] ranges = readOnlyRanges();
        long end = addr + size;
        if (Long.compareUnsigned(end, addr) < 0) {
            return false;
        }
        for (long[] range : ranges) {
            if (Long.compareUnsigned(addr, range[0]) >= 0 && Long.compareUnsigned(end, range[1]) <= 0) {
                return true;
            }
        }
        return false;
    }

    private static int mappedReadWindow(long addr, int maximum) {
        for (int attempt = 0; attempt < 2; attempt++) {
            for (long[] range : readOnlyRanges()) {
                if (Long.compareUnsigned(addr, range[0]) >= 0
                        && Long.compareUnsigned(addr, range[1]) < 0) {
                    long available = range[1] - addr;
                    return (int) Math.min(maximum, available);
                }
            }
            // Unknown address — a library may have been loaded since the last
            // parse. Refresh once, then fall back to the single-page window.
            synchronized (SmallFunctionInliner.class) {
                readOnlyRanges = null;
            }
        }
        return (int) Math.min(maximum, ((addr | 4095) + 1) - addr);
    }

    private static long[][] readOnlyRanges() {
        long[][] ranges = readOnlyRanges;
        if (ranges == null) {
            synchronized (SmallFunctionInliner.class) {
                ranges = readOnlyRanges;
                if (ranges == null) {
                    readOnlyRanges = ranges = parseReadOnlyMaps();
                }
            }
        }
        return ranges;
    }

    private static long[][] parseReadOnlyMaps() {
        // Linux only. Anywhere the file is missing/unreadable we return an
        // empty set, so RIP literals are never relocated — stub fallback.
        try {
            List<long[]> list = new ArrayList<>();
            for (String line : Files.readAllLines(Paths.get("/proc/self/maps"))) {
                int sp = line.indexOf(' ');
                if (sp < 0 || sp + 3 >= line.length()) continue;
                if (line.charAt(sp + 1) != 'r' || line.charAt(sp + 2) == 'w') continue;
                int dash = line.indexOf('-');
                if (dash < 0 || dash >= sp) continue;
                list.add(new long[] {
                        Long.parseUnsignedLong(line.substring(0, dash), 16),
                        Long.parseUnsignedLong(line.substring(dash + 1, sp), 16)
                });
            }
            return list.toArray(new long[0][]);
        } catch (Throwable t) {
            return new long[0][];
        }
    }
}
