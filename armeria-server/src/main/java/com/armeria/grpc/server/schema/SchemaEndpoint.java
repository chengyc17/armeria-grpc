package com.armeria.grpc.server.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

public class SchemaEndpoint extends AbstractHttpService {

    private final GrpcJsonSchemaGenerator generator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SchemaEndpoint(GrpcJsonSchemaGenerator generator) {
        this.generator = generator;
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
        try {
            String json = objectMapper.writeValueAsString(generator.getSchemas());
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, json);
        } catch (Exception e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8,
                    "Failed to serialize schemas: " + e.getMessage());
        }
    }
}
