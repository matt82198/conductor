# notification/ — Notification Router (Phase 1)

## Responsibility
Route classified messages to the right delivery channel based on urgency. Desktop notifications for critical items, WebSocket for everything else.

## Status: Phase 1 Complete

## Contracts

### Consumes
- `dev.conductor.server.queue.QueuedMessageEvent` (from queue/) via `QueueEventBridge` @EventListener
- Also accepts `dev.conductor.server.notification.QueuedMessageEvent` (local type) via `NotificationRouter` @EventListener

### Provides
- `NotificationRouter.route(NotificationPayload)` — sends to appropriate channel(s)
- `DndManager.enable()` / `disable()` / `isEnabled()` — Do Not Disturb state

### Events Published
```java
// Sent to Spring event bus by WebSocketChannel for UI delivery
WebSocketChannel.NotificationEvent(NotificationPayload payload, String channel, Instant sentAt)
```

## Files
| File | Purpose |
|------|---------|
| `NotificationChannel.java` | Interface: `send(NotificationPayload)` + `channelName()` |
| `WebSocketChannel.java` | @Component — publishes NotificationEvent to Spring event bus for WebSocket delivery |
| `DesktopChannel.java` | @Component — logs at INFO level (placeholder for Electron notifications) |
| `NotificationRouter.java` | @Service — routes by urgency to channel(s), respects DND |
| `DndManager.java` | @Service — AtomicBoolean toggle for Do Not Disturb |
| `NotificationPayload.java` | Record: agentId, text, urgency, timestamp |
| `QueuedMessageEvent.java` | Local event type (notification-domain's own, for decoupled testing) |
| `Urgency.java` | Local enum with `bypassesDnd()` and `isDropped()` helpers |
| `QueueEventBridge.java` | @Component — bridges queue/ domain events into notification routing pipeline |

## Tests
| Test File | Coverage |
|-----------|----------|
| `DndManagerTest.java` | Default state, enable/disable, idempotency, toggle cycles |
| `NotificationRouterTest.java` | Routing by urgency, DND suppression, DND bypass, channel error isolation, payload preservation |
| `QueueEventBridgeTest.java` | Urgency mapping, end-to-end routing through bridge, DND through bridge, payload fidelity, error isolation |

## Routing Rules
| Urgency | Channel | DND Behavior |
|---------|---------|-------------|
| CRITICAL | WebSocket + Desktop | Always delivered |
| HIGH | WebSocket + Desktop | Suppressed during DND |
| MEDIUM | WebSocket only | Suppressed during DND |
| LOW | WebSocket only | Suppressed during DND |
| NOISE | Dropped | Dropped |

## Key Design
- NotificationChannel is an interface — new channels (email, Slack) plug in later by adding a @Component
- Channel discovery is automatic via Spring DI (List<NotificationChannel> injection)
- DND state is a simple boolean toggle (AtomicBoolean), exposed programmatically; REST endpoint is future work
- DesktopChannel logs only in Phase 1; future: POST to Electron app's local notification API
- WebSocketChannel publishes NotificationEvent to Spring event bus (decoupled from api/ WebSocket internals)
- QueueEventBridge adapts between queue/ domain's event type and notification/ domain's own types
- Urgency mapping is by enum name with MEDIUM as fail-safe default for unknown values
- All channel sends are isolated — one channel failure does not block others
