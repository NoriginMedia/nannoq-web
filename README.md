## Welcome to Nannoq Web

nannoq-web is a REST (Level 3) controller implementation that is based on vertx-web and leverages [nannoq-repository](https://github.com/mikand13/nannoq-repository) for data store access.

### Prerequisites

Vert.x >= 3.5.0

Java >= 1.8

Maven

### Installing

mvn clean package -Dgpg.skip=true

## Running the tests

mvn clean test -Dgpg.skip=true

## Running the integration tests

mvn clean verify -Dgpg.skip=true

## Controller implementation

A clean implementation expects three parameters.

- The vertx app configuration
 - For Redis backed etag storage, the configuration should have a parameter "redis_host", and an optional "redis_port".
- A Repository implementation that is typed to the class use for the RestControllerImpl inheritance
- A Function that reads the routingcontext and returns a valid idObject based on the path, this is implemented by the client implementation.

```

public class EventsController extends RestControllerImpl<Event> {
    public EventsController(JsonObject appConfig,
                            Repository<Event> repository, 
                            Function<RoutingContext, JsonObject> idSupplier) {
        super(Event.class, appConfig, repository, idSupplier);
    }
}
```

## Querying

### All index operations on the API can be performed with finegrained filtering and ordering as well as aggregation with or without grouping, for doing more advanced searches.

#### Filtering

 * Filtering can be performed on any fields of an object. 
 * All filtering is defined by url encoded json and can be chained for doing multiple filtering operations on a single field.
 * Available filtering options are:
   * eq (Equals)
   * ne (Not Equals)
   * gt (Greater Than)
   * lt (Less Than)
   * ge (Greater or Equal Than)
   * le (Lesser or Equal Than)
   * contains (Field contains value)
   * notContains (Field does not contain value)
   * beginsWith (Field begins with value (CASE SENSITIVE))
   * in (Value exists in field)
   * type (Boolean type (And / Or))

##### Examples

```

{
  "eq":15000
}


[{
  "gt":10000
}, {
  "lt":5000,
  "type":"or"
}]


[{
  "gt":10000
}, {
  "lt":20000,
  "type":"and"
}] 
```

###### Above examples as Url Encoded Json. (commentCount as field)
 commentCount=%7B%22eq%22%3A15000%7D 

 commentCount=%5B%7B%22gt%22%3A10000%7D%2C%7B%22lt%22%3A5000%2C%22type%22%3A%22or%22%7D%5D 

 commentCount=%5B%7B%22gt%22%3A10000%7D%2C%7B%22lt%22%3A20000%2C%22type%22%3A%22and%22%7D%5D
#### Ordering

  * Ordering can be performed on any indexed fields of an object. Typically numerical or date fields.
  * All ordering is defined by url encoded json.
  * You can only order on a single index.
  * Available ordering options are:
    * field (Field to order by)
    * direction ("asc" or "desc", DESC is default)

##### Examples

```

{
  "field":"commentCount"
}


{
  "field":"commentCount",
  "direction":"asc"
} 
```

###### Above examples as Url Encoded Json. (commentCount as field)
 orderBy=%7B%22field%22%3A%22commentCount%22%7D 

 orderBy=%7B%22field%22%3A%22commentCount%22%2C%22direction%22%3A%22asc%22%7D

#### Aggregation

  * Aggregation can be performed on any fields that are numerical, whole or decimal.
  * All aggregation is defined by url encoded json.
  * You can only aggregate on a single field.
  * Filtering is available for all functions.
  * Ordering is not available for AVG, SUM and COUNT.
  * GroupBy can be performed on a single field basis.
  * Available aggregatin options are:
    * field (Field to aggregate on, if any. Not available for COUNT.)
    * function (MIN, MAX, AVG, SUM, COUNT)
    * groupBy (List of Grouping Configurations, currently limited to 3.)
      * groupBy (Field to group by, e.g. userId)
      * groupByUnit (Direction of value sort, asc or desc. DESC is default)
      * groupByRange (Count of objects to return, max 100, min 1, default 10)
      * groupingSortOrder (Direction of value sort, asc or desc. DESC is default)
      * groupingListLimit (Count of objects to return, max 100, min 1, default 10)
    * MIN and MAX will return an array of any corresponding objects. E.g the FeedItems with the MAX likeCount.
    * AVG, SUM, COUNT will return a JsonObject with the format of:

```

{
  "count": "<integer>",
  "results": [
      "groupByKey":"<valueOfGroupByKeyElement>",
      "<aggregateFunction>":"<aggregateValue>"
    ]
} 
```

  * Non-grouped responses

```

{
  "avg":"<avgValue>"
}

{
  "sum":"<sumValue>"
}

{
  "count":"<countValue>"
} 
```

###### Query Examples

```

{
  "field":"commentCount",
  "function":"MIN"
}


{
  "field":"likeCount",
  "function":"COUNT",
  "groupBy": [
    {
      "groupBy":"userId"
    }
  ]
}

{
  "function":"COUNT"
} 
```

###### Above examples as Url Encoded Json. (commentCount as field)
 aggregate=%7B%22field%22%3A%22commentCount%22%2C%22function%22%3A%22MIN%22%7D

 aggregate=%7B%22field%22%3A%22likeCount%22%2C%22function%22%3A%22COUNT%22%2C%22groupBy%22%3A%5B%7B%22groupBy%22%3A%22userId%22%7D%5D%7D

 aggregate=%7B%22function%22%3A%22COUNT%22%7D

 #### Projection

  * Projection can and should be performed wherever possible to ensure you only receive the specific data you need.
  * You can still do filtering and ordering as usual regardless of projected fields, youc an f.ex. do filtering on an attribute, without projecting it.
  * Projection is defined by an url encoded json.
  * Projection can be performed only all other operations, except AVG, SUM and COUNT aggregations, they are automatically projected for performance.
  * You can project on any attribute. Key Values will always be projected, regardless of projection-selection on Index routes, they must be projected to generate pageTokens regardless.
  * Available projection options are:
    * fields (String array of fields to project)

```

{
  "fields":["someField","someOtherField","someThirdField"]
} 
```

##### Examples

```

{
  "fields":["feedId"]
}

{
  "fields":["providerFeedItemId","likeCount","feedId"]
} 
```

###### Above examples as Url Encoded Json.
 projection=%7B%22fields%22%3A%5B%22feedId%22%5D%7D

 projection=%7B%22fields%22%3A%5B%22providerFeedItemId%22%2C%22likeCount%22%2C%22feedId%22%5D%7D

## Cross-Model Aggregation

  * All Cross-Model aggregations are automatically scoped to the request feedId, any filtering is on top of this.
  * Aggregation can be performed on any fields that are numerical, whole or decimal, across models.
  * All aggregation is defined by url encoded json.
  * You can aggregate on multiple fields from different models.
  * There is no projection of fields for output.
  * GroupBy can be performed on a single field basis.
  * Output is similar to single-model aggregation.
  * Available aggregate query options are:
    * function (AVG, SUM, COUNT)
    * groupBy (List of Cross Model Grouping Configurations, currently limiited to 1.)
    * groupBy (Array of strings defining field(s) to group by. If the field is equal across models a singular shorthand like ["userId"] can be used. For varying you must prepend by model name pluralized, e.g. ["providerId","likeObjectId"])
    * groupByUnit (Direction of value sort, asc or desc. DESC is default, this is applied to every groupBy element across models)
    * groupByRange (Count of objects to return, max 100, min 1, default 10, this is applied to every groupBy element across models)
    * groupingSortOrder (Direction of value sort, asc or desc. DESC is default, this is applied to every groupBy element across models)
    * groupingListLimit (Count of objects to return, max 100, min 1, default 10, this is applied to every groupBy element across models)
    * groupByUnit (Unit to Range Grouping, available values are: INTEGER (For numbers), DATE (For dates), default is nothing. Only number and date fields are supported)
    * groupByRange (Amount to range-group upon, available values are: Some x integer for numbers, HOUR, TWELVE_HOUR, DAY, WEEK, MONTH, YEAR for Dates.
    * groupBySortOrder (Direction of value sort, asc or desc. DESC is default)
    * groupByListLimit (Count of objects to return, max 100, min 1, default 10)

##### Query Examples

```

{
  "function":"COUNT",
  "groupBy": [
    {
      "groupBy": ["userId"]
    }
  ]
}

{
  "function":"SUM",
  "groupBy": [
    {
      "groupBy": ["feedId"],
      "groupingSortOrder":"asc",
      "groupingListLimit":10
    }
  ]
}

{
  "function":"COUNT",
  "groupBy": [
    "groupBy":["comments.registrationDate", "likes.createdAt"],
    "groupByUnit":"DATE",
    "groupByRange":"WEEK",
    "groupingSortOrder":"asc",
    "groupingListLimit":10
  ]
} 
```

###### Above examples as Url Encoded Json.
 aggregate=%7B%22function%22%3A%22COUNT%22%2C%22groupBy%22%3A%5B%7B%22groupBy%22%3A%5B%22userId%22%5D%7D%5D%7D

 aggregate=%7B%22function%22%3A%22SUM%22%2C%22groupBy%22%3A%5B%7B%22groupBy%22%3A%5B%22feedId%22%5D%2C%22groupBySortOrder%22%3A%22asc%22%2C%22groupByListLimit%22%3A10%7D%5D%7D

 aggregate=%7B%22function%22%3A%22COUNT%22%2C%22groupBy%22%3A%5B%22groupBy%22%3A%5B%22comments.registrationDate%2Clikes.createdAt%22%5D%2C%22groupByUnit%22%3A%22DATE%22%2C%22groupByRange%22%3A%22WEEK%22%2C%22groupBySortOrder%22%3A%22asc%22%2C%22groupByListLimit%22%3A10%5D%7D

  * Available projection query options are: 
    * models (String array of models to aggregate upon, must be pluralized)
    * fields (String array of fields to do aggregation on, prepended by pluralized model name)
      * fields must be empty for COUNT operations, due to being irrelevant

##### Query Examples

```

{
  "models":["comments", "likes"]
}

{
  "models":["feedItems", "comments"],
  "fields":["feedItems.likeCount","comments.likeCount"]
} 
``` 

###### Above examples as Url Encoded Json.
 projection=%7B%22models%22%3A%5B%22comments%22%2C%20%22likes%22%5D%7D

 projection=%7B%22models%22%3A%5B%22feedItems%22%2C%20%22comments%22%5D%2C%22fields%22%3A%5B%22feedItems.likeCount%22%2C%22comments.likeCount%22%5D%7D

  * Available filter query options are: 
    * models (Object array of models to filter upon)
      * model (String name of model to filter upon, pluralized.
      * fields (Object array of fields to filtre upon)
        * field (String name of field to fiter upon)
        * parameters (Object array of filtering parameters, params are same as normal filtering)

##### Query Examples

```

{
  "models":[
    {
      "model":"feedItems",
      "fields":[
        {
          "field":"providerName",
          "parameters":[
            {
              "eq":"FACEBOOK"
            }
          ]
        }
      ]
    }
  ]
}

{
  "models":[
    {
      "model":"comments",
      "fields":[
        {
          "field":"reply",
          "parameters":[
            {
              "eq":true
            }
          ]
        },
        {
          "field":"likeCount",
          "parameters":[
            {
              "gt":10000
            },
            {
              "lt":50000
            }
          ]
        }
      ]
    },
    {
      "model":"likes",
      "fields":[
        {
          "field":"userId",
          "parameters":[
            {
              "eq":"4554b1eda02f902beea73cd03c4acb4"
            },
            {
              "eq":"6ab6c2a487d25c6c314774a845690e6",
              "type":"or"
            }
          ]
        }
      ]
    }
  ]
} 
```  

###### Above examples as Url Encoded Json.
 projection=%7B%22models%22%3A%5B%7B%22model%22%3A%22feedItems%22%2C%22fields%22%3A%5B%7B%22field%22%3A%22providerName%22%2C%22parameters%22%3A%5B%7B%22eq%22%3A%22FACEBOOK%22%20%7D%5D%7D%5D%7D%5D%7D

 projection=%7B%22models%22%3A%5B%7B%22model%22%3A%22comments%22%2C%22fields%22%3A%5B%7B%22field%22%3A%22reply%22%2C%22parameters%22%3A%5B%7B%22eq%22%3Atrue%7D%5D%7D%2C%7B%22field%22%3A%22likeCount%22%2C%22parameters%22%3A%5B%7B%22gt%22%3A10000%7D%2C%7B%22lt%22%3A50000%7D%5D%7D%5D%7D%2C%7B%22model%22%3A%22likes%22%2C%22fields%22%3A%5B%7B%22field%22%3A%22userId%22%2C%22parameters%22%3A%5B%7B%22eq%22%3A%224554b1eda02f902beea73cd03c4acb4%22%7D%2C%7B%22eq%22%3A%226ab6c2a487d25c6c314774a845690e6%22%2C%22type%22%3A%22or%22%7D%5D%7D%5D%7D%5D%7D
