#!/usr/bin/env bash
CURRENT_DIR=`dirname $0`
curl -X PUT "http://localhost:9200/bugdemo" -H 'Content-Type: application/json' -d@${CURRENT_DIR}/bugdemo-mapping.json

