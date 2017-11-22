package com.nannoq.tools.web;

import com.nannoq.tools.web.requestHandlers.ETagVerificationHandler;
import com.nannoq.tools.web.requestHandlers.RequestLogHandler;
import com.nannoq.tools.web.responsehandlers.ResponseLogHandler;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.ResponseContentTypeHandler;
import io.vertx.ext.web.handler.ResponseTimeHandler;
import io.vertx.ext.web.handler.TimeoutHandler;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.nannoq.tools.web.responsehandlers.ResponseLogHandler.BODY_CONTENT_TAG;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

/**
 * This class contains helper methods for routing requests.
 *
 * @author Anders Mikkelsen
 * @version 17.11.2017
 */
public class RoutingHelper {
    private static final Logger logger = LoggerFactory.getLogger(RoutingHelper.class.getSimpleName());

    private static final String DATABASE_PROCESS_TIME = "X-Database-Time-To-Process";

    public static final RequestLogHandler requestLogger = new RequestLogHandler();
    public static final ResponseLogHandler responseLogger = new ResponseLogHandler();

    private static final BodyHandler bodyHandler = BodyHandler.create().setMergeFormAttributes(true);
    private static final TimeoutHandler timeOutHandler = routingContext -> {
        routingContext.vertx().setTimer(9000L, time -> {
            if (!routingContext.request().isEnded()) routingContext.fail(503);
        });

        routingContext.next();
    };

    private static final ResponseContentTypeHandler responseContentTypeHandler = ResponseContentTypeHandler.create();
    private static final ResponseTimeHandler responseTimeHandler = ResponseTimeHandler.create();

    public static void setStatusCode(int code, RoutingContext routingContext,
                                     long initialProcessTime) {
        setDatabaseProcessTime(routingContext, initialProcessTime);
        routingContext.response().setStatusCode(code);
    }

    public static void setStatusCodeAndAbort(int code, RoutingContext routingContext,
                                             long initialProcessTime) {
        setDatabaseProcessTime(routingContext, initialProcessTime);
        routingContext.fail(code);
    }

    public static void setStatusCodeAndContinue(int code, RoutingContext routingContext) {
        routingContext.response().setStatusCode(code);
        routingContext.next();
    }

    public static void setStatusCodeAndContinue(int code, RoutingContext routingContext,
                                                long initialProcessTime) {
        setDatabaseProcessTime(routingContext, initialProcessTime);
        routingContext.response().setStatusCode(code);
        routingContext.next();
    }

    private static void setDatabaseProcessTime(RoutingContext routingContext, long initialTime) {
        long processTimeInNano = System.nanoTime() - initialTime;
        routingContext.response().putHeader(DATABASE_PROCESS_TIME,
                String.valueOf(TimeUnit.NANOSECONDS.toMillis(processTimeInNano)));
    }

    public static void routeWithAuth(Supplier<Route> routeProducer, Handler<RoutingContext> authHandler,
                                     Consumer<Supplier<Route>> routeSetter) {
        routeWithAuth(routeProducer, authHandler, null, routeSetter);
    }

    public static void routeWithAuth(Supplier<Route> routeProducer, Handler<RoutingContext> authHandler,
                                     Handler<RoutingContext> finallyHandler,
                                     Consumer<Supplier<Route>> routeSetter) {
        prependStandards(routeProducer);
        routeProducer.get().handler(authHandler);
        routeSetter.accept(routeProducer);
        appendStandards(routeProducer, finallyHandler);
    }

    public static void routeWithBodyHandlerAndAuth(Supplier<Route> routeProducer, Handler<RoutingContext> authHandler,
                                                   Consumer<Supplier<Route>> routeSetter) {
        routeWithBodyHandlerAndAuth(routeProducer, authHandler, null, routeSetter);
    }

    @SuppressWarnings("Duplicates")
    public static void routeWithBodyHandlerAndAuth(Supplier<Route> routeProducer, Handler<RoutingContext> authHandler,
                                                   Handler<RoutingContext> finallyHandler,
                                                   Consumer<Supplier<Route>> routeSetter) {
        prependStandards(routeProducer);
        routeProducer.get().handler(bodyHandler);
        routeProducer.get().handler(authHandler);
        routeSetter.accept(routeProducer);
        appendStandards(routeProducer, finallyHandler);
    }

