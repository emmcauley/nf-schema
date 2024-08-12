package nextflow.validation

import groovy.util.logging.Slf4j
import groovy.transform.PackageScope


/**
 * This class allows to model a specific configuration, extracting values from a map and converting
 *
 * We anotate this class as @PackageScope to restrict the access of their methods only to class in the
 * same package
 *
 * @author : nvnieuwk <nicolas.vannieuwkerke@ugent.be>
 *
 */

@Slf4j
@PackageScope
class HelpConfig {
    final private Boolean enabled
    final private String  shortParameter
    final private String  fullParameter
    final private String  showHiddenParameter
    final private String  beforeText
    final private String  afterText
    final private String  command
    final private Boolean showHidden

    HelpConfig(Map map, Map params) {
        def config = map ?: Collections.emptyMap()
        enabled             = config.enabled                    ?: false
        shortParameter      = config.shortParameter             ?: "help"
        fullParameter       = config.fullParameter              ?: "helpFull"
        showHiddenParameter = config.showHiddenParameter        ?: "showHidden"
        beforeText          = config.beforeText                 ?: ""
        afterText           = config.afterText                  ?: ""
        command             = config.command                    ?: ""
        showHidden          = params.get(showHiddenParameter)   ?: config.showHidden    ?: false
    }
}