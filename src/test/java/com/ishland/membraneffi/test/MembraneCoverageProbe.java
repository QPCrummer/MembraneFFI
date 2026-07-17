package com.ishland.membraneffi.test;

import com.ishland.membraneffi.impl.SmallFunctionInliner;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

// Decoder-coverage probe for the ported SmallFunctionInliner: for every
// symbol name in a file, report inline accept/reject. Output format matches
// the Nalim CoverageProbe so the two decoders can be diffed symbol-by-symbol
// on identical libraries.
//
// Usage: MembraneCoverageProbe <abs-lib-path> <symbols-file> [-v]
public class MembraneCoverageProbe {
    public static void main(String[] args) throws Exception {
        String lib = args[0];
        List<String> symbols = Files.readAllLines(Paths.get(args[1]));
        boolean verbose = args.length > 2 && args[2].equals("-v");

        SymbolLookup lookup = SymbolLookup.libraryLookup(Path.of(lib), Arena.global());

        int accepted = 0, total = 0;
        List<String> acceptedNames = new ArrayList<>();
        for (String symbol : symbols) {
            symbol = symbol.trim();
            if (symbol.isEmpty()) continue;
            var addr = lookup.find(symbol);
            if (addr.isEmpty()) continue;
            total++;
            ByteBuffer buf = ByteBuffer.allocate(1024).order(ByteOrder.nativeOrder());
            if (SmallFunctionInliner.tryInline(buf, addr.get().address(), symbol, long.class)) {
                accepted++;
                acceptedNames.add(symbol);
            }
        }
        System.out.println("INLINE-ACCEPTED " + accepted + " / " + total);
        if (verbose) {
            for (String s : acceptedNames) System.out.println("  " + s);
        }
    }
}
