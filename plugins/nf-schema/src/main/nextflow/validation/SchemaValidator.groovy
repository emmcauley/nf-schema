package nextflow.validation

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.json.JsonGenerator
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.DataflowWriteChannel
import groovyx.gpars.dataflow.DataflowReadChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Matcher
import java.util.regex.Pattern
import nextflow.extension.CH
import nextflow.Channel
import nextflow.Global
import nextflow.Nextflow
import nextflow.plugin.extension.Operator
import nextflow.plugin.extension.Function
import nextflow.plugin.extension.PluginExtensionPoint
import nextflow.script.WorkflowMetadata
import nextflow.Session
import nextflow.util.Duration
import nextflow.util.MemoryUnit
import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.yaml.snakeyaml.Yaml

/**
 * @author : mirpedrol <mirp.julia@gmail.com>
 * @author : nvnieuwk <nicolas.vannieuwkerke@ugent.be>
 * @author : KevinMenden
 */

@Slf4j
@CompileStatic
class SchemaValidator extends PluginExtensionPoint {

    static final List<String> NF_OPTIONS = [
            // Options for base `nextflow` command
            'bg',
            'c',
            'C',
            'config',
            'd',
            'D',
            'dockerize',
            'h',
            'log',
            'q',
            'quiet',
            'syslog',
            'v',

            // Options for `nextflow run` command
            'ansi',
            'ansi-log',
            'bg',
            'bucket-dir',
            'c',
            'cache',
            'config',
            'dsl2',
            'dump-channels',
            'dump-hashes',
            'E',
            'entry',
            'latest',
            'lib',
            'main-script',
            'N',
            'name',
            'offline',
            'params-file',
            'pi',
            'plugins',
            'poll-interval',
            'pool-size',
            'profile',
            'ps',
            'qs',
            'queue-size',
            'r',
            'resume',
            'revision',
            'stdin',
            'stub',
            'stub-run',
            'test',
            'w',
            'with-charliecloud',
            'with-conda',
            'with-dag',
            'with-docker',
            'with-mpi',
            'with-notification',
            'with-podman',
            'with-report',
            'with-singularity',
            'with-timeline',
            'with-tower',
            'with-trace',
            'with-weblog',
            'without-docker',
            'without-podman',
            'work-dir'
    ]

    private List<String> errors = []
    private List<String> warnings = []

    @Override
    protected void init(Session session) {
        // only called in operators
    }

    Session getSession(){
        Global.getSession() as Session
    }

    boolean hasErrors() { errors.size()>0 }
    List<String> getErrors() { errors }

    boolean hasWarnings() { warnings.size()>0 }
    List<String> getWarnings() { warnings }

    //
    // Find a value in a nested map
    //
    def findDeep(Map m, String key) {
        if (m.containsKey(key)) return m[key]
        m.findResult { k, v -> v instanceof Map ? findDeep(v, key) : null }
    }

