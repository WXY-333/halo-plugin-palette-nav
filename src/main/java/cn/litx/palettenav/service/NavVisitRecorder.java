package cn.litx.palettenav.service;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

/**
 * Fire-and-forget queue for public page visits.
 *
 * <p>Rendering {@code /nav} must never wait for statistics bookkeeping. Instead of
 * subscribing inside the render pipeline, the router emits a visit event and a single
 * long-lived subscription drains it. The subscription is opened by
 * {@code PaletteNavPlugin#start()} and drained before being disposed by
 * {@code PaletteNavPlugin#stop()}, so the reactive graph contains no nested
 * subscriptions and failures surface in one place.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NavVisitRecorder {

    /**
     * Stats writes are cheap but can conflict with each other. A small concurrency
     * keeps a slow cleanup task from blocking all later visits.
     */
    private static final int MAX_CONCURRENT_RECORDS = 4;
    /** Bounds memory if statistics writes fall behind a traffic burst. */
    private static final int MAX_PENDING_VISITS = 4096;
    /** Upper bound for flushing buffered visits while the plugin is stopping. */
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(3);

    private final NavStatsService statsService;
    /**
     * Volatile because {@link #record} runs on request threads while {@link #start}
     * replaces the sink on (re)start. Unicast sinks accept a single subscriber, so a
     * fresh sink is required if the same context is ever started again.
     */
    private volatile Sinks.Many<VisitEvent> events = newSink();
    /** Counts down when the drain subscription terminates; recreated per start(). */
    private CountDownLatch drainComplete;
    private Disposable subscription;

    /** Opens the single drain subscription; called after the plugin context is ready. */
    public synchronized void start() {
        if (subscription != null && !subscription.isDisposed()) {
            return;
        }
        events = newSink();
        var latch = new CountDownLatch(1);
        drainComplete = latch;
        subscription = events.asFlux()
            .flatMap(event -> statsService.recordVisit(
                event.forwardedFor(), event.realIp(), event.remoteAddress(),
                event.userAgent(), event.trustedProxies()),
                MAX_CONCURRENT_RECORDS)
            .subscribe(null, error -> {
                log.warn("Palette Nav visit recorder failed", error);
                latch.countDown();
            }, latch::countDown);
    }

    /**
     * Stops accepting new visits and lets the drain subscription finish.
     *
     * <p>Disposing the subscription directly would cancel in-flight visit pipelines and
     * drop every buffered event. Completing the sink instead drains the queue first;
     * the bounded wait only exists so plugin shutdown cannot hang forever. Cancellation
     * after the timeout can still leave a visitor record without its counter bump, which
     * only matters at shutdown.</p>
     */
    public synchronized void stop() {
        var current = subscription;
        if (current == null) {
            return;
        }
        subscription = null;
        var latch = drainComplete;
        drainComplete = null;
        events.tryEmitComplete();
        if (latch == null) {
            current.dispose();
            return;
        }
        try {
            if (!latch.await(DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Timed out draining pending Palette Nav visits on shutdown");
                current.dispose();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            current.dispose();
        }
    }

    /**
     * Enqueues one page view. Never blocks and never fails the caller; a terminated
     * sink (plugin stopping) simply drops the event.
     */
    public void record(String forwardedFor, String realIp, String remoteAddress,
        String userAgent, String trustedProxies) {
        var result = events.tryEmitNext(
            new VisitEvent(forwardedFor, realIp, remoteAddress, userAgent, trustedProxies));
        if (result.isFailure()) {
            log.debug("Dropped a Palette Nav visit event: {}", result);
        }
    }

    private static Sinks.Many<VisitEvent> newSink() {
        return Sinks.many().unicast()
            .onBackpressureBuffer(new ArrayBlockingQueue<>(MAX_PENDING_VISITS));
    }

    private record VisitEvent(String forwardedFor, String realIp, String remoteAddress,
        String userAgent, String trustedProxies) {
    }
}