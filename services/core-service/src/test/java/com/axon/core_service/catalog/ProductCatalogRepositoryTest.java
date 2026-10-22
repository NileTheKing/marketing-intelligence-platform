package com.axon.core_service.catalog;

import com.axon.core_service.catalog.domain.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ProductCatalogRepositoryTest {

    @Autowired
    private ProductRepository productRepository;

    @Test
    void categorySliceFiltersCampaignProductsAndHasStablePageBoundary() {
        productRepository.save(new Product("tech one", 3L, java.math.BigDecimal.TEN, "TECH"));
        productRepository.save(new Product("tech campaign", 3L, java.math.BigDecimal.TEN, "TECH", null, null, true));
        productRepository.save(new Product("food", 3L, java.math.BigDecimal.TEN, "FOOD"));
        productRepository.save(new Product("tech two", 3L, java.math.BigDecimal.TEN, "TECH"));
        productRepository.save(new Product("tech three", 3L, java.math.BigDecimal.TEN, "TECH"));
        productRepository.flush();

        var first = productRepository.findByCampaignOnlyFalseAndCategoryIgnoreCase(
                "tech", PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "id")));
        var second = productRepository.findByCampaignOnlyFalseAndCategoryIgnoreCase(
                "TECH", PageRequest.of(1, 2, Sort.by(Sort.Direction.ASC, "id")));

        assertThat(first.getContent()).extracting(Product::getProductName).containsExactly("tech one", "tech two");
        assertThat(first.hasNext()).isTrue();
        assertThat(second.getContent()).extracting(Product::getProductName).containsExactly("tech three");
        assertThat(second.hasNext()).isFalse();
    }
}
