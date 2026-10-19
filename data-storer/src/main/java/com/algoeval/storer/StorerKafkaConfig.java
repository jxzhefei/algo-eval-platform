package com.algoeval.storer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;

/**
 * data-storer 消费配置（还原真实攒批调优参数）：
 * fetch.min.bytes=64KB + fetch.max.wait.ms=500（牺牲最多 0.5s 延迟换吞吐）、
 * max.poll.records=1000（单批上限）、ByteArrayDeserializer（绕过框架序列化开销，自研反序列化）。
 * 手动提交 offset：先入库再提交，宕机重复消费由 upsert 幂等兜底。
 */
@Configuration
public class StorerKafkaConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> batchKafkaListenerContainerFactory(
            ConsumerFactory<String, byte[]> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    /** 攒批消费接口约定 */
    public interface BatchHandler {
        void onBatch(List<ConsumerRecord<String, byte[]>> records, Acknowledgment ack);
    }
}
