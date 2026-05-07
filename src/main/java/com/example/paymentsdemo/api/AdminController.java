package com.example.paymentsdemo.api;

import com.example.paymentsdemo.domain.Merchant;
import com.example.paymentsdemo.service.FraudService;
import com.example.paymentsdemo.service.MerchantAdminService;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;

@RestController
@RequestMapping("/api/admin")
@Profile("!merchant-simulator")
public class AdminController {

    private final MerchantAdminService merchantAdminService;
    private final FraudService fraudService;

    public AdminController(MerchantAdminService merchantAdminService, FraudService fraudService) {
        this.merchantAdminService = merchantAdminService;
        this.fraudService = fraudService;
    }

    @PostMapping("/merchants/{merchantId}/status")
    public Merchant setMerchantStatus(@PathVariable String merchantId, @RequestParam boolean active) {
        return merchantAdminService.setMerchantActive(merchantId, active);
    }

    @PostMapping("/fraud-threshold")
    public Map<String, Double> setFraudThreshold(@RequestParam double value) {
        fraudService.setThreshold(value);
        return Map.of("fraudThreshold", fraudService.getThreshold());
    }
}
