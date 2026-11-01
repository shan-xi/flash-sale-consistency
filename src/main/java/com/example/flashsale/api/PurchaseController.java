package com.example.flashsale.api;

import com.example.flashsale.service.PurchaseService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/products")
public class PurchaseController {

    private final PurchaseService purchaseService;

    public PurchaseController(PurchaseService purchaseService) {
        this.purchaseService = purchaseService;
    }

    @PostMapping("/{productId}/purchase")
    public PurchaseResponse purchase(
            @PathVariable Long productId,
            @Valid @RequestBody PurchaseRequest request
    ) {
        return purchaseService.purchase(productId, request);
    }
}
