package com.example.Uber.serviceimpl;

import com.example.Uber.service.MatchingService;
import com.example.Uber.service.TimeOutService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
@EnableScheduling
public class TimeoutServiceImpl implements TimeOutService {

    private final StringRedisTemplate redisTemplate;
    private final MatchingService matchingService;

    @Scheduled(fixedRate = 3000)
    public void checkTimeouts() {

        // For demo: scan keys
        Set<String> keys = redisTemplate.keys("ride:*:current");

        if (keys == null || keys.isEmpty()) return;

        for (String key : keys) {

            String driver = redisTemplate.opsForValue().get(key);

            if (driver == null) {
                // TTL expired → move to next driver

                String bookingIdStr = key.split(":")[1];
                Long bookingId = Long.parseLong(bookingIdStr);

                System.out.println("⏳ Timeout → trying next driver for booking " + bookingId);

                matchingService.notifyNextDriver(bookingId);
            }
        }
    }
}
