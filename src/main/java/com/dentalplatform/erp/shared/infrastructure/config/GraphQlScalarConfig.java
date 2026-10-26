package com.dentalplatform.erp.shared.infrastructure.config;

import com.dentalplatform.erp.shared.domain.vo.Money;
import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * Scalars of the schema: UUID, BigDecimal (amounts in Bs, also accepts {@link Money}), Date (ISO-8601 date)
 * and DateTime (ISO-8601 with offset).
 */
@Configuration
public class GraphQlScalarConfig {

    @Bean
    RuntimeWiringConfigurer scalarWiring() {
        return wiring -> wiring
                .scalar(UUID_SCALAR)
                .scalar(BIG_DECIMAL_SCALAR)
                .scalar(DATE_SCALAR)
                .scalar(DATE_TIME_SCALAR);
    }

    public static final GraphQLScalarType UUID_SCALAR = stringScalar("UUID", "RFC 4122 UUID",
            UUID::fromString, value -> value instanceof UUID uuid ? uuid.toString() : null);

    public static final GraphQLScalarType DATE_SCALAR = stringScalar("Date", "ISO-8601 date (yyyy-MM-dd)",
            LocalDate::parse, value -> value instanceof LocalDate date ? date.toString() : null);

    public static final GraphQLScalarType DATE_TIME_SCALAR = stringScalar("DateTime", "ISO-8601 date-time with offset",
            OffsetDateTime::parse, GraphQlScalarConfig::formatDateTime);

    public static final GraphQLScalarType BIG_DECIMAL_SCALAR = GraphQLScalarType.newScalar()
            .name("BigDecimal")
            .description("Decimal amount, serialized as a JSON number")
            .coercing(new Coercing<BigDecimal, BigDecimal>() {
                @Override
                public BigDecimal serialize(Object value, GraphQLContext context, Locale locale) {
                    return switch (value) {
                        case BigDecimal decimal -> decimal;
                        case Money money -> money.amount();
                        case Number number -> new BigDecimal(number.toString());
                        default -> throw new CoercingSerializeException("Not a decimal: " + value);
                    };
                }

                @Override
                public BigDecimal parseValue(Object input, GraphQLContext context, Locale locale) {
                    try {
                        return new BigDecimal(input.toString());
                    } catch (NumberFormatException e) {
                        throw new CoercingParseValueException("Invalid decimal: " + input);
                    }
                }

                @Override
                public BigDecimal parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context,
                                               Locale locale) {
                    return switch (input) {
                        case FloatValue f -> f.getValue();
                        case IntValue i -> new BigDecimal(i.getValue());
                        case StringValue s -> parseValue(s.getValue(), context, locale);
                        default -> throw new CoercingParseLiteralException("Invalid decimal literal: " + input);
                    };
                }
            })
            .build();

    private static String formatDateTime(Object value) {
        return switch (value) {
            case OffsetDateTime odt -> odt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            case ZonedDateTime zdt -> zdt.toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            case Instant instant -> instant.toString();
            default -> null;
        };
    }

    private static <T> GraphQLScalarType stringScalar(String name, String description, Function<String, T> parser,
                                                      Function<Object, String> formatter) {
        return GraphQLScalarType.newScalar()
                .name(name)
                .description(description)
                .coercing(new Coercing<T, String>() {
                    @Override
                    public String serialize(Object value, GraphQLContext context, Locale locale) {
                        String formatted = formatter.apply(value);
                        if (formatted == null) {
                            throw new CoercingSerializeException("Cannot serialize " + value + " as " + name);
                        }
                        return formatted;
                    }

                    @Override
                    public T parseValue(Object input, GraphQLContext context, Locale locale) {
                        try {
                            return parser.apply(input.toString());
                        } catch (RuntimeException e) {
                            throw new CoercingParseValueException("Invalid " + name + ": " + input);
                        }
                    }

                    @Override
                    public T parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context,
                                          Locale locale) {
                        if (!(input instanceof StringValue string)) {
                            throw new CoercingParseLiteralException(name + " must be a string literal");
                        }
                        try {
                            return parser.apply(string.getValue());
                        } catch (RuntimeException e) {
                            throw new CoercingParseLiteralException("Invalid " + name + ": " + string.getValue());
                        }
                    }
                })
                .build();
    }
}
