#!/usr/bin/env bash
CURRENT_DIR=`dirname $0`
curl -X POST "http://localhost:9200/bugdemo/_doc" -H 'Content-Type: application/json' -d@${CURRENT_DIR}/data.json

