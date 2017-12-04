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
