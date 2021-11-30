/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.server.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.*
import org.slf4j.*
import kotlin.random.*
import kotlin.reflect.jvm.*

/**
 * A function that retrieves or generates call id using provided call
 */
public typealias CallIdProvider = (call: ApplicationCall) -> String?

/**
 * A function that verifies retrieved or generated call id. Should return `true` for a valid call id.
 * Also it could throw a [RejectedCallIdException] to reject an [ApplicationCall] otherwise an illegal call id
 * will be ignored or replaced with generated one.
 */
public typealias CallIdVerifier = (String) -> Boolean

/**
 * An exception that could be thrown to reject a call due to illegal call id
 * @param illegalCallId that caused rejection
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class RejectedCallIdException(
    public val illegalCallId: String
) : IllegalArgumentException(), CopyableThrowable<RejectedCallIdException> {
    override fun createCopy(): RejectedCallIdException? = RejectedCallIdException(illegalCallId).also {
        it.initCause(this)
    }
}

/**
 * A call id that is retrieved or generated by [CallId] plugin or `null` (this is possible if there is no
 * call id provided and no generators configured or [CallId] plugin is not installed)
 */
public val ApplicationCall.callId: String? get() = attributes.getOrNull(CallId.callIdKey)

/**
 * Retrieves and generates if necessary a call id. A call id (or correlation id) could be retrieved_ from a call
 * via [CallId.Configuration.retrieve] function. Multiple retrieve functions could be configured that will be invoked
 * one by one until one of them return non-null value. If no value has been provided by retrievers then a generator
 * could be applied to generate a new call id. Generators could be provided via [CallId.Configuration.generate] function.
 * Similar to retrieve, multiple generators could be configured so they will be invoked one by one.
 * Usually call id is passed via [io.ktor.http.HttpHeaders.XRequestId] so
 * one could use [CallId.Configuration.retrieveFromHeader] function to retrieve call id from a header.
 *
 * All retrieved or generated call ids are verified against [CALL_ID_DEFAULT_DICTIONARY] by default. Alternatively
 * a custom dictionary or functional predicate could be provided via [CallId.Configuration.verify] that could
 * pass a valid call id, discard an illegal call id
 * or reject completely an [ApplicationCall] with [HttpStatusCode.BadRequest] if an [RejectedCallIdException] is thrown.
 * Please note that this rejection functionality is not compatible with [StatusPages] for now and you cannot
 * configure rejection response message.
 *
 * Once a call id is retrieved or generated, it could be accessed via [ApplicationCall.callId] otherwise it will be
 * always `null`. Also a call id could be replied with response by registering [CallId.Configuration.reply] or
 * [CallId.Configuration.replyToHeader] so client will be able to know call id in case when it is generated.
 *
 * Please note that call id plugin is only intended for debugging and troubleshooting purposes to correlate
 * client requests with logs in multitier/microservices architecture. So usually it is not guaranteed that call id
 * is strictly random/unique. This is why you should NEVER rely on it's uniqueness.
 *
 * [CallId] plugin will be installed to [CallId.phase] into [ApplicationCallPipeline].
 */
