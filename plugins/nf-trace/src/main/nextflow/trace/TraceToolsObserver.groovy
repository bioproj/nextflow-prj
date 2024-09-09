package nextflow.trace

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session

@Slf4j
@CompileStatic
class TraceToolsObserver implements TraceObserver {

    @Override
    void onFlowCreate(Session session) {
        log.info "Pipeline is starting! 🚀"
    }

    @Override
    void onFlowComplete() {
        log.info "Pipeline complete! 👋"
    }
    static String randomString(int length=9){
        new Random().with {(1..length).collect {(('a'..'z')).join(null)[ nextInt((('a'..'z')).join(null).length())]}.join(null)}
    }
}
