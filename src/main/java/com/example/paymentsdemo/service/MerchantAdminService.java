package com.example.paymentsdemo.service;

import com.example.paymentsdemo.domain.Merchant;
import org.apache.ignite.Ignite;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Profile("!merchant-simulator")
public class MerchantAdminService {

    private final Ignite ignite;

    public MerchantAdminService(Ignite ignite) {
        this.ignite = ignite;
    }

    public Merchant setMerchantActive(String merchantId, boolean active) {
        Merchant merchant = (Merchant) ignite.cache(CacheNames.MERCHANTS).get(merchantId);
        if (merchant == null) {
            throw new ResponseStatusException(NOT_FOUND, "Unknown merchantId");
        }

        merchant.setActive(active);
        ignite.cache(CacheNames.MERCHANTS).put(merchantId, merchant);
        return merchant;
    }
}
