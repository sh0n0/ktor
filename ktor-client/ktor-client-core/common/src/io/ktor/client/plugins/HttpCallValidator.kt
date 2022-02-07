/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.client.plugins

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.HttpCallValidator.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.utils.*
import io.ktor.util.*
import io.ktor.util.pipeline.*

/**
 * Response validator method.
 *
 * You could throw an exception to fail the response.
 */
public typealias ResponseValidator = suspend (response: HttpResponse) -> Unit

/**
 * Response exception handler method.
 */
public typealias CallExceptionHandler = suspend (cause: Throwable) -> Unit

/**
 * Response exception handler method. [request] is null if
 */
public typealias CallRequestExceptionHandler = suspend (cause: Throwable, request: HttpRequest) -> Unit

/**
 * Response validator plugin is used for validate response and handle response exceptions.
 *
 * See also [Config] for additional details.
 */
public class HttpCallValidator internal constructor(
    private val responseValidators: List<ResponseValidator>,
    private val callExceptionHandlers: List<HandlerWrapper>,
    private val expectSuccess: Boolean
) {

    private suspend fun validateResponse(response: HttpResponse) {
        responseValidators.forEach { it(response) }
    }

    private suspend fun processException(cause: Throwable, request: HttpRequest) {
        callExceptionHandlers.forEach {
            when (it) {
                is ExceptionHandlerWrapper -> it.handler(cause)
                is RequestExceptionHandlerWrapper -> it.handler(cause, request)
            }
        }
    }

    /**
     * [HttpCallValidator] configuration.
     */
    public class Config {
        internal val responseValidators: MutableList<ResponseValidator> = mutableListOf()
        internal val responseExceptionHandlers: MutableList<HandlerWrapper> = mutableListOf()

        /**
         * Terminate [HttpClient.receivePipeline] if status code is not successful (>=300).
         */

        @Deprecated(
            "This property is ignored. Please use `expectSuccess` property in HttpClientConfig. " +
                "This is going to become internal."
        )
        public var expectSuccess: Boolean = true

        /**
         * Add [CallExceptionHandler].
         * Last added handler executes first.
         */
        public fun handleResponseException(block: CallExceptionHandler) {
            responseExceptionHandlers += ExceptionHandlerWrapper(block)
        }

        /**
         * Add [CallRequestExceptionHandler].
         * Last added handler executes first.
         */
        public fun handleResponseExceptionWithRequest(block: CallRequestExceptionHandler) {
            responseExceptionHandlers += RequestExceptionHandlerWrapper(block)
        }

        /**
         * Add [ResponseValidator].
         * Last added validator executes first.
         */
        public fun validateResponse(block: ResponseValidator) {
            responseValidators += block
        }
    }

    public companion object : HttpClientPlugin<Config, HttpCallValidator> {
        override val key: AttributeKey<HttpCallValidator> = AttributeKey("HttpResponseValidator")

        override fun prepare(block: Config.() -> Unit): HttpCallValidator {
            val config = Config().apply(block)

            @Suppress("DEPRECATION")
            return HttpCallValidator(
                config.responseValidators.reversed(),
                config.responseExceptionHandlers.reversed(),
                config.expectSuccess
            )
        }

        @OptIn(InternalAPI::class)
        override fun install(plugin: HttpCallValidator, scope: HttpClient) {
            scope.requestPipeline.intercept(HttpRequestPipeline.Before) {
                try {
                    context.attributes.computeIfAbsent(ExpectSuccessAttributeKey) { plugin.expectSuccess }
                    proceedWith(it)
                } catch (cause: Throwable) {
                    val unwrappedCause = cause.unwrapCancellationException()
                    plugin.processException(
                        unwrappedCause,
                        DefaultHttpRequest(HttpClientCall(scope), context.build())
                    )
                    throw unwrappedCause
                }
            }

            val BeforeReceive = PipelinePhase("BeforeReceive")
            scope.responsePipeline.insertPhaseBefore(HttpResponsePipeline.Receive, BeforeReceive)
            scope.responsePipeline.intercept(BeforeReceive) { container ->
                try {
                    proceedWith(container)
                } catch (cause: Throwable) {
                    val unwrappedCause = cause.unwrapCancellationException()
                    plugin.processException(unwrappedCause, context.request)
                    throw unwrappedCause
                }
            }

            scope.plugin(HttpSend).intercept { request ->
                val call = execute(request)
                plugin.validateResponse(call.response)
                call
            }
        }
    }
}

/**
 * Install [HttpCallValidator] with [block] configuration.
 */
public fun HttpClientConfig<*>.HttpResponseValidator(block: HttpCallValidator.Config.() -> Unit) {
    install(HttpCallValidator, block)
}

/**
 * Terminate [HttpClient.receivePipeline] if status code is not successful (>=300).
 */
public var HttpRequestBuilder.expectSuccess: Boolean
    get() = attributes.getOrNull(ExpectSuccessAttributeKey) ?: true
    set(value) = attributes.put(ExpectSuccessAttributeKey, value)

internal val ExpectSuccessAttributeKey = AttributeKey<Boolean>("ExpectSuccessAttributeKey")

internal interface HandlerWrapper

internal data class ExceptionHandlerWrapper(internal val handler: CallExceptionHandler) : HandlerWrapper

internal data class RequestExceptionHandlerWrapper(internal val handler: CallRequestExceptionHandler) : HandlerWrapper
