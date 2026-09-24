package org.elci.cli;

import org.elci.driver.ElciCompiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Creates a minimal project. Without {@code --validator} the project uses the single
 * {@code [entrypoint]} form; with it, a {@code [validators.<name>]} group whose first handler
 * has the chosen purpose. The purpose only selects the starter source.
 */
final class ProjectInitializer {
    private static final Set<String> ENTRYPOINT_PURPOSES = Set.of("spend", "mint", "function");
    private static final List<String> HANDLER_PURPOSES = List.of("spend", "mint", "withdraw", "certify", "vote", "propose");

    private ProjectInitializer() {}

    static int run(String... args) throws IOException {
        Path target = null;
        String purpose = null;
        String validator = null;
        for (int index = 0; index < args.length; index++) {
            if (args[index].equals("--purpose")) {
                if (purpose != null) throw new IllegalArgumentException("--purpose may be specified only once");
                if (++index == args.length) {
                    throw new IllegalArgumentException("--purpose requires spend, mint, withdraw, certify, vote, propose or function");
                }
                purpose = args[index];
            } else if (args[index].equals("--validator")) {
                if (validator != null) throw new IllegalArgumentException("--validator may be specified only once");
                if (++index == args.length) throw new IllegalArgumentException("--validator requires a validator name");
                validator = args[index];
            } else if (args[index].startsWith("--")) {
                throw new IllegalArgumentException("Unknown init option: " + args[index]);
            } else if (target == null) {
                target = Path.of(args[index]).toAbsolutePath().normalize();
            } else {
                throw new IllegalArgumentException("init accepts exactly one project directory");
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("Usage: elci init <project-directory> [--purpose spend|mint|function] "
                    + "[--validator <name> [--purpose spend|mint|withdraw|certify|vote|propose]]");
        }
        if (purpose == null) purpose = "spend";
        if (validator == null) {
            if (HANDLER_PURPOSES.contains(purpose) && !ENTRYPOINT_PURPOSES.contains(purpose))
                throw new IllegalArgumentException("The " + purpose + " purpose requires a named validator; add --validator <name>");
            if (!ENTRYPOINT_PURPOSES.contains(purpose))
                throw new IllegalArgumentException("Unsupported initial purpose: " + purpose + "; expected spend, mint or function");
        } else {
            if (!Project.VALIDATOR_NAME.matcher(validator).matches())
                throw new IllegalArgumentException("Invalid validator name '" + validator
                        + "'; use lowercase letters, digits, '-' or '_', starting with a letter");
            if (!HANDLER_PURPOSES.contains(purpose))
                throw new IllegalArgumentException("Unsupported handler purpose: " + purpose
                        + "; expected spend, mint, withdraw, certify, vote or propose");
        }
        if (Files.exists(target)) {
            if (!Files.isDirectory(target)) throw new IllegalArgumentException("Project path is not a directory: " + target);
            try (var entries = Files.list(target)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalArgumentException("Project directory is not empty: " + target);
                }
            }
        }

        Files.createDirectories(target.resolve("src"));
        if (validator == null) {
            String function = purpose.equals("function") ? "main" : "validate";
            Files.writeString(target.resolve("elci.toml"), configuration(function, purpose));
            Files.writeString(target.resolve("src/Main.elm"), source(purpose));
        } else {
            Files.writeString(target.resolve("elci.toml"), validatorConfiguration(validator, purpose));
            Files.writeString(target.resolve("src/Main.elm"), handlerSource(purpose));
            Files.createDirectories(target.resolve("tests"));
            Files.writeString(target.resolve("tests/MainTest.elm"), testSource(validator, purpose));
        }
        Files.writeString(target.resolve("README.md"), readme(target.getFileName().toString(), purpose, validator));
        Files.writeString(target.resolve(".gitignore"), "build/\n");
        System.out.println("Created Elci project at " + target + " with initial "
                + (validator == null ? purpose + " entrypoint." : purpose + " handler in validator " + validator + "."));
        System.out.println("Run: elci check " + target);
        return 0;
    }

