package com.example.paymentsdemo.api;

import com.example.paymentsdemo.dto.BlockedPaymentView;
import com.example.paymentsdemo.dto.FraudSummaryResponse;
import com.example.paymentsdemo.service.FraudMonitorService;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API for the Fraud Detection page (spec 011). */
@RestController
@RequestMapping("/api/fraud")
@Profile("!merchant-simulator & !payment-initiator & !reference-cache-sink & !oracle-cache-sink")
public class FraudController {

    private final FraudMonitorService fraudMonitorService;

    public FraudController(FraudMonitorService fraudMonitorService) {
        this.fraudMonitorService = fraudMonitorService;
    }

    @GetMapping("/summary")
    public FraudSummaryResponse summary() {
        return fraudMonitorService.summary();
    }

    @GetMapping("/blocked")
    public List<BlockedPaymentView> blocked() {
        return fraudMonitorService.recentBlocked();
    }
}
