package com.nannoq.tools.web.responsehandlers;

import io.vertx.core.Handler;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.RoutingContext;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static com.nannoq.tools.web.requestHandlers.RequestLogHandler.*;

/**
 * This interface defines the ResponseLogHandler. It starts the logging process, to be concluded by the
 * responseloghandler.
 *
 * @author Anders Mikkelsen
 * @version 17.11.2017
 */
public class ResponseLogHandler implements Handler<RoutingContext> {
    private static final Logger logger = LoggerFactory.getLogger(ResponseLogHandler.class.getSimpleName());

    public static final String BODY_CONTENT_TAG = "bodyContent";
    public static final String DEBUG_INFORMATION_OBJECT = "debugInfo";

    @Override
    public void handle(RoutingContext routingContext) {
        String uniqueToken = routingContext.get(REQUEST_ID_TAG);
        int statusCode = routingContext.response().getStatusCode();
        Object body = routingContext.get(BODY_CONTENT_TAG);
        Object debug = routingContext.get(DEBUG_INFORMATION_OBJECT);
        long processStartTime = routingContext.get(REQUEST_PROCESS_TIME_TAG);
        long processTimeInNano = System.nanoTime() - processStartTime;
        long totalProcessTime = TimeUnit.NANOSECONDS.toMillis(processTimeInNano);

        routingContext.response().putHeader("X-Nannoq-Debug", uniqueToken);
        routingContext.response().putHeader("X-Internal-Time-To-Process", String.valueOf(totalProcessTime));

        if (statusCode >= 400 || statusCode == 202) {
            routingContext.response().putHeader("Access-Control-Allow-Origin", "*");
            routingContext.response().putHeader("Access-Control-Allow-Credentials", "false");
            routingContext.response().putHeader("Access-Control-Allow-Methods", "POST, GET, PUT, DELETE, OPTIONS");
            routingContext.response().putHeader("Access-Control-Allow-Headers", "DNT,Authorization,X-Real-IP,X-Forwarded-For,Keep-Alive,User-Agent,X-Requested-With,If-None-Match,Cache-Control,Content-Type");
        }

        StringBuffer sb = buildLogs(routingContext, statusCode, uniqueToken, body, debug);

        if (body == null) {
            routingContext.response().end();
        } else {
            routingContext.response().end((body instanceof String) ? body.toString() : Json.encode(body));
        }

        outputLog(statusCode, sb);
    }

    public static StringBuffer buildLogs(RoutingContext routingContext, int statusCode,
                                  String uniqueToken, Object body, Object debug) {
        StringBuffer stringBuilder = routingContext.get(REQUEST_LOG_TAG);
        final StringBuffer sb  = stringBuilder == null ? new StringBuffer() : stringBuilder;

        sb.append("\n--- ").append("Logging Frame End: ").append(uniqueToken).append(" ---\n");
        sb.append("\n--- ").append("Request Frame : ").append(statusCode).append(" ---\n");
        sb.append("\nHeaders:\n");

        routingContext.request().headers().forEach(var -> {
            if (var.getKey().equalsIgnoreCase("Authorization")) {
                sb.append(var.getKey()).append(" : ").append("[FILTERED]").append("\n");
            } else {
                sb.append(var.getKey()).append(" : ").append(var.getValue()).append("\n");
            }
        });

        String requestBody = null;
        JsonObject bodyObject = null;

        try {
            requestBody = routingContext.getBodyAsString();

            if (routingContext.getBody() != null && routingContext.getBody().getBytes().length > 0) {
                try {
                    bodyObject = new JsonObject(requestBody);
                } catch (DecodeException ignored) {}
            }
        } catch (Exception e) {
            logger.debug("Parse exception!", e);
        }

        sb.append("\nRequest Body:\n").append(bodyObject == null ? requestBody : bodyObject.encodePrettily());
        sb.append("\n--- ").append("End Request: ").append(uniqueToken).append(" ---");
        sb.append("\n--- ").append("Debug Info: ").append(uniqueToken).append(" ---");
        sb.append("\n").append(debug == null ? null : (debug instanceof String) ? debug.toString() :
                Json.encodePrettily(debug)).append("\n");
        sb.append("\n--- ").append("End Debug Info: ").append(uniqueToken).append(" ---");
        sb.append("\n--- ").append("Response: ").append(uniqueToken).append(" ---");
        sb.append("\nHeaders:\n");

        routingContext.response().headers().forEach(var ->
                sb.append(var.getKey()).append(" : ").append(var.getValue()).append("\n"));

        sb.append("\nResponse Body:\n").append(body == null ? null : (body instanceof String) ? body.toString() :
                Json.encodePrettily(body));
        sb.append("\n--- ").append("Request Frame : ").append(statusCode).append(" ---\n");
        sb.append("\n--- ").append("End Request Logging: ").append(uniqueToken).append(" ---\n");

        return sb;
    }

    private static void outputLog(int statusCode, StringBuffer sb) {
        if (statusCode == 200) {
            if (logger.isDebugEnabled()) {
                logger.error(sb.toString());
            }
        } else if (statusCode >= 400 && statusCode < 500) {
            logger.error(sb.toString());
        } else if (statusCode >= 500) {
            logger.error(sb.toString());
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug(sb.toString());
            }
        }
    }
}