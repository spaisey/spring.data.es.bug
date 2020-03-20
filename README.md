# Test Application to demo bug

## Bug Description
When the reactive elasticsearch template encounters a 4xx response from ES, it continues to parse
the response, which causes a NPE and eventually the response `The mapper returned a null value.`

## Assumptions
1) Docker installed

## How to prepare
1) start up Elasticsearch - src/main/scripts/es-docker.sh
2) create index mapping - src/main/scripts/create-mappings.sh
3) add document to ES - src/main/scripts/create-date.sh
4) run the application (either Application.kt in IDE or `java -jar spring.data.es.bug-0.0.1-SNAPSHOT.jar`)

## How to show issue
The application is set up to issue a wildcard query in a field (called 'key') which is defined as
a long. You cannot issue a wildcard query on a non-text field, and therefore this results in an
400 BAD_REQUEST response.
1) call `http://localhost:8080/bug/example` and you'll notice the response:
    `{"timestamp":"2020-03-20T21:16:02.705+0000","path":"/bug/example","status":500,"error":"Internal Server Error","message":"The mapper returned a null value.","requestId":"a5b85a80-3"}`
    Although this is a 500 error, the message is misleading and looks like an programming error

    What's actually happening is DefaultReactiveElasticsearchClient has a method which only checks for 500 errors,
    meaning the 4xx error slips through the net, and the response is attempted to be parsed into the expected response
    class. See the following code in DefaultReactiveElasticsearchClient (only the 5xx response is checked):

```java
	private <T> Publisher<? extends T> readResponseBody(String logId, Request request, ClientResponse response,
			Class<T> responseType) {

		if (RawActionResponse.class.equals(responseType)) {

			ClientLogger.logRawResponse(logId, response.statusCode());
			return Mono.just(responseType.cast(RawActionResponse.create(response)));
		}

		if (response.statusCode().is5xxServerError()) {

			ClientLogger.logRawResponse(logId, response.statusCode());
			return handleServerError(request, response);
		}

		return response.body(BodyExtractors.toMono(byte[].class)) //
				.map(it -> new String(it, StandardCharsets.UTF_8)) //
				.doOnNext(it -> ClientLogger.logResponse(logId, response.statusCode(), it)) //
				.flatMap(content -> doDecode(response, responseType, content));
	}
```

1) call `http://localhost:8080/bug/workaround` which has been configured with a ExchangeFilterFunction to detect a 4xx
response and throw an exception. This results in the response:
`{"timestamp":"2020-03-20T21:31:29.077+0000","path":"/bug/workaround","status":500,"error":"Internal Server Error","message":"{\"error\":{\"root_cause\":[{\"type\":\"query_shard_exception\",\"reason\":\"Can only use wildcard queries on keyword and text fields - not on [key] which is of type [long]\",\"index_uuid\":\"1aL9ONyQSO6r18TKly3k7w\",\"index\":\"bugdemo\"}],\"type\":\"search_phase_execution_exception\",\"reason\":\"all shards failed\",\"phase\":\"query\",\"grouped\":true,\"failed_shards\":[{\"shard\":0,\"index\":\"bugdemo\",\"node\":\"UehNpHIjQIyJ6H5bzDR0AA\",\"reason\":{\"type\":\"query_shard_exception\",\"reason\":\"Can only use wildcard queries on keyword and text fields - not on [key] which is of type [long]\",\"index_uuid\":\"1aL9ONyQSO6r18TKly3k7w\",\"index\":\"bugdemo\"}}]},\"status\":400}","requestId":"06333dd4-4"}`
This accurately depicts the error.