    @Operator
    public DataflowWriteChannel fromSamplesheet(
        final DataflowReadChannel source,
        final Path schema,
        final Map options = null,
    ) {
        def Map params = session.params

        // Set defaults for optional inputs
        def String schemaFilename = options?.containsKey('parameters_schema') ? options.parameters_schema as String : 'nextflow_schema.json'
        def String baseDir = session.baseDir.toString()

        // Get the samplesheet schema from the parameters schema
        def slurper = new JsonSlurper()
        def Map parsed = (Map) slurper.parse( Path.of(Utils.getSchemaPath(baseDir, schemaFilename)) )
        def Map samplesheetValue = (Map) findDeep(parsed, samplesheetParam)
        def Path samplesheetFile = params[samplesheetParam] as Path

        // Some safeguard to make sure the channel factory runs correctly
        if (samplesheetValue == null) {
            log.error """
Parameter '--$samplesheetParam' was not found in the schema ($schemaFilename). 
Unable to create a channel from it.

Please make sure you correctly specified the inputs to `.fromSamplesheet`:

--------------------------------------------------------------------------------------
Channel.fromSamplesheet("input")
--------------------------------------------------------------------------------------

This would create a channel from params.input using the schema specified in the parameters JSON schema for this parameter.
"""
            throw new SchemaValidationException("", [])
        }
        else if (samplesheetFile == null) {
            log.error "Parameter '--$samplesheetParam' was not provided. Unable to create a channel from it."
            throw new SchemaValidationException("", [])
        }
        else if (!samplesheetValue.containsKey('schema')) {
            log.error "Parameter '--$samplesheetParam' does not contain a schema in the parameter schema ($schemaFilename). Unable to create a channel from it."
            throw new SchemaValidationException("", [])
        }
        
        // Convert to channel
        final channel = CH.create()
        def List arrayChannel = []
        try {
            def Path schemaFile = Path.of(Utils.getSchemaPath(baseDir, samplesheetValue['schema'].toString()))
            def SamplesheetConverter converter = new SamplesheetConverter(samplesheetFile, schemaFile)
            arrayChannel = converter.convertToList()
        } catch (Exception e) {
            log.error(
                """ Following error has been found during samplesheet conversion:
    ${e}
    ${e.getStackTrace().join("\n\t")}

Please run validateParameters() first before trying to convert a samplesheet to a channel.
Reference: https://nextflow-io.github.io/nf-schema/parameters/validation/

Also make sure that the same schema is used for validation and conversion of the samplesheet
""" as String
            )
        }

        session.addIgniter {
            arrayChannel.each { 
                channel.bind(it) 
            }
            channel.bind(Channel.STOP)
        }
        return channel
    }


    //
    // Initialise expected params if not present
    //
    Map initialiseExpectedParams(Map params) {
        if( !params.containsKey("validationFailUnrecognisedParams") ) {
            params.validationFailUnrecognisedParams = false
        }
        if( !params.containsKey("validationLenientMode") ) {
            params.validationLenientMode = false
        }
        if( !params.containsKey("help") ) {
            params.help = false
        }
        if( !params.containsKey("validationShowHiddenParams") ) {
            params.validationShowHiddenParams = false
        }
        if( !params.containsKey("validationSchemaIgnoreParams") ) {
            params.validationSchemaIgnoreParams = false
        }
        if( !params.containsKey("validationS3PathCheck") ) {
            params.validationS3PathCheck = false
        }
        if( !params.containsKey("monochromeLogs") ) {
            params.monochromeLogs = false
        }
        if( !params.containsKey("monochrome_logs") ) {
            params.monochrome_logs = false
        }

        return params
    }


    //
    // Add expected params
    //
    List addExpectedParams() {
        def List expectedParams = [
            "validationFailUnrecognisedParams",
            "validationLenientMode",
            "help",
            "validationShowHiddenParams",
            "validationSchemaIgnoreParams",
            "validationSkipDuplicateCheck",
            "validationS3PathCheck",
            "monochromeLogs",
            "monochrome_logs"
        ]

        return expectedParams
    }

