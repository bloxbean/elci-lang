package org.elci.cli;

import org.elci.driver.ElciCompiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Creates a minimal project: a {@code [validators.<name>]} group (default name {@code main})
 * whose first handler has the chosen purpose, or with {@code --purpose function} an
 * {@code [entrypoint]} function for {@code elci eval}. The purpose only selects the starter.
 */
final class ProjectInitializer {
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
            throw new IllegalArgumentException("Usage: elci init <project-directory> [--validator <name>] "
                    + "[--purpose spend|mint|withdraw|certify|vote|propose|function]");
        }
        if (purpose == null) purpose = "spend";
        if (purpose.equals("function")) {
            if (validator != null)
                throw new IllegalArgumentException("A function project has no validator; drop --validator or choose a handler purpose");
        } else {
            if (validator == null) validator = "main";
            if (!Project.VALIDATOR_NAME.matcher(validator).matches())
                throw new IllegalArgumentException("Invalid validator name '" + validator
                        + "'; use lowercase letters, digits, '-' or '_', starting with a letter");
            if (!HANDLER_PURPOSES.contains(purpose))
                throw new IllegalArgumentException("Unsupported purpose: " + purpose
                        + "; expected spend, mint, withdraw, certify, vote, propose or function");
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
            Files.writeString(target.resolve("elci.toml"), configuration());
            Files.writeString(target.resolve("src/Main.elm"), FUNCTION_SOURCE);
        } else {
            Files.writeString(target.resolve("elci.toml"), validatorConfiguration(validator, purpose));
            Files.writeString(target.resolve("src/Main.elm"), handlerSource(purpose));
            Files.createDirectories(target.resolve("tests"));
            Files.writeString(target.resolve("tests/MainTest.elm"), testSource(validator, purpose));
        }
        Files.writeString(target.resolve("README.md"), readme(target.getFileName().toString(), purpose, validator));
        Files.writeString(target.resolve(".gitignore"), "build/\n");
        System.out.println("Created Elci project at " + target + " with initial "
                + (validator == null ? "function entrypoint." : purpose + " handler in validator " + validator + "."));
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

    private static String configuration() {
        return header() + """

                [entrypoint]
                module = "Main"
                function = "main"
                purpose = "function"
                """;
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

    private static final String FUNCTION_SOURCE = """
            module Main exposing (main)


            main : Int
            main =
                42
            """;

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
        return """
                # %s

                This project was created by `elci init` with a function entrypoint for `elci eval`.
                Validators are configured as `[validators.<name>]` tables; see `elci --help`.

                ```sh
                elci check .
                elci eval .
                ```
                """.formatted(name);
    }
}
