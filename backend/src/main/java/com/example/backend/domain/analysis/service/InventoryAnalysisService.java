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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
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
import java.util.concurrent.TimeUnit;
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
    private final RedissonClient redissonClient;

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

    /**
     * Redis에 캐시된 Outbound 상태별 개수를 조회합니다.
     * 캐시에 값이 없으면 null 반환 → DB 조회는 따로 수행.
     */
    public Map<Outbound, Long> getCachedOutboundSummary() {
        Long managementId = getManagementIdFromToken(); // 인증된 사용자의 관리 ID
        String redisKey = getOutboundKey(managementId); // Redis 키 생성

        StopWatch sw = new StopWatch();
        sw.start();

        // Redis에서 Hash 전체 가져오기
        Map<Object, Object> entries = objectRedisTemplate.opsForHash().entries(redisKey);

        sw.stop();

        // 캐시가 존재하면 바로 반환
        if (entries != null && !entries.isEmpty()) {
            log.info("📦 [Redis HIT] Outbound 상태 캐시 조회 응답 시간: {} ms (Key: {})", sw.getTotalTimeMillis(), redisKey);
            return convert(entries);
        }

        // 캐시가 없으면 MISS 로그 남기고 null 반환 → loadAndCacheOutboundSummary()에서 DB 접근
        log.info("📦 [Redis MISS] Outbound 상태 캐시 조회 응답 시간: {} ms (Key: {})", sw.getTotalTimeMillis(), redisKey);
        return null;
    }

    /**
     * 캐시가 없을 때 DB에서 조회 후 Redis에 저장하고 결과를 반환합니다.
     * 동시에 여러 요청이 들어오면 Redisson Lock을 이용해 한 요청만 DB 조회를 하도록 함.
     */
    public Map<Outbound, Long> loadAndCacheOutboundSummary() {
        Long managementId = getManagementIdFromToken(); // 사용자 관리 ID
        String redisKey = getOutboundKey(managementId); // Redis 키
        String lockKey = "lock:" + redisKey; // 락 키

        RLock lock = redissonClient.getLock(lockKey); // Redisson 락 객체
        boolean acquired = false; // 락 획득 여부

        StopWatch sw = new StopWatch();
        sw.start();

        try {
            // 락을 최대 1초 동안 기다리고, 5초 동안 유지
            acquired = lock.tryLock(1, 5, TimeUnit.SECONDS);

            if (acquired) {
                // 락을 잡은 뒤에도 누군가 캐시를 넣었을 수 있으니 다시 확인 (더블 체크)
                Map<Object, Object> entries = objectRedisTemplate.opsForHash().entries(redisKey);
                if (entries != null && !entries.isEmpty()) {
                    log.info("📦 [Redis HIT after Lock] (Key: {})", redisKey);
                    return convert(entries);
                }

                // 실제 DB 조회
                List<Object[]> results = itemInstanceRepository.countAllByOutboundGroupAndManagementIdAndStatus(
                        managementId, Status.ACTIVE
                );

                // DB 결과를 Map으로 변환
                Map<Outbound, Long> mapped = results.stream()
                        .collect(Collectors.toMap(
                                r -> (Outbound) r[0],
                                r -> (Long) r[1]
                        ));

                if (!mapped.isEmpty()) {
                    // Redis 저장을 위해 String 형태로 변환
                    Map<String, String> redisMap = mapped.entrySet().stream()
                            .collect(Collectors.toMap(
                                    e -> e.getKey().name(),
                                    e -> String.valueOf(e.getValue())
                            ));

                    // Redis에 저장 및 TTL 설정
                    objectRedisTemplate.opsForHash().putAll(redisKey, redisMap);
                    objectRedisTemplate.expire(redisKey, Duration.ofMinutes(10));
                }

                return mapped;
            } else {
                // 락 획득 실패 → 잠깐 기다린 뒤 Redis 캐시 재조회
                Thread.sleep(100);
                Map<Object, Object> entries = objectRedisTemplate.opsForHash().entries(redisKey);
                if (entries != null && !entries.isEmpty()) {
                    log.info("📦 [Redis HIT after wait] (Key: {})", redisKey);
                    return convert(entries);
                } else {
                    log.warn("📦 [Fallback to DB] 락 실패 후에도 캐시 없음 (Key: {})", redisKey);
                    return Collections.emptyMap(); // or DB 재조회 가능
                }
            }
        } catch (InterruptedException e) {
            // 락 대기 중 인터럽트 발생 시 처리
            Thread.currentThread().interrupt();
            throw new RuntimeException("Redisson 락 처리 중 인터럽트 발생", e);
        } finally {
            // 락을 획득했으면 반드시 해제
            if (acquired) lock.unlock();

            sw.stop();
            log.info("📦 [Redis MISS → DB 조회] 응답 시간: {} ms (Key: {})", sw.getTotalTimeMillis(), redisKey);
        }
    }

    /**
     * Redis에서 가져온 Object 기반 Map을 Outbound, Long 형태로 변환
     */
    private Map<Outbound, Long> convert(Map<Object, Object> entries) {
        return entries.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> Outbound.valueOf(e.getKey().toString()),   // 키: Enum 변환
                        e -> Long.parseLong(e.getValue().toString())    // 값: Long 변환
                ));
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