    private static String header() {
        return """
                [project]
                source-directories = ["src"]
                target = "plutus-v3-pv11-uplc-1.1.0"
                julc-version = "%s"
                """.formatted(ElciCompiler.JULC_VERSION);
    }

    private static String configuration(String function, String purpose) {
        return header() + """

                [entrypoint]
                module = "Main"
                function = "%s"
                purpose = "%s"
                boundary = "julc-strict-v1"
                """.formatted(function, purpose);
    }

    private static String validatorConfiguration(String validator, String purpose) {
        return header() + """

                # One deployed script. Add handler keys (spend, mint, withdraw, certify, vote,
                # propose) to let this script serve more purposes; add more [validators.<name>]
                # tables for independent scripts.
                [validators.%s]
                boundary = "julc-strict-v1"
                parameters = ["owner"]
                %s = "Main.%s"
                """.formatted(validator, purpose, purpose);
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

    /**
     * A handler that accepts only transactions signed by the {@code owner} deployment parameter,
     * rather than a placeholder that accepts everything.
     */
    private static String handlerSource(String purpose) {
        boolean spend = purpose.equals("spend");
        return """
                module Main exposing (%s)

                import Julc.Ledger exposing (PubKeyHash, ScriptContext)


                {-| Accept only transactions signed by the owner, a deployment parameter.
                -}
                %s : PubKeyHash -> %s() -> ScriptContext -> Bool
                %s owner %s_ ctx =
                    List.any (\\signer -> signer == owner) ctx.txInfo.signatories
                """.formatted(purpose, purpose, spend ? "() -> " : "", purpose, spend ? "_ " : "");
    }

    /**
     * Two scenarios for the owner-signature handler: the owner's transaction passes and anyone
     * else's is rejected. Helpers stay unexposed, so only the scenarios are tests.
     */
    private static String testSource(String validator, String purpose) {
        String transaction = switch (purpose) {
            case "spend" -> "Scenario.spend \"%s\" (Scenario.outRef \"%s\" 0) Scenario.unit".formatted(validator, validator);
            case "mint" -> "Scenario.mint \"%s\"".formatted(validator);
            case "withdraw" -> "Scenario.withdraw \"%s\"".formatted(validator);
            case "certify" -> "Scenario.certify \"%s\" 0 (Ledger.RegStaking (Ledger.PubKeyCredential owner) (Data.toOptional Nothing))"
                    .formatted(validator);
            case "vote" -> "Scenario.vote \"%s\" (Ledger.DRepVoter (Ledger.PubKeyCredential owner))".formatted(validator);
            case "propose" -> "Scenario.propose \"%s\" 0 (Ledger.ProposalProcedure 0 (Ledger.PubKeyCredential owner) Ledger.InfoAction)"
                    .formatted(validator);
            default -> throw new IllegalArgumentException("Unsupported handler purpose: " + purpose);
        };
        return """
                module MainTest exposing (othersAreRejected, ownerSigns)

                import Julc.Data as Data
                import Julc.Ledger as Ledger exposing (PubKeyHash)
                import Scenario exposing (Scenario)


                owner : PubKeyHash
                owner =
                    Scenario.key "owner"


                transaction : Scenario
                transaction =
                    %s
                        |> Scenario.parameters [ Data.encode owner ]


                ownerSigns : Scenario
                ownerSigns =
                    transaction
                        |> Scenario.signedBy owner


                othersAreRejected : Scenario
                othersAreRejected =
                    transaction
                        |> Scenario.signedBy (Scenario.key "someone else")
                        |> Scenario.expectFailure
                """.formatted(transaction);
    }

    private static String readme(String name, String purpose, String validator) {
        if (validator != null) {
            return """
                    # %s

                    This project was created by `elci init` with validator `%s`, whose first handler
                    serves the `%s` purpose. The handler accepts transactions signed by the `owner`
                    deployment parameter; apply it to the built script before deployment.

                    ```sh
                    elci check .
                    elci test .
                    elci build .
                    ```

                    `tests/MainTest.elm` holds two transaction scenarios. The owner's transaction
                    passes and anyone else's is rejected. Artifacts are written to
                    `build/validators/%s/`.
                    """.formatted(name, validator, purpose, validator);
        }
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
