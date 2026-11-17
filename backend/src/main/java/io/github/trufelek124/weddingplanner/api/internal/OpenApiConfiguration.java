package io.github.trufelek124.weddingplanner.api.internal;

import io.github.trufelek124.weddingplanner.api.ProblemCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shapes the generated API description, from which both clients generate their types:
 * every field of a response is always present, and every operation can answer with a Problem.
 */
@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
class OpenApiConfiguration {

    private static final String PROBLEM = "Problem";

    @Bean
    OpenAPI apiDescription() {
        // The version here is the contract's, not the build's: it changes when clients must change.
        return new OpenAPI()
                .info(new Info().title("Wedding planner API").version("1"))
                .servers(List.of(new Server().url("/")));
    }

    @Bean
    OpenApiCustomizer contractRules() {
        return api -> {
            if (api.getComponents() == null) {
                api.setComponents(new Components());
            }
            if (api.getComponents().getSchemas() != null) {
                api.getComponents().getSchemas().values().forEach(OpenApiConfiguration::requireEveryProperty);
            }
            api.getComponents().addSchemas(PROBLEM, problemSchema());
            if (api.getPaths() != null) {
                api.getPaths().values().forEach(path -> path.readOperations()
                        .forEach(operation -> operation.getResponses().addApiResponse("default", problemResponse())));
            }
        };
    }

    private static void requireEveryProperty(Schema<?> schema) {
        if (schema.getProperties() != null) {
            schema.setRequired(new ArrayList<>(schema.getProperties().keySet()).stream().sorted().toList());
        }
    }

    private static Schema<?> problemSchema() {
        List<String> types = Arrays.stream(ProblemCode.values()).map(code -> code.type().toString()).toList();
        StringSchema type = new StringSchema();
        type.setEnum(types);
        // Generators that turn the values into constants take the constants' names from here.
        type.addExtension("x-enum-varnames", Arrays.stream(ProblemCode.values()).map(Enum::name).toList());
        type.setDescription("The kind of error. Clients decide what to do from this value.");
        return new ObjectSchema()
                .description("An error, as RFC 9457 problem details.")
                .addProperty("type", type)
                .addProperty("title", new StringSchema().description("The kind of error in words."))
                .addProperty("status", new IntegerSchema().description("The HTTP status of the response."))
                .addProperty("detail", new StringSchema().description("What went wrong this time, if there is more to say."))
                .addProperty("instance", new StringSchema().description("The path that was asked for."))
                .required(List.of("status", "title", "type"));
    }

    private static ApiResponse problemResponse() {
        Schema<?> reference = new Schema<>().$ref("#/components/schemas/" + PROBLEM);
        return new ApiResponse()
                .description("Any error.")
                .content(new Content().addMediaType("application/problem+json", new MediaType().schema(reference)));
    }
}
