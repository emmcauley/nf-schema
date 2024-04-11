package nextflow.validation

import dev.harrel.jsonschema.Evaluator
import dev.harrel.jsonschema.EvaluationContext
import dev.harrel.jsonschema.JsonNode
import nextflow.Nextflow

import groovy.util.logging.Slf4j
import java.nio.file.Path

/**
 * @author : nvnieuwk <nicolas.vannieuwkerke@ugent.be>
 */

@Slf4j
class FormatDirectoryPathEvaluator implements Evaluator {
    // The string should be a directory
  
    @Override
    public Evaluator.Result evaluate(EvaluationContext ctx, JsonNode node) {
        // To stay consistent with other keywords, types not applicable to this keyword should succeed
        if (!node.isString()) {
            return Evaluator.Result.success()
        }

        def String value = node.asString()

        // Skip validation of S3 paths for now
        if (value.startsWith('s3://')) {
            log.debug("S3 paths are not supported by 'FormatDirectoryPathEvaluator': '${value}'")
            return Evaluator.Result.success()
        }
       
        // Actual validation logic
        def Path file = Nextflow.file(value) as Path
        if (file.exists() && !file.isDirectory()) {
            return Evaluator.Result.failure("'${value}' is not a directory, but a file" as String)
        }
        return Evaluator.Result.success()
    }
}