public class CallId private constructor(
    private val providers: Array<CallIdProvider>,
    private val repliers: Array<(call: ApplicationCall, callId: String) -> Unit>,
    private val verifier: CallIdVerifier
) {
    /**
     * [CallId] plugin's configuration
     */
    public class Configuration {
        internal val retrievers = ArrayList<CallIdProvider>()
        internal val generators = ArrayList<CallIdProvider>()
        internal val responseInterceptors = ArrayList<(call: ApplicationCall, callId: String) -> Unit>()

        internal var verifier: CallIdVerifier = { false }

        init {
            verify(CALL_ID_DEFAULT_DICTIONARY)
        }

        /**
         * [block] will be used to retrieve call id from a call. It should return `null` if no call id found in request
         */
        public fun retrieve(block: CallIdProvider) {
            retrievers.add(block)
        }

        /**
         * [block] function will be applied when there is no call id retrieved. It should generate a string to be used
         * as call id or `null` if it is impossible to generate call id for some reason.
         * Note that it should conform to call id verification otherwise it may be discarded or may lead to
         * complete call rejection
         *
         * @see CallIdVerifier
         * @see verify
         */
        public fun generate(block: CallIdProvider) {
            generators.add(block)
        }

        /**
         * Verify retrieved or generated call ids using the specified [predicate]. Should return `true` for valid
         * call ids, `false` to ignore an illegal retrieved or generated call id
         * or throw an [RejectedCallIdException] to reject an [ApplicationCall].
         * Only one verify condition could be specified.
         * It is not recommended to disable verification (allow all call id values) as it could be abused
         * so that it may become a security risk.
         * By default there is always the default verifier against [CALL_ID_DEFAULT_DICTIONARY]
         * so all illegal call ids will be discarded.
         *
         * @see [CallIdVerifier] for details.
         */
        public fun verify(predicate: CallIdVerifier) {
            verifier = predicate
        }

        /**
         * Verify retrieved or generated call ids against the specified [dictionary].
         * Rejects an [ApplicationCall] if [reject] is `true`
         * otherwise an illegal call id will be simply ignored.
         * Only one verify condition or dictionary could be specified
         */
        public fun verify(dictionary: String, reject: Boolean = false) {
            val sortedDictionary = dictionary.toList().sorted().toCharArray()
            verify { callId ->
                if (!verifyCallIdAgainstDictionary(callId, sortedDictionary)) {
                    if (reject) throw RejectedCallIdException(callId)
                    false
                } else {
                    true
                }
            }
        }

        /**
         * Replies with retrieved or generated [callId]. Usually [replyToHeader] could be used instead.
         */
        public fun reply(block: (call: ApplicationCall, callId: String) -> Unit) {
            responseInterceptors.add(block)
        }

        /**
         * Setup retrieve/reply cycle via HTTP request and response headers [headerName].
         * Identical to [retrieveFromHeader] and [replyToHeader] invocations with the same [headerName]
         */
        public fun header(headerName: String) {
            retrieveFromHeader(headerName)
            replyToHeader(headerName)
        }

        /**
         * Fetch call id from a request header named [headerName] that is treated as optional
         */
        public fun retrieveFromHeader(headerName: String) {
            retrieve { it.request.headers[headerName] }
        }

        /**
         * Replies retrieved or generated callId using HTTP response header [headerName]
         */
        public fun replyToHeader(headerName: String) {
            reply { call, callId ->
                call.response.header(headerName, callId)
            }
        }
    }

    /**
     * An installable plugin for [CallId]
     */
    public companion object Plugin : RouteScopedPlugin<Configuration, CallId> {
        /**
         * An [ApplicationCallPipeline]'s phase to which this plugin is installed
         */
        public val phase: PipelinePhase = PipelinePhase("CallId")

        internal val callIdKey = AttributeKey<String>("ExtractedCallId")

        override val key: AttributeKey<CallId> = AttributeKey("CallId")
        private val logger by lazy { LoggerFactory.getLogger(CallId::class.jvmName) }

        override fun install(
            pipeline: ApplicationCallPipeline,
            configure: Configuration.() -> Unit
        ): CallId {
            val configuration = Configuration().apply(configure)

            val instance = CallId(
                providers = (configuration.retrievers + configuration.generators).toTypedArray(),
                repliers = configuration.responseInterceptors.toTypedArray(),
                verifier = configuration.verifier
            )

            pipeline.insertPhaseBefore(ApplicationCallPipeline.Setup, phase)

            if (instance.providers.isEmpty()) {
                logger.warn("CallId plugin is not configured: neither retrievers nor generators were configured")
                return instance // don't install interceptor
            }

            pipeline.intercept(phase) {
                val call = call
                val providers = instance.providers

                try {
                    for (i in 0 until providers.size) {
                        val callId = providers[i](call) ?: continue
                        if (!instance.verifier(callId)) continue // could throw a RejectedCallIdException

                        call.attributes.put(callIdKey, callId)

                        val repliers = instance.repliers
                        for (j in 0 until repliers.size) {
                            repliers[j](call, callId)
                        }
                        break
                    }
                } catch (rejection: RejectedCallIdException) {
                    logger.warn(
                        "Illegal call id retrieved or generated that is rejected by call id verifier:" +
                            " (url-encoded) " +
                            rejection.illegalCallId.encodeURLParameter()
                    )
                    call.respond(HttpStatusCode.BadRequest)
                    finish()
                }
            }

            return instance
        }
    }
}

/**
 * The default call id's generator dictionary
 */
public const val CALL_ID_DEFAULT_DICTIONARY: String = "abcdefghijklmnopqrstuvwxyz0123456789+/=-"

/**
 * Generates fixed [length] call ids using the specified [dictionary].
 * Please note that this function generates pseudo-random identifiers via regular [java.util.Random]
 * and should not be considered as cryptographically secure.
 * Also note that you should use the same dictionary for [CallIdVerifier] otherwise a generated call id could be
 * discarded or may lead to complete call rejection.
 *
 * @see [CallId.Configuration.verify]
 *
 * @param length of call ids to be generated, should be positive
 * @param dictionary to be used to generate ids, shouldn't be empty and it shouldn't contain duplicates
 */
public fun CallId.Configuration.generate(length: Int = 64, dictionary: String = CALL_ID_DEFAULT_DICTIONARY) {
    require(length >= 1) { "Call id should be at least one characters length: $length" }
    require(dictionary.length > 1) { "Dictionary should consist of several different characters" }

    val dictionaryCharacters = dictionary.toCharArray().distinct().toCharArray()

    require(dictionaryCharacters.size == dictionary.length) {
        "Dictionary should not contain duplicates, found: ${dictionary.duplicates()}"
    }

    generate { Random.nextString(length, dictionaryCharacters) }
}

/**
 * Put call id into MDC (diagnostic context value) with [name]
 */
public fun CallLogging.Configuration.callIdMdc(name: String = "CallId") {
    mdc(name) { it.callId }
}

private fun verifyCallIdAgainstDictionary(callId: String, sortedDictionary: CharArray): Boolean {
    for (index in 0 until callId.length) {
        if (sortedDictionary.binarySearch(callId[index], 0, sortedDictionary.size) < 0) {
            return false
        }
    }

    return true
}

private fun String.duplicates() = toCharArray().groupBy { it }.filterValues { it.size > 1 }.keys.sorted()
private fun Random.nextString(length: Int, dictionary: CharArray): String {
    val chars = CharArray(length)
    val dictionarySize = dictionary.size

    for (index in 0 until length) {
        chars[index] = dictionary[nextInt(dictionarySize)]
    }

    return String(chars)
}