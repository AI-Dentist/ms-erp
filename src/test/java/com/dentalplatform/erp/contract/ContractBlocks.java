package com.dentalplatform.erp.contract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** Parser for the "#@ name [expect=VALID|INVALID]" block files under src/test/resources/contract. */
final class ContractBlocks {

    record Block(String name, String expectation, String document, String variablesJson) { }

    private ContractBlocks() { }

    static List<Block> load(String resource) {
        try (var in = ContractBlocks.class.getResourceAsStream("/contract/" + resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource contract/" + resource);
            }
            return parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Block> parse(String text) {
        List<Block> blocks = new ArrayList<>();
        String name = null;
        String expectation = null;
        String variables = null;
        StringBuilder document = new StringBuilder();
        for (String line : text.replace("\r\n", "\n").split("\n")) {
            if (line.startsWith("#@vars ")) {
                variables = line.substring("#@vars ".length()).trim();
            } else if (line.startsWith("#@ ")) {
                if (name != null) {
                    blocks.add(new Block(name, expectation, document.toString().strip(), variables));
                }
                String[] parts = line.substring(3).trim().split("\s+");
                name = parts[0];
                expectation = parts.length > 1 && parts[1].startsWith("expect=") ? parts[1].substring(7) : null;
                variables = null;
                document = new StringBuilder();
            } else if (name != null && !line.startsWith("#")) {
                document.append(line).append('\n');
            }
        }
        if (name != null) {
            blocks.add(new Block(name, expectation, document.toString().strip(), variables));
        }
        return blocks;
    }
}
