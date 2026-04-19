package com.example.Uber.serviceimpl;

import com.example.Uber.configuration.RabbitMQConfig;
import com.example.Uber.dto.BookingRequest;
import com.example.Uber.dto.BookingResponse;
import com.example.Uber.entity.Booking;
import com.example.Uber.entity.Driver;
import com.example.Uber.entity.Passenger;
import com.example.Uber.mapper.BookingMapper;
import com.example.Uber.repository.BookingRepository;
import com.example.Uber.repository.DriverRepository;
import com.example.Uber.repository.PassengerRepository;
import com.example.Uber.service.BookingService;
import com.example.Uber.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.Uber.event.RideRequestEvent;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class BookingServiceImpl implements BookingService {

    private final BookingRepository bookingRepository;
    private final PassengerRepository passengerRepository;
    private final DriverRepository driverRepository;
    private final BookingMapper bookingMapper;
    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redisTemplate;
    private final NotificationService notificationService;


    @Override
    @Transactional(readOnly = true)
    public Optional<BookingResponse> findById(Long id) {
        return bookingRepository.findById(id)
                .map(bookingMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingResponse> findAll() {
        return bookingRepository.findAll().stream()
                .map(bookingMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingResponse> findByPassengerId(Long passengerId) {
        Passenger passenger = passengerRepository.findById(passengerId)
                .orElseThrow(() -> new IllegalArgumentException("Passenger not found with id: " + passengerId));

        return bookingRepository.findByPassenger(passenger).stream()
                .map(bookingMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingResponse> findByDriverId(Long driverId) {
        Driver driver = driverRepository.findById(driverId)
                .orElseThrow(() -> new IllegalArgumentException("Driver not found with id: " + driverId));

        return bookingRepository.findByDriver(driver).stream()
                .map(bookingMapper::toResponse)
                .toList();
    }

    @Override
    public BookingResponse create(BookingRequest request) {

        // 1. Validate passenger
        Passenger passenger = passengerRepository.findById(request.getPassengerId())
                .orElseThrow(() -> new IllegalArgumentException("Passenger not found"));

        // 2. Validate pickup location
        if (request.getPickupLocationLatitude() == null || request.getPickupLocationLongitude() == null) {
            throw new IllegalArgumentException("Pickup location is required");
        }

        // 3. Default fare
        BigDecimal fare = request.getFare() != null ? request.getFare() : BigDecimal.ZERO;

        // 4. Create booking (INITIAL STATE)
        Booking booking = Booking.builder()
                .passenger(passenger)
                .pickupLocationLatitude(request.getPickupLocationLatitude().toString())
                .pickupLocationLongitude(request.getPickupLocationLongitude().toString())
                .dropoffLocation(request.getDropoffLocation())
                .status(Booking.BookingStatus.PENDING)
                .fare(fare)
                .scheduledPickupTime(request.getScheduledPickupTime())
                .build();

        // 5. Save booking
        Booking savedBooking = bookingRepository.save(booking);

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.QUEUE,
                new RideRequestEvent(savedBooking.getId())
        );

        // 6. CALL MATCHING SERVICE (IMPORTANT) but will be pushed to RABBITMQ
//        matchingService.matchDriver(savedBooking.getId());

        // 7. Return response
        return bookingMapper.toResponse(savedBooking);
    }

    @Override
    public BookingResponse update(Long id, BookingRequest request) {
        return null; // skip for now
    }

    @Override
    public BookingResponse updateStatus(Long id, Booking.BookingStatus status) {

        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found"));

        booking.setStatus(status);

        if (status == Booking.BookingStatus.IN_PROGRESS && booking.getActualPickupTime() == null) {
            booking.setActualPickupTime(LocalDateTime.now());
        }

        if (status == Booking.BookingStatus.COMPLETED || status == Booking.BookingStatus.CANCELLED) {
            booking.setCompletedAt(LocalDateTime.now());

            if (booking.getDriver() != null) {
                Driver driver = booking.getDriver();
                driver.setIsAvailable(true);
                driverRepository.save(driver);
            }
        }

        Booking updatedBooking = bookingRepository.save(booking);
        return bookingMapper.toResponse(updatedBooking);
    }

    @Override
    public void deleteById(Long id) {
        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found"));

        if (booking.getDriver() != null) {
            Driver driver = booking.getDriver();
            driver.setIsAvailable(true);
            driverRepository.save(driver);
        }

        bookingRepository.deleteById(id);
    }

    @Transactional
    @Override
    public Boolean acceptRide(Long bookingId, Long driverId) {

        // 1. Check if this driver is the current eligible driver
        String currentDriver = redisTemplate.opsForValue()
                .get("ride:" + bookingId + ":current");

        if (currentDriver == null || !currentDriver.equals(driverId.toString())) {
            System.out.println("❌ Not your turn or timeout");
            return false;
        }

        // 2. Fetch driver
        Driver driver = driverRepository.findById(driverId)
                .orElseThrow(() -> new IllegalArgumentException("Driver not found"));

        // 3. Atomic DB update (race condition safe)
        int updatedRows = bookingRepository.assignDriverIfPending(bookingId, driver);

        if (updatedRows == 0) {
            System.out.println("❌ Ride already accepted by another driver");
            return false;
        }

        // 4. SUCCESS → clean Redis state
        redisTemplate.delete("ride:" + bookingId + ":current");
        redisTemplate.delete("ride:" + bookingId + ":drivers");

        // 5. (Optional but IMPORTANT) notify rider
        notificationService.sendBookingUpdate(
                bookingId,
                "CONFIRMED"
        );

        System.out.println("✅ Driver " + driverId + " successfully assigned");
        driver.setIsAvailable(false);
        driverRepository.save(driver);

        return true;
    }

    @Override
    public void rejectRide(Long bookingId, Long driverId) {

        String currentDriver = redisTemplate.opsForValue()
                .get("ride:" + bookingId + ":current");

        // only current driver can reject
        if (currentDriver == null || !currentDriver.equals(driverId.toString())) {
            System.out.println("❌ Not your turn to reject");
            return;
        }

        System.out.println("❌ Driver " + driverId + " rejected booking " + bookingId);

        // remove current driver key → triggers next driver
        redisTemplate.delete("ride:" + bookingId + ":current");
    }


}