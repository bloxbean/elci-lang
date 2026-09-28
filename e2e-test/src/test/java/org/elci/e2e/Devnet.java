package org.elci.e2e;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A local Yaci DevKit devnet: its Blockfrost-compatible Yaci Store API for Cardano Client Lib,
 * and its admin API for funding accounts and reading the chain's clock.
 */
final class Devnet {
    static final String STORE = System.getProperty("yaci.store.url", "http://localhost:8080/api/v1/");
    static final String ADMIN = System.getProperty("yaci.admin.url", "http://localhost:10000/local-cluster/api/");

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    static final BackendService BACKEND = new BFBackendService(STORE, "yaci-devkit");

    private Devnet() {}

    /** Whether a devnet answers, and is at protocol version 11, which Elci's target needs. */
    static boolean available() {
        try {
            var parameters = JSON.readTree(get(STORE + "epochs/latest/parameters"));
            return parameters.path("protocol_major_ver").asInt() >= 11 && info().has("startTime");
        } catch (IOException | InterruptedException | RuntimeException e) {
            return false;
        }
    }

    /** A new account with {@code ada} from the devnet's faucet, once the funds are on chain. */
    static Account funded(long ada) throws Exception {
        return funded(ada, 1);
    }

    /**
     * A new account funded in {@code parts} separate outputs of {@code ada} each, for transactions
     * that spend more than one of its outputs at a time.
     */
    static Account funded(long ada, int parts) throws Exception {
        var account = new Account(Networks.testnet());
        for (int part = 0; part < parts; part++) {
            var body = JSON.writeValueAsString(java.util.Map.of("address", account.baseAddress(), "adaAmount", ada));
            var response = HTTP.send(HttpRequest.newBuilder(URI.create(ADMIN + "addresses/topup"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2)
                throw new IllegalStateException("Top-up failed: " + response.statusCode() + " " + response.body());
            int expected = part + 1;
            until(() -> utxos(account.baseAddress()).size() >= expected, "funds at " + account.baseAddress());
        }
        return account;
    }

    /** The unspent outputs at an address. */
    static List<Utxo> utxos(String address) throws Exception {
        var result = BACKEND.getUtxoService().getUtxos(address, 100, 1);
        return result.isSuccessful() && result.getValue() != null ? result.getValue() : List.of();
    }

    /** An output of a transaction, once it is on chain. */
    static Utxo output(String txHash, int index) throws Exception {
        until(() -> BACKEND.getUtxoService().getTxOutput(txHash, index).isSuccessful(), txHash + "#" + index);
        return BACKEND.getUtxoService().getTxOutput(txHash, index).getValue();
    }

    /** The index of the first output of a transaction that pays an address. */
    static int outputTo(String txHash, String address) throws Exception {
        return outputsTo(txHash, address).getFirst().getOutputIndex();
    }

    /** The outputs of a transaction that pay an address, in order. */
    static List<Utxo> outputsTo(String txHash, String address) throws Exception {
        output(txHash, 0);
        var result = new java.util.ArrayList<Utxo>();
        for (int index = 0; index < 20; index++) {
            var output = BACKEND.getUtxoService().getTxOutput(txHash, index);
            if (!output.isSuccessful()) break;
            if (address.equals(output.getValue().getAddress())) result.add(output.getValue());
        }
        if (result.isEmpty()) throw new IllegalStateException("No output of " + txHash + " pays " + address);
        return result;
    }

    /** The slot of the chain's latest block. */
    static long slot() throws Exception {
        return BACKEND.getBlockService().getLatestBlock().getValue().getSlot();
    }

    /** A slot's start in POSIX milliseconds, as the ledger computes it for a validity range. */
    static long posixMs(long slot) throws Exception {
        var info = info();
        return info.path("startTime").asLong() * 1000 + slot * Math.round(info.path("slotLength").asDouble() * 1000);
    }

    /** The devnet's clock for Julc's evaluator: slot 0 at the chain's start, and its slot length. */
    static org.julclang.clientlib.eval.SlotConfig slotConfig() throws Exception {
        var info = info();
        return new org.julclang.clientlib.eval.SlotConfig(0, info.path("startTime").asLong() * 1000,
                Math.round(info.path("slotLength").asDouble() * 1000));
    }

    /** Wait until the chain reaches a slot. */
    static void awaitSlot(long slot) throws Exception {
        until(() -> slot() >= slot, "slot " + slot);
    }

    private static JsonNode info() throws IOException, InterruptedException {
        return JSON.readTree(get(ADMIN + "admin/devnet"));
    }

    private static String get(String url) throws IOException, InterruptedException {
        var response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IOException(url + ": " + response.statusCode());
        return response.body();
    }

    interface Check {
        boolean holds() throws Exception;
    }

    /** Poll until a condition holds, for at most a minute. */
    static void until(Check check, String what) throws Exception {
        var deadline = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(deadline)) {
            if (check.holds()) return;
            Thread.sleep(500);
        }
        throw new IllegalStateException("Timed out waiting for " + what);
    }
}
