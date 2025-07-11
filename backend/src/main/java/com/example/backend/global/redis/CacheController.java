package com.example.backend.global.redis;

import com.example.backend.domain.analysis.service.InventoryAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cache")
@RequiredArgsConstructor
public class CacheController {

    private final InventoryAnalysisService analysisService;

    @DeleteMapping("/category-summary")
    public ResponseEntity<String> clearCategoryCache() {
        analysisService.clearCategoryCache();
        return ResponseEntity.ok("✅ 카테고리 캐시 삭제 완료");
    }

    @DeleteMapping("/outbound-summary")
    public ResponseEntity<String> clearOutboundCache() {
        analysisService.clearGlobalOutboundCache();
        return ResponseEntity.ok("✅ Outbound 상태 캐시 삭제 완료");
    }

    @DeleteMapping("/item-usage")
    public ResponseEntity<String> clearItemUsageCache() {
        analysisService.clearItemUsageCache();
        return ResponseEntity.ok("✅ 품목 사용 빈도 캐시 삭제 완료");
    }

    @DeleteMapping("/all")
    public ResponseEntity<String> clearAllCaches() {
        analysisService.clearCategoryCache();
        analysisService.clearItemUsageCache();
        analysisService.clearGlobalOutboundCache();
        return ResponseEntity.ok("✅ 전체 캐시 삭제 완료");
    }
}
