package com.example.paymentsdemo.api;

import com.example.paymentsdemo.dto.AuthorizePaymentRequest;
import com.example.paymentsdemo.dto.PaymentResponse;
import com.example.paymentsdemo.domain.PaymentStatus;
import com.example.paymentsdemo.service.PaymentOperationResult;
import com.example.paymentsdemo.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
@Profile("!merchant-simulator & !payment-initiator")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/authorize")
    public ResponseEntity<PaymentResponse> authorize(@Valid @RequestBody AuthorizePaymentRequest request) {
        PaymentOperationResult result = paymentService.authorize(request);
        PaymentResponse response = PaymentResponse.fromPayment(result.payment(), result.message(), result.duplicate());
        HttpStatus status = result.payment().getStatus() == PaymentStatus.PENDING_MERCHANT ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @PostMapping("/{paymentId}/capture")
    public PaymentResponse capture(@PathVariable String paymentId) {
        PaymentOperationResult result = paymentService.capture(paymentId);
        return PaymentResponse.fromPayment(result.payment(), result.message(), result.duplicate());
    }

    @PostMapping("/{paymentId}/refund")
    public PaymentResponse refund(@PathVariable String paymentId) {
        PaymentOperationResult result = paymentService.refund(paymentId);
        return PaymentResponse.fromPayment(result.payment(), result.message(), result.duplicate());
    }
}
