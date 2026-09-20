package org.elci.cli;

import org.elci.driver.ElciCompiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Creates a minimal project for the current single-entrypoint configuration. */
final class ProjectInitializer {
    private static final Set<String> PURPOSES = Set.of("spend", "mint", "function");

    private ProjectInitializer() {}

    static int run(String... args) throws IOException {
        Path target = null;
        String purpose = "spend";
        boolean purposeSet = false;
        for (int index = 0; index < args.length; index++) {
            if (args[index].equals("--purpose")) {
                if (purposeSet) throw new IllegalArgumentException("--purpose may be specified only once");
                if (++index == args.length) {
                    throw new IllegalArgumentException("--purpose requires spend, mint or function");
                }
                purpose = args[index];
                purposeSet = true;
            } else if (args[index].startsWith("--")) {
                throw new IllegalArgumentException("Unknown init option: " + args[index]);
            } else if (target == null) {
                target = Path.of(args[index]).toAbsolutePath().normalize();
            } else {
                throw new IllegalArgumentException("init accepts exactly one project directory");
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("Usage: elci init <project-directory> [--purpose spend|mint|function]");
        }
        if (!PURPOSES.contains(purpose)) {
            throw new IllegalArgumentException("Unsupported initial purpose: " + purpose + "; expected spend, mint or function");
        }
        if (Files.exists(target)) {
            if (!Files.isDirectory(target)) throw new IllegalArgumentException("Project path is not a directory: " + target);
            try (var entries = Files.list(target)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalArgumentException("Project directory is not empty: " + target);
                }
            }
        }

        String function = purpose.equals("function") ? "main" : "validate";
        Files.createDirectories(target.resolve("src"));
        Files.writeString(target.resolve("elci.toml"), configuration(function, purpose));
        Files.writeString(target.resolve("src/Main.elm"), source(purpose));
        Files.writeString(target.resolve("README.md"), readme(target.getFileName().toString(), purpose));
        Files.writeString(target.resolve(".gitignore"), "build/\n");
        System.out.println("Created Elci project at " + target + " with initial " + purpose + " entrypoint.");
        System.out.println("Run: elci check " + target);
        return 0;
    }

    private static String configuration(String function, String purpose) {
        return """
                [project]
                source-directories = ["src"]
                target = "plutus-v3-pv11-uplc-1.1.0"
                julc-version = "%s"

                [entrypoint]
                module = "Main"
                function = "%s"
                purpose = "%s"
                boundary = "julc-strict-v1"
                """.formatted(ElciCompiler.JULC_VERSION, function, purpose);
    }

    private static String source(String purpose) {
        return switch (purpose) {
            case "spend" -> """
                    module Main exposing (validate)

                    import Julc.Ledger exposing (ScriptContext)


                    validate : Int -> Int -> ScriptContext -> Bool
                    validate _ _ _ =
                        True
                    """;
            case "mint" -> """
                    module Main exposing (validate)

                    import Julc.Ledger exposing (ScriptContext)


                    validate : Int -> ScriptContext -> Bool
                    validate _ _ =
                        True
                    """;
            case "function" -> """
                    module Main exposing (main)


                    main : Int
                    main =
                        42
                    """;
            default -> throw new IllegalArgumentException("Unsupported initial purpose: " + purpose);
        };
    }

    private static String readme(String name, String purpose) {
        String next = purpose.equals("function") ? "eval" : "build";
        return """
                # %s

                This project was created by `elci init` with an initial `%s` entrypoint.
                The purpose selects the starter source and does not permanently limit the project.

                ```sh
                elci check .
                elci %s .
                ```
                """.formatted(name, purpose, next);
    }
}
