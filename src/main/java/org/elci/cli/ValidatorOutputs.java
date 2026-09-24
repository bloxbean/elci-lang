package org.elci.cli;

import org.elci.abi.Abi;
import org.elci.driver.ElciCompiler;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.clientlib.ValidatorOutput;
import org.julclang.compiler.schema.ContractSchema;
import org.julclang.core.flat.UplcFlatEncoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Publication of named-validator artifacts beneath {@code build/validators/<name>/} (ADR-004).
 * Every selected group is compiled and checked before anything is written; files are then
 * replaced one by one with atomic moves. A generated-output manifest records the files Elci
 * owns, so a group removed from the configuration is cleaned up without touching other files.
 */
final class ValidatorOutputs {
    static final String MANIFEST = "elci-outputs.json";
    private static final List<String> FILES = List.of("validator.flat", "validator.plutus", "validator.plutus.json", "abi.json");
    /** Files Elci may own in a group directory: the build outputs and an applied script. */
    private static final List<String> OWNABLE = List.of("validator.flat", "validator.plutus", "validator.plutus.json",
            "abi.json", ParameterApplication.APPLIED);

    /** The complete artifact set of one group, rendered in memory. */
    record Rendered(String name, Map<String, byte[]> files, int size, String hash) {}

    private ValidatorOutputs() {}

    static Rendered render(ElciCompiler.ValidatorBuild build) {
        var program = build.program();
        byte[] flat = UplcFlatEncoder.encodeProgram(program);
        var script = JulcScriptAdapter.fromProgram(program);
        String hex = script.getCborHex();
        // Julc leaves the hash empty for a parameterized script: it changes once parameters are applied.
        String hash = build.parameters().isEmpty() ? JulcScriptAdapter.scriptHash(program) : "";
        String params = String.join(",", build.parameters().stream().map(p -> p.name() + ":" + display(p.source())).toList());
        var artifact = new ValidatorOutput("PlutusScriptV3", artifactPurpose(build), build.name(), hex, hash, params, flat.length);
        var files = new LinkedHashMap<String, byte[]>();
        files.put("validator.flat", flat);
        files.put("validator.plutus", ("{\n  \"type\": \"PlutusScriptV3\",\n  \"description\": \"Elci validator " + build.name()
                + "\",\n  \"cborHex\": \"" + hex + "\"\n}\n").getBytes(StandardCharsets.UTF_8));
        files.put("validator.plutus.json", artifact.toJson().getBytes(StandardCharsets.UTF_8));
        files.put("abi.json", Abi.validatorManifest(build).getBytes(StandardCharsets.UTF_8));
        return new Rendered(build.name(), files, flat.length, hash);
    }

    /**
     * A parameter type for Julc's {@code name:type} list, which is split on commas: short names
     * and space-separated arguments, with records shown as {@code Record}.
     */
    static String display(org.elci.types.Types.Ty type) {
        var c = (org.elci.types.Types.Con) org.elci.types.Types.prune(type);
        String name = c.name();
        if (name.startsWith("Record:")) return "Record";
        if (name.contains(".")) name = name.substring(name.lastIndexOf('.') + 1);
        var result = new StringBuilder(name);
        for (var argument : c.args()) {
            String shown = display(argument);
            result.append(' ').append(shown.contains(" ") ? "(" + shown + ")" : shown);
        }
        return result.toString();
    }

    /** Julc's artifact purpose vocabulary: {@code multi} for several handlers. */
    static String artifactPurpose(ElciCompiler.ValidatorBuild build) {
        if (build.handlers().size() > 1) return "multi";
        return switch (build.handlers().getFirst().purpose()) {
            case SPEND -> "spending";
            case MINT -> "minting";
            case WITHDRAW -> "withdraw";
            case CERTIFY -> "certifying";
            case VOTE -> "voting";
            case PROPOSE -> "proposing";
        };
    }

    /**
     * Write the rendered groups. When {@code all} is true every configured group was built, so
     * owned outputs of groups no longer configured are removed.
     */
    static void publish(Path build, List<Rendered> groups, boolean all) throws IOException {
        Path root = build.resolve("validators");
        Files.createDirectories(root);
        var owned = readManifest(root);
        // Only regular files are ever replaced: anything else at an output path stops the build
        // before a single file is written.
        for (var group : groups)
            for (String file : group.files().keySet()) {
                Path target = root.resolve(group.name()).resolve(file);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Cannot publish " + target + ": it exists and is not a regular file");
            }
        var staged = new ArrayList<Path[]>();
        try {
            for (var group : groups) {
                Path directory = root.resolve(group.name());
                Files.createDirectories(directory);
                for (var file : group.files().entrySet()) {
                    Path temporary = temporary(directory, file.getKey());
                    staged.add(new Path[] {temporary, directory.resolve(file.getKey())});
                    Files.write(temporary, file.getValue(), StandardOpenOption.CREATE_NEW);
                }
            }
        } catch (IOException | RuntimeException e) {
            for (var pair : staged) Files.deleteIfExists(pair[0]);
            throw e;
        }
        replace(staged);
        for (var group : groups) {
            // Other owned files (an applied script) derive from the previous build: remove them.
            for (String file : owned.getOrDefault(group.name(), List.of()))
                if (!FILES.contains(file)) Files.deleteIfExists(root.resolve(group.name()).resolve(file));
            owned.put(group.name(), FILES);
        }
        if (all) {
            var configured = new HashSet<String>();
            groups.forEach(g -> configured.add(g.name()));
            for (var name : List.copyOf(owned.keySet())) {
                if (configured.contains(name)) continue;
                Path directory = root.resolve(name).normalize();
                if (!directory.getParent().equals(root.normalize())) continue;
                for (String file : owned.get(name)) Files.deleteIfExists(directory.resolve(file));
                try (var rest = Files.exists(directory) ? Files.list(directory) : java.util.stream.Stream.<Path>empty()) {
                    if (rest.findAny().isEmpty()) Files.deleteIfExists(directory);
                }
                owned.remove(name);
            }
        }
        writeManifest(root, owned);
    }

