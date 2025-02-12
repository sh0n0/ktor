/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.server.routing

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*

/**
 * Represents a result of routing resolution.
 *
 * @property route specifies a routing node for successful resolution, or nearest one for failed.
 */
public sealed class RoutingResolveResult(public val route: Route) {
    /**
     * Provides all captured values for this result.
     */
    public abstract val parameters: Parameters

    /**
     * Represents a successful result
     */
    public class Success internal constructor(
        route: Route,
        override val parameters: Parameters,
        internal val quality: Double
    ) : RoutingResolveResult(route) {

        @Deprecated("This will become internal in future releases.")
        public constructor(route: Route, parameters: Parameters) : this(route, parameters, 0.0)

        override fun toString(): String = "SUCCESS${if (parameters.isEmpty()) "" else "; $parameters"} @ $route"
    }

    /**
     * Represents a failed result
     * @param reason provides information on reason of a failure
     */
    public class Failure internal constructor(
        route: Route,
        public val reason: String,
        public val errorStatusCode: HttpStatusCode
    ) : RoutingResolveResult(route) {

        @Deprecated("This will become internal in future releases.")
        public constructor(route: Route, reason: String) : this(route, reason, HttpStatusCode.NotFound)

        override val parameters: Nothing
            get() = throw UnsupportedOperationException("Parameters are available only when routing resolve succeeds")

        override fun toString(): String = "FAILURE \"$reason\" @ $route"
    }
}

/**
 * Represents a context in which routing resolution is being performed
 * @param routing root node for resolution to start at
 * @param call instance of [ApplicationCall] to use during resolution
 */
