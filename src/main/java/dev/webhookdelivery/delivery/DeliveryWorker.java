package dev.webhookdelivery.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("worker")
@EnableScheduling
public class DeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
    private final DeliveryRepository jobs;
    private final WebhookClient client;

    public DeliveryWorker(DeliveryRepository jobs, WebhookClient client) {
        this.jobs = jobs;
        this.client = client;
    }

    @Scheduled(fixedDelayString = "${delivery.poll-ms}")
    public void poll() throws InterruptedException {
        for (int i = 0; i < 32; i++) {
            boolean recovered = jobs.recoverOneExpired();
            var claim = jobs.claim();
            if (claim.isEmpty()) {
                if (recovered) continue;
                return;
            }
            var job = claim.get();
            var result = client.send(job);
            if (!jobs.finish(job, result)) log.info("Lease expired for {} attempt {}", job.eventId(), job.number());
        }
    }
}
