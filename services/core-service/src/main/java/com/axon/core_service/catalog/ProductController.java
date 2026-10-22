package com.axon.core_service.catalog;

import com.axon.core_service.catalog.domain.Product;
import com.axon.core_service.catalog.dto.ProductListResponse;
import com.axon.core_service.service.StoreViewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductRepository productRepository;
    private final StoreViewService storeViewService;

    @GetMapping
    public ResponseEntity<ProductListResponse> listProducts(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(storeViewService.getProductList(category, page, size));
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchProducts(@RequestParam("keyword") String keyword) {
        List<Product> products = productRepository.findByProductNameContainingAndCampaignOnlyFalse(keyword);

        List<Map<String, Object>> result = products.stream()
                .map(p -> {
                    Map<String, Object> map = new java.util.HashMap<>();
                    map.put("id", p.getId());
                    map.put("name", p.getProductName());
                    map.put("price", p.getPrice());
                    map.put("stock", p.getStock());
                    return map;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }
}
