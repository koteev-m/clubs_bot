package mu

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class KLogger internal constructor(private val delegate: Logger) {
    fun info(message: () -> String) {
        if (delegate.isInfoEnabled) {
            delegate.info(message())
        }
    }

    fun info(message: String) {
        delegate.info(message)
    }

    fun info(message: String, throwable: Throwable) {
        delegate.info(message, throwable)
    }

    fun info(throwable: Throwable, message: () -> String) {
        if (delegate.isInfoEnabled) {
            delegate.info(message(), throwable)
        }
    }

    fun warn(message: () -> String) {
        if (delegate.isWarnEnabled) {
            delegate.warn(message())
        }
    }

    fun warn(message: String) {
        delegate.warn(message)
    }

    fun warn(message: String, throwable: Throwable) {
        delegate.warn(message, throwable)
    }

    fun warn(throwable: Throwable, message: () -> String) {
        if (delegate.isWarnEnabled) {
            delegate.warn(message(), throwable)
        }
    }

    fun error(message: () -> String) {
        if (delegate.isErrorEnabled) {
            delegate.error(message())
        }
    }

    fun error(message: String) {
        delegate.error(message)
    }

    fun error(message: String, throwable: Throwable) {
        delegate.error(message, throwable)
    }

    fun error(throwable: Throwable, message: () -> String) {
        if (delegate.isErrorEnabled) {
            delegate.error(message(), throwable)
        }
    }
}

object KotlinLogging {
    fun logger(name: String): KLogger = KLogger(LoggerFactory.getLogger(name))
}
