package com.atompay.cardpaycore.controller;

import com.atompay.cardpaycore.dto.AmountRequest;
import com.atompay.cardpaycore.dto.AuthorizeRequest;
import com.atompay.cardpaycore.dto.PaymentResponse;
import com.atompay.cardpaycore.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
            @RequestBody AmountRequest request
    ) {
        return ResponseEntity.ok(paymentService.capture(authorizationId, request.getAmount()));
    }

    @PostMapping("/{authorizationId}/cancel")
    public ResponseEntity<PaymentResponse> cancel(@PathVariable String authorizationId) {
        return ResponseEntity.ok(paymentService.cancel(authorizationId));
    }

    @PostMapping("/{authorizationId}/partial-cancel")
    public ResponseEntity<PaymentResponse> partialCancel(
            @PathVariable String authorizationId,
            @RequestBody AmountRequest request
    ) {
        return ResponseEntity.ok(paymentService.partialCancel(authorizationId, request.getAmount()));
    }

    @PostMapping("/{authorizationId}/refund")
    public ResponseEntity<PaymentResponse> refund(
            @PathVariable String authorizationId,
            @RequestBody AmountRequest request
    ) {
        return ResponseEntity.ok(paymentService.refund(authorizationId, request.getAmount()));
    }
}
