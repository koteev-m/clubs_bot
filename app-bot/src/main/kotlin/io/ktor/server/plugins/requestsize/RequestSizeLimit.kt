package io.ktor.server.plugins.requestsize

import io.ktor.server.application.RouteScopedPlugin
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.bodylimit.RequestBodyLimitConfig

public typealias RequestSizeLimitConfig = RequestBodyLimitConfig

public val RequestSizeLimit: RouteScopedPlugin<RequestBodyLimitConfig> = RequestBodyLimit

public var RequestSizeLimitConfig.maxRequestSize: Long
    get() = error("maxRequestSize getter is not supported")
    set(value) {
        bodyLimit { value }
    }
