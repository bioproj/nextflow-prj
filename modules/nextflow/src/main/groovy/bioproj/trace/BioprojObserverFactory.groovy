package bioproj.trace

import nextflow.Session
import nextflow.trace.TraceObserver
import nextflow.trace.TraceObserverFactory

class BioprojObserverFactory implements TraceObserverFactory {


    @Override
    Collection<TraceObserver> create(Session session) {
        final config = session.config
        String endpoint = config.navigate('bioproj.endpoint') as String

        final enabled = session.config.navigate('bioproj.enabled')
        final kafkaEnabled = session.config.navigate('bioproj.kafkaEnabled')
        if(enabled){
            if(kafkaEnabled){
                return   [ new BioprojKafkaObserver(session,endpoint) ]
            }else {
                return   [ new BioprojObserver(session,endpoint) ]
            }
        }else {
            return []
        }

    }
}
