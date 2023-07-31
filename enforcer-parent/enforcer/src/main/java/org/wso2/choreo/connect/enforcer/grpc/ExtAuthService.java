/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.choreo.connect.enforcer.grpc;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.rpc.Code;
import com.google.rpc.Status;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.envoyproxy.envoy.service.auth.v3.DeniedHttpResponse;
import io.envoyproxy.envoy.service.auth.v3.OkHttpResponse;
import io.envoyproxy.envoy.type.v3.HttpStatus;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.apache.logging.log4j.ThreadContext;
import org.json.JSONObject;
import org.wso2.choreo.connect.enforcer.api.ResponseObject;
import org.wso2.choreo.connect.enforcer.constants.APIConstants;
import org.wso2.choreo.connect.enforcer.constants.HttpConstants;
import org.wso2.choreo.connect.enforcer.constants.MetadataConstants;
import org.wso2.choreo.connect.enforcer.constants.RouterAccessLogConstants;
import org.wso2.choreo.connect.enforcer.metrics.MetricsExporter;
import org.wso2.choreo.connect.enforcer.metrics.MetricsManager;
import org.wso2.choreo.connect.enforcer.server.HttpRequestHandler;
import org.wso2.choreo.connect.enforcer.tracing.TracingConstants;
import org.wso2.choreo.connect.enforcer.tracing.TracingContextHolder;
import org.wso2.choreo.connect.enforcer.tracing.TracingSpan;
import org.wso2.choreo.connect.enforcer.tracing.TracingTracer;
import org.wso2.choreo.connect.enforcer.tracing.Utils;

import java.util.List;
import java.util.Map;

/**
 * This is the gRPC server written to match with the envoy ext-authz filter
 * proto file. Envoy proxy call this service.
 * This is the entry point to the filter chain process for a request.
 */
public class ExtAuthService extends AuthorizationGrpc.AuthorizationImplBase {

    private HttpRequestHandler requestHandler = new HttpRequestHandler();

    @Override
    public void check(CheckRequest request, StreamObserver<CheckResponse> responseObserver) {
        TracingSpan extAuthServiceSpan = null;
        Scope extAuthServiceSpanScope = null;
        long starTimestamp = System.currentTimeMillis();
        try {
            String traceId = request.getAttributes().getRequest().getHttp()
                    .getHeadersOrDefault(HttpConstants.X_REQUEST_ID_HEADER,
                            request.getAttributes().getRequest().getHttp().getId());
            if (Utils.tracingEnabled()) {
                TracingTracer tracer =  Utils.getGlobalTracer();
                Context parentContext = TracingContextHolder.getInstance().getContext();
                // This span will be the parent span for all the filters
                extAuthServiceSpan = Utils.startSpan(TracingConstants.EXT_AUTH_SERVICE_SPAN, parentContext, tracer);
                extAuthServiceSpanScope = extAuthServiceSpan.getSpan().makeCurrent();
                Utils.setTag(extAuthServiceSpan, APIConstants.LOG_TRACE_ID, traceId);
            }
            ThreadContext.put(APIConstants.LOG_TRACE_ID, traceId);
            ResponseObject responseObject = requestHandler.process(request);
            CheckResponse response = buildResponse(request, responseObject);
            responseObserver.onNext(response);
            // When you are done, you must call onCompleted.
            responseObserver.onCompleted();
            ThreadContext.remove(APIConstants.LOG_TRACE_ID);
        } finally {
            if (Utils.tracingEnabled()) {
                extAuthServiceSpanScope.close();
                Utils.finishSpan(extAuthServiceSpan);
            }
            if (MetricsManager.isMetricsEnabled()) {
                MetricsExporter metricsExporter = MetricsManager.getInstance();
                metricsExporter.trackMetric("enforcerLatency", System.currentTimeMillis() - starTimestamp);
            }
        }
    }

