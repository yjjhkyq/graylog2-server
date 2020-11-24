package org.graylog2.shared.messageq.pulsar;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.inject.assistedinject.Assisted;
import org.apache.pulsar.client.api.CompressionType;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerInterceptor;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.graylog2.shared.messageq.MessageQueueException;
import org.graylog2.shared.messageq.MessageQueueWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.codahale.metrics.MetricRegistry.name;
import static java.nio.charset.StandardCharsets.UTF_8;

public class PulsarMessageQueueWriter extends AbstractIdleService implements MessageQueueWriter {
    public interface Factory extends MessageQueueWriter.Factory<PulsarMessageQueueWriter> {
        @Override
        PulsarMessageQueueWriter create(@Assisted("name") String name);
    }

    private static final Logger LOG = LoggerFactory.getLogger(PulsarMessageQueueWriter.class);

    private final String name;
    private final String topic;
    private final String serviceUrl;

    private final CountDownLatch latch = new CountDownLatch(1);
    private final Meter messageMeter;
    private final Counter byteCounter;
    private final Meter byteMeter;
    private final Timer writeTimer;

    private PulsarClient client;
    private Producer<byte[]> producer;

    @Inject
    public PulsarMessageQueueWriter(MetricRegistry metricRegistry, @Assisted("name") String name) {
        this.name = name;
        this.topic = name + "-message-queue"; // TODO: Make configurable
        this.serviceUrl = "pulsar://localhost:6650"; // TODO: Make configurable

        this.messageMeter = metricRegistry.meter(name("system.message-queue.pulsar", name, "writer.messages"));
        this.byteCounter = metricRegistry.counter(name("system.message-queue.pulsar", name, "writer.byte-count"));
        this.byteMeter = metricRegistry.meter(name("system.message-queue.pulsar", name, "writer.bytes"));
        this.writeTimer = metricRegistry.timer(name("system.message-queue.pulsar", name, "write.writes"));
    }

    @Override
    protected void startUp() throws Exception {
        LOG.info("Starting pulsar message queue writer service: {}", name);

        this.client = PulsarClient.builder()
                .serviceUrl(serviceUrl)
                .build();
        this.producer = client.newProducer(Schema.BYTES)
                .topic(topic)
                .producerName(name)
                .compressionType(CompressionType.ZSTD)
                .enableBatching(true)
                .batchingMaxMessages(1000)
                .batchingMaxPublishDelay(1, TimeUnit.MILLISECONDS)
                .intercept(new MessageInterceptor())
                .create();

        // Service is ready for writing
        latch.countDown();
    }

    @Override
    protected void shutDown() throws Exception {
        if (producer != null) {
            producer.close();
        }
        if (client != null) {
            client.close();
            client.shutdown();
        }
    }

    @Override
    public Entry createEntry(byte[] id, @Nullable byte[] key, byte[] value, long timestamp) {
        return new PulsarMessageQueueEntry(id, key, value, timestamp);
    }

    @Override
    public void write(List<Entry> entries) throws MessageQueueException {
        try {
            latch.await();
        } catch (InterruptedException e) {
            LOG.info("Got interrupted", e);
            Thread.currentThread().interrupt();
            return;
        }
        if (!isRunning()) {
            throw new MessageQueueException("Message queue service is not running");
        }
        for (final Entry entry : entries) {
            final TypedMessageBuilder<byte[]> newMessage = producer.newMessage().value(entry.value());

            final byte[] key = entry.key();
            if (key != null) {
                newMessage.key(new String(key, UTF_8));
            }

            // Pulsar only accepts timestamps > 0
            if (entry.timestamp() > 0) {
                newMessage.eventTime(entry.timestamp());
            }

            LOG.info("Sending message {} (producer {})", entry, producer.getProducerName());
            try (final Timer.Context ignored = writeTimer.time()) {
                newMessage.send();
            } catch (PulsarClientException e) {
                throw new MessageQueueException("Couldn't send entry <" + entry.toString() + ">", e);
            }
        }
    }

    private class MessageInterceptor implements ProducerInterceptor<byte[]> {
        @Override
        public void close() {

        }

        @Override
        public Message<byte[]> beforeSend(Producer<byte[]> producer, Message<byte[]> message) {
            final int length = message.getData().length;

            messageMeter.mark();
            byteCounter.inc(length);
            byteMeter.mark(length);

            return message;
        }

        @Override
        public void onSendAcknowledgement(Producer<byte[]> producer, Message<byte[]> message, MessageId msgId, Throwable exception) {

        }
    }
}