    /*
    * Function to loop over all parameters defined in schema and check
    * whether the given parameters adhere to the specifications
    */
    @Function
    void validateParameters(
        Map options = null
    ) {

        def Map params = initialiseExpectedParams(session.params)
        def String baseDir = session.baseDir.toString()
        def Boolean s3PathCheck = params.validationS3PathCheck ? params.validationS3PathCheck : false
        def Boolean useMonochromeLogs = options?.containsKey('monochrome_logs') ? options.monochrome_logs as Boolean :
            params.monochrome_logs ? params.monochrome_logs as Boolean : 
            params.monochromeLogs  ? params.monochromeLogs as Boolean :
            false
        def String schemaFilename = options?.containsKey('parameters_schema') ? options.parameters_schema as String : 'nextflow_schema.json'
        log.debug "Starting parameters validation"

        // Clean the parameters
        def cleanedParams = cleanParameters(params)
        // Convert to JSONObject
        def paramsJSON = new JSONObject(new JsonBuilder(cleanedParams).toString())

        //=====================================================================//
        // Check for nextflow core params and unexpected params
        def slurper = new JsonSlurper()
        def Map parsed = (Map) slurper.parse( Path.of(Utils.getSchemaPath(baseDir, schemaFilename)) )
        def Map schemaParams = (Map) parsed.get('defs')
        def specifiedParamKeys = params.keySet()

        // Collect expected parameters from the schema
        def enumsTuple = collectEnums(schemaParams)
        def List expectedParams = (List) enumsTuple[0] + addExpectedParams()
        def Map enums = (Map) enumsTuple[1]
        // Collect expected parameters from the schema when parameters are specified outside of "defs"
        if (parsed.containsKey('properties')) {
            def enumsTupleTopLevel = collectEnums(['top_level': ['properties': parsed.get('properties')]])
            expectedParams += (List) enumsTupleTopLevel[0]
            enums += (Map) enumsTupleTopLevel[1]
        }

        //=====================================================================//
        def Boolean failUnrecognisedParams = params.validationFailUnrecognisedParams ? params.validationFailUnrecognisedParams : false

        for (String specifiedParam in specifiedParamKeys) {
            // nextflow params
            if (NF_OPTIONS.contains(specifiedParam)) {
                errors << "You used a core Nextflow option with two hyphens: '--${specifiedParam}'. Please resubmit with '-${specifiedParam}'".toString()
            }
            // unexpected params
            def String schemaIgnoreParams = params.validationSchemaIgnoreParams
            def List params_ignore = schemaIgnoreParams ? schemaIgnoreParams.split(',') + 'schemaIgnoreParams' as List : []
            def expectedParamsLowerCase = expectedParams.collect{ it -> 
                def String p = it
                p.replace("-", "").toLowerCase() 
            }
            def specifiedParamLowerCase = specifiedParam.replace("-", "").toLowerCase()
            def isCamelCaseBug = (specifiedParam.contains("-") && !expectedParams.contains(specifiedParam) && expectedParamsLowerCase.contains(specifiedParamLowerCase))
            if (!expectedParams.contains(specifiedParam) && !params_ignore.contains(specifiedParam) && !isCamelCaseBug) {
                if (failUnrecognisedParams) {
                    errors << "* --${specifiedParam}: ${params[specifiedParam]}".toString()
                } else {
                    warnings << "* --${specifiedParam}: ${params[specifiedParam]}".toString()
                }
            }
        }

        //=====================================================================//
        // Validate parameters against the schema
        def String schema_string = Files.readString( Path.of(Utils.getSchemaPath(baseDir, schemaFilename)) )
        def validator = new JsonSchemaValidator()

        // check for warnings
        if( this.hasWarnings() ) {
            def msg = "The following invalid input values have been detected:\n\n" + this.getWarnings().join('\n').trim() + "\n\n"
            log.warn(msg)
        }

        // Colors
        def colors = logColours(useMonochromeLogs)

        // Validate
        List<String> validationErrors = validator.validate(paramsJSON, schema_string)
        this.errors.addAll(validationErrors)
        if (this.hasErrors()) {
            def msg = "${colors.red}The following invalid input values have been detected:\n\n" + errors.join('\n').trim() + "\n${colors.reset}\n"
            log.error("Validation of pipeline parameters failed!")
            throw new SchemaValidationException(msg, this.getErrors())
        }

        log.debug "Finishing parameters validation"
    }

    //
    // Function to collect enums (options) of a parameter and expected parameters (present in the schema)
    //
    Tuple collectEnums(Map schemaParams) {
        def expectedParams = []
        def enums = [:]
        for (group in schemaParams) {
            def Map properties = (Map) group.value['properties']
            for (p in properties) {
                def String key = (String) p.key
                expectedParams.push(key)
                def Map property = properties[key] as Map
                if (property.containsKey('enum')) {
                    enums[key] = property['enum']
                }
            }
        }
        return new Tuple (expectedParams, enums)
    }


    //
    // Wrap too long text
    //
    String wrapText(String text, Integer lineWidth, Integer indent) {
        List olines = []
        String oline = "" // " " * indent
        text.split(" ").each() { wrd ->
            if ((oline.size() + wrd.size()) <= lineWidth) {
                oline += wrd + " "
            } else {
                olines += oline
                oline = wrd + " "
            }
        }
        olines += oline
        return olines.join("\n" + " " * indent)
    }

