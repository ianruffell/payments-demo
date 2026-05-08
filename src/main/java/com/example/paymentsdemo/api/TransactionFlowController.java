package com.example.paymentsdemo.api;

import com.example.paymentsdemo.dto.TransactionFlowSnapshot;
import com.example.paymentsdemo.service.TransactionFlowService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard/transaction-flow")
@Profile("!merchant-simulator & !payment-initiator")
public class TransactionFlowController {

    private final TransactionFlowService transactionFlowService;

    public TransactionFlowController(TransactionFlowService transactionFlowService) {
        this.transactionFlowService = transactionFlowService;
    }

    @GetMapping
    public TransactionFlowSnapshot snapshot() {
        return transactionFlowService.snapshot();
    }
}
