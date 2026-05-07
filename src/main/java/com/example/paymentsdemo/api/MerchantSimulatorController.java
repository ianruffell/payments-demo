package com.example.paymentsdemo.api;

import com.example.paymentsdemo.dto.MerchantAuthorizationRequest;
import com.example.paymentsdemo.service.MerchantSimulatorService;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/merchant")
@Profile("merchant-simulator")
public class MerchantSimulatorController {

    private final MerchantSimulatorService merchantSimulatorService;

    public MerchantSimulatorController(MerchantSimulatorService merchantSimulatorService) {
        this.merchantSimulatorService = merchantSimulatorService;
    }

    @PostMapping("/payments")
    public Map<String, Object> receivePayment(@RequestBody MerchantAuthorizationRequest request) {
        return merchantSimulatorService.receivePayment(request);
    }
}