    //
    // Beautify parameters for --help
    //
    @Function
    String paramsHelp(
        Map options = null,
        String command
    ) {
        def Map params = initialiseExpectedParams(session.params)

        def String schemaFilename = options?.containsKey('parameters_schema') ? options.parameters_schema as String : 'nextflow_schema.json'
        def Boolean useMonochromeLogs = options?.containsKey('monochrome_logs') ? options.monochrome_logs as Boolean : 
            params.monochrome_logs ? params.monochrome_logs as Boolean : 
            params.monochromeLogs  ? params.monochromeLogs as Boolean :
            false

        def colors = logColours(useMonochromeLogs)
        Integer num_hidden = 0
        String output  = ''
        output        += 'Typical pipeline command:\n\n'
        output        += "  ${colors.cyan}${command}${colors.reset}\n\n"
        Map params_map = paramsLoad( Path.of(Utils.getSchemaPath(session.baseDir.toString(), schemaFilename)) )
        Integer max_chars  = paramsMaxChars(params_map) + 1
        Integer desc_indent = max_chars + 14
        Integer dec_linewidth = 160 - desc_indent

        // If a value is passed to help
        if (params.help instanceof String) {
            def String param = params.help
            def Map get_param = [:]
            for (group in params_map.keySet()) {
                def Map group_params = params_map.get(group) as Map // This gets the parameters of that particular group
                if (group_params.containsKey(param)) {
                    get_param = group_params.get(param) as Map 
                }
            }
            if (!get_param) {
                throw new Exception("Specified param '${param}' does not exist in JSON schema.")
            }
            output += "--" + param + '\n'
            for (property in get_param) {
                if (property.key == "fa_icon") {
                    continue;
                }
                def String key = property.key
                def String value = property.value
                def Integer lineWidth = 160 - 17
                def Integer indent = 17
                if (value.length() > lineWidth) {
                    value = wrapText(value, lineWidth, indent)
                }
                output += "    " + colors.dim + key.padRight(11) + ": " + colors.reset + value + '\n'
            }
            output += "-${colors.dim}----------------------------------------------------${colors.reset}-"
            return output
        }

        for (group in params_map.keySet()) {
            Integer num_params = 0
            String group_output = "$colors.underlined$colors.bold$group$colors.reset\n"
            def Map group_params = params_map.get(group) as Map // This gets the parameters of that particular group
            for (String param in group_params.keySet()) {
                def Map get_param = group_params.get(param) as Map 
                def String type = '[' + get_param.type + ']'
                def String enums_string = ""
                if (get_param.enum != null) {
                    def List enums = (List) get_param.enum
                    def String chop_enums = enums.join(", ")
                    if(chop_enums.length() > dec_linewidth){
                        chop_enums = chop_enums.substring(0, dec_linewidth-5)
                        chop_enums = chop_enums.substring(0, chop_enums.lastIndexOf(",")) + ", ..."
                    }
                    enums_string = " (accepted: " + chop_enums + ")"
                }
                def String description = get_param.description
                def defaultValue = get_param.default != null ? " [default: " + get_param.default.toString() + "]" : ''
                def description_default = description + colors.dim + enums_string + defaultValue + colors.reset
                // Wrap long description texts
                // Loosely based on https://dzone.com/articles/groovy-plain-text-word-wrap
                if (description_default.length() > dec_linewidth){
                    description_default = wrapText(description_default, dec_linewidth, desc_indent)
                }
                if (get_param.hidden && !params.validationShowHiddenParams) {
                    num_hidden += 1
                    continue;
                }
                group_output += "  --" +  param.padRight(max_chars) + colors.dim + type.padRight(10) + colors.reset + description_default + '\n'
                num_params += 1
            }
            group_output += '\n'
            if (num_params > 0){
                output += group_output
            }
        }
        if (num_hidden > 0){
            output += "$colors.dim !! Hiding $num_hidden params, use --validationShowHiddenParams to show them !!\n$colors.reset"
        }
        output += "-${colors.dim}----------------------------------------------------${colors.reset}-"
        return output
    }

