package com.dentalplatform.erp.shared.infrastructure.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.boot.amqp.autoconfigure.ConnectionFactoryCustomizer;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * RabbitMQ topology: the shared topic exchange, the queue of finished clinical consultations (saga entry point) with
 * its dead-letter exchange/queue, and a JSON message converter. Spring Boot applies the converter bean to the
 * auto-configured {@code RabbitTemplate} and listener containers.
 */
@Configuration
public class RabbitMQConfig {

    @Bean
    TopicExchange erpTopicExchange(@Value("${erp.messaging.exchange}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    DirectExchange erpDeadLetterExchange(@Value("${erp.messaging.dead-letter-exchange}") String exchange) {
        return new DirectExchange(exchange, true, false);
    }

    @Bean
    Queue consultaFinalizadaQueue(@Value("${erp.messaging.consulta-finalizada-queue}") String queue,
                                  @Value("${erp.messaging.dead-letter-exchange}") String deadLetterExchange,
                                  @Value("${erp.messaging.consulta-finalizada-dlq}") String deadLetterQueue) {
        return QueueBuilder.durable(queue)
                .deadLetterExchange(deadLetterExchange)
                .deadLetterRoutingKey(deadLetterQueue)
                .build();
    }

    @Bean
    Queue consultaFinalizadaDeadLetterQueue(@Value("${erp.messaging.consulta-finalizada-dlq}") String deadLetterQueue) {
        return QueueBuilder.durable(deadLetterQueue).build();
    }

    @Bean
    Binding consultaFinalizadaBinding(Queue consultaFinalizadaQueue, TopicExchange erpTopicExchange,
                                      @Value("${erp.messaging.consulta-finalizada-routing-key}") String routingKey) {
        return BindingBuilder.bind(consultaFinalizadaQueue).to(erpTopicExchange).with(routingKey);
    }

    @Bean
    Binding consultaFinalizadaDeadLetterBinding(Queue consultaFinalizadaDeadLetterQueue,
                                                DirectExchange erpDeadLetterExchange,
                                                @Value("${erp.messaging.consulta-finalizada-dlq}") String dlq) {
        return BindingBuilder.bind(consultaFinalizadaDeadLetterQueue).to(erpDeadLetterExchange).with(dlq);
    }

    @Bean
    RabbitTemplateCustomizer confirmedMandatoryPublishing() {
        return template -> {
            if (!(template.getConnectionFactory() instanceof CachingConnectionFactory factory)) {
                throw new IllegalStateException("Catalog outbox requires CachingConnectionFactory");
            }
            factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            factory.setPublisherReturns(true);
            template.setMandatory(true);
        };
    }

    @Bean
    ConnectionFactoryCustomizer boundedRabbitConnections() {
        return factory -> {
            factory.setConnectionTimeout(2000);
            factory.setHandshakeTimeout(2000);
            factory.setChannelRpcTimeout(2000);
        };
    }

    @Bean
    JacksonJsonMessageConverter jsonMessageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper);
    }
}
