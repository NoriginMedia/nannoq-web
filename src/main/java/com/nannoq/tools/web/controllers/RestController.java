/*
 * MIT License
 *
 * Copyright (c) 2017 Anders Mikkelsen
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package com.nannoq.tools.web.controllers;

import com.nannoq.tools.repository.models.ETagable;
import com.nannoq.tools.repository.models.Model;
import com.nannoq.tools.repository.models.ModelUtils;
import com.nannoq.tools.repository.models.ValidationError;
import com.nannoq.tools.repository.utils.*;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.apache.commons.lang3.ArrayUtils;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Function;

import static com.nannoq.tools.repository.models.Model.buildValidationErrorObject;
import static com.nannoq.tools.web.RoutingHelper.*;
import static com.nannoq.tools.web.requestHandlers.RequestLogHandler.REQUEST_PROCESS_TIME_TAG;
import static com.nannoq.tools.web.requestHandlers.RequestLogHandler.addLogMessageToRequestLog;
import static com.nannoq.tools.web.responsehandlers.ResponseLogHandler.BODY_CONTENT_TAG;

/**
 * This interface defines the RestController. It defines a chain of operations for CRUD and Index operations. Overriding
 * functions must remember to call the next element in the chain.
 *
 * @author Anders Mikkelsen
 * @version 17.11.2017
 */
public interface RestController<E extends ETagable & Model> {
    default void show(RoutingContext routingContext) {
        try {
            preShow(routingContext);
        } catch (Exception e) {
            addLogMessageToRequestLog(routingContext, "Error in Show!", e);

            routingContext.fail(e);
        }
    }

    default void preShow(RoutingContext routingContext) {
        performShow(routingContext);
    }

    void performShow(RoutingContext routingContext);

    default void postShow(RoutingContext routingContext, E item, @Nonnull String[] projections) {
        long initialNanoTime = routingContext.get(REQUEST_PROCESS_TIME_TAG);
        String requestEtag = routingContext.request().getHeader("If-None-Match");

        if (requestEtag != null && requestEtag.equals(item.getEtag())) {
            unChangedShow(routingContext);
        } else {
            routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8");
            routingContext.put(BODY_CONTENT_TAG, item.toJsonString(projections));

            setStatusCodeAndContinue(200, routingContext, initialNanoTime);
        }
    }

    default void unChangedShow(RoutingContext routingContext) {
        long initialNanoTime = routingContext.get(REQUEST_PROCESS_TIME_TAG);

        setStatusCodeAndContinue(304, routingContext, initialNanoTime);
    }

    default void notFoundShow(RoutingContext routingContext) {
        long initialNanoTime = routingContext.get(REQUEST_PROCESS_TIME_TAG);

        setStatusCodeAndAbort(404, routingContext, initialNanoTime);
    }

    default void failedShow(RoutingContext routingContext, JsonObject debugInformation) {
        long initialNanoTime = routingContext.get(REQUEST_PROCESS_TIME_TAG);

        routingContext.put(BODY_CONTENT_TAG, debugInformation.encode());
        setStatusCodeAndAbort(500, routingContext, initialNanoTime);
    }

    default void index(RoutingContext routingContext) {
        try {
            preIndex(routingContext, null);
        } catch (Exception e) {
            addLogMessageToRequestLog(routingContext, "Error in Index!", e);

            routingContext.fail(e);
        }
    }

    default void index(RoutingContext routingContext, String customQuery) {
        try {
            preIndex(routingContext, customQuery);
        } catch (Exception e) {
            addLogMessageToRequestLog(routingContext, "Error in Index!", e);

            routingContext.fail(e);
        }
    }

    default void preIndex(RoutingContext routingContext, String customQuery) {
        prepareQuery(routingContext, customQuery);
    }

    void prepareQuery(RoutingContext routingContext, String customQuery);

    default void preProcessQuery(RoutingContext routingContext, Map<String, List<String>> queryMap) {
        processQuery(routingContext, queryMap);
    }

