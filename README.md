# MembraneFFI

MembraneFFI is a library for linking Java methods to native code / functions using [JVMCI](https://openjdk.org/jeps/243).

Java methods annotated with `@Link` are installed as raw nmethods that adapt
HotSpot's Java calling convention to the native ABI and transfer directly to
the target function — no JNI transition, no thread-state switch, no argument
packing. `@InstallMachineCode` installs raw machine code per OS/architecture,
and `@VarargCall` supports C variadic functions (the System&nbsp;V `AL`
vector-count and the Win64 shadow-register duplication are handled).

Two calling-convention adapters are provided and selected automatically
(overridable via `CallingConventionOverride`):

- **LinuxX86_64CallingConvention** — the fast path for Linux/macOS x86-64:
  rotates the argument registers in place and tail-jumps to the target.
  Signatures whose arguments spill to the native stack pass through untouched
  (HotSpot's compiled Java convention and System&nbsp;V spill the same
  argument set at the same slots).
- **FramedX86_64CallingConvention** — the generic path (and the Windows
  default): queries JVMCI's own `JavaCall`/`NativeCall` convention
  descriptions, spills incoming registers to a real frame and re-marshals
  each argument to its native location, then performs a real call. Handles
  any signature, including narrow returns with native stack arguments.

### Return-value normalization

The System&nbsp;V and Win64 ABIs define only AL/AX for `boolean`, `byte`,
`short` and `char` returns — the bits above are garbage — while compiled Java
consumes a full EAX (booleans strictly 0/1). Both adapters normalize narrow
returns with the appropriate `movzx`/`movsx` after the native call returns;
the Linux adapter uses a real-call bridge for such signatures (a tail jump
cannot run code after the callee) and delegates narrow-return signatures that
also need native stack arguments to the Framed adapter. Without this,
boolean-returning natives produce corrupted and even *non-canonical* values
(`true` that fails `== true`).

### Auto-inline (x86-64 Linux/macOS)

When the target of `@Link` is a small function (≤128 bytes) consisting only
of whitelisted position-independent instructions, `SmallFunctionInliner`
copies its body directly into the nmethod after the argument shuffle,
removing the `movabs rax; jmp rax` tail and its indirect branch. The verified
decoder (adapted from the Nalim linker and validated bit-exactly against the
same fixture corpus) accepts the integer ALU core, SSE/SSE2/SSE4.1,
VEX-encoded AVX/AVX2/FMA/BMI1/BMI2, loops and forward/backward jumps, and
relocates up to eight RIP-relative constants from read-only mappings into an
in-nmethod literal pool. Anything with a call, indirect branch, EVEX
encoding or `lock` prefix stays on the tail-jump path.

Measured on a Ryzen 9 7950X3D (JDK 25, pinned core, best of 5 × 200M
direct-loop iterations): `add(int,int)` 1.203&nbsp;ns/call via the tail jump
→ **1.010&nbsp;ns/call inlined** (16% faster; Panama critical: 3.5&nbsp;ns).

Flags: `-Dmembrane.noinline=true` disables the inliner,
`-Dmembrane.trace.inline=true` logs per-symbol decisions with the bail-out
opcode.

### Running

JVMCI must be enabled: `-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI`.
Initialization works agent-less on the classpath (the JVMCI packages are
opened reflectively), or via `MembraneFFI.initialize(instrumentation)` when
an instrumentation agent is preferred. JDK 17 – 25 are supported; nmethods
carry the entry-barrier mark required since JDK 21. Note the barrier is the
non-executed trailing form: on JDK 26+ add `-XX:-UseCodeCacheFlushing` if a
linked method may sit idle for many GC cycles (cold-flushed Membrane
nmethods cannot be recompiled).

### Tests

`./gradlew test` runs the unit tests. The full correctness battery — 23
million three-way comparisons (Membrane vs `java.lang.foreign` vs trusted
Java references on the same native functions, both adapters, narrow returns
with deliberately dirty registers, mixed/stack signatures, plus the 60-
fixture bit-exact inliner corpus) — needs the native fixtures:

```
sh test-native/build.sh
./gradlew test -Dmembrane.testlibs=$(pwd)/test-native
```

The batteries also run standalone (`CorrectnessBattery`,
`InlineFixturesBattery`, `MemBench*` in `src/test`).
