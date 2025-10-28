package testing
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(DockerRequiredCondition::class)
annotation class RequiresDocker

class DockerRequiredCondition : ExecutionCondition {
    override fun evaluateExecutionCondition(ctx: ExtensionContext): ConditionEvaluationResult {
        val available =
            try {
                org.testcontainers.DockerClientFactory.instance().client()
                true
            } catch (_: Throwable) {
                false
            }
        return if (available) {
            ConditionEvaluationResult.enabled("Docker available")
        } else {
            ConditionEvaluationResult.disabled("Docker not available, skipping integration test")
        }
    }
}