    private CheckResponse buildResponse(CheckRequest request, ResponseObject responseObject) {
        DeniedHttpResponse.Builder responseBuilder = DeniedHttpResponse.newBuilder();
        HttpStatus status = HttpStatus.newBuilder().setCodeValue(responseObject.getStatusCode()).build();
        String traceKey = request.getAttributes().getRequest().getHttp().getId();
        Struct.Builder structBuilder = Struct.newBuilder();
        // Used to identify that the choreo-connect-enforcer handled the request. It is used to
        // provide local reply for authentication failures.
        addMetadata(structBuilder, MetadataConstants.CHOREO_CONNECT_ENFORCER_REPLY,
                MetadataConstants.CHOREO_CONNECT_ENFORCER_REPLY_OK);
        addMetadata(structBuilder, "correlationID", responseObject.getCorrelationID());
        if (responseObject.isDirectResponse()) {
            if (responseObject.getHeaderMap() != null) {
                responseObject.getHeaderMap().forEach((key, value) -> {
                            HeaderValueOption headerValueOption = HeaderValueOption.newBuilder()
                                    .setHeader(HeaderValue.newBuilder().setKey(key).setValue(value).build())
                                    .build();
                            responseBuilder.addHeaders(headerValueOption);
                        }
                );
            }
            // To handle pre flight options request
            if (responseObject.getStatusCode() == HttpConstants.NO_CONTENT_STATUS_CODE) {
                includeMetadataForAccessLogs(structBuilder, request, responseObject);
                return CheckResponse.newBuilder()
                        .setStatus(Status.newBuilder().setCode(getCode(responseObject.getStatusCode())))
                        .setDeniedResponse(responseBuilder.setStatus(status).build())
                        .setDynamicMetadata(structBuilder.build())
                        .build();
            }
            // Error handling
            JSONObject responseJson = new JSONObject();
            responseJson.put(APIConstants.MessageFormat.ERROR_CODE, responseObject.getErrorCode());
            responseJson.put(APIConstants.MessageFormat.ERROR_MESSAGE, responseObject.getErrorMessage());
            responseJson.put(APIConstants.MessageFormat.ERROR_DESCRIPTION, responseObject.getErrorDescription());
            HeaderValueOption headerValueOption = HeaderValueOption.newBuilder().setHeader(
                    HeaderValue.newBuilder().setKey(APIConstants.CONTENT_TYPE_HEADER)
                            .setValue(APIConstants.APPLICATION_JSON).build())
                    .setHeader(HeaderValue.newBuilder().setKey(APIConstants.API_TRACE_KEY).setValue(traceKey).build())
                    .build();
            responseBuilder.addHeaders(headerValueOption);

            if (responseObject.getMetaDataMap() != null) {
                responseObject.getMetaDataMap().forEach((key, value) ->
                        addMetadata(structBuilder, key, value));
            }
            includeMetadataForAccessLogs(structBuilder, request, responseObject);

            return CheckResponse.newBuilder()
                    .setStatus(Status.newBuilder().setCode(getCode(responseObject.getStatusCode())))
                    .setDeniedResponse(responseBuilder.setBody(responseJson.toString()).setStatus(status).build())
                    .setDynamicMetadata(structBuilder.build())
                    .build();
        } else {
            OkHttpResponse.Builder okResponseBuilder = OkHttpResponse.newBuilder();

            // If the user is sending the APIKey credentials within query parameters, those query parameters should
            // not be sent to the backend. Hence, the :path header needs to be constructed again removing the apiKey
            // query parameter. In this scenario, apiKey query parameter is sent within the property called
            // 'queryParamsToRemove' so that the custom filters also can utilize the method.
            if (responseObject.getQueryParamsToRemove().size() > 0) {
                String constructedPath = constructQueryParamString(responseObject.getRequestPath(),
                        responseObject.getQueryParamMap(), responseObject.getQueryParamsToRemove());
                HeaderValueOption headerValueOption = HeaderValueOption.newBuilder()
                        .setHeader(HeaderValue.newBuilder().setKey(APIConstants.PATH_HEADER).setValue(constructedPath)
                                .build())
                        .build();
                okResponseBuilder.addHeaders(headerValueOption);
            }

            if (responseObject.getHeaderMap() != null) {
                responseObject.getHeaderMap().forEach((key, value) -> {
                            HeaderValueOption headerValueOption = HeaderValueOption.newBuilder()
                                    .setHeader(HeaderValue.newBuilder().setKey(key).setValue(value).build())
                                    .build();
                            okResponseBuilder.addHeaders(headerValueOption);
                        }
                );
            }
            okResponseBuilder.addAllHeadersToRemove(responseObject.getRemoveHeaderMap());
            if (responseObject.getMetaDataMap() != null) {
                responseObject.getMetaDataMap().forEach((key, value) ->
                        addMetadata(structBuilder, key, value));
            }
            includeMetadataForAccessLogs(structBuilder, request, responseObject);

            HeaderValueOption headerValueOption = HeaderValueOption.newBuilder()
                    .setHeader(HeaderValue.newBuilder().setKey(APIConstants.API_TRACE_KEY).setValue(traceKey).build())
                    .build();
            okResponseBuilder.addHeaders(headerValueOption);
            return CheckResponse.newBuilder().setStatus(Status.newBuilder().setCode(Code.OK_VALUE).build())
                    .setOkResponse(okResponseBuilder.build())
                    .setDynamicMetadata(structBuilder.build())
                    .build();
        }
    }

