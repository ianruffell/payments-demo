package com.example.paymentsdemo.api;

import com.example.paymentsdemo.dto.SimulatorStatusResponse;
import com.example.paymentsdemo.simulator.PaymentSimulator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/simulator")
public class SimulatorController {

    private final PaymentSimulator paymentSimulator;

    public SimulatorController(PaymentSimulator paymentSimulator) {
        this.paymentSimulator = paymentSimulator;
    }

    @PostMapping("/start")
    public SimulatorStatusResponse start(@RequestParam(defaultValue = "120") int ratePerSecond) {
        paymentSimulator.start(ratePerSecond);
        return status();
    }

    @PostMapping("/stop")
    public SimulatorStatusResponse stop() {
        paymentSimulator.stop();
        return status();
    }

    @GetMapping
    public SimulatorStatusResponse status() {
        return new SimulatorStatusResponse(
                paymentSimulator.isRunning(),
                paymentSimulator.getRatePerSecond(),
                paymentSimulator.getGeneratedPayments()
        );
    }
}
