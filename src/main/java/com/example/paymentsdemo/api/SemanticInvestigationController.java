package com.example.paymentsdemo.api;

import com.example.paymentsdemo.dto.SemanticInvestigationRequest;
import com.example.paymentsdemo.dto.SemanticInvestigationResponse;
import com.example.paymentsdemo.service.SemanticInvestigationService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/investigation")
@Profile("!merchant-simulator & !payment-initiator & !reference-cache-sink & !oracle-cache-sink")
public class SemanticInvestigationController {

    private final SemanticInvestigationService semanticInvestigationService;

    public SemanticInvestigationController(SemanticInvestigationService semanticInvestigationService) {
        this.semanticInvestigationService = semanticInvestigationService;
    }

    @PostMapping("/semantic")
    public SemanticInvestigationResponse semantic(@Valid @RequestBody SemanticInvestigationRequest request) {
        return semanticInvestigationService.investigate(request);
    }
}