    //
    // Groovy Map summarising parameters/workflow options used by the pipeline
    //
    @Function
    public LinkedHashMap paramsSummaryMap(
        Map options = null,
        WorkflowMetadata workflow
        ) {
        
        def String schemaFilename = options?.containsKey('parameters_schema') ? options.parameters_schema as String : 'nextflow_schema.json'
        def Map params = session.params
        
        // Get a selection of core Nextflow workflow options
        def Map workflow_summary = [:]
        if (workflow.revision) {
            workflow_summary['revision'] = workflow.revision
        }
        workflow_summary['runName']      = workflow.runName
        if (workflow.containerEngine) {
            workflow_summary['containerEngine'] = workflow.containerEngine
        }
        if (workflow.container) {
            workflow_summary['container'] = workflow.container
        }
        def String configFiles = workflow.configFiles
        workflow_summary['launchDir']    = workflow.launchDir
        workflow_summary['workDir']      = workflow.workDir
        workflow_summary['projectDir']   = workflow.projectDir
        workflow_summary['userName']     = workflow.userName
        workflow_summary['profile']      = workflow.profile
        workflow_summary['configFiles']  = configFiles.join(', ')

        // Get pipeline parameters defined in JSON Schema
        def Map params_summary = [:]
        def Map params_map = paramsLoad( Path.of(Utils.getSchemaPath(session.baseDir.toString(), schemaFilename)) )
        for (group in params_map.keySet()) {
            def sub_params = new LinkedHashMap()
            def Map group_params = params_map.get(group)  as Map // This gets the parameters of that particular group
            for (String param in group_params.keySet()) {
                if (params.containsKey(param)) {
                    def String params_value = params.get(param)
                    def Map group_params_value = group_params.get(param) as Map 
                    def String schema_value = group_params_value.default
                    def String param_type   = group_params_value.type
                    if (schema_value != null) {
                        if (param_type == 'string') {
                            if (schema_value.contains('$projectDir') || schema_value.contains('${projectDir}')) {
                                def sub_string = schema_value.replace('\$projectDir', '')
                                sub_string     = sub_string.replace('\${projectDir}', '')
                                if (params_value.contains(sub_string)) {
                                    schema_value = params_value
                                }
                            }
                            if (schema_value.contains('$params.outdir') || schema_value.contains('${params.outdir}')) {
                                def sub_string = schema_value.replace('\$params.outdir', '')
                                sub_string     = sub_string.replace('\${params.outdir}', '')
                                if ("${params.outdir}${sub_string}" == params_value) {
                                    schema_value = params_value
                                }
                            }
                        }
                    }

                    // We have a default in the schema, and this isn't it
                    if (schema_value != null && params_value != schema_value) {
                        sub_params.put(param, params_value)
                    }
                    // No default in the schema, and this isn't empty or false
                    else if (schema_value == null && params_value != "" && params_value != null && params_value != false && params_value != 'false') {
                        sub_params.put(param, params_value)
                    }
                }
            }
            params_summary.put(group, sub_params)
        }
        return [ 'Core Nextflow options' : workflow_summary ] << params_summary as LinkedHashMap
    }

    //
    // Beautify parameters for summary and return as string
    //
    @Function
    public String paramsSummaryLog(
        Map options = null,
        WorkflowMetadata workflow
    ) {

        def Map params = session.params

        def String schemaFilename = options?.containsKey('parameters_schema') ? options.parameters_schema as String : 'nextflow_schema.json'
        def Boolean useMonochromeLogs = options?.containsKey('monochrome_logs') ? options.monochrome_logs as Boolean :
            params.monochrome_logs ? params.monochrome_logs as Boolean : 
            params.monochromeLogs  ? params.monochromeLogs as Boolean :
            false

        def colors = logColours(useMonochromeLogs)
        String output  = ''
        def LinkedHashMap params_map = paramsSummaryMap(workflow, parameters_schema: schemaFilename)
        def max_chars  = paramsMaxChars(params_map)
        for (group in params_map.keySet()) {
            def Map group_params = params_map.get(group) as Map // This gets the parameters of that particular group
            if (group_params) {
                output += "$colors.bold$group$colors.reset\n"
                for (String param in group_params.keySet()) {
                    output += "  " + colors.blue + param.padRight(max_chars) + ": " + colors.green +  group_params.get(param) + colors.reset + '\n'
                }
                output += '\n'
            }
        }
        output += "!! Only displaying parameters that differ from the pipeline defaults !!\n"
        output += "-${colors.dim}----------------------------------------------------${colors.reset}-"
        return output
    }

