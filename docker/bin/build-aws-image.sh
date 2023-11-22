#!/usr/bin/env bash

set -e
(
    cd $(dirname $0)/..

    if [ "$1" == "--clean" ] || ! [ -e aws/build/libs/xtdb-aws.jar ]; then
        ../gradlew :docker:aws:shadowJar
    fi

    sha=$(git rev-parse --short HEAD)

    echo Building Docker image ...
    docker build -t ghcr.io/xtdb/xtdb-aws-ea:latest --build-arg GIT_SHA="$sha" --build-arg XTDB_VERSION="${XTDB_VERSION:-dev-SNAPSHOT}" --output type=docker aws
    echo Done
)