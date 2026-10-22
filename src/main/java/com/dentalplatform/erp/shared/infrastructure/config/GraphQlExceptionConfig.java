package com.dentalplatform.erp.shared.infrastructure.config;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.graphql.execution.DataFetcherExceptionResolver;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;

/**
 * Translates domain exceptions into GraphQL errors with a meaningful classification.
 */
@Configuration
public class GraphQlExceptionConfig {

    @Bean
    DataFetcherExceptionResolver businessExceptionResolver() {
        return new DataFetcherExceptionResolverAdapter() {
            @Override
            protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
                return switch (ex) {
                    case BusinessRuleException e -> error(env, ErrorType.BAD_REQUEST, e.getMessage());
                    case ResourceNotFoundException e -> error(env, ErrorType.NOT_FOUND, e.getMessage());
                    case DataIntegrityViolationException e ->
                            error(env, ErrorType.BAD_REQUEST, "El registro viola una restricción de unicidad o integridad");
                    default -> null;
                };
            }
        };
    }

    private static GraphQLError error(DataFetchingEnvironment env, ErrorType type, String message) {
        return GraphqlErrorBuilder.newError(env).errorType(type).message(message).build();
    }
}
