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
 */

package com.nannoq.tools.web.controllers;

import com.google.common.util.concurrent.AtomicDouble;
import com.nannoq.tools.repository.models.ETagable;
import com.nannoq.tools.repository.models.Model;
import com.nannoq.tools.repository.models.ModelUtils;
import com.nannoq.tools.repository.models.ValidationError;
import com.nannoq.tools.repository.repository.Repository;
import com.nannoq.tools.repository.utils.*;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.impl.ConcurrentHashSet;
import io.vertx.core.json.*;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.RoutingContext;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.*;

import static com.nannoq.tools.repository.dynamodb.DynamoDBRepository.PAGINATION_INDEX;
import static com.nannoq.tools.repository.utils.AggregateFunctions.AVG;
import static com.nannoq.tools.repository.utils.AggregateFunctions.COUNT;
import static com.nannoq.tools.web.RoutingHelper.*;
import static com.nannoq.tools.web.requestHandlers.RequestLogHandler.addLogMessageToRequestLog;
import static com.nannoq.tools.web.responsehandlers.ResponseLogHandler.BODY_CONTENT_TAG;
import static java.util.AbstractMap.SimpleEntry;
import static java.util.stream.Collectors.*;

/**
 * This class defines a Handler implementation that handles aggregation queries on multiple models.
 *
 * @author Anders Mikkelsen
 * @version 13/11/17
 */
public class CrossModelAggregationController implements Handler<RoutingContext> {
    private final static Logger logger = LoggerFactory.getLogger(CrossModelAggregationController.class.getSimpleName());

    private static final String PROJECTION_KEY = "projection";
    private static final String PROJECTION_FIELDS_KEY = "fields";
    private static final String FILTER_KEY = "filter";
    private static final String AGGREGATE_KEY = "aggregate";

    private static final String PAGING_TOKEN_KEY = "pageToken";
    private static final String END_OF_PAGING_KEY = "END_OF_LIST";

    private final Map<String, Class> modelMap;
    private final Function<Class, Repository> repositoryProvider;

    public CrossModelAggregationController(Function<Class, Repository> repositoryProvider, Class[] models) {
        this.repositoryProvider = repositoryProvider;
        this.modelMap = buildModelMap(models);
    }

    private Map<String, Class> buildModelMap(Class[] models) {
        return Arrays.stream(models)
                .map(model -> new SimpleEntry<>(buildCollectionName(model.getSimpleName()), model))
                .collect(toMap(SimpleEntry::getKey, SimpleEntry::getValue));
    }

    private String buildCollectionName(String typeName) {
        char c[] = typeName.toCharArray();
        c[0] += 32;

        return new String(c) + "s";
    }

