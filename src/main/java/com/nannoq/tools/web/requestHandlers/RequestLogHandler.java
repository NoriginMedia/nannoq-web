package com.nannoq.tools.web.requestHandlers;

import io.vertx.core.Handler;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.RoutingContext;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.rmi.server.UID;

/**
 * This interface defines the RequestLogHandler. It starts the logging process, to be concluded by the
 * responseloghandler.
 *
 * @author Anders Mikkelsen
 * @version 17.11.2017
 */
public class RequestLogHandler implements Handler<RoutingContext> {
    private static final Logger logger = LoggerFactory.getLogger(RequestLogHandler.class.getSimpleName());

    public static final String REQUEST_PROCESS_TIME_TAG = "processTimeTag";
    public static final String REQUEST_ID_TAG = "uniqueRequestId";
    public static final String REQUEST_LOG_TAG = "requestLog";

    @Override
    public void handle(RoutingContext routingContext) {
        routingContext.put(REQUEST_PROCESS_TIME_TAG, System.nanoTime());
        String uniqueToken = new UID().toString();
        routingContext.put(REQUEST_ID_TAG, uniqueToken);
        StringBuffer sb = new StringBuffer();

        sb.append("\n--- ").append("Start Request Logging: ").append(uniqueToken).append(" ---\n");
        sb.append("\n--- ").append(routingContext.request().rawMethod())
                .append(" : ")
                .append(routingContext.request().absoluteURI()).append(" ")
                .append(uniqueToken).append(" ---\n");
        sb.append("\n--- ").append("Logging Frame: ").append(uniqueToken).append(" ---\n");
        routingContext.put(REQUEST_LOG_TAG, sb);

        routingContext.next();
    }

    public static void addLogMessageToRequestLog(RoutingContext routingContext, String message) {
        addLogMessageToRequestLog(routingContext, message, null);
    }

    public static void addLogMessageToRequestLog(RoutingContext routingContext, String message, Throwable t) {
        StringBuffer sb = routingContext.get(REQUEST_LOG_TAG);

        if (sb != null) {
            sb.append("\n\n").append(message).append("\n\n");

            if (t != null) {
                sb.append("\n\n").append(ExceptionUtils.getStackTrace(t)).append("\n\n");
            }
        } else {
            logger.warn("Routinglogger not available, printing: " + message, t);
        }
    }
}
