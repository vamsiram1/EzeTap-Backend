package com.easytap.contoller;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.json.JSONObject;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

@RestController
@RequestMapping("/api/pos")
@CrossOrigin(origins = "*")
public class PaymentController {

    private final Map<String, String> transactionStore = new HashMap<>();
    private final WebClient webClient = WebClient.create("https://demo.ezetap.com/api/3.0");
    
    
    
    
    private boolean schedulerActive ;
    private LocalDateTime startTime ;
    
    
    

    @PostMapping("/start")
    public Map<String, Object> startPayment(@RequestBody Map<String, Object> requestData) {
        String amount = String.valueOf(requestData.get("amount"));
        String deviceId = String.valueOf(requestData.get("deviceId"));
        String customerName = String.valueOf(requestData.get("customerName"));
        String customerMobile = String.valueOf(requestData.get("customerMobileNumber"));

        String externalRefNumber = UUID.randomUUID().toString();

        Map<String, Object> ezetapPayload = new HashMap<>();
        ezetapPayload.put("appKey", "4dd308ca-e0c7-47b7-827b-550f8235beb6");
        ezetapPayload.put("pushTo", Map.of("deviceId", deviceId));
        ezetapPayload.put("username", "0501202301");
        ezetapPayload.put("amount", amount);
        ezetapPayload.put("externalRefNumber", externalRefNumber);
        ezetapPayload.put("customerMobileNumber", customerMobile);
        ezetapPayload.put("customerName", customerName);
        ezetapPayload.put("callbackUrl", "https://yet-indie-bye-damages.trycloudflare.com/api/pos/webhook");
        System.out.println("Sending to Ezetap: " + ezetapPayload);

        // send to Ezetap
        String response = webClient.post()
                .uri("/p2p/start")
                .bodyValue(ezetapPayload)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        System.out.println("Ezetap Response: " + response);
        JSONObject json = new JSONObject(response);
        String p2pRequestId = json.getString("p2pRequestId");
        System.out.println("P2P Request ID: " + p2pRequestId);

        // Store Ezetap ID, not your UUID
        transactionStore.put(p2pRequestId, "PENDING");
        
        // Activate scheduler after payment starts
        schedulerActive = true;
        startTime = LocalDateTime.now();
        System.out.println("Polling scheduler activated for transaction: " + p2pRequestId);

        return Map.of(
                "externalRefNumber", externalRefNumber,
                "p2pRequestId", p2pRequestId
        );
    }

    
    
//    @Scheduled(fixedDelay = 5000, initialDelay = 10000)
//    public void checkPendingTransactions() {
//        for (Map.Entry<String, String> entry : transactionStore.entrySet()) {
//            String ezetapTxnId = entry.getKey();
//            String status = entry.getValue();
//
//            if ("PENDING".equalsIgnoreCase(status)) {
//                try {
//                    String appKey = "4dd308ca-e0c7-47b7-827b-550f8235beb6";
//                    String password = "123456Q";
//                    String auth = Base64.getEncoder()
//                            .encodeToString((appKey + ":" + password).getBytes());
//
//                    String response = webClient.get()
//                            .uri("/transaction/status/" + ezetapTxnId)
//                            .header("Authorization", "Basic " + auth)
//                            .retrieve()
//                            .bodyToMono(String.class)
//                            .block();
//
//                    System.out.println("Status Response for " + ezetapTxnId + ": " + response);
//
//                    String newStatus = parseStatus(response);
//                    transactionStore.put(ezetapTxnId, newStatus);
//
//                    System.out.println("Transaction " + ezetapTxnId + " updated to " + newStatus);
//                } catch (Exception e) {
//                    System.err.println("Error checking transaction " + ezetapTxnId + ": " + e.getMessage());
//                }
//            }
//        }
//    }
//
//    private String parseStatus(String response) {
//        if (response.contains("SUCCESS")) return "SUCCESS";
//        if (response.contains("FAILED")) return "FAILED";
//        return "PENDING";
//    }
    
    
    
    
    
    
    

    // Run every 15 seconds 
    @Scheduled(fixedDelay = 15000, initialDelay = 10000)
    public void checkPendingTransactions() {

        if (!schedulerActive) {
            return; 
        }

        long secondsElapsed = Duration.between(startTime, LocalDateTime.now()).getSeconds();

        if (secondsElapsed >= 195) {
            schedulerActive = false;
            System.out.println(" Scheduler stopped automatically after 3.15 minutes (195 seconds).");
            return;
        }

        System.out.println(" Scheduler running... elapsed " + secondsElapsed + " seconds");

        for (Map.Entry<String, String> entry : transactionStore.entrySet()) {
            String ezetapTxnId = entry.getKey();
            String status = entry.getValue();

            if ("PENDING".equalsIgnoreCase(status)) {
                try {
                    String appKey = "4dd308ca-e0c7-47b7-827b-550f8235beb6";
                    String password = "123456Q";
                    String auth = Base64.getEncoder()
                            .encodeToString((appKey + ":" + password).getBytes());

                    String response = webClient.get()
                            .uri("/transaction/status/" + ezetapTxnId)
                            .header("Authorization", "Basic " + auth)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();

                    System.out.println("Status Response for " + ezetapTxnId + ": " + response);

                    String newStatus = parseStatus(response);
                    transactionStore.put(ezetapTxnId, newStatus);

                    System.out.println("Transaction " + ezetapTxnId + " updated to " + newStatus);

                    if ("SUCCESS".equalsIgnoreCase(newStatus) || "FAILED".equalsIgnoreCase(newStatus)) {
                        schedulerActive = false;
                        System.out.println("Scheduler stopped after final status: " + newStatus);
                    }

                } catch (Exception e) {
                    System.err.println("Error checking transaction " + ezetapTxnId + ": " + e.getMessage());
                }
            }
        }
    }

    private String parseStatus(String response) {
        if (response.contains("SUCCESS")) return "SUCCESS";
        if (response.contains("FAILED")) return "FAILED";
        return "PENDING";
    }
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    @PostMapping("/webhook")
    public ResponseEntity<String> ezetapWebhook(@RequestBody Map<String, Object> payload) {
        System.out.println(" Webhook received from Ezetap: " + payload);

        try {
            Map<String, Object> txn = (Map<String, Object>) payload.get("txn");

            if (txn != null) {
                String p2pRequestId = String.valueOf(txn.get("p2pRequestId"));
                String status = String.valueOf(txn.get("status"));
                String amount = String.valueOf(txn.get("amount"));
                String externalRefNumber = String.valueOf(txn.get("externalRefNumber"));

                // Update your store or DB
                transactionStore.put(p2pRequestId, status);

                System.out.println(" Transaction Update:");
                System.out.println("   P2P ID: " + p2pRequestId);
                System.out.println("   External Ref: " + externalRefNumber);
                System.out.println("   Status: " + status);
                System.out.println("   Amount: " + amount);
            } else {
                System.err.println(" Webhook payload missing 'txn' object.");
            }
        } catch (Exception e) {
            System.err.println(" Error parsing webhook payload: " + e.getMessage());
        }

        return ResponseEntity.ok(" Webhook received successfully");
    }
        
}



















