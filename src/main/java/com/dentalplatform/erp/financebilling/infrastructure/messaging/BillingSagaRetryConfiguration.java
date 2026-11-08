package com.dentalplatform.erp.financebilling.infrastructure.messaging;

import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.LongConsumer;

@Configuration(proxyBeanMethods = false)
public class BillingSagaRetryConfiguration {
    @Bean
    public SimpleRabbitListenerContainerFactory billingSagaRawListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        // No transport JSON/Java deserialization, regardless of content type or __TypeId__ headers.
        factory.setMessageConverter(new MessageConverter() {
            @Override
            public Object fromMessage(Message message) { return message.getBody(); }

            @Override
            public Message toMessage(Object object, MessageProperties properties) {
                throw new UnsupportedOperationException("Billing saga listener does not send replies");
            }
        });
        return factory;
    }

    @Bean
    public static LongConsumer billingSagaRetryPause() {
        return delay -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ImmediateRequeueAmqpException("Billing saga retry interrupted", interrupted);
            }
        };
    }
}
