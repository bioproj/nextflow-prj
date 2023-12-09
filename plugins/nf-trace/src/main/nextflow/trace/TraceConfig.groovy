package nextflow.trace

import groovy.transform.PackageScope

@PackageScope
class TraceConfig {
    final private String prefix

    TraceConfig(Map map){
        def config = map ?: Collections.emptyMap()
        prefix = config.prefix ?: 'Mr.'
    }

    String getPrefix() { prefix }
}
