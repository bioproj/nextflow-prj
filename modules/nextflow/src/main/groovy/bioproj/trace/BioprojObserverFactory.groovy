package bioproj.trace

import nextflow.Session
import nextflow.trace.TraceObserver
import nextflow.trace.TraceObserverFactory

class BioprojObserverFactory implements TraceObserverFactory {


    @Override
    Collection<TraceObserver> create(Session session) {
        final config = session.config
        String endpoint = config.navigate('bioproj.endpoint') as String

        final enabled = true //session.config.navigate('myplugin.enabled')
        return enabled ? [ new BioprojObserver(session,endpoint) ] : []
    }
}