    @Override
    public void handle(RoutingContext routingContext) {
        try {
            final long initialProcessNanoTime = System.nanoTime();
            final HttpServerRequest request = routingContext.request();
            final String query = request.query();

                AggregationPack aggregationPack = verifyRequest(routingContext, query, initialProcessNanoTime);

                if (aggregationPack == null || aggregationPack.aggregate == null) {
                    routingContext.put(BODY_CONTENT_TAG, new JsonObject()
                            .put("request_error", "aggregate function cannot be null!"));

                    setStatusCodeAndContinue(400, routingContext, initialProcessNanoTime);
                } else {
                    performAggregation(routingContext, aggregationPack.getAggregate(), aggregationPack.getProjection(),
                            initialProcessNanoTime);
                }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void performAggregation(RoutingContext routingContext, @Nonnull CrossModelAggregateFunction aggregateFunction,
                                    Map<Class, Set<String>> projection, long initialNanoTime) {
        final HttpServerRequest request = routingContext.request();
        final String route = request.path();
        final String query = request.query();
        final String requestEtag = request.getHeader(HttpHeaders.IF_NONE_MATCH);
        final JsonObject identifier = new JsonObject();
        final AggregateFunctions function = aggregateFunction.getFunction();
        final String[] projections = getProjections(routingContext);

        switch (function) {
            case AVG:
            case SUM:
            case COUNT:
                doValueAggregation(routingContext, route, query, requestEtag, identifier, aggregateFunction, projection,
                        function, projections, initialNanoTime);

                break;
            default:
                JsonObject supportErrorObject = new JsonObject().put("function_support_error",
                        "Function " + function.name() + " is not yet supported...");
                sendQueryErrorResponse(supportErrorObject, routingContext, initialNanoTime);

                break;
        }
    }

    @SuppressWarnings("unchecked")
    private void doValueAggregation(RoutingContext routingContext, String route, String query, String requestEtag,
                                    JsonObject identifier, @Nonnull CrossModelAggregateFunction aggregate,
                                    Map<Class, Set<String>> projection, AggregateFunctions aggregateFunction,
                                    String[] projections, long initialNanoTime) {
        final List<Map<String, Double>> groupingList = new CopyOnWriteArrayList<>();
        final AtomicDouble totalValue = new AtomicDouble();
        final List<Future> aggFutures = new CopyOnWriteArrayList<>();

        if (logger.isDebugEnabled()) {
            addLogMessageToRequestLog(routingContext, "ProjectionMap: " + Json.encodePrettily(projection));
        }

        final BiConsumer<ValueAggregationResultPack, List<Map<String, Double>>> valueResultHandler =
                getResultHandler(aggregate);

        if (valueResultHandler == null) {
            addLogMessageToRequestLog(routingContext, "ResultHandler is null!");

            setStatusCodeAndAbort(500, routingContext, initialNanoTime);
        } else {
            final Map<String, List<String>> queryMap = splitQuery(query);
            @SuppressWarnings("ConstantConditions")
            final FilterPack filterPack = convertToFilterPack(routingContext, queryMap.get(FILTER_KEY));

            projection.keySet().forEach(klazz -> {
                List<GroupingConfiguration> groupingConfigurations = getGroupingConfigurations(aggregate, klazz, true);
                final Comparator<JsonObject> longSorter = buildLongSorter(aggregate, groupingConfigurations);
                final Comparator<JsonObject> doubleSorter = buildDoubleSorter(aggregate, groupingConfigurations);

                final BiConsumer<AggregationResultPack, List<Map<String, Double>>> aggregationResultHandler =
                        getAggregationResultHandler(longSorter, doubleSorter, aggregateFunction);

                Consumer<String> aggregator = field -> {
                    Future<Boolean> fut = Future.future();
                    final AggregateFunction temp = AggregateFunction.builder()
                            .withAggregateFunction(aggregateFunction)
                            .withField(aggregateFunction != COUNT ? field : null)
                            .withGroupBy(groupingConfigurations)
                            .build();
                    Repository repo = repositoryProvider.apply(klazz);

                    if (repo == null) {
                        addLogMessageToRequestLog(routingContext, klazz.getSimpleName() + " is not valid!");

                        Future<Boolean> future = Future.future();
                        aggFutures.add(future);

                        future.fail(new IllegalArgumentException(klazz.getSimpleName() + " is not valid!"));
                    } else {
                        valueAggregation(routingContext, klazz, repositoryProvider.apply(klazz), fut,
                                aggregationResultHandler, identifier, route, requestEtag,
                                aggregateFunction == COUNT ? null : projections,
                                groupingList, totalValue, aggregate, temp, filterPack);

                        aggFutures.add(fut);
                    }
                };

                if (aggregationResultHandler == null) {
                    addLogMessageToRequestLog(routingContext, "AggResultHandler is null!");

                    Future<Boolean> fut = Future.future();
                    aggFutures.add(fut);
                    fut.tryFail(new IllegalArgumentException("AggResultHandler cannot be null!"));
                } else {
                    if (projection.get(klazz).size() == 0) {
                        aggregator.accept(null);
                    } else {
                        projection.get(klazz).forEach(aggregator);
                    }
                }
            });

            CompositeFuture.all(aggFutures).setHandler(res -> {
                if (res.failed()) {
                    addLogMessageToRequestLog(routingContext, "Unknown aggregation error!", res.cause());

                    routingContext.put(BODY_CONTENT_TAG, new JsonObject()
                            .put("unknown_error", "Something went horribly wrong..."));
                    routingContext.response().setStatusCode(500);
                    routingContext.fail(res.cause());
                } else {
                    valueResultHandler.accept(
                            new ValueAggregationResultPack(aggregate, routingContext, initialNanoTime, totalValue),
                            groupingList);
                }
            });
        }
    }

    private String getModelName(Class klazz) {
        return klazz.getSimpleName().toLowerCase() + "s";
    }

    private Comparator<JsonObject> buildLongSorter(CrossModelAggregateFunction function,
                                                   List<GroupingConfiguration> groupingConfigurations) {
        String lowerCaseFunctionName = function.getFunction().name().toLowerCase();
        if (groupingConfigurations.size() > 3) throw new IllegalArgumentException("GroupBy size of three is max!");
        GroupingConfiguration levelOne = groupingConfigurations.size() > 0 ? groupingConfigurations.get(0) : null;
        GroupingConfiguration levelTwo = groupingConfigurations.size() > 1 ? groupingConfigurations.get(1) : null;
        GroupingConfiguration levelThree = groupingConfigurations.size() > 2 ? groupingConfigurations.get(2) : null;
        GroupingConfiguration finalConfig = null;

        if (levelThree != null) {
            finalConfig = levelThree;
        } else if (levelTwo != null) {
            finalConfig = levelTwo;
        } else if (levelOne != null) {
            finalConfig = levelOne;
        }

        return finalConfig == null || finalConfig.getGroupingSortOrder().equals("asc") ?
                Comparator.comparingLong(item -> item.getLong(lowerCaseFunctionName)) :
                Comparator.<JsonObject>comparingLong(item -> item.getLong(lowerCaseFunctionName)).reversed();
    }

    private Comparator<JsonObject> buildDoubleSorter(CrossModelAggregateFunction function,
                                                     List<GroupingConfiguration> groupingConfigurations) {
        String lowerCaseFunctionName = function.getFunction().name().toLowerCase();
        if (groupingConfigurations.size() > 3) throw new IllegalArgumentException("GroupBy size of three is max!");
        GroupingConfiguration levelOne = groupingConfigurations.size() > 0 ? groupingConfigurations.get(0) : null;
        GroupingConfiguration levelTwo = groupingConfigurations.size() > 1 ? groupingConfigurations.get(1) : null;
        GroupingConfiguration levelThree = groupingConfigurations.size() > 2 ? groupingConfigurations.get(2) : null;
        GroupingConfiguration finalConfig = null;

        if (levelThree != null) {
            finalConfig = levelThree;
        } else if (levelTwo != null) {
            finalConfig = levelTwo;
        } else if (levelOne != null) {
            finalConfig = levelOne;
        }

        return finalConfig == null || finalConfig.getGroupingSortOrder().equals("asc") ?
                Comparator.comparingDouble(item -> item.getDouble(lowerCaseFunctionName)) :
                Comparator.<JsonObject>comparingDouble(item -> item.getDouble(lowerCaseFunctionName)).reversed();
    }

    private class AggregationResultPack {
        private final CrossModelAggregateFunction aggregate;
        private final JsonObject result;
        private final AtomicDouble value;

        private AggregationResultPack(CrossModelAggregateFunction aggregate, JsonObject result, AtomicDouble value) {
            this.aggregate = aggregate;
            this.result = result;
            this.value = value;
        }

        CrossModelAggregateFunction getAggregate() {
            return aggregate;
        }

        JsonObject getResult() {
            return result;
        }

        AtomicDouble getValue() {
            return value;
        }
    }

    private BiConsumer<AggregationResultPack, List<Map<String, Double>>> getAggregationResultHandler(
            Comparator<JsonObject> longSorter, Comparator<JsonObject> doubleSorter,
            AggregateFunctions aggregateFunction) {
        BinaryOperator<Double> mergeFunction = (u, v) -> {
            throw new IllegalStateException(String.format("Duplicate key %s", u));
        };

        BiFunction<AggregationResultPack, JsonObject, String> keyMapper = (resultPack, item) -> {
            CrossModelAggregateFunction aggregate = resultPack.getAggregate();

            if (aggregate.hasGrouping() && aggregate.getGroupBy().get(0).hasGroupRanging()) {
                return item.encode();
            } else {
                return item.getString("groupByKey");
            }
        };

        switch (aggregateFunction) {
            case AVG:
                return (resultPack, groupingList) -> {
                    if (resultPack.getAggregate().hasGrouping()) {
                        JsonArray array = resultPack.getResult().getJsonArray("results");

                        logger.debug("Results before sort is: " + array.encodePrettily());
                        logger.debug("Aggregate: " + Json.encodePrettily(aggregateFunction));

                        Map<String, Double> collect = array.stream()
                                .map(itemAsString -> new JsonObject(itemAsString.toString()))
                                .sorted(doubleSorter)
                                .collect(toMap(
                                        item -> keyMapper.apply(resultPack, item),
                                        item -> item.getDouble(aggregateFunction.name().toLowerCase()),
                                        mergeFunction, LinkedHashMap::new));

                        groupingList.add(collect);
                    } else {
                        resultPack.getValue().addAndGet(
                                resultPack.getResult().getDouble(aggregateFunction.name().toLowerCase()));
                    }
                };
            case SUM:
            case COUNT:
                return (resultPack, groupingList) -> {
                    if (resultPack.getAggregate().hasGrouping()) {
                        JsonArray array = resultPack.getResult().getJsonArray("results");

                        logger.debug("Results before sort is: " + array.encodePrettily());
                        logger.debug("Aggregate: " + Json.encodePrettily(aggregateFunction));

                        Map<String, Double> collect = array.stream()
                                .map(itemAsString -> new JsonObject(itemAsString.toString()))
                                .sorted(longSorter)
                                .collect(toMap(
                                        item -> keyMapper.apply(resultPack, item),
                                        item -> item.getLong(aggregateFunction.name().toLowerCase()).doubleValue(),
                                        mergeFunction, LinkedHashMap::new));

                        groupingList.add(collect);
                    } else {
                        resultPack.getValue().addAndGet(
                                resultPack.getResult().getLong(aggregateFunction.name().toLowerCase()));
                    }
                };
            default:
                return null;
        }
    }

    private class ValueAggregationResultPack {
        private final CrossModelAggregateFunction aggregate;
        private final RoutingContext routingContext;
        private final long initTime;
        private final AtomicDouble value;

        private ValueAggregationResultPack(CrossModelAggregateFunction aggregate, RoutingContext routingContext,
                                           long initNanoTime, AtomicDouble value) {
            this.aggregate = aggregate;
            this.routingContext = routingContext;
            this.initTime = initNanoTime;
            this.value = value;
        }

        CrossModelAggregateFunction getAggregate() {
            return aggregate;
        }

        RoutingContext getRoutingContext() {
            return routingContext;
        }

        long getInitTime() {
            return initTime;
        }

        AtomicDouble getValue() {
            return value;
        }
    }

    private BiConsumer<ValueAggregationResultPack, List<Map<String, Double>>> getResultHandler(
            @Nonnull CrossModelAggregateFunction aggregateFunction) {
        boolean asc = aggregateFunction.hasGrouping() &&
                aggregateFunction.getGroupBy().get(0).getGroupingSortOrder().equalsIgnoreCase("asc");
        BiConsumer<JsonArray, Map.Entry<String, Long>> comparator =
                buildComparator(aggregateFunction, aggregateFunction.hasGrouping() ?
                        aggregateFunction.getGroupBy().get(0) : null);

        Function<List<Map<String, Double>>, JsonArray> valueMapper = groupingList -> {
            JsonArray results = new JsonArray();
            Map<String, Long> countMap = new LinkedHashMap<>();
            groupingList.forEach(map -> map.forEach((k, v) -> {
                if (aggregateFunction.hasGrouping() && aggregateFunction.getGroupBy().get(0).hasGroupRanging()) {
                    JsonObject groupingObject = new JsonObject(k);
                    String key = new JsonObject()
                            .put("floor", groupingObject.getLong("floor"))
                            .put("ceil", groupingObject.getLong("ceil")).encode();

                    if (countMap.containsKey(key)) {
                        countMap.put(key, countMap.get(key) + v.longValue());
                    } else {
                        countMap.put(key, v.longValue());
                    }
                } else {
                    if (countMap.containsKey(k)) {
                        countMap.put(k, countMap.get(k) + v.longValue());
                    } else {
                        countMap.put(k, v.longValue());
                    }
                }
            }));

            countMap.entrySet().stream()
                    .sorted(createValueComparator(asc, aggregateFunction.hasGrouping() ?
                            aggregateFunction.getGroupBy().get(0) : null))
                    .limit(aggregateFunction.hasGrouping() ?
                            aggregateFunction.getGroupBy().get(0).getGroupingListLimit() :
                            10)
                    .forEachOrdered(x -> comparator.accept(results, x));

            return results;
        };

        switch (aggregateFunction.getFunction()) {
            case AVG:
                BiConsumer<JsonArray, Map.Entry<String, Double>> doubleComparator =
                        buildComparator(aggregateFunction, aggregateFunction.hasGrouping() ?
                                aggregateFunction.getGroupBy().get(0) : null);

                valueMapper = groupingList -> {
                    JsonArray results = new JsonArray();
                    Map<String, Double> countMap = new LinkedHashMap<>();
                    groupingList.forEach(map -> map.forEach((k, v) -> {
                        if (aggregateFunction.hasGrouping() && aggregateFunction.getGroupBy().get(0).hasGroupRanging()) {
                            JsonObject groupingObject = new JsonObject(k);
                            String key = new JsonObject()
                                    .put("floor", groupingObject.getLong("floor"))
                                    .put("ceil", groupingObject.getLong("ceil")).encode();

                            if (countMap.containsKey(key)) {
                                countMap.put(key, countMap.get(key) + v);
                            } else {
                                countMap.put(key, v);
                            }
                        } else {
                            if (countMap.containsKey(k)) {
                                countMap.put(k, countMap.get(k) + v);
                            } else {
                                countMap.put(k, v);
                            }
                        }
                    }));

                    countMap.entrySet().stream()
                            .sorted(createValueComparator(asc,
                                    aggregateFunction.hasGrouping() ?
                                            aggregateFunction.getGroupBy().get(0) : null))
                            .limit(aggregateFunction.hasGrouping() ?
                                    aggregateFunction.getGroupBy().get(0).getGroupingListLimit() :
                                    10)
                            .forEachOrdered(x -> doubleComparator.accept(results, x));

                    return results;
                };
            case SUM:
            case COUNT:
                Function<List<Map<String, Double>>, JsonArray> finalValueMapper = valueMapper;

                return (resultPack, groupingList) -> {
                    RoutingContext routingContext = resultPack.getRoutingContext();
                    long initialNanoTime = resultPack.getInitTime();

                    if (resultPack.getAggregate().hasGrouping()) {
                        JsonArray result = finalValueMapper.apply(groupingList);
                        String newEtag = ModelUtils.returnNewEtag(result.encode().hashCode());
                        JsonObject results = new JsonObject().put("count", result.size());

                        if (resultPack.getAggregate().hasGrouping() && resultPack.getAggregate().getGroupBy().get(0).hasGroupRanging()) {
                            results.put("rangeGrouping", new JsonObject()
                                    .put("unit", aggregateFunction.getGroupBy().get(0).getGroupByUnit())
                                    .put("range", aggregateFunction.getGroupBy().get(0).getGroupByRange()));
                        }

                        results.put("results", result);
                        String content = results.encode();

                        checkEtagAndReturn(content, newEtag, routingContext, initialNanoTime);
                    } else {
                        String functionName = aggregateFunction.getFunction().name().toLowerCase();
                        JsonObject result = new JsonObject().put(functionName, aggregateFunction.getFunction() == AVG ?
                                resultPack.getValue() : resultPack.getValue().longValue());
                        String content = result.encode();
                        String newEtag = ModelUtils.returnNewEtag(content.hashCode());

                        checkEtagAndReturn(content, newEtag, routingContext, initialNanoTime);
                    }
                };
            default:
                return null;
        }
    }

    private <T extends Comparable<? super T>> Comparator<Map.Entry<String, T>> createValueComparator(
            boolean asc, CrossModelGroupingConfiguration groupingConfiguration) {
        if (groupingConfiguration != null && groupingConfiguration.hasGroupRanging()) {
            return asc ? new KeyComparator<>() : new KeyComparator<String, T>().reversed();
        } else {
            return asc ? new ValueThenKeyComparator<>() : new ValueThenKeyComparator<String, T>().reversed();
        }
    }

    private <T> BiConsumer<JsonArray, Map.Entry<String, T>> buildComparator(
            CrossModelAggregateFunction aggregateFunction, CrossModelGroupingConfiguration groupingConfiguration) {
        if (groupingConfiguration != null && groupingConfiguration.hasGroupRanging()) {
            return (results, x) -> results.add(
                    new JsonObject(x.getKey())
                            .put(aggregateFunction.getFunction().name().toLowerCase(), x.getValue()));
        } else {
            return (results, x) -> results.add(new JsonObject().put(x.getKey(), x.getValue()));
        }
    }

    private class KeyComparator<K extends Comparable<? super K>, V extends Comparable<? super V>>
            implements Comparator<Map.Entry<K, V>> {
        public int compare(Map.Entry<K, V> a, Map.Entry<K, V> b) {
            JsonObject keyObjectA = new JsonObject(a.getKey().toString());
            JsonObject keyObjectB = new JsonObject(b.getKey().toString());

            return keyObjectA.getLong("ceil").compareTo(keyObjectB.getLong("ceil"));
        }
    }

    private class ValueThenKeyComparator<K extends Comparable<? super K>, V extends Comparable<? super V>>
            implements Comparator<Map.Entry<K, V>> {
        public int compare(Map.Entry<K, V> a, Map.Entry<K, V> b) {
            int cmp1 = a.getValue().compareTo(b.getValue());

            if (cmp1 != 0) {
                return cmp1;
            } else {
                return a.getKey().compareTo(b.getKey());
            }
        }
    }

    private void checkEtagAndReturn(String content, String newEtag, RoutingContext routingContext, long initialNanoTime) {
        final String requestEtag = routingContext.request().getHeader(HttpHeaders.IF_NONE_MATCH);

        if (newEtag != null && requestEtag != null && requestEtag.equalsIgnoreCase(newEtag)) {
            setStatusCodeAndContinue(304, routingContext, initialNanoTime);
        } else {
            if (newEtag != null) routingContext.response().putHeader(HttpHeaders.ETAG, newEtag);
            routingContext.put(BODY_CONTENT_TAG, content);
            setStatusCodeAndContinue(200, routingContext, initialNanoTime);
        }
    }

    @SuppressWarnings("unchecked")
    private <E extends ETagable & Model> void valueAggregation(RoutingContext routingContext,
                                                               Class<E> TYPE, Repository<E> repo, Future<Boolean> fut,
                                                               BiConsumer<AggregationResultPack,
                                                                       List<Map<String, Double>>> aggregationResultHandler,
                                                               JsonObject identifier, String route, String requestEtag,
                                                               String[] projections, List<Map<String, Double>> groupingList,
                                                               AtomicDouble totalCount, CrossModelAggregateFunction aggregate,
                                                               AggregateFunction temp, FilterPack filterPack) {
        String finalRoute = TYPE.getSimpleName() + "_AGG_" + route;
        Map<String, List<FilterParameter>> params = convertToFilterList(TYPE, filterPack);
        QueryPack valueQueryPack = QueryPack.builder(TYPE)
                .withRoutingContext(routingContext)
                .withCustomRoute(finalRoute)
                .withPageToken(routingContext.request().getParam(PAGING_TOKEN_KEY))
                .withRequestEtag(requestEtag)
                .withFilterParameters(params)
                .withAggregateFunction(temp)
                .withProjections(projections)
                .withIndexName(PAGINATION_INDEX)
                .build();

        if (logger.isDebugEnabled()) {
            addLogMessageToRequestLog(routingContext, "PROJECTIONS: " + Arrays.toString(projections));
        }

        repo.aggregation(identifier, valueQueryPack, projections, countRes -> {
            if (countRes.failed()) {
                addLogMessageToRequestLog(routingContext, "Unable to fetch res for " + TYPE.getSimpleName() + "!",
                        countRes.cause());

                fut.fail(countRes.cause());
            } else {
                if (logger.isDebugEnabled()) {
                    addLogMessageToRequestLog(routingContext, "Countres: " + countRes.result());
                }

                JsonObject valueResultObject = new JsonObject(countRes.result());

                if (logger.isDebugEnabled()) {
                    addLogMessageToRequestLog(routingContext, TYPE.getSimpleName() + "_agg: " +
                            Json.encodePrettily(valueResultObject));
                }

                AggregationResultPack pack = new AggregationResultPack(aggregate, valueResultObject, totalCount);
                aggregationResultHandler.accept(pack, groupingList);

                fut.complete(Boolean.TRUE);
            }
        });
    }

    private FilterPack convertToFilterPack(RoutingContext routingContext, List<String> filter) {
        if (filter == null || filter.get(0) == null) return null;

        try {
            return Json.decodeValue(filter.get(0), FilterPack.class);
        } catch (DecodeException e) {
            addLogMessageToRequestLog(routingContext, "Cannot decode FilterPack...", e);
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private <E extends ETagable & Model> Map<String, List<FilterParameter>> convertToFilterList(
            Class<E> TYPE, FilterPack filterPack) {
        if (filterPack == null) return new ConcurrentHashMap<>();

        Map<String, List<FilterParameter>> params = new ConcurrentHashMap<>();

        Optional<FilterPackModel> first = filterPack.getModels().stream()
                .filter(model -> model.getModel().equalsIgnoreCase(TYPE.getSimpleName() + "s"))
                .findFirst();

        if (first.isPresent()) {
            List<FilterParameter> parameters = new ArrayList<>();

            Map<String, List<FilterPackField>> groupedFields = getGroupedParametersForFields(first.get());
            groupedFields.keySet().forEach(field -> {
                groupedFields.get(field).forEach(fpf -> fpf.getParameters().forEach(
                        fieldFilter -> {
                            fieldFilter.setField(field);
                            parameters.add(fieldFilter);
                        }));

                params.put(field, parameters);
            });
        }

        return params;
    }

    private Map<String, List<FilterPackField>> getGroupedParametersForFields(FilterPackModel pm) {
        return pm.getFields().stream()
                .collect(groupingBy(FilterPackField::getField));
    }

    private AggregationPack verifyRequest(RoutingContext routingContext,
                                          String query, long initialProcessTime) {
        if (query == null) {
            noQueryError(routingContext, initialProcessTime);
        } else {
            Map<String, List<String>> queryMap = splitQuery(query);

            if (verifyQuery(queryMap, routingContext, initialProcessTime)) {
                try {
                    @SuppressWarnings("ConstantConditions")
                    CrossModelAggregateFunction aggregateFunction =
                            Json.decodeValue(queryMap.get(AGGREGATE_KEY).get(0), CrossModelAggregateFunction.class);
                    CrossTableProjection crossTableProjection =
                            Json.decodeValue(queryMap.get(PROJECTION_KEY).get(0), CrossTableProjection.class);
                    crossTableProjection = new CrossTableProjection(
                            crossTableProjection.getModels(),
                            new ArrayList<>(modelMap.keySet()),
                            crossTableProjection.getFields());
                    String pageToken = routingContext.request().getParam(PAGING_TOKEN_KEY);

                    if (aggregateFunction.getField() != null) {
                        JsonObject aggregationQueryErrorObject = new JsonObject().put("aggregate_field_error",
                                "Field must be null in aggregate, use fields in projection instead");

                        sendQueryErrorResponse(aggregationQueryErrorObject, routingContext, initialProcessTime);
                    } else {
                        List<ValidationError> errors = crossTableProjection.validate(aggregateFunction.getFunction());

                        if (errors.isEmpty()) {
                            Map<Class, Set<String>> projectionMap = buildProjectionMap(crossTableProjection);
                            List<JsonObject> aggregationErrors = verifyAggregation(aggregateFunction, projectionMap);

                            if (aggregationErrors.isEmpty()) {
                                return new AggregationPack(aggregateFunction, projectionMap, pageToken);
                            } else {
                                sendQueryErrorResponse(buildJsonErrorObject("Aggregate",
                                        aggregationErrors), routingContext, initialProcessTime);
                            }
                        } else {
                            sendQueryErrorResponse(buildValidationErrorObject("Projection", errors),
                                    routingContext, initialProcessTime);
                        }
                    }
                } catch (DecodeException e) {
                    JsonObject aggregationQueryErrorObject = new JsonObject()
                            .put("json_parse_error", "Unable to parse json in query, are you sure it is URL encoded?");

                    sendQueryErrorResponse(aggregationQueryErrorObject, routingContext, initialProcessTime);
                }
            }
        }

        return null;
    }

    private Map<Class, Set<String>> buildProjectionMap(CrossTableProjection crossTableProjection) {
        return crossTableProjection.getModels().stream()
                .map(model ->
                        new SimpleEntry<>(modelMap.get(model), getFieldsForCollection(crossTableProjection, model)))
                .collect(toMap(SimpleEntry::getKey, SimpleEntry::getValue));
    }

    private Set<String> getFieldsForCollection(CrossTableProjection crossTableProjection, String collection) {
        if (crossTableProjection.getFields() == null) return new ConcurrentHashSet<>();

        return crossTableProjection.getFields().stream()
                .map(field -> {
                    String[] fieldSplit = field.split("\\.");

                    return fieldSplit[0].equalsIgnoreCase(collection) ? fieldSplit[1] : null;
                })
                .collect(toSet());
    }

    @SuppressWarnings("unchecked")
    private List<JsonObject> verifyAggregation(CrossModelAggregateFunction aggregateFunction,
                                               Map<Class, Set<String>> crossTableProjection) {
        AggregateFunctions function = aggregateFunction.getFunction();
        List<JsonObject> errors = new ArrayList<>();

        if (aggregateFunction.getGroupBy().size() > 1) {
            errors.add(new JsonObject().put("grouping_error", "Max grouping for cross model is 1!"));
        } else {
            crossTableProjection.forEach((klazz, fieldSet) -> fieldSet.forEach(field -> {
                List<GroupingConfiguration> groupingConfigurations = getGroupingConfigurations(aggregateFunction, klazz);
                AggregateFunction temp = AggregateFunction.builder()
                        .withAggregateFunction(function)
                        .withField(field)
                        .withGroupBy(groupingConfigurations)
                        .build();
                temp.validateFieldForFunction(klazz);
                if (!temp.getValidationError().isEmpty()) errors.add(temp.getValidationError());
            }));
        }

        return errors;
    }

    private @Nonnull
    List<GroupingConfiguration> getGroupingConfigurations(@Nonnull CrossModelAggregateFunction aggregateFunction,
                                                          @Nonnull Class klazz) {
        return getGroupingConfigurations(aggregateFunction, klazz, false);
    }

    private @Nonnull
    List<GroupingConfiguration> getGroupingConfigurations(@Nonnull CrossModelAggregateFunction aggregateFunction,
                                                          @Nonnull Class klazz, boolean fullList) {
        final List<CrossModelGroupingConfiguration> groupBy = aggregateFunction.getGroupBy();
        if (groupBy == null) return new ArrayList<>();
        String modelName = getModelName(klazz);

        return aggregateFunction.getGroupBy()
                .stream()
                .map(cmgf -> {
                    final List<String> innerGroupBy = cmgf.getGroupBy();
                    if (innerGroupBy.size() == 1) return innerGroupBy.get(0);

                    return innerGroupBy.stream()
                            .filter(gb -> gb.startsWith(modelName))
                            .findFirst()
                            .map(s -> s.split("\\.")[1])
                            .orElse(null);
                })
                .findFirst()
                .map(s -> aggregateFunction.getGroupBy().stream()
                        .map(cmgf -> GroupingConfiguration.builder()
                                        .withGroupBy(s)
                                        .withGroupByUnit(cmgf.getGroupByUnit())
                                        .withGroupByRange(cmgf.getGroupByRange())
                                        .withGroupingSortOrder(cmgf.getGroupingSortOrder())
                                        .withGroupingListLimit(cmgf.getGroupingListLimit())
                                        .withFullList(fullList).build())
                        .collect(toList()))
                .orElseGet(ArrayList::new);
    }

    @SuppressWarnings("SameParameterValue")
    private JsonObject buildValidationErrorObject(String projection, List<ValidationError> errors) {
        JsonArray validationArray = new JsonArray();
        errors.stream().map(ValidationError::toJson).forEach(validationArray::add);

        return new JsonObject()
                .put("validation_error", projection + " is invalid...")
                .put("errors", validationArray);
    }

    @SuppressWarnings("SameParameterValue")
    private JsonObject buildJsonErrorObject(String projection, List<JsonObject> errors) {
        JsonArray validationArray = new JsonArray();
        errors.forEach(validationArray::add);

        return new JsonObject()
                .put("validation_error", projection + " is invalid...")
                .put("errors", validationArray);
    }

    private boolean verifyQuery(Map<String, List<String>> queryMap,
                                RoutingContext routingContext, long initialProcessTime) {
        if (queryMap == null) {
            noAggregateError(routingContext, initialProcessTime);

            return false;
        } else if (queryMap.get(AGGREGATE_KEY) == null) {
            noAggregateError(routingContext, initialProcessTime);

            return false;
        } else if (queryMap.get(PROJECTION_KEY) == null) {
            noProjectionError(routingContext, initialProcessTime);

            return false;
        } else if (queryMap.get(PAGING_TOKEN_KEY) != null &&
                queryMap.get(PAGING_TOKEN_KEY).get(0).equalsIgnoreCase(END_OF_PAGING_KEY)) {
            noPageError(routingContext, initialProcessTime);

            return false;
        }

        return true;
    }

    private void noAggregateError(RoutingContext routingContext, long initialProcessTime) {
        JsonObject aggregationQueryErrorObject = new JsonObject()
                .put("aggregate_query_error", AGGREGATE_KEY + " query param is required!");

        sendQueryErrorResponse(aggregationQueryErrorObject, routingContext, initialProcessTime);
    }

    private void noProjectionError(RoutingContext routingContext, long initialProcessTime) {
        JsonObject aggregationQueryErrorObject = new JsonObject()
                .put("projection_query_error", PROJECTION_KEY + " query param is required!");

        sendQueryErrorResponse(aggregationQueryErrorObject, routingContext, initialProcessTime);
    }

    private void noQueryError(RoutingContext routingContext, long initialProcessTime) {
        JsonObject aggregationQueryErrorObject = new JsonObject()
                .put("aggregation_error", "Query cannot be null for this endpoint!");

        sendQueryErrorResponse(aggregationQueryErrorObject, routingContext, initialProcessTime);
    }

    private void noPageError(RoutingContext routingContext, long initialProcessTime) {
        JsonObject aggregationQueryErrorObject = new JsonObject()
                .put("paging_error", "You cannot page for the " + END_OF_PAGING_KEY + ", " +
                        "this message means you have reached the end of the results requested.");

        sendQueryErrorResponse(aggregationQueryErrorObject, routingContext, initialProcessTime);
    }

    private void sendQueryErrorResponse(JsonObject aggregationQueryErrorObject,
                                        RoutingContext routingContext, long initialProcessTimeNano) {
        routingContext.put(BODY_CONTENT_TAG, aggregationQueryErrorObject);
        setStatusCodeAndContinue(400, routingContext, initialProcessTimeNano);
    }

    private String[] getProjections(RoutingContext routingContext) {
        String projectionJson = routingContext.request().getParam(PROJECTION_KEY);
        String[] projections = null;

        if (projectionJson != null) {
            try {
                JsonObject projection = new JsonObject(projectionJson);
                JsonArray array = projection.getJsonArray(PROJECTION_FIELDS_KEY, null);

                if (array != null) {
                    projections = array.stream()
                            .map(o -> o.toString().split("\\.")[1])
                            .toArray(String[]::new);

                    if (logger.isDebugEnabled()) {
                        addLogMessageToRequestLog(routingContext, "Projection ready!");
                    }
                }
            } catch (DecodeException | EncodeException e) {
                addLogMessageToRequestLog(routingContext, "Unable to parse projections: " + e);

                projections = null;
            }
        }

        return projections;
    }

    private class AggregationPack {
        private final CrossModelAggregateFunction aggregate;
        private final Map<Class, Set<String>> projection;
        private final String pageToken;

        private AggregationPack(CrossModelAggregateFunction aggregate, Map<Class, Set<String>> projection,
                                String pageToken) {
            this.aggregate = aggregate;
            this.projection = projection;
            this.pageToken = pageToken;
        }

        CrossModelAggregateFunction getAggregate() {
            return aggregate;
        }

        Map<Class, Set<String>> getProjection() {
            return projection;
        }

        String getPageToken() {
            return pageToken;
        }
    }
}