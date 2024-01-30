package nextflow.validation

import java.nio.file.Path

import nextflow.plugin.Plugins
import nextflow.plugin.TestPluginDescriptorFinder
import nextflow.plugin.TestPluginManager
import nextflow.plugin.extension.PluginExtensionProvider
import org.junit.Rule
import org.pf4j.PluginDescriptorFinder
import spock.lang.Shared
import test.Dsl2Spec
import test.OutputCapture

/**
 * @author : mirpedrol <mirp.julia@gmail.com>
 * @author : nvnieuwk <nicolas.vannieuwkerke@ugent.be>
 * @author : jorgeaguileraseqera
 */
class ParamsSummaryLogTest extends Dsl2Spec{

    @Rule
    OutputCapture capture = new OutputCapture()


    @Shared String pluginsMode

    Path root = Path.of('.').toAbsolutePath().normalize()
    Path getRoot() { this.root }
    String getRootString() { this.root.toString() }

    def setup() {
        // reset previous instances
        PluginExtensionProvider.reset()
        // this need to be set *before* the plugin manager class is created
        pluginsMode = System.getProperty('pf4j.mode')
        System.setProperty('pf4j.mode', 'dev')
        // the plugin root should
        def root = this.getRoot()
        def manager = new TestPluginManager(root){
            @Override
            protected PluginDescriptorFinder createPluginDescriptorFinder() {
                return new TestPluginDescriptorFinder(){
                    @Override
                    protected Path getManifestPath(Path pluginPath) {
                        return pluginPath.resolve('build/resources/main/META-INF/MANIFEST.MF')
                    }
                }
            }
        }
        Plugins.init(root, 'dev', manager)
    }

    def cleanup() {
        Plugins.stop()
        PluginExtensionProvider.reset()
        pluginsMode ? System.setProperty('pf4j.mode',pluginsMode) : System.clearProperty('pf4j.mode')
    }

    def 'should print params summary' () {
        given:
        def schema = Path.of('src/testResources/nextflow_schema.json').toAbsolutePath().toString()
        def  SCRIPT_TEXT = """
            params.outdir = "outDir"
            include { paramsSummaryLog } from 'plugin/nf-validation'
            
            def summary_params = paramsSummaryLog(workflow, parameters_schema: '$schema')
            log.info summary_params
        """

        when:
        dsl_eval(SCRIPT_TEXT)
        def stdout = capture
                .toString()
                .readLines()
                .findResults {it.contains('Only displaying parameters that differ from the pipeline defaults') ||
                    it.contains('Core Nextflow options') ||
                    it.contains('runName') ||
                    it.contains('launchDir') ||
                    it.contains('workDir') ||
                    it.contains('projectDir') ||
                    it.contains('userName') ||
                    it.contains('profile') ||
                    it.contains('configFiles') ||
                    it.contains('Input/output options') ||
                    it.contains('outdir') 
                    ? it : null }
        
        then:
        noExceptionThrown()
        stdout.size() == 11
        stdout ==~ /.*\[0;34moutdir     : .\[0;32moutDir.*/
    }
}