package uk.co.itello.example.spring.data.es.bug

import kotlinx.coroutines.reactive.awaitLast
import org.elasticsearch.index.query.BoolQueryBuilder
import org.elasticsearch.index.query.QueryBuilders.wildcardQuery
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.data.elasticsearch.client.ClientConfiguration
import org.springframework.data.elasticsearch.client.reactive.ReactiveRestClients.create
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchTemplate
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.server.*
import org.springframework.web.reactive.function.server.ServerResponse.ok
import reactor.core.publisher.Mono
import uk.co.itello.example.spring.data.es.bug.Application.Companion.workaroundReactiveElasticsearchTemplate
import java.time.Instant.now


fun main(args: Array<String>) {
//	Hooks.onOperatorDebug()
	runApplication<Application>(*args)
}

@SpringBootApplication
class Application {
	companion object {
		/**
		 * The 'workaround' ReactiveElasticsearchTemplate which has been configured with a response filter to detect a
		 * 4xx response and throw an exception.
		 */
		fun workaroundReactiveElasticsearchTemplate(): ReactiveElasticsearchTemplate {
			val clientConfiguration = ClientConfiguration.builder()
					.connectedTo("localhost:9200")
					.withWebClientConfigurer { webClient: WebClient ->
						webClient.mutate().filter(create4xxExceptionFilter()).build()
					}
					.build()

			return ReactiveElasticsearchTemplate(create(clientConfiguration))
		}

		/**
		 * The 4xx detection response filter
		 */
		private fun create4xxExceptionFilter(): ExchangeFilterFunction {
			return ExchangeFilterFunction.ofResponseProcessor { clientResponse: ClientResponse ->
				if (clientResponse.statusCode().is4xxClientError) {
					return@ofResponseProcessor clientResponse
							.bodyToMono(String::class.java)
							.flatMap { errorBody: String -> Mono.error(RuntimeException(errorBody)) }
				}
				Mono.just(clientResponse)
			}
		}
	}

	/**
	 * The endpoint routing
	 */
	@Bean
	fun routes(handler: Handler): RouterFunction<ServerResponse> = coRouter {
		accept(APPLICATION_JSON).nest {
			GET("ping", handler::ping)
			GET("bug/example", handler::bugExample)
			GET("bug/workaround", handler::workAround)
		}
	}
}

/**
 * Endpoint handler methods
 */
@Component
class Handler(val reactiveElasticsearchTemplate: ReactiveElasticsearchTemplate) {

	/**
	 * Convenience ping() method to check we're up and running ok :o)
	 */
	suspend fun ping(request: ServerRequest): ServerResponse {
		return ok().bodyValueAndAwait("Ping at ${now()}")
	}

	/**
	 * This method exhibits the issue:
	 * 1) uses the default ReactiveElasticsearchTemplate created by Spring Boot
	 * 2) an invalid wildcard search on the 'key' field which is a long
	 * 3) ES returns a 400 BAD_REQUEST - however this is not detected and an attempt is made to process the payload
	 * causing the NPE - this results in the message "The mapper returned a null value"
	 *
	 * to call: curl http://localhost:8080/bug/example
	 */
	suspend fun bugExample(request: ServerRequest): ServerResponse {
		return issueErroneousFindRequest(reactiveElasticsearchTemplate)
	}

	/**
	 * This is a workaround.
	 * Uses a webclient with a response filter that detects the 4xx response and throws an exception - see the Application
	 * companion object.
	 *
	 * to call: curl http://localhost:8080/bug/workaround
	 */
	suspend fun workAround(request: ServerRequest): ServerResponse {
		return issueErroneousFindRequest(workaroundReactiveElasticsearchTemplate())
	}

	/**
	 * Issues a wildcard search on a field defined as 'long'
	 */
	private suspend fun issueErroneousFindRequest(reactiveElasticsearchTemplate: ReactiveElasticsearchTemplate): ServerResponse {
		val query = NativeSearchQueryBuilder()
				.withIndices("bugdemo")
				.withQuery(BoolQueryBuilder().apply {
					filter(wildcardQuery("key", "1*"))
				})

		val results = reactiveElasticsearchTemplate.find(query.build(), BugDemo::class.java)

		return ok().body(results).awaitLast()

	}
}

data class BugDemo(val key: Long, val value: String)
