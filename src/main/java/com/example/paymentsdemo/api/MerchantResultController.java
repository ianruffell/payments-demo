package com.example.paymentsdemo.api;

import com.example.paymentsdemo.dto.MerchantAuthorizationResult;
import com.example.paymentsdemo.dto.PaymentResponse;
import com.example.paymentsdemo.service.PaymentOperationResult;
import com.example.paymentsdemo.service.PaymentService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/merchant-results")
@Profile("!merchant-simulator & !payment-initiator & !oracle-cache-sink")
public class MerchantResultController {

    private final PaymentService paymentService;

    public MerchantResultController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public PaymentResponse receive(@RequestBody MerchantAuthorizationResult result) {
        PaymentOperationResult operationResult = paymentService.processMerchantResult(result);
        return PaymentResponse.fromPayment(operationResult.payment(), operationResult.message(), operationResult.duplicate());
    }
}
