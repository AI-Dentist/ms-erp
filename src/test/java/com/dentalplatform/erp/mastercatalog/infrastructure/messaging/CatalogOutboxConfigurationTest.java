package com.dentalplatform.erp.mastercatalog.infrastructure.messaging;

import com.dentalplatform.erp.mastercatalog.infrastructure.outbox.CatalogOutboxSettings;
import com.dentalplatform.erp.shared.infrastructure.config.RabbitMQConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.boot.amqp.autoconfigure.ConnectionFactoryCustomizer;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogOutboxConfigurationTest {
    @Test
    void actualJavaConfigEnablesCorrelatedMandatoryReturnsWithoutReplacingConverter() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "outbox-test", java.util.Map.of(
                    "erp.messaging.exchange", "test.exchange",
                    "erp.messaging.dead-letter-exchange", "test.dlx",
                    "erp.messaging.consulta-finalizada-queue", "test.queue",
                    "erp.messaging.consulta-finalizada-dlq", "test.dlq",
                    "erp.messaging.consulta-finalizada-routing-key", "test.key")));
            context.registerBean(JsonMapper.class, () -> new JsonMapper());
            context.register(RabbitMQConfig.class);
            context.refresh();
            var client = new com.rabbitmq.client.ConnectionFactory();
            context.getBean(ConnectionFactoryCustomizer.class).customize(client);
            assertThat(client.getConnectionTimeout()).isEqualTo(2000);
            assertThat(client.getHandshakeTimeout()).isEqualTo(2000);
            assertThat(client.getChannelRpcTimeout()).isEqualTo(2000);
            var connection = new CachingConnectionFactory(client);
            try {
                var template = new RabbitTemplate(connection);
                var converter = context.getBean(JacksonJsonMessageConverter.class);
                template.setMessageConverter(converter);
                context.getBean(RabbitTemplateCustomizer.class).customize(template);
                assertThat(connection.isPublisherConfirms()).isTrue();
                assertThat(connection.isPublisherReturns()).isTrue();
                assertThat(template.isMandatoryFor(new Message(new byte[0], new MessageProperties()))).isTrue();
                assertThat(template.getMessageConverter()).isSameAs(converter);
            } finally {
                connection.destroy();
            }
        }
    }

    @Test
    void exponentialRetryIsCappedAndCounterOverflowCannotResetTheDelay() {
        var settings = new CatalogOutboxSettings(1, 2000, 1000, 300000);
        assertThat(settings.backoffMs(1)).isEqualTo(1000);
        assertThat(settings.backoffMs(2)).isEqualTo(2000);
        assertThat(settings.backoffMs(9)).isEqualTo(256000);
        assertThat(settings.backoffMs(10)).isEqualTo(300000);
        assertThat(settings.backoffMs(Integer.MAX_VALUE)).isEqualTo(300000);
    }

    @Test
    void invalidResourceBoundsFailFast() {
        assertThatThrownBy(() -> new CatalogOutboxSettings(11, 2000, 1000, 300000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogOutboxSettings(0, 2000, 1000, 300000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogOutboxSettings(1, 3001, 1000, 300000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogOutboxSettings(1, 49, 1000, 300000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogOutboxSettings(1, 2000, 99, 300000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogOutboxSettings(1, 2000, 1000, 999)).isInstanceOf(IllegalArgumentException.class);
    }
}