    public static void routeWithAuthAndEtagVerification(Supplier<Route> routeProducer,
                                                        ETagVerificationHandler etag, Handler<RoutingContext> authHandler,
                                                        Consumer<Supplier<Route>> routeSetter) {
        routeWithBodyHandlerAndAuth(routeProducer, etag, authHandler, routeSetter);
    }

    @SuppressWarnings("Duplicates")
    public static void routeWithAuthAndEtagVerification(Supplier<Route> routeProducer,
                                                        ETagVerificationHandler etag, Handler<RoutingContext> authHandler,
                                                        Handler<RoutingContext> finallyHandler,
                                                        Consumer<Supplier<Route>> routeSetter) {
        prependStandards(routeProducer);
        routeProducer.get().handler(etag);
        routeProducer.get().handler(authHandler);
        routeSetter.accept(routeProducer);
        appendStandards(routeProducer, finallyHandler);
    }

    private static void prependStandards(Supplier<Route> routeProducer) {
        routeProducer.get().handler(responseTimeHandler);
        routeProducer.get().handler(timeOutHandler);
        routeProducer.get().handler(responseContentTypeHandler);
        routeProducer.get().handler(requestLogger);
    }

    private static void appendStandards(Supplier<Route> routeProducer, Handler<RoutingContext> finallyHandler) {
        routeProducer.get().failureHandler(RoutingHelper::handleErrors);
        if (finallyHandler != null) routeProducer.get().handler(finallyHandler);
        routeProducer.get().handler(responseLogger);
    }

    public static void routeWithLogger(Supplier<Route> routeProducer,
                                       Consumer<Supplier<Route>> routeSetter) {
        routeWithLogger(routeProducer, null, routeSetter);
    }

    public static void routeWithLogger(Supplier<Route> routeProducer,
                                       Handler<RoutingContext> finallyHandler,
                                       Consumer<Supplier<Route>> routeSetter) {
        routeProducer.get().handler(responseTimeHandler);
        routeProducer.get().handler(timeOutHandler);
        routeProducer.get().handler(responseContentTypeHandler);
        routeProducer.get().handler(requestLogger);
        routeSetter.accept(routeProducer);
        routeProducer.get().failureHandler(RoutingHelper::handleErrors);
        if (finallyHandler != null) routeProducer.get().handler(finallyHandler);
        routeProducer.get().handler(responseLogger);
    }

    public static void handleErrors(RoutingContext routingContext) {
        int statusCode = routingContext.statusCode();

        routingContext.response().setStatusCode(statusCode != -1 ? statusCode : 500);

        routingContext.next();
    }

    public static boolean denyQuery(RoutingContext routingContext) {
        String query = routingContext.request().query();

        if (query != null && !routingContext.request().rawMethod().equalsIgnoreCase("GET")) {
            return true;
        } else if (query != null) {
            Map<String, List<String>> queryMap = splitQuery(query);

            if (queryMap == null) {
                routingContext.put(BODY_CONTENT_TAG, new JsonObject()
                        .put("query_error", "Cannot parse this query string, are you sure it is in UTF-8?"));
                routingContext.fail(400);
            } else if (queryMap.size() > 1 || (queryMap.size() == 1 && queryMap.get("projection") == null)) {
                routingContext.put(BODY_CONTENT_TAG, new JsonObject()
                        .put("query_error", "No query accepted for this route"));
                routingContext.fail(400);

                return true;
            }
        }

        return false;
    }

    public static Map<String, List<String>> splitQuery(String query) {
        String decoded;

        try {
            decoded = URLDecoder.decode(query, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            logger.error(e);

            return null;
        }

        return Arrays.stream(decoded.split("&"))
                .map(RoutingHelper::splitQueryParameter)
                .collect(Collectors.groupingBy(
                        AbstractMap.SimpleImmutableEntry::getKey,
                        LinkedHashMap::new,
                        mapping(Map.Entry::getValue, toList())));
    }

    private static AbstractMap.SimpleImmutableEntry<String, String> splitQueryParameter(String it) {
        final int idx = it.indexOf("=");
        final String key = idx > 0 ? it.substring(0, idx) : it;
        final String value = idx > 0 && it.length() > idx + 1 ? it.substring(idx + 1) : null;

        return new AbstractMap.SimpleImmutableEntry<>(key, value);
    }
}