    void processQuery(RoutingContext routingContext, Map<String, List<String>> queryMap);

    default void postProcessQuery(RoutingContext routingContext, AggregateFunction aggregateFunction,
                                  Queue<OrderByParameter> orderByQueue, Map<String, List<FilterParameter>> params,
                                  @Nonnull String[] projections, String indexName, Integer limit) {
        postPrepareQuery(routingContext, aggregateFunction, orderByQueue, params, projections, indexName, limit);
    }

    default void postPrepareQuery(RoutingContext routingContext, AggregateFunction aggregateFunction,
                                  Queue<OrderByParameter> orderByQueue, Map<String, List<FilterParameter>> params,
                                  String[] projections, String indexName, Integer limit) {
        createIdObjectForIndex(routingContext, aggregateFunction, orderByQueue, params, projections, indexName, limit);
    }

    void createIdObjectForIndex(RoutingContext routingContext, AggregateFunction aggregateFunction,
                                Queue<OrderByParameter> orderByQueue, Map<String, List<FilterParameter>> params,
                                String[] projections, String indexName, Integer limit);

    void performIndex(RoutingContext routingContext, JsonObject identifiers, AggregateFunction aggregateFunction,
                      Queue<OrderByParameter> orderByQueue, Map<String, List<FilterParameter>> params,
                      String[] projections, String indexName, Integer limit);

    void proceedWithPagedIndex(JsonObject id, String pageToken,
                               QueryPack queryPack, String[] projections, RoutingContext routingContext);

    void proceedWithAggregationIndex(RoutingContext routingContext, String etag, JsonObject id,
                                     QueryPack queryPack, String[] projections);

    default void postIndex(RoutingContext routingContext, @Nonnull ItemList<E> items, @Nonnull String[] projections) {
        long initialNanoTime = routingContext.get(REQUEST_PROCESS_TIME_TAG);
        String requestEtag = routingContext.request().getHeader("If-None-Match");

        if (requestEtag != null && requestEtag.equals(items.getEtag())) {
            unChangedIndex(routingContext);
        } else {
            String content = items.toJsonString(projections);

            routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8");
            routingContext.put(BODY_CONTENT_TAG, content);

            setStatusCodeAndContinue(200, routingContext, initialNanoTime);
        }
    }

    default void postAggregation(RoutingContext routingContext, @Nonnull String content) {
        long initialNanoTime = routingContext.get(REQUEST_PROCESS_TIME_TAG);
        String requestEtag = routingContext.request().getHeader("If-None-Match");

        if (requestEtag != null && requestEtag.equals(ModelUtils.returnNewEtag(content.hashCode()))) {
            unChangedIndex(routingContext);
        } else {
            routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8");
            routingContext.put(BODY_CONTENT_TAG, content);

            setStatusCodeAndContinue(200, routingContext, initialNanoTime);
        }
    }

    default void unChangedIndex(RoutingContext routingContext) {
        long initialNanoTime = routingContext.get(REQUEST_PROCESS_TIME_TAG);

        setStatusCodeAndContinue(304, routingContext, initialNanoTime);
    }

    default void failedIndex(RoutingContext routingContext, JsonObject debugInformation) {
        long initialNanoTime = routingContext.get(REQUEST_PROCESS_TIME_TAG);

        routingContext.put(BODY_CONTENT_TAG, debugInformation.encode());
        setStatusCodeAndAbort(500, routingContext, initialNanoTime);
    }

    default void create(RoutingContext routingContext) {
        try {
            preCreate(routingContext);
        } catch (Exception e) {
            addLogMessageToRequestLog(routingContext, "Error in Create!", e);

            routingContext.fail(e);
        }
    }

    default void preCreate(RoutingContext routingContext) {
        if (denyQuery(routingContext)) return;

        parseBodyForCreate(routingContext);
    }

    void parseBodyForCreate(RoutingContext routingContext);

    default void preVerifyNotExists(E newRecord, RoutingContext routingContext) {
        verifyNotExists(newRecord, routingContext);
    }

