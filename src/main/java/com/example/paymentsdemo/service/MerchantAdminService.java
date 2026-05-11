package com.example.paymentsdemo.service;

import com.example.paymentsdemo.domain.Merchant;
import org.apache.ignite.Ignite;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Profile("!merchant-simulator & !payment-initiator & !oracle-cache-sink")
public class MerchantAdminService {

    private final Ignite ignite;
    private final OracleSystemOfRecordRepository oracleRepository;

    public MerchantAdminService(Ignite ignite, OracleSystemOfRecordRepository oracleRepository) {
        this.ignite = ignite;
        this.oracleRepository = oracleRepository;
    }

    public Merchant setMerchantActive(String merchantId, boolean active) {
        Merchant merchant = oracleRepository.setMerchantActive(merchantId, active);
        if (merchant == null) {
            throw new ResponseStatusException(NOT_FOUND, "Unknown merchantId");
        }

        ignite.cache(CacheNames.MERCHANTS).put(merchantId, merchant);
        return merchant;
    }
}