    private int getCode(int statusCode) {
        switch (statusCode) {
            case 200:
                return Code.OK_VALUE;
            case 401:
                return Code.UNAUTHENTICATED_VALUE;
            case 403:
                return Code.PERMISSION_DENIED_VALUE;
            case 409:
                return Code.RESOURCE_EXHAUSTED_VALUE;
        }
        return Code.INTERNAL_VALUE;
    }

    private String constructQueryParamString(String requestPath, Map<String, String> queryParamMap,
                                             List<String> queryParamsToRemove) {
        // If no query parameters needs to be removed, then the request path can be applied as it is.
        if (queryParamsToRemove.size() == 0) {
            return requestPath;
        }

        String pathWithoutQueryParams = requestPath.split("\\?")[0];
        StringBuilder requestPathBuilder = new StringBuilder(pathWithoutQueryParams);
        int count = 0;
        if (queryParamMap.size() > 0) {
            for (String queryParam : queryParamMap.keySet()) {
                if (queryParamsToRemove.contains(queryParam)) {
                    continue;
                }
                if (count == 0) {
                    requestPathBuilder.append("?");
                } else {
                    requestPathBuilder.append("&");
                }
                requestPathBuilder.append(queryParam);
                if (queryParamMap.get(queryParam) != null) {
                    requestPathBuilder.append("=").append(queryParamMap.get(queryParam));
                }
                count++;
            }
        }
        return requestPathBuilder.toString();
    }

    /**
     * This will include ext auth metadata required for access logs.
     *
     * @param structBuilder  the builder in which the metadata is stored.
     * @param request        check request
     * @param responseObject response object for check response
     */
    private void includeMetadataForAccessLogs(Struct.Builder structBuilder, CheckRequest request,
                                              ResponseObject responseObject) {
        addMetadata(structBuilder, RouterAccessLogConstants.ORIGINAL_PATH_DATA_NAME,
                responseObject.getRequestPath());
        addMetadata(structBuilder, RouterAccessLogConstants.ORIGINAL_HOST_DATA_NAME,
                request.getAttributes().getRequest().getHttp().getHost());
        addMetadata(structBuilder, RouterAccessLogConstants.API_UUID_DATA_NAME,
                responseObject.getApiUuid());
        addMetadata(structBuilder, RouterAccessLogConstants.EXT_AUTH_DETAILS,
                responseObject.getExtAuthDetails());
    }

    /**
     * Adds a given key and value as a metadata
     *
     * @param structBuilder the builder in which the metadata is stored.
     * @param key           Metadata Key
     * @param value         Metadata value
     */
    private void addMetadata(Struct.Builder structBuilder, String key, String value) {
        // Otherwise it could result in a NPE
        if (value == null) {
            return;
        }
        structBuilder.putFields(key, Value.newBuilder().setStringValue(value).build());
    }
}
