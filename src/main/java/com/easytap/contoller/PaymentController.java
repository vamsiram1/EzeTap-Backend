//package com.easytap.contoller;
//
//import java.time.Duration;
//import java.time.LocalDateTime;
//import java.util.Base64;
//import java.util.HashMap;
//import java.util.HashSet;
//import java.util.Iterator;
//import java.util.Map;
//import java.util.Set;
//import java.util.UUID;
//
//import jakarta.annotation.PostConstruct;
//
//import org.json.JSONObject;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.ResponseEntity;
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.web.bind.annotation.*;
//import org.springframework.web.reactive.function.client.WebClient;
//
//@RestController
//@RequestMapping("/api/pos")
//@CrossOrigin(origins = "*")
//public class PaymentController {
//
//    // ---------------- STORES ----------------
//
//    private final Map<String, String> transactionStore = new HashMap<>();
//    private final Map<String, LocalDateTime> transactionStartTime = new HashMap<>();
//
//    //  IDEMPOTENCY
//    private final Set<String> processedTransactions = new HashSet<>();
//
//    //  BACKOFF (NEW)
//    private final Map<String, Integer> retryCount = new HashMap<>();
//    private final Map<String, LocalDateTime> nextRetryTime = new HashMap<>();
//
//    @Value("${ezetap.mode}")
//    private int ezetapMode;
//
//    private WebClient webClient;
//
//    
//    @PostConstruct
//    public void initWebClient() {
//
//        String baseUrl =
//                (ezetapMode == 0)
//                        ? "https://www.ezetap.com/api/3.0"
//                        : "https://demo.ezetap.com/api/3.0";
//
//        System.out.println("EZETAP MODE → " + (ezetapMode == 0 ? "PROD" : "TEST"));
//        this.webClient = WebClient.create(baseUrl);
//    }
//
//    // ---------------- START PAYMENT ----------------
//    @PostMapping("/start")
//    public ResponseEntity<?> startPayment(@RequestBody Map<String, Object> requestData) {
//
//        String externalRefNumber = UUID.randomUUID().toString();
//
//        Map<String, Object> ezetapPayload = new HashMap<>();
//        ezetapPayload.put("appKey", "4dd308ca-e0c7-47b7-827b-550f8235beb6");
//        ezetapPayload.put("pushTo", Map.of("deviceId", requestData.get("deviceId")));
//        ezetapPayload.put("username", "0501202301");
//        ezetapPayload.put("amount", requestData.get("amount"));
//        ezetapPayload.put("externalRefNumber", externalRefNumber);
//        ezetapPayload.put("customerMobileNumber", requestData.get("customerMobileNumber"));
//        ezetapPayload.put("customerName", requestData.get("customerName"));
//        ezetapPayload.put(
//                "callbackUrl",
//                "https://yet-indie-bye-damages.trycloudflare.com/api/pos/webhook"
//        );
//
//        String response;
//        try {
//            response = webClient.post()
//                    .uri("/p2p/start")
//                    .bodyValue(ezetapPayload)
//                    .retrieve()
//                    .bodyToMono(String.class)
//                    .block();
//        } catch (Exception ex) {
//            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
//                    .body("Ezetap API unreachable: " + ex.getMessage());
//        }
//
//        JSONObject json = new JSONObject(response);
//        if (!json.optBoolean("success")) {
//            return ResponseEntity.badRequest().body(json.toMap());
//        }
//
//        String p2pRequestId = json.optString("p2pRequestId");
//        if (p2pRequestId == null) {
//            return ResponseEntity.badRequest().body("p2pRequestId missing");
//        }
//
//        // Track transaction
//        transactionStore.put(p2pRequestId, "PENDING");
//        transactionStartTime.put(p2pRequestId, LocalDateTime.now());
//
//        // BACKOFF INIT
//        retryCount.put(p2pRequestId, 0);
//        nextRetryTime.put(p2pRequestId, LocalDateTime.now());
//
//        System.out.println("Payment started → " + p2pRequestId);
//
//        return ResponseEntity.ok(Map.of(
//                "success", true,
//                "externalRefNumber", externalRefNumber,
//                "p2pRequestId", p2pRequestId
//        ));
//    }
//
//    // ---------------- POLLING ----------------
//    @Scheduled(fixedDelay = 15000, initialDelay = 10000)
//    public void checkPendingTransactions() {
//
//        Iterator<Map.Entry<String, String>> iterator =
//                transactionStore.entrySet().iterator();
//
//        while (iterator.hasNext()) {
//
//            Map.Entry<String, String> entry = iterator.next();
//            String txnId = entry.getKey();
//            String status = entry.getValue();
//
//            //  IDEMPOTENCY
//            if (processedTransactions.contains(txnId)) {
//                cleanup(txnId, iterator);
//                continue;
//            }
//
//            // BACKOFF CHECK
//            if (LocalDateTime.now().isBefore(nextRetryTime.get(txnId))) {
//                continue;
//            }
//
//            long elapsedSeconds =
//                    Duration.between(
//                            transactionStartTime.get(txnId),
//                            LocalDateTime.now()
//                    ).getSeconds();
//
//            if (elapsedSeconds >= 195) {
//                System.out.println("Timeout → " + txnId);
//                processedTransactions.add(txnId);
//                cleanup(txnId, iterator);
//                continue;
//            }
//
//            try {
//                String auth = Base64.getEncoder()
//                        .encodeToString(
//                                ("4dd308ca-e0c7-47b7-827b-550f8235beb6:123456Q")
//                                        .getBytes()
//                        );
//
//                String response = webClient.get()
//                        .uri("/transaction/status/" + txnId)
//                        .header("Authorization", "Basic " + auth)
//                        .retrieve()
//                        .bodyToMono(String.class)
//                        .block();
//
//                String newStatus = parseStatus(response);
//                transactionStore.put(txnId, newStatus);
//
//                System.out.println("Polled [" + txnId + "] → " + newStatus);
//
//                if (!"PENDING".equalsIgnoreCase(newStatus)) {
//                    processedTransactions.add(txnId);
//                    cleanup(txnId, iterator);
//                }
//
//                // reset retry count on success
//                retryCount.put(txnId, 0);
//
//            } catch (Exception e) {
//
//                int retries = retryCount.getOrDefault(txnId, 0) + 1;
//                retryCount.put(txnId, retries);
//
//                if (retries > 4) {
//                    System.out.println("Max retries reached → " + txnId);
//                    processedTransactions.add(txnId);
//                    cleanup(txnId, iterator);
//                    continue;
//                }
//
//                int backoffSeconds = (int) Math.pow(2, retries) * 15;
//                nextRetryTime.put(
//                        txnId,
//                        LocalDateTime.now().plusSeconds(backoffSeconds)
//                );
//
//                System.err.println(
//                        "Retry " + retries + " for " + txnId +
//                        " after " + backoffSeconds + " sec"
//                );
//            }
//        }
//    }
//
//    private void cleanup(String txnId, Iterator<Map.Entry<String, String>> iterator) {
//        iterator.remove();
//        transactionStartTime.remove(txnId);
//        retryCount.remove(txnId);
//        nextRetryTime.remove(txnId);
//    }
//
//    // ---------------- STATUS PARSER ----------------
//    private String parseStatus(String response) {
//        JSONObject json = new JSONObject(response);
//        if (!json.has("txn")) return "PENDING";
//        return json.getJSONObject("txn").optString("status", "PENDING");
//    }
//
//    // ---------------- WEBHOOK ----------------
//    @PostMapping("/webhook")
//    public ResponseEntity<String> ezetapWebhook(@RequestBody String payload) {
//
//        try {
//            JSONObject json = new JSONObject(payload);
//            if (!json.has("txn")) return ResponseEntity.ok("Ignored");
//
//            String txnId = json.getJSONObject("txn")
//                    .optString("p2pRequestId");
//
//            if (processedTransactions.contains(txnId)) {
//                return ResponseEntity.ok("Duplicate ignored");
//            }
//
//            processedTransactions.add(txnId);
//            transactionStore.remove(txnId);
//            transactionStartTime.remove(txnId);
//            retryCount.remove(txnId);
//            nextRetryTime.remove(txnId);
//
//            System.out.println("Webhook processed → " + txnId);
//
//        } catch (Exception e) {
//            System.err.println("Webhook parse error: " + e.getMessage());
//        }
//
//        return ResponseEntity.ok("Webhook received");
//    }
//}










