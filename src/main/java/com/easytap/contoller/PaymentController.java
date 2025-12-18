package com.easytap.contoller;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

@RestController
@RequestMapping("/api/pos")
@CrossOrigin(origins = "*")
public class PaymentController {

	private final Map<String, String> transactionStore = new HashMap<>();
	private final WebClient webClient = WebClient.create("https://demo.ezetap.com/api/3.0");

	private boolean schedulerActive;
	private LocalDateTime startTime;

	@PostMapping("/start")
	public ResponseEntity<?> startPayment(@RequestBody Map<String, Object> requestData) {

		String amount = String.valueOf(requestData.get("amount"));
		String deviceId = String.valueOf(requestData.get("deviceId"));
		String customerName = String.valueOf(requestData.get("customerName"));
		String customerMobile = String.valueOf(requestData.get("customerMobileNumber"));

		String externalRefNumber = UUID.randomUUID().toString();

//		JSONArray split = new JSONArray();
//
////        Account 1 to split 
//		JSONObject acc1 = new JSONObject();
//		acc1.put("accountNumber", "123456");
//		acc1.put("ifsc", "UTIB001122");
//		acc1.put("amount", "10");
//
////      Account 2 to split 
//		JSONObject acc2 = new JSONObject();
//		acc2.put("accountNumber", "123456");
//		acc2.put("ifsc", "UTIB001122");
//		acc2.put("amount", "10");
//
//		split.put(acc1);
//		split.put(acc2);

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

		String response;
		try {
			response = webClient.post().uri("/p2p/start").bodyValue(ezetapPayload).retrieve().bodyToMono(String.class)
					.block();
		} catch (Exception ex) {
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
					.body(Map.of("success", false, "message", "Ezetap API unreachable", "error", ex.getMessage()));
		}

		System.out.println("Ezetap Response: " + response);

		JSONObject json = new JSONObject(response);

		// If response is failure, return directly
		if (!json.optBoolean("success")) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
					.body(Map.of("success", false, "message", json.optString("message"), "errorCode",
							json.optString("errorCode"), "realCode", json.optString("realCode")));
		}

		// SAFE extraction
		String p2pRequestId = json.optString("p2pRequestId", null);

		if (p2pRequestId == null) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
					.body(Map.of("success", false, "message", "p2pRequestId missing from Ezetap success response"));
		}

		System.out.println("P2P Request ID: " + p2pRequestId);

		transactionStore.put(p2pRequestId, "PENDING");

		// Start scheduler
		schedulerActive = true;
		startTime = LocalDateTime.now();
		System.out.println("Polling scheduler activated for transaction: " + p2pRequestId);

		return ResponseEntity
				.ok(Map.of("success", true, "externalRefNumber", externalRefNumber, "p2pRequestId", p2pRequestId));
	}

	// Runs every 15 seconds
	@Scheduled(fixedDelay = 15000, initialDelay = 10000)
	public void checkPendingTransactions() {

		if (!schedulerActive) {
			return;
		}

		long secondsElapsed = Duration.between(startTime, LocalDateTime.now()).getSeconds();

		if (secondsElapsed >= 195) {
			schedulerActive = false;
			System.out.println("Scheduler stopped automatically after 3.15 minutes");
			return;
		}

		System.out.println("Scheduler running..." + secondsElapsed + " seconds");

		for (String ezetapTxnId : transactionStore.keySet()) {
			String currentStatus = transactionStore.get(ezetapTxnId);

			if ("PENDING".equalsIgnoreCase(currentStatus)) {

				try {
					String appKey = "4dd308ca-e0c7-47b7-827b-550f8235beb6";
					String password = "123456Q";
					String auth = Base64.getEncoder().encodeToString((appKey + ":" + password).getBytes());

					String response = webClient.get().uri("/transaction/status/" + ezetapTxnId)
							.header("Authorization", "Basic " + auth).retrieve().bodyToMono(String.class).block();

					System.out.println("Status Response for " + ezetapTxnId + ": " + response);

					String newStatus = parseStatus(response);
					transactionStore.put(ezetapTxnId, newStatus);

					if (!"PENDING".equalsIgnoreCase(newStatus)) {
						schedulerActive = false;
						System.out.println("Final status received. Scheduler stopped: " + newStatus);
					}

				} catch (Exception e) {
					System.err.println("Error checking transaction " + ezetapTxnId + ": " + e.getMessage());
				}
			}
		}
	}

	private String parseStatus(String response) {
		JSONObject json = new JSONObject(response);
		return json.optString("status", "PENDING");
	}

	@PostMapping("/webhook")
	public ResponseEntity<String> ezetapWebhook(@RequestBody Map<String, Object> payload) {

		System.out.println("Webhook received from Ezetap: " + payload);

		try {
			Map<String, Object> txn = (Map<String, Object>) payload.get("txn");

			if (txn != null) {
				String p2pRequestId = String.valueOf(txn.get("p2pRequestId"));
				String status = String.valueOf(txn.get("status"));
				String amount = String.valueOf(txn.get("amount"));
				String externalRefNumber = String.valueOf(txn.get("externalRefNumber"));

				transactionStore.put(p2pRequestId, status);

				System.out.println("Webhook Update:");
				System.out.println("P2P ID: " + p2pRequestId);
				System.out.println("External Ref: " + externalRefNumber);
				System.out.println("Status: " + status);
				System.out.println("Amount: " + amount);
			} else {
				System.err.println("Webhook payload missing 'txn' object.");
			}
		} catch (Exception e) {
			System.err.println("Error parsing webhook payload: " + e.getMessage());
		}

		return ResponseEntity.ok("Webhook received successfully");
	}
}
