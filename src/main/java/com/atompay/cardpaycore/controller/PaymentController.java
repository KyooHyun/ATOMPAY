package com.atompay.cardpaycore.controller;

import com.atompay.cardpaycore.dto.AmountRequest;
import com.atompay.cardpaycore.dto.AuthorizeRequest;
import com.atompay.cardpaycore.dto.PaymentResponse;
import com.atompay.cardpaycore.dto.PaymentTransactionResponse;
import com.atompay.cardpaycore.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/authorize")
    public ResponseEntity<PaymentResponse> authorize(
            @RequestBody AuthorizeRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ResponseEntity.ok(paymentService.authorize(request, idempotencyKey));
    }

    @PostMapping("/{authorizationId}/capture")
    public ResponseEntity<PaymentResponse> capture(
            @PathVariable String authorizationId,
            @RequestBody AmountRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ResponseEntity.ok(paymentService.capture(authorizationId, request.getAmount(), idempotencyKey));
    }

    @PostMapping("/{authorizationId}/cancel")
    public ResponseEntity<PaymentResponse> cancel(
            @PathVariable String authorizationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ResponseEntity.ok(paymentService.cancel(authorizationId, idempotencyKey));
    }

    @PostMapping("/{authorizationId}/partial-refund")
    public ResponseEntity<PaymentResponse> partialRefund(
            @PathVariable String authorizationId,
            @RequestBody AmountRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ResponseEntity.ok(paymentService.partialRefund(authorizationId, request.getAmount(), idempotencyKey));
    }

    @PostMapping("/{authorizationId}/refund")
    public ResponseEntity<PaymentResponse> refund(
            @PathVariable String authorizationId,
            @RequestBody AmountRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ResponseEntity.ok(paymentService.refund(authorizationId, request.getAmount(), idempotencyKey));
    }

    @GetMapping("/{authorizationId}/transactions")
    public ResponseEntity<List<PaymentTransactionResponse>> getTransactions(@PathVariable String authorizationId) {
        return ResponseEntity.ok(paymentService.listTransactions(authorizationId));
    }

    @GetMapping("/{authorizationId}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable String authorizationId) {
        return ResponseEntity.ok(paymentService.getPayment(authorizationId));
    }
}