    /**
     * Write an applied script. Inside the group's output directory it is owned, so the next build
     * of the group, which changes the unapplied script, removes it.
     */
    static void writeApplied(Path build, String group, Path target, String json, boolean owned) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = temporary(parent, target.getFileName().toString());
        Files.writeString(temporary, json, StandardOpenOption.CREATE_NEW);
        move(temporary, target);
        if (!owned) return;
        Path root = build.resolve("validators");
        var manifest = readManifest(root);
        var files = new ArrayList<>(manifest.getOrDefault(group, List.of()));
        if (!files.contains(ParameterApplication.APPLIED)) files.add(ParameterApplication.APPLIED);
        manifest.put(group, List.copyOf(files));
        writeManifest(root, manifest);
    }

    /**
     * Move every staged file over its target. The previous targets are kept aside until all moves
     * succeed; if one fails, the moved files are restored so no mixture of old and new remains.
     */
    private static void replace(List<Path[]> staged) throws IOException {
        var backups = new ArrayList<Path[]>();
        var placed = new ArrayList<Path>();
        try {
            for (var pair : staged) {
                if (Files.exists(pair[1], LinkOption.NOFOLLOW_LINKS)) {
                    Path backup = pair[1].resolveSibling("." + pair[1].getFileName() + "." + UUID.randomUUID() + ".old");
                    move(pair[1], backup);
                    backups.add(new Path[] {backup, pair[1]});
                }
                move(pair[0], pair[1]);
                placed.add(pair[1]);
            }
        } catch (IOException | RuntimeException e) {
            for (var target : placed) Files.deleteIfExists(target);
            for (var backup : backups) move(backup[0], backup[1]);
            for (var pair : staged) Files.deleteIfExists(pair[0]);
            throw new IOException("Publishing validator outputs failed; previous outputs were restored: " + e.getMessage(), e);
        }
        for (var backup : backups) Files.deleteIfExists(backup[0]);
    }

    /** A new file beside {@code name}, created with the default permissions of the directory. */
    private static Path temporary(Path directory, String name) {
        return directory.resolve("." + name + "." + UUID.randomUUID() + ".tmp");
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The owned files of each previously published group. */
    private static TreeMap<String, List<String>> readManifest(Path root) throws IOException {
        var result = new TreeMap<String, List<String>>();
        Path manifest = root.resolve(MANIFEST);
        if (!Files.exists(manifest)) return result;
        // The manifest is Elci's own canonical output: {"validators":{"name":["file",...]}}.
        String text = Files.readString(manifest);
        var matcher = java.util.regex.Pattern.compile("\"([a-z][a-z0-9_-]*)\":\\[([^\\]]*)\\]").matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!Project.VALIDATOR_NAME.matcher(name).matches()) continue;
            var files = new ArrayList<String>();
            for (String part : matcher.group(2).split(",")) {
                String file = part.strip().replace("\"", "");
                if (OWNABLE.contains(file)) files.add(file);
            }
            result.put(name, List.copyOf(files));
        }
        return result;
    }

    private static void writeManifest(Path root, Map<String, List<String>> owned) throws IOException {
        Path temporary = temporary(root, MANIFEST);
        Files.writeString(temporary, Abi.json(Map.of("formatVersion", 1, "validators", owned)) + "\n",
                StandardOpenOption.CREATE_NEW);
        move(temporary, root.resolve(MANIFEST));
    }

    /** One status line per group, as printed by {@code check} and {@code build}. */
    static String summary(ElciCompiler.ValidatorBuild build, int size) {
        var purposes = build.handlers().stream().map(h -> ElciCompiler.purpose(h.purpose())).toList();
        return "validator " + build.name() + " (" + String.join(", ", purposes) + "): checked; " + size + " FLAT bytes; "
                + build.target().profileId();
    }

    static boolean isPurpose(String name) {
        return Arrays.stream(ContractSchema.Purpose.values()).anyMatch(p -> ElciCompiler.purpose(p).equals(name));
    }
}