    void verifyNotExists(E newRecord, RoutingContext routingContext);

    default void postVerifyNotExists(E newRecord, RoutingContext routingContext) {
        preSetIdentifiers(newRecord, routingContext);
    }

    default void preSetIdentifiers(E newRecord, RoutingContext routingContext) {
        setIdentifiers(newRecord, routingContext);
    }

    void setIdentifiers(E newRecord, RoutingContext routingContext);

    default void preSanitizeForCreate(E record, RoutingContext routingContext) {
        performSanitizeForCreate(record, routingContext);
    }

    default void performSanitizeForCreate(E record, RoutingContext routingContext) {
        record.sanitize();

        postSanitizeForCreate(record, routingContext);
    }

    default void postSanitizeForCreate(E record, RoutingContext routingContext) {
        preValidateForCreate(record, routingContext);
    }

    default void preValidateForCreate(E record, RoutingContext routingContext) {
        performValidateForCreate(record, routingContext);
    }

    default void performValidateForCreate(E record, RoutingContext routingContext) {
        long initialNanoTime = routingContext.get(REQUEST_PROCESS_TIME_TAG);
        List<ValidationError> errors = record.validateCreate();

        if (errors.size() > 0) {
            routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8");
            routingContext.put(BODY_CONTENT_TAG, Json.encodePrettily(buildValidationErrorObject(errors)));
            setStatusCodeAndAbort(422, routingContext, initialNanoTime);
        } else {
            postValidateForCreate(record, routingContext);
        }
    }

    default void postValidateForCreate(E record, RoutingContext routingContext) {
        performCreate(record, routingContext);
    }

    void performCreate(E newRecord, RoutingContext routingContext);

    default void postCreate(@Nonnull E createdRecord, RoutingContext routingContext) {
        long initialNanoTime = routingContext.get(REQUEST_PROCESS_TIME_TAG);

        routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8");
        routingContext.put(BODY_CONTENT_TAG, createdRecord.toJsonString());
        setStatusCodeAndContinue(201, routingContext, initialNanoTime);
    }

    default void failedCreate(RoutingContext routingContext, JsonObject userFeedBack) {
        long initialNanoTime = routingContext.get(REQUEST_PROCESS_TIME_TAG);

        if (userFeedBack != null) {
            routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8");
            routingContext.put(BODY_CONTENT_TAG, userFeedBack.encode());
        }

        setStatusCodeAndContinue(500, routingContext, initialNanoTime);
    }

    default void update(RoutingContext routingContext) {
        try {
            preUpdate(routingContext);
        } catch (Exception e) {
            addLogMessageToRequestLog(routingContext, "Error in Update!", e);

            routingContext.fail(e);
        }
    }

    default void preUpdate(RoutingContext routingContext) {
        if (denyQuery(routingContext)) return;

        parseBodyForUpdate(routingContext);
    }

    void parseBodyForUpdate(RoutingContext routingContext);

    default void preVerifyExistsForUpdate(E newRecord, RoutingContext routingContext) {
        verifyExistsForUpdate(newRecord, routingContext);
    }

    void verifyExistsForUpdate(E newRecord, RoutingContext routingContext);

    default void postVerifyExistsForUpdate(E oldRecord, E newRecord, RoutingContext routingContext) {
        preSanitizeForUpdate(oldRecord, newRecord, routingContext);
    }

    default void preSanitizeForUpdate(E record, E newRecord, RoutingContext routingContext) {
        performSanitizeForUpdate(record, newRecord, routingContext);
    }

    default void performSanitizeForUpdate(E record, E newRecord, RoutingContext routingContext) {
        Function<E, E> setNewValues = rec -> {
            rec.setModifiables(newRecord);
            rec.sanitize();

            return rec;
        };

        postSanitizeForUpdate(setNewValues.apply(record), setNewValues, routingContext);
    }

    default void postSanitizeForUpdate(E record, Function<E, E> setNewValues, RoutingContext routingContext) {
        preValidateForUpdate(record, setNewValues, routingContext);
    }