    //
    // Clean and check parameters relative to Nextflow native classes
    //
    private static Map cleanParameters(Map params) {
        def Map new_params = (Map) params.getClass().newInstance(params)
        for (p in params) {
            // remove anything evaluating to false
            if (!p['value'] && p['value'] != 0) {
                new_params.remove(p.key)
            }
            // Cast MemoryUnit to String
            if (p['value'] instanceof MemoryUnit) {
                new_params.replace(p.key, p['value'].toString())
            }
            // Cast Duration to String
            if (p['value'] instanceof Duration) {
                new_params.replace(p.key, p['value'].toString())
            }
            // Cast LinkedHashMap to String
            if (p['value'] instanceof LinkedHashMap) {
                new_params.replace(p.key, p['value'].toString())
            }
        }
        return new_params
    }

    //
    // This function tries to read a JSON params file
    //
    private static LinkedHashMap paramsLoad(Path json_schema) {
        def params_map = new LinkedHashMap()
        try {
            params_map = paramsRead(json_schema)
        } catch (Exception e) {
            println "Could not read parameters settings from JSON. $e"
            params_map = new LinkedHashMap()
        }
        return params_map
    }

    //
    // Method to actually read in JSON file using Groovy.
    // Group (as Key), values are all parameters
    //    - Parameter1 as Key, Description as Value
    //    - Parameter2 as Key, Description as Value
    //    ....
    // Group
    //    -
    private static LinkedHashMap paramsRead(Path json_schema) throws Exception {
        def slurper = new JsonSlurper()
        def Map schema = (Map) slurper.parse( json_schema )
        def Map schema_defs = (Map) schema.get('defs')
        def Map schema_properties = (Map) schema.get('properties')
        /* Tree looks like this in nf-core schema
        * defs <- this is what the first get('defs') gets us
                group 1
                    title
                    description
                        properties
                        parameter 1
                            type
                            description
                        parameter 2
                            type
                            description
                group 2
                    title
                    description
                        properties
                        parameter 1
                            type
                            description
        * properties <- parameters can also be ungrouped, outside of defs
                parameter 1
                    type
                    description
        */

        def params_map = new LinkedHashMap()
        // Grouped params
        if (schema_defs) {
            for (group in schema_defs) {
                def Map group_property = (Map) group.value['properties'] // Gets the property object of the group
                def String title = (String) group.value['title']
                def sub_params = new LinkedHashMap()
                group_property.each { innerkey, value ->
                    sub_params.put(innerkey, value)
                }
                params_map.put(title, sub_params)
            }
        }

        // Ungrouped params
        if (schema_properties) {
            def ungrouped_params = new LinkedHashMap()
            schema_properties.each { innerkey, value ->
                ungrouped_params.put(innerkey, value)
            }
            params_map.put("Other parameters", ungrouped_params)
        }

        return params_map
    }

    //
    // Get maximum number of characters across all parameter names
    //
    private static Integer paramsMaxChars( Map params_map) {
        Integer max_chars = 0
        for (group in params_map.keySet()) {
            def Map group_params = (Map) params_map.get(group)  // This gets the parameters of that particular group
            for (String param in group_params.keySet()) {
                if (param.size() > max_chars) {
                    max_chars = param.size()
                }
            }
        }
        return max_chars
    }

