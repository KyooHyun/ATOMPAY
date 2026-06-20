package com.atompay.cardpaycore.controller;

import com.atompay.cardpaycore.dto.AmountRequest;
import com.atompay.cardpaycore.dto.AuthorizeRequest;
import com.atompay.cardpaycore.dto.PaymentResponse;
import com.atompay.cardpaycore.dto.PaymentTransactionResponse;
import com.atompay.cardpaycore.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Payment lifecycle: authorize → capture → cancel / refund")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/authorize")
    @Operation(summary = "Authorize a payment", description = "Reserves funds on the card account. Returns AUTHORIZED status.")
    public ResponseEntity<PaymentResponse> authorize(
            @Valid @RequestBody AuthorizeRequest request,
            @Parameter(description = "Client-generated unique key for idempotent retries", required = true)
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey
    ) {
        return ResponseEntity.ok(paymentService.authorize(request, idempotencyKey));
    }

    @PostMapping("/{authorizationId}/capture")
    @Operation(summary = "Capture an authorized payment", description = "Finalizes the payment. Amount must equal the authorized amount.")
    public ResponseEntity<PaymentResponse> capture(
            @PathVariable String authorizationId,
            @Valid @RequestBody AmountRequest request,
            @Parameter(description = "Client-generated unique key for idempotent retries", required = true)
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey
    ) {
        return ResponseEntity.ok(paymentService.capture(authorizationId, request.getAmount(), idempotencyKey));
    }

    @PostMapping("/{authorizationId}/cancel")
    @Operation(summary = "Cancel an authorized payment", description = "Voids the authorization before capture. Restores available credit.")
    public ResponseEntity<PaymentResponse> cancel(
            @PathVariable String authorizationId,
            @Parameter(description = "Client-generated unique key for idempotent retries", required = true)
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey
    ) {
        return ResponseEntity.ok(paymentService.cancel(authorizationId, idempotencyKey));
    }

    @PostMapping("/{authorizationId}/partial-refund")
    @Operation(summary = "Partial refund", description = "Refunds part of a captured payment. Restores the refunded amount to available credit.")
    public ResponseEntity<PaymentResponse> partialRefund(
            @PathVariable String authorizationId,
            @Valid @RequestBody AmountRequest request,
            @Parameter(description = "Client-generated unique key for idempotent retries", required = true)
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey
    ) {
        return ResponseEntity.ok(paymentService.partialRefund(authorizationId, request.getAmount(), idempotencyKey));
    }

    @PostMapping("/{authorizationId}/refund")
    @Operation(summary = "Full or remaining refund", description = "Refunds up to the remaining refundable amount of a captured payment.")
    public ResponseEntity<PaymentResponse> refund(
            @PathVariable String authorizationId,
            @Valid @RequestBody AmountRequest request,
            @Parameter(description = "Client-generated unique key for idempotent retries", required = true)
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey
    ) {
        return ResponseEntity.ok(paymentService.refund(authorizationId, request.getAmount(), idempotencyKey));
    }

    @GetMapping("/{authorizationId}/transactions")
    @Operation(summary = "List transaction history", description = "Returns the append-only ledger of all events for this authorization.")
    public ResponseEntity<List<PaymentTransactionResponse>> getTransactions(@PathVariable String authorizationId) {
        return ResponseEntity.ok(paymentService.listTransactions(authorizationId));
    }

    @GetMapping("/{authorizationId}")
    @Operation(summary = "Get payment status", description = "Returns the current state, authorized amount, and cumulative refunded amount.")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable String authorizationId) {
        return ResponseEntity.ok(paymentService.getPayment(authorizationId));
    }
}
