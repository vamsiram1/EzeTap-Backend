package com.easytap.contoller;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

@RestController
@RequestMapping("/api/pos")
@CrossOrigin(origins = "*")
public class PaymentController {

    // ---------------- STORES (THREAD SAFE) ----------------
    private final Map<String, String> transactionStore = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> transactionStartTime = new ConcurrentHashMap<>();
    private final Map<String, Integer> retryCount = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> nextRetryTime = new ConcurrentHashMap<>();

    private final Set<String> processedTransactions = ConcurrentHashMap.newKeySet();

    @Value("${ezetap.mode}") // 0 = PROD, 1 = DEMO
    private int ezetapMode;

    private WebClient webClient;

    // ---------------- INIT ----------------
    @PostConstruct
    public void initWebClient() {

        String baseUrl =
                (ezetapMode == 1)
                        ? "https://www.ezetap.com/api/3.0"
                        : "https://demo.ezetap.com/api/3.0";

       
        System.out.println("EZETAP INITIALIZED");
        System.out.println("MODE             " + (ezetapMode == 1 ? "PROD" : "DEMO"));
        System.out.println("BASE URL         " + baseUrl);
       

        this.webClient = WebClient.create(baseUrl);
    }

    // ---------------- START PAYMENT ----------------
    @PostMapping("/start")
    public ResponseEntity<?> startPayment(@RequestBody Map<String, Object> requestData) {

        System.out.println("\n--------- START PAYMENT ----------");
        System.out.println("REQUEST DATA → " + requestData);

        String externalRefNumber = UUID.randomUUID().toString();

        Map<String, Object> ezetapPayload = Map.of(
                "appKey", "6a7f0df7-0cfe-4373-b079-c028766febba",
                "pushTo", Map.of("deviceId", requestData.get("deviceId")),
                "username", "1034573865",
                "amount", requestData.get("amount"),
                "externalRefNumber", externalRefNumber,
                "customerMobileNumber", requestData.get("customerMobileNumber"),
                "customerName", requestData.get("customerName"),
                "callbackUrl", "https://yet-indie-bye-damages.trycloudflare.com/api/pos/webhook"
        );

        System.out.println("P2P START PAYLOAD → " + ezetapPayload);

        String response;
        try {
            response = webClient.post()
                    .uri("/p2p/start")
                    .bodyValue(ezetapPayload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            System.out.println("P2P START RESPONSE → " + response);

        } catch (Exception ex) {
            System.err.println(" EZETAP START API FAILED");
            ex.printStackTrace();
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("Ezetap API unreachable");
        }

        JSONObject json = new JSONObject(response);
        if (!json.optBoolean("success")) {
            System.err.println(" START FAILED → " + json);
            return ResponseEntity.badRequest().body(json.toMap());
        }

        String p2pRequestId = json.optString("p2pRequestId", "");
        System.out.println("P2P REQUEST ID → " + p2pRequestId);

        transactionStore.put(p2pRequestId, "PENDING");
        transactionStartTime.put(p2pRequestId, LocalDateTime.now());
        retryCount.put(p2pRequestId, 0);
        nextRetryTime.put(p2pRequestId, LocalDateTime.now());

        return ResponseEntity.ok(Map.of(
                "success", true,
                "externalRefNumber", externalRefNumber,
                "p2pRequestId", p2pRequestId
        ));
    }

    // ---------------- SCHEDULER ----------------
    @Scheduled(fixedDelay = 15000, initialDelay = 10000)
    public void checkPendingTransactions() {

        System.out.println(" SCHEDULER RUNNING AT " + LocalDateTime.now());

        if (ezetapMode == 0) {
            System.out.println("DEMO MODE → POLLING SKIPPED");
            return;
        }

        for (String txnId : transactionStore.keySet()) {

            System.out.println("CHECKING TXN → " + txnId);

            if (processedTransactions.contains(txnId)) {
                System.out.println("ALREADY PROCESSED → CLEANUP");
                cleanup(txnId);
                continue;
            }

            String status = transactionStore.get(txnId);
            System.out.println("CURRENT STATUS → " + status);

            if (!"PENDING".equalsIgnoreCase(status)) {
                processedTransactions.add(txnId);
                cleanup(txnId);
                continue;
            }

            try {
                String newStatus = fetchStatusFromEzetap(txnId);
                System.out.println("NEW STATUS FROM EZETAP → " + newStatus);

                transactionStore.put(txnId, newStatus);

            } catch (Exception e) {
                System.err.println(" POLLING ERROR FOR " + txnId);
                e.printStackTrace();
            }
        }
    }

    // ---------------- STATUS FETCH ----------------
    private String fetchStatusFromEzetap(String txnId) {

        System.out.println("\n CALLING P2P STATUS API");
        System.out.println("TXN ID → " + txnId);

        if (ezetapMode == 0) {
            System.out.println("DEMO MODE → RETURNING PENDING");
            return "PENDING";
        }

        JSONObject payload = new JSONObject();
        payload.put("username", "1034573865");
        payload.put("appKey", "6a7f0df7-0cfe-4373-b079-c028766febba");
        payload.put("origP2pRequestId", txnId);

        System.out.println("STATUS PAYLOAD → " + payload);

        String response = webClient.post()
                .uri("/p2p/status")
                .header("Content-Type", "application/json")
                .bodyValue(payload.toString())
                .exchangeToMono(res ->
                        res.bodyToMono(String.class)
                           .map(body -> "HTTP=" + res.statusCode() + " BODY=" + body)
                )
                .block();

        System.out.println("RAW STATUS RESPONSE → " + response);

        return parseStatus(response);
    }

    // ---------------- PARSER ----------------
    private String parseStatus(String response) {

        System.out.println("PARSING RESPONSE → " + response);

        if (response == null || !response.contains("{")) {
            System.out.println("INVALID RESPONSE → RETURNING PENDING");
            return "PENDING";
        }

        JSONObject json = new JSONObject(response.substring(response.indexOf("{")));

        if (!json.optBoolean("success")) {
            System.out.println("EZETAP SAYS NOT SUCCESS → PENDING");
            return "PENDING";
        }

        String status = json.optString("status", "PENDING");
        System.out.println("FINAL STATUS → " + status);

        return status;
    }

    // ---------------- CLEANUP ----------------
    private void cleanup(String txnId) {
        System.out.println("🧹 CLEANUP → " + txnId);
        transactionStore.remove(txnId);
        transactionStartTime.remove(txnId);
        retryCount.remove(txnId);
        nextRetryTime.remove(txnId);
    }

    // ---------------- WEBHOOK ----------------
    @PostMapping("/webhook")
    public ResponseEntity<String> ezetapWebhook(@RequestBody String payload) {

        System.out.println("\n WEBHOOK RECEIVED ");
        System.out.println("WEBHOOK PAYLOAD → " + payload);

        try {
            JSONObject json = new JSONObject(payload);
            JSONObject txn = json.optJSONObject("txn");

            if (txn == null) {
                System.out.println("NO TXN OBJECT → IGNORED");
                return ResponseEntity.ok("Ignored");
            }

            String txnId = txn.optString("p2pRequestId");
            String status = txn.optString("status");

            System.out.println("WEBHOOK TXN ID → " + txnId);
            System.out.println("WEBHOOK STATUS → " + status);

            processedTransactions.add(txnId);
            transactionStore.put(txnId, status);
            cleanup(txnId);

        } catch (Exception e) {
            System.err.println(" WEBHOOK ERROR");
            e.printStackTrace();
        }

        return ResponseEntity.ok("Webhook received");
    }
}


