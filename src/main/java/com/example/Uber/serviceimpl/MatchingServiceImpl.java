package com.example.Uber.serviceimpl;

import com.example.Uber.dto.DriverLocationDTO;
import com.example.Uber.dto.DriverRideRequestDTO;
import com.example.Uber.entity.Booking;
import com.example.Uber.entity.Driver;
import com.example.Uber.repository.BookingRepository;
import com.example.Uber.repository.DriverRepository;
import com.example.Uber.service.LocationService;
import com.example.Uber.service.MatchingService;
import com.example.Uber.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MatchingServiceImpl implements MatchingService {

    private final BookingRepository bookingRepository;
    private final DriverRepository driverRepository;
    private final LocationService locationService;
    private final NotificationService notificationService;
    private final StringRedisTemplate redisTemplate;

    @Override
    public void findNearbyDriversAndNotify(Long bookingId) {

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        List<DriverLocationDTO> drivers =
                locationService.getNearbyDrivers(
                        Double.parseDouble(booking.getPickupLocationLatitude()),
                        Double.parseDouble(booking.getPickupLocationLongitude()),
                        5.0
                );

        if (drivers.isEmpty()) {
            System.out.println("❌ No drivers found for booking " + bookingId);
            return;
        }

        String key = "ride:" + bookingId + ":drivers";

        // 🔥 clear old data (important for retries)
        redisTemplate.delete(key);

        // ✅ filter only AVAILABLE drivers
        for (DriverLocationDTO d : drivers) {

            Driver driver = driverRepository.findById(d.getDriverId().longValue())
                    .orElse(null);

            if (driver != null && Boolean.TRUE.equals(driver.getIsAvailable())) {
                redisTemplate.opsForList().rightPush(key, d.getDriverId().toString());
            }
        }

        // check if any driver left after filtering
        Long size = redisTemplate.opsForList().size(key);
        if (size == null || size == 0) {
            System.out.println("❌ No AVAILABLE drivers found for booking " + bookingId);
            return;
        }

        notifyNextDriver(bookingId);
    }

    @Override
    public void notifyNextDriver(Long bookingId) {

        String key = "ride:" + bookingId + ":drivers";

        String driverIdStr = redisTemplate.opsForList().leftPop(key);

        if (driverIdStr == null) {
            System.out.println("❌ No drivers left for booking " + bookingId);
            return;
        }

        Long driverId = Long.parseLong(driverIdStr);

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        DriverRideRequestDTO request = DriverRideRequestDTO.builder()
                .bookingId(bookingId)
                .pickupLat(Double.parseDouble(booking.getPickupLocationLatitude()))
                .pickupLng(Double.parseDouble(booking.getPickupLocationLongitude()))
                .build();

        // 🔥 send WebSocket notification
        notificationService.notifyDriver(driverId, request);

        System.out.println("🚀 Offered booking " + bookingId + " to driver " + driverId);

        // 🔥 set current driver with TTL (timeout)
        redisTemplate.opsForValue().set(
                "ride:" + bookingId + ":current",
                driverIdStr,
                Duration.ofSeconds(10)
        );
    }
}