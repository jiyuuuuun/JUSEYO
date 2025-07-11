package com.example.backend.domain.analysis.service;

import com.example.backend.domain.analysis.dto.CategorySummaryDTO;
import com.example.backend.domain.analysis.dto.ItemUsageFrequencyDTO;
import com.example.backend.domain.analysis.dto.MonthlyInventoryDTO;
import com.example.backend.domain.inventory.inventoryIn.entity.InventoryIn;
import com.example.backend.domain.inventory.inventoryIn.repository.InventoryInRepository;
import com.example.backend.domain.inventory.inventoryOut.entity.InventoryOut;
import com.example.backend.domain.inventory.inventoryOut.repository.InventoryOutRepository;
import com.example.backend.domain.item.entity.Item;
import com.example.backend.domain.item.repository.ItemRepository;
import com.example.backend.domain.itemInstance.repository.ItemInstanceRepository;
import com.example.backend.domain.user.repository.UserRepository;
import com.example.backend.enums.Outbound;
import com.example.backend.enums.Status;
import com.example.backend.global.exception.BusinessLogicException;
import com.example.backend.global.exception.ExceptionCode;
import com.example.backend.global.security.jwt.service.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StopWatch;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryAnalysisService {

    @Qualifier("objectRedisTemplate")
    private final RedisTemplate<String, Object> objectRedisTemplate;

    @Qualifier("rawStringRedisTemplate")
    private final RedisTemplate<String, String> stringRedisTemplate;

    private final ItemRepository itemRepository;
    private final InventoryInRepository inventoryInRepository;
    private final InventoryOutRepository inventoryOutRepository;
    private final ItemInstanceRepository itemInstanceRepository;
    private final TokenService tokenService;
    private final UserRepository userRepository;

    // Redis 키 생성기
    private String getCategorySummaryKey(Long managementId) {
        return "category_summary:" + managementId;
    }

    private String getItemUsageKey(Long managementId) {
        return "item_usage_frequency:" + managementId;
    }

    private String getOutboundKey(Long managementId) {
        return "item_instances:outbound_count:" + managementId;
    }

    private Long getManagementIdFromToken() {
        Long userId = tokenService.getIdFromToken();
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessLogicException(ExceptionCode.USER_NOT_FOUND))
                .getManagementDashboard().getId();
    }

    public Map<String, CategorySummaryDTO> getCategorySummary() {

        StopWatch sw = new StopWatch();
        sw.start("getCategorySummary");

        Long managementId = getManagementIdFromToken();
        String key = getCategorySummaryKey(managementId);

        Map<String, CategorySummaryDTO> cached = (Map<String, CategorySummaryDTO>)
                objectRedisTemplate.opsForValue().get(key);
        if (cached != null) {
            sw.stop();
            log.info("📦 [Redis HIT] 카테고리 요약 캐시 응답 시간: {} ms", sw.getTotalTimeMillis());
            return cached;
        }

        List<Item> items = itemRepository.findAllByManagementDashboardIdAndStatus(managementId, Status.ACTIVE);

        Map<String, CategorySummaryDTO> result = items.stream()
                .collect(Collectors.groupingBy(
                        item -> item.getCategory().getName(),
                        Collectors.collectingAndThen(Collectors.toList(), list -> {
                            long totalQty = list.stream().mapToLong(Item::getTotalQuantity).sum();
                            long typeCount = list.stream().map(Item::getName).distinct().count();
                            return new CategorySummaryDTO(totalQty, typeCount);
                        })
                ));

        objectRedisTemplate.opsForValue().set(key, result, Duration.ofMinutes(30));
        sw.stop();
        log.info("📦 [Redis MISS → DB 조회] 카테고리 요약 응답 시간: {} ms", sw.getTotalTimeMillis());

        return result;
    }

    public void increaseItemUsage(String itemName, long quantity) {
        Long managementId = getManagementIdFromToken();
        objectRedisTemplate.opsForZSet().incrementScore(getItemUsageKey(managementId), itemName, quantity);
    }

    public List<ItemUsageFrequencyDTO> getItemUsageRanking(int topN) {
        StopWatch sw = new StopWatch();
        sw.start();

        Long managementId = getManagementIdFromToken();
        String redisKey = getItemUsageKey(managementId);

        Set<ZSetOperations.TypedTuple<String>> zset =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(redisKey, 0, topN - 1);

        sw.stop();
        log.info("📊 [Redis HIT] 품목 사용 빈도 조회 응답 시간: {} ms", sw.getTotalTimeMillis());

        if (zset == null || zset.isEmpty()) return Collections.emptyList();

        return zset.stream()
                .map(tuple -> new ItemUsageFrequencyDTO(
                        tuple.getValue(),
                        tuple.getScore() != null ? tuple.getScore().longValue() : 0
                ))
                .collect(Collectors.toList());
    }

    public List<MonthlyInventoryDTO> getMonthlyInventorySummary(int year) {
        StopWatch sw = new StopWatch();
        sw.start();

        Long managementId = getManagementIdFromToken();
        LocalDateTime start = LocalDate.of(year, 1, 1).atStartOfDay();
        LocalDateTime end = LocalDate.of(year, 12, 31).atTime(23, 59, 59);

        List<InventoryIn> ins = inventoryInRepository.findByCreatedAtBetweenAndManagementDashboardId(start, end, managementId);
        List<InventoryOut> outs = inventoryOutRepository.findByCreatedAtBetweenAndManagementDashboardId(start, end, managementId);

        Map<YearMonth, Long> inboundMap = ins.stream()
                .collect(Collectors.groupingBy(
                        i -> YearMonth.from(i.getCreatedAt()),
                        Collectors.summingLong(InventoryIn::getQuantity)));

        Map<YearMonth, Long> outboundMap = outs.stream()
                .collect(Collectors.groupingBy(
                        o -> YearMonth.from(o.getCreatedAt()),
                        Collectors.summingLong(InventoryOut::getQuantity)));

        List<MonthlyInventoryDTO> result = IntStream.rangeClosed(1, 12)
                .mapToObj(month -> {
                    YearMonth ym = YearMonth.of(year, month);
                    return new MonthlyInventoryDTO(
                            ym,
                            inboundMap.getOrDefault(ym, 0L),
                            outboundMap.getOrDefault(ym, 0L)
                    );
                }).collect(Collectors.toList());

        sw.stop();
        log.info("📊 월별 입출고 요약 응답 시간: {} ms", sw.getTotalTimeMillis());

        return result;
    }

    public Map<Outbound, Long> getCachedOutboundSummary() {
        StopWatch sw = new StopWatch();
        sw.start();
        Long managementId = getManagementIdFromToken();
        Map<Object, Object> entries = objectRedisTemplate.opsForHash().entries(getOutboundKey(managementId));

        sw.stop();
        log.info("📦 [Redis HIT] Outbound 상태 캐시 조회 응답 시간: {} ms", sw.getTotalTimeMillis());

        if (entries == null || entries.isEmpty()) return null;

        return entries.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> Outbound.valueOf((String) e.getKey()),
                        e -> Long.parseLong((String) e.getValue())
                ));
    }

    public Map<Outbound, Long> loadAndCacheOutboundSummary() {
        StopWatch sw = new StopWatch();
        sw.start();

        Long managementId = getManagementIdFromToken();
        List<Object[]> results = itemInstanceRepository.countAllByOutboundGroupAndManagementIdAndStatus(managementId,Status.ACTIVE);

        Map<Outbound, Long> mapped = results.stream()
                .collect(Collectors.toMap(
                        r -> (Outbound) r[0],
                        r -> (Long) r[1]
                ));

        Map<String, String> redisMap = mapped.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().name(),
                        e -> String.valueOf(e.getValue())
                ));

        objectRedisTemplate.opsForHash().putAll(getOutboundKey(managementId), redisMap);
        objectRedisTemplate.expire(getOutboundKey(managementId), Duration.ofMinutes(10));


        sw.stop();
        log.info("📦 [Redis MISS → DB 조회] Outbound 상태 통계 캐시 적재 응답 시간: {} ms", sw.getTotalTimeMillis());

        return mapped;
    }

    public void clearCategoryCache() {
        objectRedisTemplate.delete(getCategorySummaryKey(getManagementIdFromToken()));
    }

    public void clearGlobalOutboundCache() {
        objectRedisTemplate.delete(getOutboundKey(getManagementIdFromToken()));
    }
    public void clearItemUsageCache() {
        stringRedisTemplate.delete(getItemUsageKey(getManagementIdFromToken()));
    }
}
