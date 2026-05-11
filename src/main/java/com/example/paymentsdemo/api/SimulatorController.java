package com.example.paymentsdemo.api;

import com.example.paymentsdemo.dto.SimulatorStatusResponse;
import com.example.paymentsdemo.service.SimulatorGatewayService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/simulator")
@Profile("!merchant-simulator & !payment-initiator & !oracle-cache-sink")
public class SimulatorController {

    private final SimulatorGatewayService simulatorGatewayService;

    public SimulatorController(SimulatorGatewayService simulatorGatewayService) {
        this.simulatorGatewayService = simulatorGatewayService;
    }

    @PostMapping("/start")
    public SimulatorStatusResponse start(@RequestParam(defaultValue = "120") int ratePerSecond) {
        return simulatorGatewayService.start(ratePerSecond);
    }

    @PostMapping("/stop")
    public SimulatorStatusResponse stop() {
        return simulatorGatewayService.stop();
    }

    @GetMapping
    public SimulatorStatusResponse status() {
        return simulatorGatewayService.status();
    }
}