public class RoutingResolveContext(
    public val routing: Route,
    public val call: ApplicationCall,
    private val tracers: List<(RoutingResolveTrace) -> Unit>
) {

    /**
     * List of path segments parsed out of a [call]
     */
    public val segments: List<String>

    /**
     * Flag showing if path ends with slash
     */
    public val hasTrailingSlash: Boolean = call.request.path().endsWith('/')

    private val trace: RoutingResolveTrace?

    init {
        try {
            segments = parse(call.request.path())
            trace = if (tracers.isEmpty()) null else RoutingResolveTrace(call, segments)
        } catch (cause: URLDecodeException) {
            throw BadRequestException("Url decode failed for ${call.request.uri}", cause)
        }
    }

    private fun parse(path: String): List<String> {
        if (path.isEmpty() || path == "/") return listOf()
        val length = path.length
        var beginSegment = 0
        var nextSegment = 0
        val segmentCount = path.count { it == '/' }
        val segments = ArrayList<String>(segmentCount)
        while (nextSegment < length) {
            nextSegment = path.indexOf('/', beginSegment)
            if (nextSegment == -1) {
                nextSegment = length
            }
            if (nextSegment == beginSegment) {
                // empty path segment, skip it
                beginSegment = nextSegment + 1
                continue
            }
            val segment = path.decodeURLPart(beginSegment, nextSegment)
            segments.add(segment)
            beginSegment = nextSegment + 1
        }
        if (!call.ignoreTrailingSlash && path.endsWith("/")) {
            segments.add("")
        }
        return segments
    }

    /**
     * Executes resolution procedure in this context and returns [RoutingResolveResult]
     */
    public fun resolve(): RoutingResolveResult {
        val root = routing
        val rootEvaluation = root.selector.evaluate(this, 0)
        if (rootEvaluation is RouteSelectorEvaluation.Failure) {
            val result = RoutingResolveResult.Failure(root, "rootPath didn't match", rootEvaluation.failureStatusCode)
            trace?.skip(root, 0, result)
            return result
        }
        check(rootEvaluation is RouteSelectorEvaluation.Success)
        val successResults = mutableListOf<List<RoutingResolveResult.Success>>()

        val rootResolveResult = RoutingResolveResult.Success(root, rootEvaluation.parameters, rootEvaluation.quality)
        val rootTrait = listOf(rootResolveResult)

        trace?.begin(root, 0)
        val failedEvaluation = resolveStep(
            root,
            successResults,
            rootTrait,
            rootEvaluation.segmentIncrement
        )
        trace?.finish(root, 0, rootResolveResult)

        trace?.registerSuccessResults(successResults)
        val resolveResult = findBestRoute(root, successResults, failedEvaluation)
        trace?.registerFinalResult(resolveResult)

        trace?.apply { tracers.forEach { it(this) } }
        return resolveResult
    }

    private fun resolveStep(
        entry: Route,
        successResults: MutableList<List<RoutingResolveResult.Success>>,
        trait: List<RoutingResolveResult.Success>,
        segmentIndex: Int
    ): RouteSelectorEvaluation.Failure? {
        var failedEvaluation: RouteSelectorEvaluation.Failure? = RouteSelectorEvaluation.FailedPath
        var bestSucceedChildQuality: Double = -Double.MAX_VALUE

        if (entry.children.isEmpty() && segmentIndex != segments.size) {
            trace?.skip(
                entry,
                segmentIndex,
                RoutingResolveResult.Failure(entry, "Not all segments matched", HttpStatusCode.NotFound)
            )
            return RouteSelectorEvaluation.FailedPath
        }
        if (entry.handlers.isNotEmpty() && segmentIndex == segments.size) {
            successResults.add(trait)
            failedEvaluation = null
        }

        // iterate using indices to avoid creating iterator
        for (childIndex in 0..entry.children.lastIndex) {
            val child = entry.children[childIndex]
            val childEvaluation = child.selector.evaluate(this, segmentIndex)
            if (childEvaluation is RouteSelectorEvaluation.Failure) {
                trace?.skip(
                    child,
                    segmentIndex,
                    RoutingResolveResult.Failure(child, "Selector didn't match", childEvaluation.failureStatusCode)
                )
                failedEvaluation = max(failedEvaluation, childEvaluation)
                continue // selector didn't match, skip entire subtree
            }
            check(childEvaluation is RouteSelectorEvaluation.Success)
            if (childEvaluation.quality != RouteSelectorEvaluation.qualityTransparent &&
                childEvaluation.quality < bestSucceedChildQuality
            ) {
                trace?.skip(
                    child,
                    segmentIndex,
                    RoutingResolveResult.Failure(child, "Better match was already found", HttpStatusCode.NotFound)
                )
                continue
            }

            val result = RoutingResolveResult.Success(child, childEvaluation.parameters, childEvaluation.quality)
            val newIndex = segmentIndex + childEvaluation.segmentIncrement
            trace?.begin(child, newIndex)
            val failedSubtreeEvaluation = resolveStep(child, successResults, trait + result, newIndex)
            trace?.finish(child, newIndex, result)

            if (failedSubtreeEvaluation == null && bestSucceedChildQuality < childEvaluation.quality) {
                bestSucceedChildQuality = childEvaluation.quality
            }

            failedEvaluation = max(failedEvaluation, failedSubtreeEvaluation)
        }
        return failedEvaluation
    }

    private fun findBestRoute(
        root: Route,
        successResults: List<List<RoutingResolveResult.Success>>,
        failedEvaluation: RouteSelectorEvaluation.Failure?
    ): RoutingResolveResult {
        if (successResults.isEmpty()) {
            return RoutingResolveResult.Failure(
                root,
                "No matched subtrees found",
                failedEvaluation?.failureStatusCode ?: HttpStatusCode.NotFound
            )
        }
        val bestPath = successResults
            .maxWithOrNull { result1, result2 ->
                var index1 = 0
                var index2 = 0
                while (index1 < result1.size && index2 < result2.size) {
                    val quality1 = result1[index1].quality
                    val quality2 = result2[index2].quality
                    if (quality1 == RouteSelectorEvaluation.qualityTransparent) {
                        index1++
                        continue
                    }
                    if (quality2 == RouteSelectorEvaluation.qualityTransparent) {
                        index2++
                        continue
                    }
                    if (quality1 != quality2) {
                        return@maxWithOrNull compareValues(quality1, quality2)
                    }
                    index1++
                    index2++
                }
                compareValues(
                    result1.count { it.quality != RouteSelectorEvaluation.qualityTransparent },
                    result2.count { it.quality != RouteSelectorEvaluation.qualityTransparent }
                )
            }!!

        val parameters = bestPath
            .fold(ParametersBuilder()) { builder, result -> builder.apply { appendAll(result.parameters) } }
            .build()
        return RoutingResolveResult.Success(
            bestPath.last().route,
            parameters,
            bestPath.minOf { result ->
                when (result.quality) {
                    RouteSelectorEvaluation.qualityTransparent -> RouteSelectorEvaluation.qualityConstant
                    else -> result.quality
                }
            }
        )
    }

    private fun max(
        first: RouteSelectorEvaluation.Failure?,
        second: RouteSelectorEvaluation.Failure?
    ): RouteSelectorEvaluation.Failure? = when {
        first == null || second == null -> null
        first.quality >= second.quality -> first
        else -> second
    }
}
