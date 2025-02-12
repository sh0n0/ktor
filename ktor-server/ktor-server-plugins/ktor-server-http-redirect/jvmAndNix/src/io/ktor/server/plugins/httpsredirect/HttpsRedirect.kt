/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.plugins.httpsredirect

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.util.*
import io.ktor.util.*

/**
 * Redirect plugin configuration
 */
@KtorDsl
public class HttpsRedirectConfig {
    /**
     * HTTPS port (443 by default) to redirect to
     */
    public var sslPort: Int = URLProtocol.HTTPS.defaultPort

    /**
     * Use permanent redirect or temporary
     */
    public var permanentRedirect: Boolean = true

    /**
     * The list of call predicates for redirect exclusion.
     * Any call matching any of the predicates will not be redirected by this plugin.
     */
    public val excludePredicates: MutableList<(ApplicationCall) -> Boolean> = ArrayList()

    /**
     * Exclude calls with paths matching the [pathPrefix] from being redirected to https by this plugin.
     */
    public fun excludePrefix(pathPrefix: String) {
        exclude { call ->
            call.request.origin.uri.startsWith(pathPrefix)
        }
    }

    /**
     * Exclude calls with paths matching the [pathSuffix] from being redirected to https by this plugin.
     */
    public fun excludeSuffix(pathSuffix: String) {
        exclude { call ->
            call.request.origin.uri.endsWith(pathSuffix)
        }
    }

    /**
     * Exclude calls matching the [predicate] from being redirected to https by this plugin.
     */
    public fun exclude(predicate: (call: ApplicationCall) -> Boolean) {
        excludePredicates.add(predicate)
    }
}

/**
 * A plugin that redirects non-secure HTTP calls to HTTPS
 */
public val HttpsRedirect: ApplicationPlugin<Application, HttpsRedirectConfig, PluginInstance> =
    createApplicationPlugin("HttpsRedirect", ::HttpsRedirectConfig) {
        onCall { call ->
            if (call.request.origin.scheme == "http" &&
                pluginConfig.excludePredicates.none { predicate -> predicate(call) }
            ) {
                val redirectUrl = call.url { protocol = URLProtocol.HTTPS; port = pluginConfig.sslPort }
                if (!call.response.isCommitted) {
                    call.respondRedirect(redirectUrl, pluginConfig.permanentRedirect)
                }
            }
        }
    }