package com.easytap.contoller;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
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

    // IDEMPOTENCY
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

        System.out.println("EZETAP MODE → " + (ezetapMode == 0 ? "PROD" : "DEMO"));
        this.webClient = WebClient.create(baseUrl);
    }

    // ---------------- START PAYMENT ----------------
    @PostMapping("/start")
    public ResponseEntity<?> startPayment(@RequestBody Map<String, Object> requestData) {

        String externalRefNumber = UUID.randomUUID().toString();

        Map<String, Object> ezetapPayload = Map.of(
                "appKey", "4dd308ca-e0c7-47b7-827b-550f8235beb6",
                "pushTo", Map.of("deviceId", requestData.get("deviceId")),
                "username", "0501202301",
                "amount", requestData.get("amount"),
                "externalRefNumber", externalRefNumber,
                "customerMobileNumber", requestData.get("customerMobileNumber"),
                "customerName", requestData.get("customerName"),
                "callbackUrl", "https://yet-indie-bye-damages.trycloudflare.com/api/pos/webhook"
        );

        String response;
        try {
            response = webClient.post()
                    .uri("/p2p/start")
                    .bodyValue(ezetapPayload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("Ezetap API unreachable: " + ex.getMessage());
        }

        JSONObject json = new JSONObject(response);
        if (!json.optBoolean("success")) {
            return ResponseEntity.badRequest().body(json.toMap());
        }

        // ✅ FIX 1: correct p2pRequestId check
        String p2pRequestId = json.optString("p2pRequestId", "");
        if (p2pRequestId.isBlank()) {
            return ResponseEntity.badRequest().body("p2pRequestId missing");
        }

        // Register transaction
        transactionStore.put(p2pRequestId, "PENDING");
        transactionStartTime.put(p2pRequestId, LocalDateTime.now());
        retryCount.put(p2pRequestId, 0);
        nextRetryTime.put(p2pRequestId, LocalDateTime.now());

        System.out.println("Payment started → " + p2pRequestId);

        // -------- IMMEDIATE STATUS CHECK (PROD ONLY) --------
        if (ezetapMode == 0) {
            try {
                String immediateStatus = fetchStatusFromEzetap(p2pRequestId);
                transactionStore.put(p2pRequestId, immediateStatus);

                if (!"PENDING".equalsIgnoreCase(immediateStatus)) {
                    processedTransactions.add(p2pRequestId);
                    cleanup(p2pRequestId);
                }
            } catch (Exception e) {
                System.err.println("Immediate status check failed → " + e.getMessage());
            }
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "externalRefNumber", externalRefNumber,
                "p2pRequestId", p2pRequestId
        ));
    }

    // ---------------- SCHEDULER (PROD ONLY) ----------------
    @Scheduled(fixedDelay = 15000, initialDelay = 10000)
    public void checkPendingTransactions() {

        // ✅ FIX 2: No polling in DEMO
        if (ezetapMode == 1) return;

        for (String txnId : transactionStore.keySet()) {

            if (processedTransactions.contains(txnId)) {
                cleanup(txnId);
                continue;
            }

            String status = transactionStore.get(txnId);
            if (!"PENDING".equalsIgnoreCase(status)) {
                processedTransactions.add(txnId);
                cleanup(txnId);
                continue;
            }

            LocalDateTime allowedTime = nextRetryTime.get(txnId);
            if (allowedTime != null && LocalDateTime.now().isBefore(allowedTime)) {
                continue;
            }

            long elapsedSeconds =
                    Duration.between(transactionStartTime.get(txnId), LocalDateTime.now())
                            .getSeconds();

            if (elapsedSeconds >= 195) {
                System.out.println("Timeout → " + txnId);
                processedTransactions.add(txnId);
                cleanup(txnId);
                continue;
            }

            try {
                String newStatus = fetchStatusFromEzetap(txnId);
                transactionStore.put(txnId, newStatus);

                if (!"PENDING".equalsIgnoreCase(newStatus)) {
                    processedTransactions.add(txnId);
                    cleanup(txnId);
                }

                retryCount.put(txnId, 0);
                nextRetryTime.put(txnId, LocalDateTime.now());

            } catch (Exception e) {

                int retries = retryCount.getOrDefault(txnId, 0) + 1;
                retryCount.put(txnId, retries);

                if (retries > 4) {
                    System.out.println("Max retries reached → " + txnId);
                    processedTransactions.add(txnId);
                    cleanup(txnId);
                    continue;
                }

                int backoffSeconds = (int) Math.pow(2, retries) * 15;
                nextRetryTime.put(txnId, LocalDateTime.now().plusSeconds(backoffSeconds));
            }
        }
    }

    // ---------------- STATUS FETCH (PROD ONLY) ----------------
    private String fetchStatusFromEzetap(String txnId) {

        // ⚠️ DEMO SHOULD NEVER CALL THIS
        if (ezetapMode == 1) return "PENDING";

        String auth = Base64.getEncoder()
                .encodeToString(("0501202301:123456X").getBytes());

        String response = webClient.get()
                .uri("/transaction/status/" + txnId)
                .header("Authorization", "Basic " + auth)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return parseStatus(response);
    }

    private void cleanup(String txnId) {
        transactionStore.remove(txnId);
        transactionStartTime.remove(txnId);
        retryCount.remove(txnId);
        nextRetryTime.remove(txnId);
    }

    // ---------------- STATUS PARSER ----------------
    private String parseStatus(String response) {
        JSONObject json = new JSONObject(response);
        if (!json.has("txn")) return "PENDING";
        return json.getJSONObject("txn").optString("status", "PENDING");
    }

    // ---------------- WEBHOOK (FINAL TRUTH) ----------------
    @PostMapping("/webhook")
    public ResponseEntity<String> ezetapWebhook(@RequestBody String payload) {

        try {
            JSONObject json = new JSONObject(payload);
            if (!json.has("txn")) return ResponseEntity.ok("Ignored");

            JSONObject txn = json.getJSONObject("txn");
            String txnId = txn.optString("p2pRequestId");
            String status = txn.optString("status");

            if (processedTransactions.contains(txnId)) {
                return ResponseEntity.ok("Duplicate ignored");
            }

            processedTransactions.add(txnId);
            transactionStore.put(txnId, status);
            cleanup(txnId);
            System.out.println("Webhook processed → " + txnId + " : " + status);

        } catch (Exception e) {
            System.err.println("Webhook parse error → " + e.getMessage());
        }

        return ResponseEntity.ok("Webhook received");
    }
}
