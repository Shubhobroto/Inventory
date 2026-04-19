package com.example.Uber.service;

public interface MatchingService {
    void findNearbyDriversAndNotify(Long bookingId);
    public void notifyNextDriver(Long bookingId);
}
