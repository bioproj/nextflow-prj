package nextflow.trace

import groovy.transform.CompileStatic
import nextflow.plugin.BasePlugin
import org.pf4j.PluginWrapper

@CompileStatic
class TracePlugin extends BasePlugin {

    TracePlugin(PluginWrapper wrapper) {
        super(wrapper)
    }
    static String randomString(int length=9){
        new Random().with {(1..length).collect {(('a'..'z')).join(null)[ nextInt((('a'..'z')).join(null).length())]}.join(null)}
    }
}