    default void preValidateForUpdate(E record, Function<E, E> setNewValues, RoutingContext routingContext) {
        performValidateForUpdate(record, setNewValues, routingContext);
    }

    default void performValidateForUpdate(E record, Function<E, E> setNewValues, RoutingContext routingContext) {
        long initialNanoTime = routingContext.get(REQUEST_PROCESS_TIME_TAG);
        record.setUpdatedAt(new Date());
        List<ValidationError> errors = record.validateUpdate();

        if (errors.size() > 0) {
            routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8");
            routingContext.put(BODY_CONTENT_TAG, Json.encodePrettily(buildValidationErrorObject(errors)));
            setStatusCodeAndAbort(422, routingContext, initialNanoTime);
        } else {
            postValidateForUpdate(record, setNewValues, routingContext);
        }
    }

    default void postValidateForUpdate(E record, Function<E, E> setNewValues, RoutingContext routingContext) {
        performUpdate(record, setNewValues, routingContext);
    }

    void performUpdate(E updatedRecord, Function<E, E> setNewValues, RoutingContext routingContext);

    default void postUpdate(@Nonnull E updatedRecord, RoutingContext routingContext) {
        long initialNanoTime = routingContext.get(REQUEST_PROCESS_TIME_TAG);

        routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8");
        routingContext.put(BODY_CONTENT_TAG, updatedRecord.toJsonString());
        setStatusCodeAndContinue(200, routingContext, initialNanoTime);
    }

    default void failedUpdate(RoutingContext routingContext, JsonObject userFeedBack) {
        long initialNanoTime = routingContext.get(REQUEST_PROCESS_TIME_TAG);

        if (userFeedBack != null) {
            routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8");
            routingContext.put(BODY_CONTENT_TAG, userFeedBack.encode());
        }

        setStatusCodeAndContinue(500, routingContext, initialNanoTime);
    }

    default void destroy(RoutingContext routingContext) {
        try {
            preDestroy(routingContext);
        } catch (Exception e) {
            addLogMessageToRequestLog(routingContext, "Error in Destroy!", e);

            routingContext.fail(e);
        }
    }

    default void preDestroy(RoutingContext routingContext) {
        if (denyQuery(routingContext)) return;

        verifyExistsForDestroy(routingContext);
    }

    void verifyExistsForDestroy(RoutingContext routingContext);

    default void postVerifyExistsForDestroy(E recordForDestroy, RoutingContext routingContext) {
        performDestroy(recordForDestroy, routingContext);
    }

    void performDestroy(E recordForDestroy, RoutingContext routingContext);

    default void postDestroy(@Nonnull E destroyedRecord, RoutingContext routingContext) {
        long initialNanoTime = routingContext.get(REQUEST_PROCESS_TIME_TAG);

        setStatusCodeAndContinue(204, routingContext, initialNanoTime);
    }

    default void failedDestroy(RoutingContext routingContext, JsonObject userFeedBack) {
        long initialNanoTime = routingContext.get(REQUEST_PROCESS_TIME_TAG);

        if (userFeedBack != null) {
            routingContext.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8");
            routingContext.put(BODY_CONTENT_TAG, userFeedBack.encode());
        }

        setStatusCodeAndContinue(500, routingContext, initialNanoTime);
    }

    default Field[] getAllFieldsOnType(Class klazz) {
        Field[] fields = klazz.getDeclaredFields();

        if (klazz.getSuperclass() != null && klazz.getSuperclass() != Object.class) {
            return ArrayUtils.addAll(fields, getAllFieldsOnType(klazz.getSuperclass()));
        }

        return fields;
    }

    default Method[] getAllMethodsOnType(Class klazz) {
        Method[] methods = klazz.getDeclaredMethods();

        if (klazz.getSuperclass() != null && klazz.getSuperclass() != Object.class) {
            return ArrayUtils.addAll(methods, getAllMethodsOnType(klazz.getSuperclass()));
        }

        return methods;
    }
}
