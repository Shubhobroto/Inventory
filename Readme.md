# Uber Backend System 🚀

## Features
- Booking lifecycle (PENDING → CONFIRMED)
- Redis-based driver matching
- RabbitMQ async processing
- WebSocket driver notifications
- Driver accept/reject flow
- Timeout-based retry

## Tech Stack
- Spring Boot
- Redis
- RabbitMQ
- WebSocket
- MySQL / H2

## How to Run
1. Start Redis
2. Start RabbitMQ
3. Run Spring Boot app
4. Test APIs via Bruno/Postman

## Architecture
Event-driven microservice-style backend inspired by Uber.



## Sample Data

```

[
    {
        "driverId": "4",
        "latitude": 77.20499955275207,
        "longitude": 28.60700100660324
    },
    {
        "driverId": "2",
        "latitude": 77.2089993427415,
        "longitude": 28.613899648189545
    },
    {
        "driverId": "3",
        "latitude": 77.21499902772567,
        "longitude": 28.61999899148941
    },
    {
        "driverId": "5",
        "latitude": 77.21499902772567,
        "longitude": 28.61999899148941
    }
]

```