    //
    // ANSII Colours used for terminal logging
    //
    private static Map logColours(Boolean monochrome_logs) {
        Map colorcodes = [:]

        // Reset / Meta
        colorcodes['reset']      = monochrome_logs ? '' : "\033[0m"
        colorcodes['bold']       = monochrome_logs ? '' : "\033[1m"
        colorcodes['dim']        = monochrome_logs ? '' : "\033[2m"
        colorcodes['underlined'] = monochrome_logs ? '' : "\033[4m"
        colorcodes['blink']      = monochrome_logs ? '' : "\033[5m"
        colorcodes['reverse']    = monochrome_logs ? '' : "\033[7m"
        colorcodes['hidden']     = monochrome_logs ? '' : "\033[8m"

        // Regular Colors
        colorcodes['black']      = monochrome_logs ? '' : "\033[0;30m"
        colorcodes['red']        = monochrome_logs ? '' : "\033[0;31m"
        colorcodes['green']      = monochrome_logs ? '' : "\033[0;32m"
        colorcodes['yellow']     = monochrome_logs ? '' : "\033[0;33m"
        colorcodes['blue']       = monochrome_logs ? '' : "\033[0;34m"
        colorcodes['purple']     = monochrome_logs ? '' : "\033[0;35m"
        colorcodes['cyan']       = monochrome_logs ? '' : "\033[0;36m"
        colorcodes['white']      = monochrome_logs ? '' : "\033[0;37m"

        // Bold
        colorcodes['bblack']     = monochrome_logs ? '' : "\033[1;30m"
        colorcodes['bred']       = monochrome_logs ? '' : "\033[1;31m"
        colorcodes['bgreen']     = monochrome_logs ? '' : "\033[1;32m"
        colorcodes['byellow']    = monochrome_logs ? '' : "\033[1;33m"
        colorcodes['bblue']      = monochrome_logs ? '' : "\033[1;34m"
        colorcodes['bpurple']    = monochrome_logs ? '' : "\033[1;35m"
        colorcodes['bcyan']      = monochrome_logs ? '' : "\033[1;36m"
        colorcodes['bwhite']     = monochrome_logs ? '' : "\033[1;37m"

        // Underline
        colorcodes['ublack']     = monochrome_logs ? '' : "\033[4;30m"
        colorcodes['ured']       = monochrome_logs ? '' : "\033[4;31m"
        colorcodes['ugreen']     = monochrome_logs ? '' : "\033[4;32m"
        colorcodes['uyellow']    = monochrome_logs ? '' : "\033[4;33m"
        colorcodes['ublue']      = monochrome_logs ? '' : "\033[4;34m"
        colorcodes['upurple']    = monochrome_logs ? '' : "\033[4;35m"
        colorcodes['ucyan']      = monochrome_logs ? '' : "\033[4;36m"
        colorcodes['uwhite']     = monochrome_logs ? '' : "\033[4;37m"

        // High Intensity
        colorcodes['iblack']     = monochrome_logs ? '' : "\033[0;90m"
        colorcodes['ired']       = monochrome_logs ? '' : "\033[0;91m"
        colorcodes['igreen']     = monochrome_logs ? '' : "\033[0;92m"
        colorcodes['iyellow']    = monochrome_logs ? '' : "\033[0;93m"
        colorcodes['iblue']      = monochrome_logs ? '' : "\033[0;94m"
        colorcodes['ipurple']    = monochrome_logs ? '' : "\033[0;95m"
        colorcodes['icyan']      = monochrome_logs ? '' : "\033[0;96m"
        colorcodes['iwhite']     = monochrome_logs ? '' : "\033[0;97m"

        // Bold High Intensity
        colorcodes['biblack']    = monochrome_logs ? '' : "\033[1;90m"
        colorcodes['bired']      = monochrome_logs ? '' : "\033[1;91m"
        colorcodes['bigreen']    = monochrome_logs ? '' : "\033[1;92m"
        colorcodes['biyellow']   = monochrome_logs ? '' : "\033[1;93m"
        colorcodes['biblue']     = monochrome_logs ? '' : "\033[1;94m"
        colorcodes['bipurple']   = monochrome_logs ? '' : "\033[1;95m"
        colorcodes['bicyan']     = monochrome_logs ? '' : "\033[1;96m"
        colorcodes['biwhite']    = monochrome_logs ? '' : "\033[1;97m"

        return colorcodes
    }
}
