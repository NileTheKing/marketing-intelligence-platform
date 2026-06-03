package com.axon.core_service.controller;

import com.axon.core_service.config.auth.JwtTokenProvider;
import com.axon.core_service.domain.coupon.UserCoupon;
import com.axon.core_service.domain.user.CustomOAuth2User;
import com.axon.core_service.service.StoreViewService;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
@Slf4j
public class StoreController {

    private final JwtTokenProvider jwtTokenProvider;
    private final StoreViewService storeViewService;

    @Value("${axon.entry-service-url}")
    private String entryServiceUrl;

    @GetMapping("/mainshop")
    public String mainshop(@RequestParam(required = false) String category, Model model) {
        StoreViewService.MainShopViewData viewData = storeViewService.getMainShopViewData(category);

        model.addAttribute("rankings", viewData.getRankings());
        if (category != null && !category.isEmpty()) {
            model.addAttribute("categoryProducts", viewData.getCategoryProducts());
            model.addAttribute("selectedCategory", viewData.getSelectedCategory());
        } else {
            model.addAttribute("techDeals", viewData.getTechDeals());
            model.addAttribute("foodDeals", viewData.getFoodDeals());
            model.addAttribute("homeDeals", viewData.getHomeDeals());
            model.addAttribute("fashionDeals", viewData.getFashionDeals());
        }

        return "mainshop";
    }

    @GetMapping("/product/{id}")
    public String productDetail(@PathVariable Long id, Model model) {
        model.addAttribute("product", storeViewService.getProductDisplay(id));
        return "product/detail";
    }

    @GetMapping("/checkout")
    public String checkout(@RequestParam Long productId,
            @CookieValue(value = "accessToken", required = false) String accessToken,
            Model model) {
        Long userId = extractUser(accessToken).userId();

        StoreViewService.CheckoutViewData viewData = storeViewService.getCheckoutViewData(userId, productId);
        model.addAttribute("product", viewData.getProduct());
        model.addAttribute("coupons", viewData.getCoupons());
        log.info("Loaded checkout view for userId={}, productId={}", userId, productId);

        return "checkout";
    }

    @GetMapping("/events")
    public String getCampaignActivities(
            @CookieValue(value = "accessToken", required = false) String accessToken,
            Model model) {
        Long userId = extractUser(accessToken).userId();
        StoreViewService.CampaignActivitiesViewData viewData =
                storeViewService.getCampaignActivitiesViewData(userId);

        model.addAttribute("fcfsCampaignActivities", viewData.getFcfsCampaignActivities());
        model.addAttribute("raffleCampaignActivities", viewData.getRaffleCampaignActivities());
        model.addAttribute("coupons", viewData.getCoupons());
        model.addAttribute("entryServiceUrl", entryServiceUrl);
        model.addAttribute("issuedCouponIds", viewData.getIssuedCouponIds());

        return "campaign-activities";
    }

    @GetMapping("/campaign-activity/{id}")
    public String getCampaignActivityDetail(@PathVariable Long id, Model model) {
        StoreViewService.CampaignActivityDisplayDto campaignActivity =
                storeViewService.getActiveCampaignActivity(id);

        if (campaignActivity == null) {
            model.addAttribute("message", "현재 진행 중인 캠페인이 아닙니다.");
            return "alert_back";
        }

        model.addAttribute("campaignActivity", campaignActivity);
        return "entry";
    }

    @GetMapping("/cart")
    public String cart() {
        return "cart";
    }

    @GetMapping("/cart/checkout")
    public String payment() {
        return "payment";
    }

    @GetMapping("/payment/success")
    public String paymentSuccess(
            @RequestParam(required = false) Long couponId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) BigDecimal finalPrice,
            @CookieValue(value = "accessToken", required = false) String accessToken) {
        Long userId = extractUser(accessToken).userId();

        try {
            storeViewService.recordPaymentSuccess(userId, productId, couponId, finalPrice);
        } catch (Exception e) {
            log.error("Failed to record payment success", e);
        }

        return "payment-success";
    }

    @GetMapping("/mypage")
    public String mypage(
            @CookieValue(value = "accessToken", required = false) String accessToken,
            Model model) {
        AuthenticatedStoreUser user = extractUser(accessToken);
        List<UserCoupon> userCoupons = storeViewService.getValidUserCoupons(user.userId());

        model.addAttribute("username", user.username());
        model.addAttribute("userCoupons", userCoupons);
        return "mypage";
    }

    private AuthenticatedStoreUser extractUser(String accessToken) {
        if (accessToken == null || !jwtTokenProvider.validateToken(accessToken)) {
            return AuthenticatedStoreUser.guest();
        }

        try {
            Authentication authentication = jwtTokenProvider.getAuthentication(accessToken);
            Object principal = authentication.getPrincipal();

            if (principal instanceof CustomOAuth2User oauthUser) {
                return new AuthenticatedStoreUser(oauthUser.getUserId(), oauthUser.getDisplayName());
            }

            if (principal instanceof UserDetails userDetails) {
                Long userId = Long.parseLong(userDetails.getUsername());
                return new AuthenticatedStoreUser(userId, userDetails.getUsername());
            }
        } catch (Exception e) {
            log.warn("Failed to extract user data from token", e);
        }

        return AuthenticatedStoreUser.guest();
    }

    private record AuthenticatedStoreUser(Long userId, String username) {
        private static AuthenticatedStoreUser guest() {
            return new AuthenticatedStoreUser(null, "Guest");
        }
    }
}
