package com.example.Uber.consumer;

import com.example.Uber.configuration.RabbitMQConfig;
import com.example.Uber.event.RideRequestEvent;
import com.example.Uber.service.MatchingService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RideRequestConsumer {

    private final MatchingService matchingService;

    @RabbitListener(queues = RabbitMQConfig.QUEUE)
    public void consume(RideRequestEvent event) {

        System.out.println("Received ride request for booking: " + event.getBookingId());

        try {
            Thread.sleep(5000); // simulate delay
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Thread interrupted during delay", e);
        }

//        matchingService.matchDriver(event.getBookingId());
        matchingService.findNearbyDriversAndNotify(event.getBookingId());

        System.out.println("Matching completed for booking: " + event.getBookingId());
    }
}
